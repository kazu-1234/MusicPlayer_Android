package com.example.musicplayer.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ペアリングを許可した PC。トークンは PC がリクエストのヘッダ X-Token に付けてくる。 */
data class PairedPc(val clientId: String, val name: String, val token: String, val pairedAt: Long)

/**
 * PC から届いたプレイリスト (v3.1.0 で中身・置き換えを追加)。
 * @param entries 同期先フォルダからの相対パスの並び（中身が無いときの予備）
 * @param fileName PC のファイル名（例 "Music 1.m3u"）
 * @param content PC の .m3u ファイルのバイト列そのまま（無ければ null）
 * @param replacements PC の曲の絶対パス（小文字）→ 同期先フォルダからの相対パス。読み替えで見つからない行の補いに使う
 */
data class SyncedPlaylist(
    val name: String,
    val entries: List<String>,
    val fileName: String = "",
    val content: ByteArray? = null,
    val replacements: Map<String, String> = emptyMap()
)

/**
 * 同期まわりの保存先をまとめたもの。
 * - 端末 ID・同期先フォルダ・反映待ちフラグ: アプリ共通の SharedPreferences（MusicPlayerPrefs）
 * - 許可した PC・届いたプレイリスト: アプリ専用領域（filesDir）の JSON
 */
object SyncStore {
    // MainActivity と同じ設定ファイル・キー（ソースフォルダの一覧を読むため）
    private const val PREFS_NAME = "MusicPlayerPrefs"
    private const val KEY_SOURCE_FOLDER_URIS = "sourceFolderUris"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_SYNC_ROOT_URI = "syncRootUri"
    private const val KEY_PLAYLIST_FOLDER_URI = "syncPlaylistFolderUri"
    private const val KEY_PENDING_APPLY = "syncPendingApply"
    private const val PAIRED_PCS_FILE = "paired_pcs.json"
    private const val PENDING_PLAYLISTS_FILE = "sync_pending_playlists.json"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** この端末の ID（PC が IP の変化に関係なく同じ端末と分かるように使う）。初回に作って保存する。 */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val created = java.util.UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    // ───────── 同期先フォルダ ─────────

    /**
     * 同期先にできるフォルダ = ライブラリのソースフォルダのうち、書き込み権限を持っているもの。
     * 書き込めないフォルダ（古い版で読み取りだけ許可したもの）は、設定でフォルダを追加し直すと書き込めるようになる。
     */
    fun writableSourceFolders(context: Context): List<Uri> {
        val uris = prefs(context).getStringSet(KEY_SOURCE_FOLDER_URIS, emptySet()).orEmpty().map { Uri.parse(it) }
        val persisted = context.contentResolver.persistedUriPermissions
        return uris.filter { uri -> persisted.any { it.uri == uri && it.isWritePermission } }
            .sortedBy { it.toString() }
    }

    /** 同期先フォルダ。選んだものが無効になっていたら、書き込めるソースフォルダの先頭を使う。 */
    fun syncRoot(context: Context): Uri? {
        val writable = writableSourceFolders(context)
        val saved = prefs(context).getString(KEY_SYNC_ROOT_URI, null)?.let { Uri.parse(it) }
        return if (saved != null && saved in writable) saved else writable.firstOrNull()
    }

    /**
     * 同期画面で選んだフォルダを、ライブラリのソースフォルダにも加える（同期した曲をライブラリに出すため）。
     * 呼ぶ前に読み書きの永続権限を取っておくこと。
     */
    fun addSourceFolder(context: Context, uri: Uri) {
        val p = prefs(context)
        val current = p.getStringSet(KEY_SOURCE_FOLDER_URIS, emptySet()).orEmpty().toMutableSet()
        current.add(uri.toString())
        p.edit().putStringSet(KEY_SOURCE_FOLDER_URIS, current).apply()
    }

    fun setSyncRoot(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_SYNC_ROOT_URI, uri.toString()).apply()
    }

    // ───────── プレイリストの保存先 (v3.1.0) ─────────

    /**
     * PC から届いた .m3u を書き出すフォルダ。未指定、または書き込み権限が無くなっていたら null（アプリ内のプレイリストだけ更新する）。
     */
    fun playlistFolder(context: Context): Uri? {
        val saved = prefs(context).getString(KEY_PLAYLIST_FOLDER_URI, null)?.let { Uri.parse(it) } ?: return null
        val writable = context.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
        return if (writable) saved else null
    }

    /** 呼ぶ前に読み書きの永続権限を取っておくこと。ライブラリのソースフォルダには加えない（.m3u だけを置く場所のため）。 */
    fun setPlaylistFolder(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_PLAYLIST_FOLDER_URI, uri.toString()).apply()
    }

    /** 画面表示用のフォルダ名（"primary:Music" → "Music"）。 */
    fun displayName(context: Context, uri: Uri): String {
        return runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':') }.getOrNull()
            ?.ifEmpty { null }
            ?: DocumentFile.fromTreeUri(context, uri)?.name
            ?: uri.toString()
    }

    // ───────── 許可した PC ─────────

    fun pairedPcs(context: Context): List<PairedPc> {
        val file = File(context.filesDir, PAIRED_PCS_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                PairedPc(o.getString("clientId"), o.optString("name"), o.getString("token"), o.optLong("pairedAt"))
            }
        }.getOrDefault(emptyList())
    }

    private fun savePairedPcs(context: Context, list: List<PairedPc>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("clientId", it.clientId).put("name", it.name).put("token", it.token).put("pairedAt", it.pairedAt))
        }
        File(context.filesDir, PAIRED_PCS_FILE).writeText(array.toString())
    }

    /** 同じ PC が再ペアリングしたら、古いトークンは捨てて新しいものに置き換える。 */
    @Synchronized
    fun addPairedPc(context: Context, pc: PairedPc) {
        savePairedPcs(context, pairedPcs(context).filterNot { it.clientId == pc.clientId } + pc)
    }

    @Synchronized
    fun removePairedPc(context: Context, clientId: String) {
        savePairedPcs(context, pairedPcs(context).filterNot { it.clientId == clientId })
    }

    fun findByToken(context: Context, token: String): PairedPc? =
        if (token.isEmpty()) null else pairedPcs(context).firstOrNull { it.token == token }

    // ───────── 届いたプレイリスト（アプリへの反映待ち） ─────────

    fun savePendingPlaylists(context: Context, json: String) {
        File(context.filesDir, PENDING_PLAYLISTS_FILE).writeText(json)
    }

    fun loadPendingPlaylists(context: Context): List<SyncedPlaylist> {
        val file = File(context.filesDir, PENDING_PLAYLISTS_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val entries = o.optJSONArray("entries") ?: JSONArray()
                // 中身は PC のファイルのバイト列を Base64 にしたもの（v3.1.0 より前の PC 版からは届かないので無ければ null）
                val content = o.optString("contentBase64").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                // 置き換えは [{from, to}]。照合は大文字小文字を区別しない（Windows のパスのため）ので、キーを小文字にしておく
                val replacementArray = o.optJSONArray("replacements") ?: JSONArray()
                val replacements = (0 until replacementArray.length()).associate { j ->
                    val r = replacementArray.getJSONObject(j)
                    r.optString("from").lowercase() to r.optString("to")
                }
                SyncedPlaylist(
                    name = o.getString("name"),
                    entries = (0 until entries.length()).map { entries.getString(it) },
                    fileName = o.optString("fileName"),
                    content = content,
                    replacements = replacements
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clearPendingPlaylists(context: Context) {
        File(context.filesDir, PENDING_PLAYLISTS_FILE).delete()
    }

    /**
     * 同期が終わり、ライブラリの再スキャンとプレイリストの反映がまだのとき true。
     * アプリの画面が閉じている間に同期が終わっても、次に開いたときに反映できるよう保存しておく。
     */
    fun isPendingApply(context: Context): Boolean = prefs(context).getBoolean(KEY_PENDING_APPLY, false)

    fun setPendingApply(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_PENDING_APPLY, pending).apply()
    }
}
