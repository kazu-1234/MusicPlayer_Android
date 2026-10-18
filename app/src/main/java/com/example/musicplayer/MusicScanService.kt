package com.example.musicplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File

class MusicScanService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isScanning = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START_SCAN = "com.example.musicplayer.action.START_SCAN"
        const val ACTION_SCAN_PROGRESS = "com.example.musicplayer.action.SCAN_PROGRESS"
        const val ACTION_SCAN_COMPLETE = "com.example.musicplayer.action.SCAN_COMPLETE"

        const val ACTION_CANCEL_SCAN = "com.example.musicplayer.action.CANCEL_SCAN" // New
        const val ACTION_SCAN_STARTED_BROADCAST = "com.example.musicplayer.action.SCAN_STARTED_BROADCAST" // New
        const val EXTRA_SCAN_URIS = "com.example.musicplayer.extra.SCAN_URIS"
        const val EXTRA_PROGRESS = "com.example.musicplayer.extra.PROGRESS"
        const val EXTRA_CURRENT_COUNT = "extra_current_count"
        const val EXTRA_TOTAL_COUNT = "extra_total_count"
        const val EXTRA_NEW_SONGS = "extra_new_songs" // In a real app, passing large list via Intent is bad. Using file cache or DB is better.
        // For this app, we simply notify completion and let MainActivity reload from file.
        
        private const val NOTIFICATION_CHANNEL_ID = "music_scan_channel"
        private const val NOTIFICATION_ID = 2
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("MusicScanService", "onCreate called")
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("音楽スキャンサービス")
            .setContentText("待機中...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
            
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            android.util.Log.d("MusicScanService", "startForeground executed in onCreate")
        } catch (e: Exception) {
            android.util.Log.e("MusicScanService", "Failed to startForeground in onCreate", e)
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MusicPlayer:ScanServiceWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("MusicScanService", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_SCAN -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(EXTRA_SCAN_URIS)
                if (uris != null && !isScanning) {
                    android.util.Log.d("MusicScanService", "Starting scan with ${uris.size} URIs")
                    startScan(uris)
                } else if (!isScanning) {
                    android.util.Log.w("MusicScanService", "URIs null or already scanning. Stopping.")
                }
            }
            ACTION_CANCEL_SCAN -> {
                android.util.Log.d("MusicScanService", "Cancel scan requested")
                if (isScanning) {
                    // Cancel the scope, which will trigger finally block
                    serviceJob.cancelChildren()
                    // Re-create job for next time if service stays alive? 
                    // Usually service stops after. But let's just cancel children.
                    isScanning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            else -> {
               if (!isScanning) {
                   android.util.Log.w("MusicScanService", "Unknown action or not scanning. Stopping.")
                   stopSelf()
               }
            }
        }
        return START_NOT_STICKY
    }

    private fun startScan(uris: List<Uri>) {
        val readableUris = uris.filter { hasReadPermission(it) }
        if (readableUris.isEmpty()) {
            android.util.Log.w("MusicScanService", "No readable URIs. Stopping scan.")
            stopSelf()
            return
        }
        isScanning = true
        wakeLock?.acquire(10 * 60 * 1000L)
        android.util.Log.d("MusicScanService", "Wakelock acquired")

        // 初期通知
        updateNotificationProgress(0)

        serviceScope.launch {
            var newSongs: MutableList<Song> = mutableListOf() // Define outside try
            try {
                // Send START broadcast
                sendBroadcast(Intent(ACTION_SCAN_STARTED_BROADCAST).setPackage(packageName))
                
                android.util.Log.d("MusicScanService", "Scan coroutine started")
                
                
                // 事前推定を廃止してスキャンを高速化
                // val uriTotals = ...
                // val totalExpected = ...
                var processedGlobal = 0


                readableUris.forEach { uri ->
                    android.util.Log.d("MusicScanService", "Processing URI: $uri")
                    android.util.Log.d("MusicScanService", "Processing URI: $uri")
                    val songs = scanAudioFiles(this@MusicScanService, uri, 0) { countInUri ->
                        val current = processedGlobal + countInUri
                        updateNotificationProgress(current)
                        sendProgressBroadcast(0f, current, 0)
                    }
                    newSongs.addAll(songs)
                    processedGlobal += songs.size
                    updateNotificationProgress(processedGlobal)
                    sendProgressBroadcast(0f, processedGlobal, 0)
                }
                android.util.Log.d("MusicScanService", "Scan complete. Found ${newSongs.size} songs.")
                
            } catch (e: Exception) {
                android.util.Log.e("MusicScanService", "Error during scan", e)
                e.printStackTrace()
            } finally {
                // ALWAYS send complete, even if error
                sendCompleteBroadcast(newSongs)
                
                android.util.Log.d("MusicScanService", "Stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                isScanning = false
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopSelf()
            }
        }
    }

    private fun updateNotificationProgress(current: Int) {
        val titleText = "音楽スキャン中: $current 曲"
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setContentTitle(titleText)
            .setContentText("読み込み中...")
            .setStyle(NotificationCompat.BigTextStyle().bigText("読み込み中... ($current 曲)"))
            .setProgress(0, 0, true) // Indeterminate

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private data class Quad(val max: Int, val value: Int, val pct: Float, val text: String)

    // Hybrid Scan Strategy
    private suspend fun scanAudioFiles(context: Context, directoryUri: Uri, totalEstimate: Int, onProgress: (Int) -> Unit): List<Song> {
        // 1. Try File API (High speed, path based)
        try {
            if (DocumentsContract.isTreeUri(directoryUri)) {
                val docId = DocumentsContract.getTreeDocumentId(directoryUri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val pathStr = split[1]
                    val targetPath = if (type == "primary") "/storage/emulated/0/$pathStr" else "/storage/$type/$pathStr"
                    val file = File(targetPath)
                    
                    if (file.exists() && file.isDirectory && file.canRead()) {
                        android.util.Log.d("MusicScanService", "Attempting File API scan for $targetPath")
                        val results = runFileApiScan(context, file, directoryUri, totalEstimate, onProgress)
                        if (results.isNotEmpty()) {
                             android.util.Log.d("MusicScanService", "File API scan successful: ${results.size} songs")
                             return results
                        }
                        android.util.Log.w("MusicScanService", "File API returned 0 songs, falling back to SAF")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicScanService", "File API check failed, falling back to SAF", e)
        }
        
        // 2. Fallback to SAF
        android.util.Log.d("MusicScanService", "Using SAF fallback for $directoryUri")
        return runSafScan(context, directoryUri, totalEstimate, onProgress)
    }

    // Logic 1: File API Scan (Fast & Safe)
    private suspend fun runFileApiScan(context: Context, rootFile: File, directoryUri: Uri, totalEstimate: Int, onProgress: (Int) -> Unit): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()
        val processedLocal = java.util.concurrent.atomic.AtomicInteger(0)
        val total = 0

        
        try {
             // Sequence to avoid huge list memory usage
             val fileSequence = rootFile.walk()
                .onEnter { !it.name.startsWith(".") && it.name != "Android" } // Skip hidden and system folders
                .filter { it.isFile && it.length() > 0 && // 0バイトファイル除外
                          setOf("mp3", "m4a", "flac", "wav", "aac", "ogg").contains(it.extension.lowercase()) }

             // Chunked processing for memory safety
             fileSequence.chunked(10).forEach { batch ->
                 if (!isActive) return@withContext songList // Check cancellation
                 val results = coroutineScope {
                     batch.map { file ->
                         async {
                             try {
                                 val count = processedLocal.incrementAndGet()
                                 if (total == 0) onProgress(count) // Always report progress for 1-by-1 feel

                                 val localRetriever = MediaMetadataRetriever()
                                 var songTitle = file.nameWithoutExtension
                                 var songArtist = "Unknown Artist"
                                 var songAlbum = "Unknown Album"
                                 var trackNumber = 0
                                 
                                 try {
                                     // Safe FD usage: Open FileInputStream and use its FD.
                                     // This ensures FD is closed when stream is closed.
                                     java.io.FileInputStream(file).use { fis ->
                                        if (fis.fd.valid()) {
                                            localRetriever.setDataSource(fis.fd)
                                            val extractedTitle = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                            val extractedArtist = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                            val extractedAlbum = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

                                            val fixedTitle = fixEncoding(extractedTitle)
                                            val fixedArtist = fixEncoding(extractedArtist)
                                            val fixedAlbum = fixEncoding(extractedAlbum)

                                            songTitle = if (isGarbled(fixedTitle)) songTitle else (fixedTitle ?: songTitle)
                                            songArtist = if (isGarbled(fixedArtist)) "Unknown Artist" else (fixedArtist ?: "Unknown Artist")
                                            songAlbum = if (isGarbled(fixedAlbum)) "Unknown Album" else (fixedAlbum ?: "Unknown Album")
                                            
                                            val trackString = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                                            trackNumber = trackString?.substringBefore("/")?.toIntOrNull() ?: 0
                                        }
                                     }
                                 } catch (e: Exception) {
                                     // Metadata failed
                                 } finally {
                                     try { localRetriever.release() } catch (ignored: Exception) {}
                                 }
                                 
                                 Song(Uri.fromFile(file), file.name, songTitle, songArtist, songAlbum, 0, trackNumber, directoryUri)
                             } catch (e: Exception) {
                                 null
                             }
                         }
                     }.awaitAll()
                 }
                 songList.addAll(results.filterNotNull())
                 val current = processedLocal.get()
                 if (total > 0) onProgress(current)
             }
        } catch (e: Exception) {
            android.util.Log.e("MusicScanService", "File API Scan Error", e)
        }
        return@withContext songList.sortedBy { it.title }
    }

    // Logic 2: SAF Scan (Fallback) - Renamed from scanAudioFilesSaf
    private suspend fun runSafScan(context: Context, directoryUri: Uri, totalEstimate: Int, onProgress: (Int) -> Unit): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()
        var processedCount = 0
        val contentResolver = context.contentResolver
        val total = 0
        
        // ツリーURIの構築: ディレクトリへのアクセス権確認
        val documentsTree = try {
            DocumentsContract.buildDocumentUriUsingTree(directoryUri, DocumentsContract.getTreeDocumentId(directoryUri))
        } catch (e: Exception) {
            android.util.Log.e("MusicScanService", "Invalid URI: $directoryUri", e)
            return@withContext emptyList()
        }
        
        // 再帰処理関数
        suspend fun traverseDirectory(currentUri: Uri) {
             if (!isActive) return // Check cancellation
             try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(currentUri))
                
                contentResolver.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE
                ), null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)


                    while (cursor.moveToNext()) {
                        if (!isActive) return@use // Check cancellation
                        val docId = if (idCol != -1) cursor.getString(idCol) else continue
                        val mimeType = if (mimeCol != -1) cursor.getString(mimeCol) else ""
                        val name = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                        val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                        
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            // ディレクトリなら再帰 (Androidフォルダや隠しフォルダはスキップ)
                            if (!name.startsWith(".") && name != "Android") {
                                traverseDirectory(DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId))
                            }
                        } else {
                            // ファイル判定
                            val ext = name.substringAfterLast('.', "").lowercase()
                            
                            // 0バイトファイルはスキップ（クラッシュ回避）、拡張子とMimeTypeチェック
                            if (size > 0 && (mimeType.startsWith("audio/") || mimeType == "application/ogg" || setOf("mp3", "m4a", "flac", "wav", "aac", "ogg").contains(ext))) {
                                  processedCount++
                                  if (total == 0 || true) { // Always report
                                      onProgress(processedCount)
                                  }
                                
                                var songTitle = name.substringBeforeLast('.')
                                var songArtist = "Unknown Artist"
                                var songAlbum = "Unknown Album"
                                var trackNumber = 0
                                
                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId)
                                
                                // メタデータ取得 (厳格なリソース管理)
                                val localRetriever = MediaMetadataRetriever()
                                try {
                                    // FileDescriptorを明示的に開き、useブロックで確実に閉じることでFDリークを防ぐ
                                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                                        if (pfd.fileDescriptor.valid()) {
                                            localRetriever.setDataSource(pfd.fileDescriptor)
                                            val extractedTitle = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                            val extractedArtist = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                            val extractedAlbum = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

                                            val fixedTitle = fixEncoding(extractedTitle)
                                            val fixedArtist = fixEncoding(extractedArtist)
                                            val fixedAlbum = fixEncoding(extractedAlbum)

                                            songTitle = if (isGarbled(fixedTitle)) songTitle else (fixedTitle ?: songTitle)
                                            songArtist = if (isGarbled(fixedArtist)) "Unknown Artist" else (fixedArtist ?: "Unknown Artist")
                                            songAlbum = if (isGarbled(fixedAlbum)) "Unknown Album" else (fixedAlbum ?: "Unknown Album")

                                            val trackString = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                                            trackNumber = trackString?.substringBefore("/")?.toIntOrNull() ?: 0
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Metadata error, use filename defaults
                                } finally {
                                    try { localRetriever.release() } catch (ignored: Exception) {}
                                }
                                
                                songList.add(Song(fileUri, name, songTitle, songArtist, songAlbum, 0, trackNumber, directoryUri))
                            }
                        }
                    }
                }
             } catch (e: Exception) { 
                 android.util.Log.e("MusicScanService", "Error traversing SAF", e)
             }
        }
        
        traverseDirectory(documentsTree)
        return@withContext songList.sortedBy { it.title }
    }

    // SAF経由で対象ディレクトリ内の音声ファイル数をカウント
    private fun countSafAudioFiles(context: Context, directoryUri: Uri): Int {
        val resolver = context.contentResolver
        val documentsTree = try {
            DocumentsContract.buildDocumentUriUsingTree(directoryUri, DocumentsContract.getTreeDocumentId(directoryUri))
        } catch (_: Exception) { return 0 }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        fun countDir(currentUri: Uri): Int {
            var local = 0
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(currentUri))
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        if (idCol == -1 || mimeCol == -1) continue
                        val docId = cursor.getString(idCol) ?: continue
                        val mimeType = cursor.getString(mimeCol) ?: ""
                        val name = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            local += countDir(DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId))
                        } else {
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (mimeType.startsWith("audio/") || mimeType == "application/ogg" ||
                                setOf("mp3", "m4a", "flac", "wav", "aac", "ogg").contains(ext)) {
                                local++
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
            return local
        }

        return countDir(documentsTree)
    }

    // 事前にファイル数を推定して進捗割合を計算する
    private suspend fun estimateAudioCount(context: Context, directoryUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            if (DocumentsContract.isTreeUri(directoryUri)) {
                val docId = DocumentsContract.getTreeDocumentId(directoryUri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val pathStr = split[1]
                    val targetPath = if (type == "primary") "/storage/emulated/0/$pathStr" else "/storage/$type/$pathStr"
                    val file = File(targetPath)
                    if (file.exists() && file.isDirectory && file.canRead()) {
                        return@withContext try {
                            file.walk().count { it.isFile && it.length() > 0 &&
                                setOf("mp3", "m4a", "flac", "wav", "aac", "ogg").contains(it.extension.lowercase()) }
                        } catch (_: Exception) { 0 }
                    }
                }
            }
        } catch (_: Exception) { }
        return@withContext countSafAudioFiles(context, directoryUri)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Music Scan",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendProgressBroadcast(progress: Float, currentCount: Int, totalCount: Int) {
        val intent = Intent(ACTION_SCAN_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_CURRENT_COUNT, currentCount)
            putExtra(EXTRA_TOTAL_COUNT, totalCount)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun sendCompleteBroadcast(songs: List<Song>) {
        ScanResultHolder.scannedSongs = songs
        val intent = Intent(ACTION_SCAN_COMPLETE).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // 文字化け検出関数
    private fun isGarbled(s: String?): Boolean {
        if (s == null) return false
        if (s.contains('\uFFFD')) return true
        if (s.any { 
            val c = it.code
            (c < 32 && c != 9 && c != 10 && c != 13) || 
            (c == 0x7F) || 
            (c in 0x80..0x9F) 
        }) return true
        val japaneseChars = s.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
        if (japaneseChars) return false
        val mojibakeChars = "‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ¡¢£¤¥¦§¨©ª«¬®¯°±²³´µ¶·¸¹º»¼½¾¿ÃÅÆÇÈÉÊËÌÍÎÏ"
        val mojibakeCount = s.count { it in mojibakeChars }
        return s.length > 0 && mojibakeCount.toFloat() / s.length.toFloat() > 0.3f
    }

    // 文字化け修復関数 (Latin-1 to Shift_JIS)
    private fun fixEncoding(original: String?): String? {
        if (original == null) return null
        try {
            val bytes = original.toByteArray(Charsets.ISO_8859_1)
            val sjis = String(bytes, java.nio.charset.Charset.forName("Shift_JIS"))
            val originalJpCount = original.count { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
            val sjisJpCount = sjis.count { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
            if (sjisJpCount > originalJpCount) return sjis
        } catch (e: Exception) {}
        return original
    }

    private fun hasReadPermission(uri: Uri): Boolean {
        val resolver = contentResolver
        val persisted = resolver.persistedUriPermissions
        val hasPersisted = persisted.any { it.uri == uri && it.isReadPermission }
        val hasGrant = checkUriPermission(
            uri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return hasPersisted || hasGrant
    }
}

// Global holder for result to avoid Parcelable overhead
object ScanResultHolder {
    var scannedSongs: List<Song>? = null
}
