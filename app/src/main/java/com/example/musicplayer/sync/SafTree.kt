package com.example.musicplayer.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 同期先フォルダ（SAF のツリー URI）を「相対パス」で読み書きするための道具。
 *
 * SAF では「フォルダ/ファイルのパス」を直接開けず、フォルダごとに子の一覧を問い合わせてたどる必要がある。
 * 同じフォルダを何度も問い合わせないよう、調べた子の一覧はこのオブジェクトの寿命の間だけ覚えておく
 * （1 回の受信待機の間に使い回す。PC 以外がフォルダを変えることは想定しない）。
 * 名前の比較は大文字小文字を区別しない（Windows も Android の内部ストレージも区別しないため）。
 */
class SafTree(private val context: Context, private val treeUri: Uri) {
    private val resolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private data class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    /** フォルダの docId → （小文字の名前 → 子）。 */
    private val cache = HashMap<String, MutableMap<String, Child>>()

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun children(dirDocId: String): MutableMap<String, Child> = cache.getOrPut(dirDocId) {
        val map = LinkedHashMap<String, Child>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                map[name.lowercase()] = Child(id, name, isDir, if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        map
    }

    // ───────── 一覧 ─────────

    /**
     * 同期先フォルダ以下の音楽ファイルを (相対パス, サイズ) で返す。
     * 内部ストレージなどで実パスが読めるときは java.io.File でたどる（SAF の問い合わせより桁違いに速い）。
     * 読めないとき（SD カードの権限など）は SAF でたどる。
     */
    fun listAudioFiles(): List<Pair<String, Long>> {
        realRootFile()?.let { root ->
            if (root.isDirectory && root.canRead()) {
                val result = ArrayList<Pair<String, Long>>()
                root.walkTopDown()
                    .onEnter { it == root || !it.name.startsWith(".") } // 隠しフォルダ（.thumbnails など）は見ない
                    .filter { it.isFile && isAudio(it.name) }
                    .forEach { result += it.relativeTo(root).path.replace(File.separatorChar, '/') to it.length() }
                // 音楽の読み取り権限が無いと File では 0 件に見えることがあるので、そのときは SAF で数え直す
                if (result.isNotEmpty()) return result
            }
        }

        val result = ArrayList<Pair<String, Long>>()
        fun walk(dirDocId: String, prefix: String) {
            for (child in children(dirDocId).values) {
                if (child.name.startsWith(".")) continue
                val path = prefix + child.name
                if (child.isDir) walk(child.docId, "$path/")
                else if (isAudio(child.name)) result += path to child.size
            }
        }
        walk(rootDocId, "")
        return result
    }

    private fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in SyncConfig.AUDIO_EXTENSIONS

    private fun realRootFile(): File? = SyncPaths.realPath(rootDocId)?.let { File(it) }

    // ───────── 書き込み ─────────

    /**
     * 相対パスの位置へ、入力ストリームから length バイトを書く。
     * 途中で切れても壊れたファイルが残らないよう、「名前 + .mpsync-part」に書いてから本来の名前に変える。
     * 同じ名前のファイルがあれば（上書き）、書き終えてから古い方を消して入れ替える。
     */
    fun writeFile(relativePath: String, input: InputStream, length: Long, onProgress: (Long) -> Unit) {
        val segments = splitPath(relativePath)
        val fileName = segments.last()
        val dirDocId = ensureDir(segments.dropLast(1))
        val dir = children(dirDocId)

        // 前回の中断で残った書きかけがあれば消す
        val partName = fileName + SyncConfig.PART_SUFFIX
        dir.remove(partName.lowercase())?.let { DocumentsContract.deleteDocument(resolver, docUri(it.docId)) }

        val partUri = DocumentsContract.createDocument(resolver, docUri(dirDocId), "application/octet-stream", partName)
            ?: throw IOException("ファイルを作成できません: $relativePath")
        try {
            resolver.openOutputStream(partUri, "w")?.use { out ->
                val buffer = ByteArray(SyncConfig.COPY_BUFFER_SIZE)
                var remaining = length
                var written = 0L
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("受信が途中で切れました: $relativePath")
                    out.write(buffer, 0, read)
                    remaining -= read
                    written += read
                    onProgress(written)
                }
            } ?: throw IOException("ファイルを開けません: $relativePath")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
            throw e
        }

        // 上書き: 古いファイルを消してから、書き終えた .part を本来の名前にする
        dir.remove(fileName.lowercase())?.let { DocumentsContract.deleteDocument(resolver, docUri(it.docId)) }
        val finalUri = DocumentsContract.renameDocument(resolver, partUri, fileName) ?: partUri
        dir[fileName.lowercase()] = Child(DocumentsContract.getDocumentId(finalUri), fileName, false, length)
    }

    /** 途中のフォルダが無ければ作りながらたどり、最後のフォルダの docId を返す。 */
    private fun ensureDir(segments: List<String>): String {
        var current = rootDocId
        for (name in segments) {
            val dir = children(current)
            val existing = dir[name.lowercase()]
            current = if (existing != null && existing.isDir) {
                existing.docId
            } else {
                val created = DocumentsContract.createDocument(resolver, docUri(current), DocumentsContract.Document.MIME_TYPE_DIR, name)
                    ?: throw IOException("フォルダを作成できません: $name")
                val id = DocumentsContract.getDocumentId(created)
                dir[name.lowercase()] = Child(id, name, true, 0L)
                cache[id] = LinkedHashMap() // 作ったばかりのフォルダは空なので問い合わせない
                id
            }
        }
        return current
    }

    // ───────── 削除 ─────────

    /** ファイルを消し、それで空になったフォルダも（同期先フォルダ自体は残して）消す。消せたら true。 */
    fun deleteFile(relativePath: String): Boolean {
        val segments = splitPath(relativePath)
        val chain = ArrayList<String>() // たどったフォルダの docId（親 → 子）
        var current = rootDocId
        for (name in segments.dropLast(1)) {
            val child = children(current)[name.lowercase()] ?: return false
            if (!child.isDir) return false
            chain += current
            current = child.docId
        }
        val dir = children(current)
        val file = dir[segments.last().lowercase()] ?: return false
        if (file.isDir) return false
        if (!DocumentsContract.deleteDocument(resolver, docUri(file.docId))) return false
        dir.remove(segments.last().lowercase())

        // 空になったフォルダを下から順に消す
        var dirId = current
        for (i in segments.size - 2 downTo 0) {
            if (children(dirId).isNotEmpty()) break
            val parentId = chain[i]
            if (!runCatching { DocumentsContract.deleteDocument(resolver, docUri(dirId)) }.getOrDefault(false)) break
            children(parentId).remove(segments[i].lowercase())
            cache.remove(dirId)
            dirId = parentId
        }
        return true
    }

    /**
     * PC から届いた相対パスを分ける。".." や空の部分があるパスは、同期先フォルダの外に書かれないよう拒否する。
     */
    private fun splitPath(relativePath: String): List<String> {
        val segments = relativePath.split('/')
        if (segments.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') }) {
            throw IllegalArgumentException("不正なパス: $relativePath")
        }
        return segments
    }
}

/** 曲の URI と「同期先フォルダからの相対パス」を対応づける。 */
object SyncPaths {
    /** "primary:Music" → /storage/emulated/0/Music、"XXXX-XXXX:Music" → /storage/XXXX-XXXX/Music。 */
    fun realPath(treeDocId: String): String? {
        val parts = treeDocId.split(":", limit = 2)
        if (parts.size != 2) return null
        val base = if (parts[0] == "primary") "/storage/emulated/0" else "/storage/${parts[0]}"
        return if (parts[1].isEmpty()) base else "$base/${parts[1]}"
    }

    /**
     * 曲の URI から、同期先フォルダ（root）からの相対パス（"/" 区切り）を求める。root の外の曲は null。
     * スキャンの方式によって曲の URI は file://（実パス）か content://（SAF の docId）のどちらかになるので、両方に対応する。
     */
    fun relativePath(root: Uri, song: Uri): String? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull() ?: return null
        return when (song.scheme) {
            "file" -> {
                val rootPath = realPath(rootDocId) ?: return null
                song.path?.takeIf { it.startsWith("$rootPath/") }?.substring(rootPath.length + 1)
            }
            "content" -> {
                val docId = runCatching { DocumentsContract.getDocumentId(song) }.getOrNull() ?: return null
                docId.takeIf { it.startsWith("$rootDocId/") }?.substring(rootDocId.length + 1)
            }
            else -> null
        }
    }
}
