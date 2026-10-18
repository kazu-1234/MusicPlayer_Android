// ----------------------------------------------------------------------------------
// MainActivity.kt
// UI/UXを大幅に改善し、設定画面や高度なライブラリ管理機能を追加した最終完成版
// Gemini Model: Final Build 2025-09-09 v5 (修正版)
// ----------------------------------------------------------------------------------
package com.example.musicplayer

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.DownloadManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import com.example.musicplayer.sync.SyncPaths
import com.example.musicplayer.sync.SyncReceiverService
import com.example.musicplayer.sync.SyncScreen
import com.example.musicplayer.sync.SyncStore
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.os.Build
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.example.musicplayer.ui.theme.MusicPlayerTheme
import org.json.JSONArray
import java.io.File
import java.util.Collections

// --- アプリ情報 ---
// v2.5.4: スキャンクラッシュ修正 & バックグラウンドサービス統合
private const val APP_VERSION = "v3.1.0"
private const val GEMINI_MODEL_VERSION = "Final Build 2026-01-15 v72"

// --- アルバムアートキャッシュ (v2.5.0: 永続化対応) ---
object AlbumArtCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8 // メモリの1/8を使用
    private val memoryCache = object : android.util.LruCache<String, android.graphics.Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int {
            return bitmap.byteCount / 1024 // KB単位
        }
    }

    private fun getCacheDir(context: Context): java.io.File {
        val dir = java.io.File(context.cacheDir, "album_art")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getKeyFile(context: Context, key: String): java.io.File {
        // URI文字列をハッシュ化してファイル名にする
        val filename = key.hashCode().toString() + ".png"
        return java.io.File(getCacheDir(context), filename)
    }

    fun get(context: Context, key: String): android.graphics.Bitmap? {
        // 1. メモリキャッシュ確認
        memoryCache.get(key)?.let { return it }

        // 2. ディスクキャッシュ確認
        val file = getKeyFile(context, key)
        if (file.exists()) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    return bitmap
                }
            } catch (e: Exception) {
                file.delete()
            }
        }
        return null
    }

    fun put(context: Context, key: String, bitmap: android.graphics.Bitmap) {
        // 1. メモリ保存
        if (memoryCache.get(key) == null) {
            memoryCache.put(key, bitmap)
        }

        // 2. ディスク保存 (非同期)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = getKeyFile(context, key)
                if (!file.exists()) {
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } catch (e: Exception) {
                // 保存失敗は無視
            }
        }
    }
    
    fun clear(context: Context) {
        memoryCache.evictAll()
        try {
            getCacheDir(context).deleteRecursively()
        } catch (e: Exception) {}
    }
}

// 画像リサイズ用ユーティリティ
fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        while (inSampleSize * 2 <= 64 && (halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

// --- データ構造の定義 ---
enum class SortType { DEFAULT, TITLE, ARTIST, ALBUM, PLAY_COUNT }
enum class SortOrder { ASC, DESC }
enum class TabType { SONGS, PLAYLISTS, ARTISTS, ALBUMS }
enum class RepeatMode { OFF, ALL, ONE }

// --- 定数 ---
private const val PREFS_NAME = "MusicPlayerPrefs"
private const val KEY_SOURCE_FOLDER_URIS = "sourceFolderUris"
private const val KEY_TAB_ORDER = "tabOrder"
private const val KEY_PLAYLIST_BASE_PATH = "playlistBasePath"
private const val KEY_USE_SYSTEM_MEDIA_CONTROLLER = "useSystemMediaController"
private const val KEY_SHOW_MISSING_FILES = "showMissingFiles"
private const val LIBRARY_CACHE_FILE = "library.json"
private const val PLAYLIST_CACHE_FILE = "playlists.json"

// Actions
private const val ACTION_NEXT = "com.example.musicplayer.ACTION_NEXT"
private const val ACTION_PREVIOUS = "com.example.musicplayer.ACTION_PREVIOUS"
private const val ACTION_PLAY_PAUSE = "com.example.musicplayer.ACTION_PLAY_PAUSE"
private const val ACTION_SHUFFLE = "com.example.musicplayer.ACTION_SHUFFLE"
private const val ACTION_REPEAT = "com.example.musicplayer.ACTION_REPEAT"
private const val ACTION_STOP = "com.example.musicplayer.ACTION_STOP"
private const val ACTION_SEEK = "com.example.musicplayer.ACTION_SEEK"
private const val EXTRA_SEEK_POS = "com.example.musicplayer.EXTRA_SEEK_POS"
private const val NOTIFICATION_CHANNEL_ID = "music_notification_channel"
private const val NOTIFICATION_ID = 1

class MainActivity : ComponentActivity() {

    // MediaSession
    private lateinit var mediaSession: android.support.v4.media.session.MediaSessionCompat
    private var useSystemMediaController by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaSession = android.support.v4.media.session.MediaSessionCompat(this, "MusicPlayerSession").apply {
            setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                override fun onPlay() { 
                    sendBroadcast(Intent(ACTION_PLAY_PAUSE).setPackage(packageName)) 
                }
                override fun onPause() { 
                    sendBroadcast(Intent(ACTION_PLAY_PAUSE).setPackage(packageName)) 
                }
                override fun onSkipToNext() { 
                    sendBroadcast(Intent(ACTION_NEXT).setPackage(packageName)) 
                }
                override fun onSkipToPrevious() { 
                    sendBroadcast(Intent(ACTION_PREVIOUS).setPackage(packageName)) 
                }
                override fun onStop() { 
                    sendBroadcast(Intent(ACTION_STOP).setPackage(packageName)) 
                }
                override fun onSeekTo(pos: Long) {
                    sendBroadcast(Intent(ACTION_SEEK).setPackage(packageName).putExtra(EXTRA_SEEK_POS, pos))
                }
            })
            isActive = true
        }

        createNotificationChannel()
        requestNotificationPermission()
        setContent {
            MusicPlayerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MusicApp(
                        useSystemMediaController = useSystemMediaController,
                        onUseSystemMediaControllerChange = { 
                            useSystemMediaController = it 
                            saveUseSystemMediaController(this, it)
                        }
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.action?.let { action ->
            val broadcastIntent = Intent(action)
            broadcastIntent.setPackage(packageName)
            if (action == ACTION_SEEK) {
                broadcastIntent.putExtras(intent)
            }
            sendBroadcast(broadcastIntent)
            if (action == ACTION_STOP) {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "楽曲再生"
            val descriptionText = "再生中の楽曲情報を通知に表示します"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    internal fun updateNotification(
        song: Song,
        isPlaying: Boolean,
        isShuffleEnabled: Boolean,
        repeatMode: RepeatMode
    ) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        fun createActionIntent(action: String): PendingIntent {
            val intent = Intent(this, MusicNotificationReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                this,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            
        if (useSystemMediaController) {
             val style = androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(1, 2, 3)
             notificationBuilder.setStyle(style)
             
             val metadataBuilder = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, 0L)
             
             try {
                val ret = MediaMetadataRetriever()
                ret.setDataSource(this, song.uri)
                val artBytes = ret.embeddedPicture
                if (artBytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    metadataBuilder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    notificationBuilder.setLargeIcon(bitmap)
                }
                ret.release()
             } catch (e: Exception) { }
             
             mediaSession.setMetadata(metadataBuilder.build())
             
             val stateBuilder = android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setActions(
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP or
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO
                )
             stateBuilder.setState(
                 if (isPlaying) android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING else android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,
                 android.support.v4.media.session.PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                 1.0f
             )
             mediaSession.setPlaybackState(stateBuilder.build())
        } else {
             notificationBuilder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3) 
            )
        }

        notificationBuilder.addAction(
            if (isShuffleEnabled) R.drawable.ic_shuffle else R.drawable.ic_shuffle_off,
            if (isShuffleEnabled) "シャッフル ON" else "シャッフル OFF",
            createActionIntent(ACTION_SHUFFLE)
        )
        notificationBuilder.addAction(
            R.drawable.ic_skip_previous,
            "前の曲",
            createActionIntent(ACTION_PREVIOUS)
        )
        notificationBuilder.addAction(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (isPlaying) "一時停止" else "再生",
            createActionIntent(ACTION_PLAY_PAUSE)
        )
        notificationBuilder.addAction(
            R.drawable.ic_skip_next,
            "次の曲",
            createActionIntent(ACTION_NEXT)
        )
        notificationBuilder.addAction(
            R.drawable.ic_close,
            "×",
            createActionIntent(ACTION_STOP)
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }
}

sealed class Screen {
    object Main : Screen()
    object Settings : Screen()
    object Sync : Screen()
}

// ヘルパー関数: サービス起動
fun startScanServiceHelper(context: Context, uris: List<Uri>) {
    val readableUris = filterReadableUris(context, uris)
    if (readableUris.isEmpty()) {
        Toast.makeText(context, "フォルダの読み取り権限が失われています。再選択してください", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(context, MusicScanService::class.java).apply {
        action = MusicScanService.ACTION_START_SCAN
        putParcelableArrayListExtra(MusicScanService.EXTRA_SCAN_URIS, ArrayList(readableUris))
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun filterReadableUris(context: Context, uris: List<Uri>): List<Uri> {
    val resolver = context.contentResolver
    val persisted = resolver.persistedUriPermissions
    return uris.filter { uri ->
        val hasPersisted = persisted.any { it.uri == uri && it.isReadPermission }
        val hasGrant = context.checkUriPermission(
            uri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasPersisted || hasGrant
    }
}

@Composable
fun MusicApp(useSystemMediaController: Boolean, onUseSystemMediaControllerChange: (Boolean) -> Unit) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
    var songList by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    
    // スキャン進捗を管理するState (v2.5.3)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var scanCount by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }
    // PC からの同期の完了後に再スキャンしているフォルダ（スキャン完了の受信処理で、通常の追加ではなく入れ替えをするため）
    var syncApplyRoot by remember { mutableStateOf<Uri?>(null) }

    // スキャン中は画面スリープを防止
    DisposableEffect(isScanning) {
        val activity = context as? Activity
        if (isScanning) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Scan Service Receiver
    DisposableEffect(Unit) {
        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    MusicScanService.ACTION_SCAN_STARTED_BROADCAST -> {
                        isScanning = true
                        scanProgress = 0f
                        scanCount = 0
                        scanTotal = 0
                    }
                    MusicScanService.ACTION_SCAN_PROGRESS -> {
                        scanProgress = intent.getFloatExtra(MusicScanService.EXTRA_PROGRESS, 0f)
                        scanCount = intent.getIntExtra(MusicScanService.EXTRA_CURRENT_COUNT, 0)
                        scanTotal = intent.getIntExtra(MusicScanService.EXTRA_TOTAL_COUNT, 0) // Total count from service
                        isScanning = true
                    }
                    MusicScanService.ACTION_SCAN_COMPLETE -> {
                        isScanning = false
                        val newSongs = ScanResultHolder.scannedSongs
                        val applyRoot = syncApplyRoot
                        if (applyRoot != null) {
                            // PC からの同期後の再スキャン（v3.0.0）: 同期先フォルダの曲を入れ替え、届いたプレイリストを反映する
                            syncApplyRoot = null
                            ScanResultHolder.scannedSongs = null
                            coroutineScope.launch {
                                val (songs, lists) = applySyncResult(context!!, applyRoot, songList, newSongs.orEmpty())
                                songList = songs
                                playlists = lists
                                Toast.makeText(context, "PC からの同期を反映しました", Toast.LENGTH_SHORT).show()
                            }
                        } else if (!newSongs.isNullOrEmpty()) {
                            songList = (songList + newSongs).distinctBy { it.uri }.sortedBy { it.title }
                            ScanResultHolder.scannedSongs = null // Clear memory
                            Toast.makeText(context, "${newSongs.size}曲を追加しました", Toast.LENGTH_SHORT).show()
                            coroutineScope.launch { saveLibraryToFile(context!!, songList) }
                        } else {
                            // Toast.makeText(context, "スキャン完了", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(MusicScanService.ACTION_SCAN_STARTED_BROADCAST)
            addAction(MusicScanService.ACTION_SCAN_PROGRESS)
            addAction(MusicScanService.ACTION_SCAN_COMPLETE)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            scanReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(scanReceiver)
        }
    }
    var libraryUpdateKey by remember { mutableStateOf(0) }
    // val context = LocalContext.current (Removed duplicate)

    // ───── PC からの同期の反映 (v3.0.0) ─────
    val receiverState by SyncReceiverService.state.collectAsState()
    // 同期が完了するたび（completedCount が増えるたび）と、起動時に反映待ちが残っていたときに再スキャンする
    LaunchedEffect(receiverState.completedCount) {
        if (!SyncStore.isPendingApply(context) || isScanning) return@LaunchedEffect
        val root = SyncStore.syncRoot(context) ?: return@LaunchedEffect
        syncApplyRoot = root
        startScanServiceHelper(context, listOf(root))
    }

    LaunchedEffect(libraryUpdateKey) {
        songList = loadLibraryFromFile(context) ?: emptyList()
        if (songList.isNotEmpty()) {
            playlists = loadPlaylistsFromFile(context, songList) ?: emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MusicPlayerScreen(
            songList = songList,
            playlists = playlists,
            onSongListChange = { songList = it },
            onPlaylistsChange = { playlists = it },
            onNavigateToSettings = { currentScreen = Screen.Settings },
            onNavigateToSync = { currentScreen = Screen.Sync }
        )

        if (currentScreen is Screen.Sync) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SyncScreen(
                    onNavigateBack = { currentScreen = Screen.Main },
                    onFolderAdded = { uri -> if (!isScanning) startScanServiceHelper(context, listOf(uri)) }
                )
            }
            BackHandler { currentScreen = Screen.Main }
        }

        if (currentScreen is Screen.Settings) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SettingsScreen(
                    onNavigateBack = {
                        libraryUpdateKey++
                        currentScreen = Screen.Main
                    },
                    useSystemMediaController = useSystemMediaController,
                    onUseSystemMediaControllerChange = onUseSystemMediaControllerChange,
                    isScanning = isScanning,
                    scanProgress = scanProgress,
                    scanCount = scanCount,
                    scanTotal = scanTotal,
                    onStartScan = { uris -> 
                         if (!isScanning) {
                             startScanServiceHelper(context, uris)
                             // Reset state locally to show immediate feedback before service broadcast?
                             // Service broadcast is fast enough usually.
                             scanProgress = 0f
                             scanCount = 0
                             isScanning = true // Optimistic update
                         }
                    }
                )
            }
            BackHandler {
                libraryUpdateKey++
                currentScreen = Screen.Main
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(
    songList: List<Song>,
    playlists: List<Playlist>,
    onSongListChange: (List<Song>) -> Unit,
    onPlaylistsChange: (List<Playlist>) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSync: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // スキャン進捗状態 (v2.0.9)
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf(0f) }
    var scanCount by remember { mutableStateOf(0) }
    var scanTotal by remember { mutableStateOf(0) } // 推定合計
    
    // --- Service連携 (v2.4.0) ---
    var musicService by remember { mutableStateOf<MusicPlaybackService?>(null) }
    var isBound by remember { mutableStateOf(false) }
    
    // 再生状態（Serviceから同期）
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var currentIndex by remember { mutableStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    
    // 歌詞状態 (v2.5.6)
    var currentLyrics by remember { mutableStateOf<LyricsResult?>(null) }
    var isLyricsLoading by remember { mutableStateOf(false) }
    
    // 歌詞取得
    LaunchedEffect(currentSong) {
        currentLyrics = null // リセット
        isLyricsLoading = false
        currentSong?.let { song ->
            isLyricsLoading = true
            // APIから取得 (キャッシュ含む) - search APIを使用
            currentLyrics = LyricsManager.getLyrics(context, song.artist, song.title)
            isLyricsLoading = false
        }
    }
    
    // 再生キュー管理
    var playingQueue by remember { mutableStateOf<List<Song>>(emptyList()) }
    var originalQueue by remember { mutableStateOf<List<Song>>(emptyList()) }
    
    // 再生コントロール
    var repeatMode by remember { mutableStateOf(RepeatMode.OFF) }
    var isShuffleEnabled by remember { mutableStateOf(false) }
    
    // 検索機能 (v2.5.0)
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    
    // ナビゲーション機能 (v2.5.2)
    var targetArtist by remember { mutableStateOf<String?>(null) }
    var targetAlbum by remember { mutableStateOf<String?>(null) }
    
    // ServiceConnection
    val serviceConnection = remember {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                val binder = service as MusicPlaybackService.LocalBinder
                musicService = binder.getService().also { svc ->
                    // コールバック設定
                    svc.onPlaybackStateChanged = { playing -> isPlaying = playing }
                    svc.onSongChanged = { song -> currentSong = song }
                    svc.onPositionChanged = { pos, dur -> 
                        currentPosition = pos
                        duration = dur
                    }
                    svc.onQueueChanged = { queue -> playingQueue = queue }
                    svc.onPlayCountUpdated = { song ->
                        songList.indexOf(songList.find { it.uri == song.uri }).takeIf { it != -1 }?.let { idx ->
                            val updatedSong = songList[idx].copy(playCount = songList[idx].playCount + 1)
                            val updatedList = songList.toMutableList().also { it[idx] = updatedSong }
                            onSongListChange(updatedList)
                            currentSong = updatedSong
                            coroutineScope.launch { saveLibraryToFile(context, updatedList) }
                        }
                    }
                    
                    // 現在の状態を同期
                    isPlaying = svc.isPlaying
                    currentSong = svc.currentSong
                    currentIndex = svc.currentIndex
                    playingQueue = svc.playingQueue
                    repeatMode = svc.repeatMode
                    isShuffleEnabled = svc.isShuffleEnabled
                }
                isBound = true
            }
            
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                musicService = null
                isBound = false
            }
        }
    }
    
    // Service起動・バインド
    DisposableEffect(Unit) {
        val intent = Intent(context, MusicPlaybackService::class.java)
        context.startService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        onDispose {
            if (isBound) {
                // コールバックを解除してリーク/クラッシュを防止
                musicService?.let { svc ->
                    svc.onPlaybackStateChanged = null
                    svc.onSongChanged = null
                    svc.onPositionChanged = null
                    svc.onQueueChanged = null
                    svc.onPlayCountUpdated = null
                }
                context.unbindService(serviceConnection)
                isBound = false
                musicService = null
            }
        }
    }
    
    // フルスクリーンプレイヤー表示
    var showFullPlayer by remember { mutableStateOf(false) }
    
    // タブ関連
    var selectedTabIndex by remember { mutableStateOf(0) }
    var sortType by remember { mutableStateOf(SortType.TITLE) }
    var sortOrder by remember { mutableStateOf(SortOrder.ASC) }
    var tabOrder by remember { mutableStateOf(getTabOrder(context)) }
    
    // --- Service経由の再生制御関数 (v2.4.0) ---
    
    // 曲を再生（タップ時用）
    val playSong: (Song, List<Song>) -> Unit = { song, contextList ->
        if (!song.exists) {
            Toast.makeText(context, "ファイルが見つかりません", Toast.LENGTH_SHORT).show()
        } else {
            originalQueue = contextList
            musicService?.let { svc ->
                svc.isShuffleEnabled = isShuffleEnabled
                svc.playSong(song, contextList)
            }
        }
    }
    
    // シャッフル切り替え
    val toggleShuffle: () -> Unit = {
        isShuffleEnabled = !isShuffleEnabled
        musicService?.toggleShuffle()
    }
    
    // 前の曲
    val playPrevious: () -> Unit = {
        musicService?.playPrevious()
    }
    
    // 次の曲
    val playNext: () -> Unit = {
        musicService?.playNext()
    }

    // --- 通知連携 ---
    // 1. 再生状態の変更を通知に反映
    LaunchedEffect(currentSong, isPlaying, isShuffleEnabled, repeatMode) {
        if (currentSong != null) {
            (context as? MainActivity)?.updateNotification(currentSong!!, isPlaying, isShuffleEnabled, repeatMode)
        }
    }

    // 関数参照をrememberUpdatedStateで保持（Recomposition時の参照無効化を防止）
    val currentPlayPrevious by rememberUpdatedState(playPrevious)
    val currentPlayNext by rememberUpdatedState(playNext)
    val currentMusicService by rememberUpdatedState(musicService)
    val currentIsPlaying by rememberUpdatedState(isPlaying)



    // 2. 通知からのアクション受信とクリーンアップ
    DisposableEffect(Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastActionTime = 0L
        val debounceMs = 300L // 300ms以内の連続アクションを無視
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val now = System.currentTimeMillis()
                // Scan events should NOT be debounced (need real-time updates and guaranteed completion)
                val isScanEvent = intent.action == MusicScanService.ACTION_SCAN_PROGRESS || 
                                  intent.action == MusicScanService.ACTION_SCAN_COMPLETE
                                  
                if (!isScanEvent && now - lastActionTime < debounceMs) {
                    return // デバウンス: 再生制御などの短時間の連続アクションを無視
                }
                lastActionTime = now
                
                // メインスレッドで状態更新を実行
                mainHandler.post {
                    when (intent.action) {
                        ACTION_PREVIOUS -> currentPlayPrevious()
                        ACTION_NEXT -> currentPlayNext()
                        ACTION_PLAY_PAUSE -> {
                            if (currentIsPlaying) {
                                currentMusicService?.pause()
                            } else {
                                currentMusicService?.resume()
                            }
                        }
                        ACTION_SHUFFLE -> { toggleShuffle() }
                        ACTION_REPEAT -> {
                            repeatMode = when (repeatMode) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            }
                            currentMusicService?.repeatMode = repeatMode
                        }
                        ACTION_STOP -> {
                            // Serviceを停止してアプリを終了
                            currentMusicService?.stop()
                            // 通知をキャンセル
                            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(NOTIFICATION_ID)
                            // Activityを終了
                            (ctx as? android.app.Activity)?.finishAffinity()
                        }
                        ACTION_SEEK -> {
                            val pos = intent.getLongExtra(EXTRA_SEEK_POS, 0L)
                            currentMusicService?.seekTo(pos.toInt())
                            currentPosition = pos.toInt()
                        }
                        MusicScanService.ACTION_SCAN_PROGRESS -> {
                            val p = intent.getFloatExtra(MusicScanService.EXTRA_PROGRESS, 0f)
                            val c = intent.getIntExtra(MusicScanService.EXTRA_CURRENT_COUNT, 0)
                            val t = intent.getIntExtra(MusicScanService.EXTRA_TOTAL_COUNT, 0)
                            scanProgress = p
                            scanCount = c
                            scanTotal = t
                            // Totalは推定値だが、Progressから逆算またはServiceから受け取る必要があるが...
                            // 今回は簡易的にService側でProgressしているので、そのまま表示
                            isScanning = true // Only set isScanning to true if we receive progress (recovery from background)
                        }
                        MusicScanService.ACTION_SCAN_COMPLETE -> {
                            isScanning = false
                            scanProgress = 1f // Visual completion
                            // ScanResultHolderから結果を取得
                            val newSongs = ScanResultHolder.scannedSongs ?: emptyList()
                            if (newSongs.isNotEmpty()) {
                                // 既存リストとマージ（重複排除）
                                val merged = (songList + newSongs).distinctBy { it.uri }
                                onSongListChange(merged.sortedBy { it.title }) // タイトル順
                                coroutineScope.launch { saveLibraryToFile(context, merged) }
                                Toast.makeText(context, "スキャン完了: ${newSongs.size}曲追加", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "スキャン完了: 新規ファイルなし", Toast.LENGTH_SHORT).show()
                            }
                            ScanResultHolder.scannedSongs = null // Clear
                        }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_NEXT)
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_SHUFFLE)
            addAction(ACTION_REPEAT)
            addAction(ACTION_STOP)
            addAction(MusicScanService.ACTION_SCAN_PROGRESS) // スキャン進捗受信
            addAction(MusicScanService.ACTION_SCAN_COMPLETE) // スキャン完了受信
        }
        // RECEIVER_NOT_EXPORTEDで同一パッケージからのブロードキャストのみ受信
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // 既に登録解除済みの場合は無視
            }
            // Serviceへの参照解除はServiceConnection側で処理
        }
    }
    
    // スキャン制御関数 (Service起動)
    fun startScanService(uris: List<Uri>?) {
        val targetUris = uris ?: getUriList(context, KEY_SOURCE_FOLDER_URIS)
        if (targetUris.isEmpty()) {
            Toast.makeText(context, "フォルダが選択されていません", Toast.LENGTH_SHORT).show()
            return
        }
        val readableUris = filterReadableUris(context, targetUris)
        if (readableUris.isEmpty()) {
            Toast.makeText(context, "フォルダの読み取り権限がありません。設定から再選択してください", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(context, MusicScanService::class.java).apply {
            action = MusicScanService.ACTION_START_SCAN
            putParcelableArrayListExtra(MusicScanService.EXTRA_SCAN_URIS, ArrayList(readableUris))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        isScanning = true
        scanProgress = 0f
        scanCount = 0
        scanTotal = 0
    }
    
    // メイン画面とフルスクリーンプレイヤーを重ね合わせ（閉じるアニメーションを正しく動作させるため）
    Box(modifier = Modifier.fillMaxSize()) {
        // メイン画面（常に表示）
        Scaffold(
            topBar = {
                if (isSearchActive) {
                    // 検索バー
                    TopAppBar(
                        title = {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("曲、アーティスト、アルバムを検索") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                isSearchActive = false
                                searchQuery = ""
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close Search") }
                        },
                        actions = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear") }
                            }
                        }
                    )
                } else {
                    // 通常ヘッダー
                    TopAppBar(
                        title = { 
                             Column {
                                 Text("Music Player")
                                 if (isScanning) {
                                     Text("読み込み中... (${scanCount}曲)", style = MaterialTheme.typography.bodySmall)
                                     LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // Indeterminate
                                 }
                             }
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = onNavigateToSync) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync")
                            }
                            IconButton(onClick = onNavigateToSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                }
            },
            bottomBar = {
                // ミニプレイヤーバー（曲が選択されている場合のみ表示）
                if (currentSong != null) {
                    MiniPlayerBar(
                        context = context,
                        song = currentSong!!,
                        isPlaying = isPlaying,
                        isShuffleEnabled = isShuffleEnabled,
                        repeatMode = repeatMode,
                        onPrevious = playPrevious,
                        onPlayPause = {
                            if (isPlaying) musicService?.pause() else musicService?.resume()
                        },
                        onNext = playNext,
                        onShuffleToggle = { toggleShuffle() },
                        onRepeatToggle = {
                            repeatMode = when (repeatMode) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            }
                        },
                        onClick = { showFullPlayer = true }
                    )
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                // タブ
                val currentTabs = remember(tabOrder) { tabOrder }
                TabRow(selectedTabIndex = selectedTabIndex.coerceIn(0, currentTabs.size - 1)) {
                    currentTabs.forEachIndexed { index, tabType ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(tabType.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                // リストのフィルタリング
                val filteredSongs = remember(songList, searchQuery) {
                    if (searchQuery.isBlank()) songList
                    else songList.filter {
                        it.title.contains(searchQuery, true) ||
                        it.artist.contains(searchQuery, true) ||
                        it.album.contains(searchQuery, true)
                    }
                }
                
                when (currentTabs.getOrNull(selectedTabIndex)) {
                    TabType.SONGS -> SongsTab(filteredSongs, currentSong, sortType, sortOrder, onSongClick = playSong, onSortTypeChange = { sortType = it }, onSortOrderChange = { sortOrder = it })
                    TabType.PLAYLISTS -> PlaylistTab(playlists, songList, currentSong, onPlaylistChanged = onPlaylistsChange, onSongClick = playSong)
                    TabType.ARTISTS -> ArtistFolderTab(songList, onSongClick = playSong, targetArtist = targetArtist, onTargetConsume = { targetArtist = null })
                    TabType.ALBUMS -> AlbumFolderTab(songList, onSongClick = playSong, targetAlbum = targetAlbum, onTargetConsume = { targetAlbum = null })
                    null -> {}
                }
            }
        }
        
        // フルスクリーンプレイヤー（上に重ねて表示、アニメーション付き）
        AnimatedVisibility(
            visible = showFullPlayer && currentSong != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 250)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 200)
            )
        ) {
            // 戻るボタンで閉じる (v2.5.6)
            BackHandler(enabled = showFullPlayer) {
                showFullPlayer = false
            }

            currentSong?.let { song ->
                FullScreenPlayer(
                    context = context,
                    currentSong = song,
                    currentLyrics = currentLyrics,
                    isLyricsLoading = isLyricsLoading,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    repeatMode = repeatMode,
                    isShuffleEnabled = isShuffleEnabled,
                    playingQueue = playingQueue,
                    onPlayPause = {
                        if (isPlaying) musicService?.pause() else musicService?.resume()
                    },
                    onPrevious = playPrevious,
                    onNext = playNext,
                    onSeek = { newValue ->
                        currentPosition = newValue.toInt()
                        musicService?.seekTo(newValue.toInt())
                    },
                    onRepeatToggle = {
                        repeatMode = when (repeatMode) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        }
                    },
                    onShuffleToggle = { toggleShuffle() },
                    onReorder = { from, to ->
                        if (from in playingQueue.indices && to in playingQueue.indices) {
                            val newList = playingQueue.toMutableList()
                            Collections.swap(newList, from, to)
                            playingQueue = newList
                            if (currentIndex == from) currentIndex = to
                            else if (currentIndex == to) currentIndex = from
                        }
                    },
                    onDismiss = { showFullPlayer = false },
                    onArtistClick = { artist ->
                        // 検索状態をクリアして、アーティストタブへ移動・展開
                        isSearchActive = false
                        searchQuery = ""
                        targetArtist = artist
                        val index = tabOrder.indexOf(TabType.ARTISTS)
                        selectedTabIndex = if (index >= 0) index else 0
                        showFullPlayer = false
                    },
                    onAlbumClick = { album ->
                        // 検索状態をクリアして、アルバムタブへ移動・展開
                        isSearchActive = false
                        searchQuery = ""
                        targetAlbum = album
                        val index = tabOrder.indexOf(TabType.ALBUMS)
                        selectedTabIndex = if (index >= 0) index else 0
                        showFullPlayer = false
                    }
                )
            }
        }
    }
}

// 時間をフォーマットする関数
private fun formatTime(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

// アーティスト名を正規化する関数（feat.などを除去）
private fun normalizeArtistName(artist: String): String {
    val patterns = listOf(" feat.", " Feat.", " FEAT.", " ft.", " Ft.", " FT.", " with ", " With ", " & ", " x ", " X ")
    var normalized = artist
    patterns.forEach { pattern ->
        val index = normalized.indexOf(pattern, ignoreCase = true)
        if (index > 0) normalized = normalized.substring(0, index).trim()
    }
    return normalized
}

// アルバムアートを取得する関数
@Composable
fun rememberAlbumArt(context: Context, uri: Uri): android.graphics.Bitmap? {
    return remember(uri) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val art = retriever.embeddedPicture
            retriever.release()
            art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * ミニプレイヤーバー - 画面下部に表示
 */
@Composable
fun MiniPlayerBar(
    context: Context,
    song: Song,
    isPlaying: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onClick: () -> Unit
) {
    val albumArt = rememberAlbumArt(context, song.uri)
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // アルバムアート
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(
                        bitmap = albumArt.asImageBitmap(),
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // 曲名とアーティスト
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // シャッフル（ON時に背景表示）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (isShuffleEnabled) 
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        else 
                            Color.Transparent
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onShuffleToggle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        modifier = Modifier.size(20.dp),
                        tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // リピート（ON時に背景表示）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (repeatMode != RepeatMode.OFF) 
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        else 
                            Color.Transparent
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onRepeatToggle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        modifier = Modifier.size(20.dp),
                        tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 前の曲
            IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(28.dp)
                )
            }
            
            // 再生/一時停止
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(32.dp)
                )
            }
            
            // 次の曲
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * フルスクリーンプレイヤー - スワイプダウンで閉じる
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FullScreenPlayer(
    context: Context,
    currentSong: Song,
    currentLyrics: LyricsResult?,
    isLyricsLoading: Boolean,
    isPlaying: Boolean,
    currentPosition: Int,
    duration: Int,
    repeatMode: RepeatMode,
    isShuffleEnabled: Boolean,
    playingQueue: List<Song> = emptyList(),
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onRepeatToggle: () -> Unit,
    onShuffleToggle: () -> Unit,
    onReorder: (Int, Int) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {}
) {
    var offsetY by remember { mutableStateOf(0f) }
    val albumArt = rememberAlbumArt(context, currentSong.uri)
    // キュー表示用の状態
    var showQueue by remember { mutableStateOf(false) }
    // 歌詞表示用の状態
    var showLyrics by remember { mutableStateOf(false) }
    
    // ドラッグ中のアイテムインデックス
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    
    // キュー表示ダイアログ
    if (showQueue) {
        val listState = rememberLazyListState()
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("再生キュー (${playingQueue.size}曲)") },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    // 行高さ（padding 8+8=16dp + コンテンツ高さ〜40dp? → 合計56dp）
                    val density = LocalDensity.current
                    val itemHeightPx = with(density) { 56.dp.toPx() }.toInt()
                    
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(playingQueue.size, key = { playingQueue[it].uri.toString() }) { index ->
                            val song = playingQueue[index]
                            val isCurrent = song.uri == currentSong.uri
                            val isDragging = draggedIndex == index
                            
                            // ドラッグ中のアイテムのオフセット
                            val itemOffset = if (isDragging) dragOffset.toInt() else 0
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (!isDragging) Modifier.animateItem() else Modifier)
                                    .offset { IntOffset(0, itemOffset) }
                                    .zIndex(if (isDragging) 1f else 0f) // ドラッグ中を最前面に
                                    .background(
                                        when {
                                            isDragging -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
                                            isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                            else -> Color.Transparent
                                        }
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 曲番号
                                Text(
                                    "${index + 1}",
                                    modifier = Modifier.width(28.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                // 曲情報
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                
                                // ドラッグハンドル
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "ドラッグ",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .pointerInput(song.uri) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggedIndex = index
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    draggedIndex = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggedIndex = null
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffset += dragAmount.y
                                                    
                                                    val moveBy = (dragOffset / itemHeightPx).toInt()
                                                    if (moveBy != 0) {
                                                        val currentIndex = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                                        val targetIndex = (currentIndex + moveBy).coerceIn(0, playingQueue.size - 1)
                                                        
                                                        if (targetIndex != currentIndex) {
                                                            onReorder(currentIndex, targetIndex)
                                                            draggedIndex = targetIndex
                                                            dragOffset -= moveBy * itemHeightPx
                                                        }
                                                    }
                                                }
                                            )
                                        },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (index < playingQueue.size - 1) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQueue = false }) {
                    Text("閉じる")
                }
            }
        )
    }
    
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (offsetY > 200) {
                            onDismiss()
                        }
                        offsetY = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
                    }
                )
            },
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ヘッダー: 閉じるボタンのみ（中央）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Close",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // アルバムアート
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(
                        bitmap = albumArt.asImageBitmap(),
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 曲名
            Text(
                currentSong.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // アーティスト名 / アルバム名 (v2.5.3: 自動調整レイアウト)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val constraints = maxWidth
                val density = LocalDensity.current
                val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
                val artistAlbumText = "${currentSong.artist} - ${currentSong.album}"
                
                val style = MaterialTheme.typography.bodyLarge
                val measuredResult = textMeasurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(artistAlbumText),
                    style = style,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = with(density) { constraints.roundToPx() })
                )
                
                // 1行に収まらない場合は2段表示
                // hasVisualOverflowはmultiLineだとtrueになるので、1行制限での計測が必要だが、
                // LayoutResultのlineCountやdidOverflowWidthで判定
                
                // 簡易判定: 計測された幅がコンテナ幅より小さいか？
                // measureは制約内で折り返す可能性があるので、
                // constraints無制限でmeasureしてwidthを見るのが確実。
                val infiniteWidthConstraints = androidx.compose.ui.unit.Constraints(maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
                val unconstrainedResult = textMeasurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(artistAlbumText),
                    style = style,
                    constraints = infiniteWidthConstraints
                )
                
                // 90%を超えたら2段にする（パディングやレンダリング誤差のマージン）
                val isOverflowing = with(density) { unconstrainedResult.size.width.toDp() } > (constraints * 0.9f)

                if (!isOverflowing) {
                    // 1行表示 (横並び)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                         Text(
                            text = currentSong.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.clickable { onArtistClick(currentSong.artist) } // weightなし、fit content
                        )
                        Text(
                            text = " - ",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentSong.album,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.clickable { onAlbumClick(currentSong.album) } // weightなし
                        )
                    }
                } else {
                    // 2段表示
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentSong.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable { onArtistClick(currentSong.artist) }
                        )
                        Text(
                            text = currentSong.album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable { onAlbumClick(currentSong.album) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // プログレスバー
            Slider(
                value = currentPosition.toFloat(),
                onValueChange = onSeek,
                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                modifier = Modifier.fillMaxWidth()
            )
            
            // 時間表示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(currentPosition), style = MaterialTheme.typography.bodySmall)
                Text("-${formatTime(duration - currentPosition)}", style = MaterialTheme.typography.bodySmall)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // メイン再生コントロール
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // シャッフル（ON時に背景表示）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (isShuffleEnabled) 
                                MaterialTheme.colorScheme.surfaceVariant 
                            else 
                                MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onShuffleToggle) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // 前の曲
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
                }
                
                // 再生/一時停止
                IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(56.dp)
                    )
                }
                
                // 次の曲
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
                }
                
                // リピート（ON時に背景表示）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (repeatMode != RepeatMode.OFF) 
                                MaterialTheme.colorScheme.surfaceVariant 
                            else 
                                MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onRepeatToggle) {
                        Icon(
                            if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // ボトムコントロール（歌詞・キュー）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, // 両端配置
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左下: 歌詞ボタン
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Subject, // 歌詞アイコン代用
                        contentDescription = "Lyrics",
                        modifier = Modifier.size(28.dp),
                        tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 右下: キューボタン

                IconButton(onClick = { showQueue = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = "Queue",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    
    // 歌詞オーバーレイ (最前面)
    if (showLyrics) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .clickable(enabled = false) {} // タッチ透過防止
        ) {
            LyricsView(
                lyrics = currentLyrics,
                isLoading = isLyricsLoading,
                artist = currentSong.artist,
                title = currentSong.title,
                currentPosition = currentPosition,
                onSeek = { onSeek(it.toFloat()) }, // 歌詞タップでシーク
                onDismiss = { showLyrics = false }
            )
        }
    }
}

@Composable
fun LyricsView(
    lyrics: LyricsResult?,
    isLoading: Boolean,
    artist: String,
    title: String,
    currentPosition: Int,
    onSeek: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            // 読み込み中 (v2.5.6)
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("歌詞を読み込み中...", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss) { Text("閉じる") }
            }
        } else if (lyrics == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("歌詞が見つかりません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "検索条件:\n$artist - $title",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss) { Text("閉じる") }
            }
        } else {
            val syncedLines = remember(lyrics) { LyricsManager.parseLrc(lyrics.syncedLyrics) }
            val hasSynced = syncedLines.isNotEmpty()
            
            if (hasSynced) {
                val listState = rememberLazyListState()
                
                // 自動スクロール
                LaunchedEffect(currentPosition, syncedLines) {
                    val index = syncedLines.indexOfLast { it.timeMs <= currentPosition }
                    if (index >= 0) {
                        // 画面中央付近に来るようにスクロール
                        listState.animateScrollToItem((index - 1).coerceAtLeast(0), 0)
                    }
                }
                
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 200.dp), // 上下の余白
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(syncedLines.size) { index ->
                        val line = syncedLines[index]
                        val isCurrent = if (index == syncedLines.size - 1) {
                            currentPosition >= line.timeMs
                        } else {
                            currentPosition >= line.timeMs && currentPosition < syncedLines[index + 1].timeMs
                        }
                        
                        Text(
                            text = line.text,
                            style = if (isCurrent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 16.dp)
                                .clickable { onSeek(line.timeMs.toInt()) }
                        )
                    }
                }
            } else {
                // プレーンテキスト
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = lyrics.plainLyrics ?: "歌詞データがありません",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
            
            // 閉じるボタン (右上)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Close, "閉じる")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    useSystemMediaController: Boolean,
    onUseSystemMediaControllerChange: (Boolean) -> Unit,
    isScanning: Boolean,
    scanProgress: Float,
    scanCount: Int,
    scanTotal: Int,
    onStartScan: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var sourceFolders by remember { mutableStateOf(getUriList(context, KEY_SOURCE_FOLDER_URIS)) }
    var tabOrder by remember { mutableStateOf(getTabOrder(context)) }
    // Local scan state removed - using passed state
    // プレイリスト用ベースパス設定
    var playlistBasePath by remember { mutableStateOf(getPlaylistBasePath(context)) }
    // スキャン中は画面をスリープさせない (Moved to MusicApp, but kept solely for safety if used independently, 
    // though MusicApp handles it via DisposableEffect. Removed here to avoid duplication if MusicApp handles it)
    // Actually, MusicApp's DisposableEffect works for the whole app window, so we don't need it here.

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri: Uri? ->
            treeUri?.let { uri ->
                // 権限永続化
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) { e.printStackTrace() }
                
                // フォルダリスト保存
                coroutineScope.launch {
                    val currentFolders = getUriList(context, KEY_SOURCE_FOLDER_URIS).toMutableSet()
                    currentFolders.add(uri)
                    saveUriList(context, KEY_SOURCE_FOLDER_URIS, currentFolders.toList())
                    sourceFolders = currentFolders.toList() // UI更新
                    
                    // スキャン開始 (Service) via Callback
                    onStartScan(listOf(uri))
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "バックグラウンドでスキャンを開始しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            item {
                Text("設定", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                // MediaSession設定
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("システムメディアプレイヤーを使用", modifier = Modifier.weight(1f))
                    Switch(checked = useSystemMediaController, onCheckedChange = onUseSystemMediaControllerChange)
                }
                Text("通知パネルのデザインを変更します", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                
                // 存在しないファイル表示設定
                var showMissingFiles by remember { mutableStateOf(getShowMissingFiles(context)) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("存在しないファイルを表示", modifier = Modifier.weight(1f))
                    Switch(
                        checked = showMissingFiles,
                        onCheckedChange = {
                            showMissingFiles = it
                            saveShowMissingFiles(context, it)
                        }
                    )
                }
                Text("プレイリスト内のファイルがない曲をグレーアウトで表示します", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text("ライブラリ管理", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { folderPickerLauncher.launch(null) }) { Text("音楽フォルダを追加") }
                Spacer(Modifier.height(16.dp))
                Text("スキャン対象フォルダ:")

                sourceFolders.forEach { uri ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uri.path ?: "不明なフォルダ", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = {
                            if (isScanning) {
                                // Stop scanning if running
                                val intent = Intent(context, MusicScanService::class.java).apply {
                                    action = MusicScanService.ACTION_CANCEL_SCAN
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                                Toast.makeText(context, "スキャンを中断しました", Toast.LENGTH_SHORT).show()
                            }
                            
                            coroutineScope.launch {
                                val remainingFolders = sourceFolders.filterNot { it == uri }
                                saveUriList(context, KEY_SOURCE_FOLDER_URIS, remainingFolders)
                                sourceFolders = remainingFolders
                                val existingSongs = loadLibraryFromFile(context) ?: emptyList()
                                val updatedSongs = existingSongs.filterNot { it.sourceFolderUri == uri }
                                saveLibraryToFile(context, updatedSongs)
                                withContext(Dispatchers.Main) { Toast.makeText(context, "フォルダをライブラリから削除しました", Toast.LENGTH_SHORT).show() }
                            }
                        }) { Icon(Icons.Default.Delete, "Remove folder") }

                        IconButton(onClick = {
                            if (isScanning) return@IconButton
                            // Trigger service scanning
                            onStartScan(listOf(uri))
                        }) { Icon(Icons.Filled.Refresh, "Rescan folder") }
                    }
                }

                if (isScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // Indeterminate
                    Text(
                        text = "読み込み中... (${scanCount}曲)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            item {
                Text("タブの順序", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
            }
            items(tabOrder.size, key = { tabOrder[it] }) { index ->
                val tab = tabOrder[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (index > 0) {
                            val newOrder = tabOrder.toMutableList()
                            Collections.swap(newOrder, index, index - 1)
                            tabOrder = newOrder
                            saveTabOrder(context, newOrder)
                        }
                    }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Move Up") }
                    IconButton(onClick = {
                        if (index < tabOrder.size - 1) {
                            val newOrder = tabOrder.toMutableList()
                            Collections.swap(newOrder, index, index + 1)
                            tabOrder = newOrder
                            saveTabOrder(context, newOrder)
                        }
                    }, enabled = index < tabOrder.size - 1) { Icon(Icons.Default.ArrowDownward, "Move Down") }
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text("プレイリスト設定", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("M3Uファイル内のWindowsパスのベース部分を設定します。", style = MaterialTheme.typography.bodySmall)
                Text("例: C:\\Users\\Music\\ （末尾のバックスラッシュを含む）", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = playlistBasePath,
                    onValueChange = { playlistBasePath = it },
                    label = { Text("ベースパス") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    savePlaylistBasePath(context, playlistBasePath)
                    Toast.makeText(context, "ベースパスを保存しました", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                // キャッシュ設定 (v2.5.0)
                Text("ストレージ設定", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        AlbumArtCache.clear(context)
                        Toast.makeText(context, "キャッシュを削除しました", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("画像キャッシュを削除")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                // バージョン情報 + アップデート統合
                Text("バージョン情報", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("App Version: $APP_VERSION", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                
                var isCheckingUpdate by remember { mutableStateOf(false) }
                var updateInfo by remember { mutableStateOf<String?>(null) }
                var latestVersion by remember { mutableStateOf<String?>(null) }
                var downloadUrl by remember { mutableStateOf<String?>(null) }
                var updateReleaseNotes by remember { mutableStateOf<String?>(null) }
                var showUpdateConfirmDialog by remember { mutableStateOf(false) }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            updateInfo = null
                            coroutineScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        checkForUpdate()
                                    }
                                    latestVersion = result.first
                                    downloadUrl = result.second
                                    updateReleaseNotes = result.third
                                    
                                    if (latestVersion != null) {
                                        val normalize = { v: String -> v.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 } }
                                        val currentParts = normalize(APP_VERSION)
                                        val latestParts = normalize(latestVersion!!)
                                        
                                        var isNewer = false
                                        val length = maxOf(currentParts.size, latestParts.size)
                                        for (i in 0 until length) {
                                            val c = currentParts.getOrElse(i) { 0 }
                                            val l = latestParts.getOrElse(i) { 0 }
                                            if (l > c) {
                                                isNewer = true
                                                break
                                            }
                                            if (l < c) break
                                        }

                                        if (isNewer) {
                                            updateInfo = "新しいバージョン $latestVersion が利用可能です"
                                            showUpdateConfirmDialog = true
                                        } else {
                                            updateInfo = "最新バージョンです"
                                        }
                                    }
                                } catch (e: Exception) {
                                    updateInfo = "エラー: ${e.message}"
                                }
                                isCheckingUpdate = false
                            }
                        },
                        enabled = !isCheckingUpdate
                    ) {
                        Text(if (isCheckingUpdate) "確認中..." else "更新を確認")
                    }
                    
                    updateInfo?.let {
                        Spacer(Modifier.width(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (showUpdateConfirmDialog && latestVersion != null && downloadUrl != null) {
                    AlertDialog(
                        onDismissRequest = { showUpdateConfirmDialog = false },
                        title = { Text("アップデートあり") },
                        text = {
                            Column {
                                Text("新しいバージョン $latestVersion が見つかりました。")
                                if (!updateReleaseNotes.isNullOrBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("変更点:", style = MaterialTheme.typography.titleSmall)
                                    Text(updateReleaseNotes!!, style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(Modifier.height(16.dp))
                                Text("ダウンロードして更新しますか？")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showUpdateConfirmDialog = false
                                downloadAndInstallApk(context, downloadUrl!!)
                            }) { Text("更新") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUpdateConfirmDialog = false }) { Text("キャンセル") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SongsTab(songList: List<Song>, currentSong: Song?, sortType: SortType, sortOrder: SortOrder, onSongClick: (Song, List<Song>) -> Unit, onSortTypeChange: (SortType) -> Unit, onSortOrderChange: (SortOrder) -> Unit) {
    val sortedList = remember(songList, sortType, sortOrder) {
        val comparator = when (sortType) {
            SortType.DEFAULT -> compareBy { song: Song -> song.displayName } // ファイル名順
            SortType.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { song: Song -> song.title }
            SortType.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { song: Song -> song.artist }
            SortType.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { song: Song -> song.album }
            SortType.PLAY_COUNT -> compareBy { song: Song -> song.playCount }
        }
        if (sortOrder == SortOrder.DESC) songList.sortedWith(comparator.reversed()) else songList.sortedWith(comparator)
    }
    SongList(
        songs = sortedList, 
        currentSong = currentSong, 
        onSongClick = onSongClick,
        onPlayAll = { songs -> onSongClick(songs.first(), songs) },
        onShuffleAll = { songs -> 
            val shuffled = songs.shuffled()
            onSongClick(shuffled.first(), shuffled)
        },
        headerContent = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), 
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Button(onClick = { expanded = true }) {
                        Text(when(sortType){
                            SortType.DEFAULT -> "標準"; SortType.TITLE -> "曲名"; SortType.ARTIST -> "アーティスト"
                            SortType.ALBUM -> "アルバム"; SortType.PLAY_COUNT -> "再生回数"
                        })
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("標準") }, onClick = { onSortTypeChange(SortType.DEFAULT); expanded = false })
                        DropdownMenuItem(text = { Text("曲名") }, onClick = { onSortTypeChange(SortType.TITLE); expanded = false })
                        DropdownMenuItem(text = { Text("アーティスト") }, onClick = { onSortTypeChange(SortType.ARTIST); expanded = false })
                        DropdownMenuItem(text = { Text("アルバム") }, onClick = { onSortTypeChange(SortType.ALBUM); expanded = false })
                        DropdownMenuItem(text = { Text("再生回数") }, onClick = { onSortTypeChange(SortType.PLAY_COUNT); expanded = false })
                    }
                }
                Button(onClick = { onSortOrderChange(if (sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC) }) { 
                    Text(if (sortOrder == SortOrder.ASC) "昇順" else "降順") 
                }
            }
            HorizontalDivider()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistTab(playlists: List<Playlist>, songList: List<Song>, currentSong: Song?, onPlaylistChanged: (List<Playlist>) -> Unit, onSongClick: (Song, List<Song>) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    // 戻るボタンのハンドリング
    BackHandler(enabled = selectedPlaylist != null) {
        selectedPlaylist = null
    }

    // 削除確認ダイアログ用の状態
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    
    val playlistPickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            // ベースパスをメインスレッドで取得
            val basePath = getPlaylistBasePath(context)
            coroutineScope.launch {
                // 修正: クラッシュを防ぐため、安全にプレイリストを解析し、結果をUIに反映
                val newPlaylist = parseM3uPlaylist(context, it, songList, basePath)
                if (newPlaylist != null && newPlaylist.songs.isNotEmpty()) {
                    val updatedPlaylists = playlists + newPlaylist
                    savePlaylistsToFile(context, updatedPlaylists)
                    withContext(Dispatchers.Main) { onPlaylistChanged(updatedPlaylists) }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "プレイリストを読み込めませんでした（空のプレイリストか、一致する曲がありません）", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    // 削除確認ダイアログ
    if (playlistToDelete != null) {
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("プレイリストを削除") },
            text = { Text("「${playlistToDelete!!.name}」をリストから削除しますか？\n（元のファイルは削除されません）") },
            confirmButton = {
                TextButton(onClick = {
                    val updatedPlaylists = playlists.filter { it != playlistToDelete }
                    coroutineScope.launch {
                        savePlaylistsToFile(context, updatedPlaylists)
                        withContext(Dispatchers.Main) { 
                            onPlaylistChanged(updatedPlaylists)
                            Toast.makeText(context, "プレイリストを削除しました", Toast.LENGTH_SHORT).show()
                        }
                    }
                    playlistToDelete = null
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("キャンセル")
                }
            }
        )
    }
    
    // プレイリストが選択されている場合：曲リストを表示
    if (selectedPlaylist != null) {
        PlaylistDetailScreen(
            playlist = selectedPlaylist!!,
            currentSong = currentSong,
            onSongClick = onSongClick,
            onBack = { selectedPlaylist = null }
        )
    } else {
        // プレイリスト一覧を表示（フォルダ形式）
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = { playlistPickerLauncher.launch("*/*") }, enabled = songList.isNotEmpty()) { 
                Text("プレイリスト(.m3u)を追加") 
            }
            if (songList.isEmpty()) { 
                Text("先に音楽フォルダを選択してください", style = MaterialTheme.typography.bodySmall) 
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            if (playlists.isEmpty()) {
                Text("プレイリストがありません", style = MaterialTheme.typography.bodyMedium)
            } else {
                 // 文言削除
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(state = listState) {
                        items(playlists) { playlist ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { selectedPlaylist = playlist },
                                        onLongClick = { playlistToDelete = playlist }
                                    )
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${playlist.songs.size} 曲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        listState = listState
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlist: Playlist, currentSong: Song?, onSongClick: (Song, List<Song>) -> Unit, onBack: () -> Unit) {
    var sortType by remember { mutableStateOf(SortType.DEFAULT) }
    var sortOrder by remember { mutableStateOf(SortOrder.ASC) }

    val sortedSongs = remember(playlist.songs, sortType, sortOrder) {
        if (sortType == SortType.DEFAULT) {
            // 標準：M3Uファイルの記載順序をそのまま維持
            playlist.songs
        } else {
            val comparator = when (sortType) {
                SortType.DEFAULT -> compareBy { song: Song -> 0 } // 使われない
                SortType.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { song: Song -> song.title }
                SortType.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { song: Song -> song.artist }
                SortType.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { song: Song -> song.album }
                SortType.PLAY_COUNT -> compareBy { song: Song -> song.playCount }
            }
            if (sortOrder == SortOrder.DESC) playlist.songs.sortedWith(comparator.reversed()) else playlist.songs.sortedWith(comparator)
        }
    }

    Column {
        TopAppBar(title = { Text(playlist.name) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        
        SongList(
            songs = sortedSongs, 
            currentSong = currentSong, 
            onSongClick = onSongClick,
            onPlayAll = { songs -> onSongClick(songs.first(), songs) },
            onShuffleAll = { songs -> 
                val shuffled = songs.shuffled()
                onSongClick(shuffled.first(), shuffled)
            },
            headerContent = {
                // プレイリスト内ソート機能
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), 
                    horizontalArrangement = Arrangement.SpaceBetween, 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        Button(onClick = { expanded = true }) {
                            Text(when(sortType){
                                SortType.DEFAULT -> "標準"; SortType.TITLE -> "曲名"; SortType.ARTIST -> "アーティスト"
                                SortType.ALBUM -> "アルバム"; SortType.PLAY_COUNT -> "再生回数"
                            })
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("標準") }, onClick = { sortType = SortType.DEFAULT; expanded = false })
                            DropdownMenuItem(text = { Text("曲名") }, onClick = { sortType = SortType.TITLE; expanded = false })
                            DropdownMenuItem(text = { Text("アーティスト") }, onClick = { sortType = SortType.ARTIST; expanded = false })
                            DropdownMenuItem(text = { Text("アルバム") }, onClick = { sortType = SortType.ALBUM; expanded = false })
                            DropdownMenuItem(text = { Text("再生回数") }, onClick = { sortType = SortType.PLAY_COUNT; expanded = false })
                        }
                    }
                    Button(onClick = { sortOrder = if (sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC }) { 
                        Text(if (sortOrder == SortOrder.ASC) "昇順" else "降順") 
                    }
                }
                HorizontalDivider()
            }
        )
    }
}

/**
 * フォルダ（アーティスト/アルバム）の一覧を表示するための共通コンポーザブル
 * @param items 表示する文字列のリスト
 * @param onItemClick アイテムがクリックされたときのコールバック
 */
@Composable
fun FolderList(
    items: List<String>,
    onItemClick: (String) -> Unit
) {
    var sortOrder by remember { mutableStateOf(SortOrder.ASC) }
    // 昇順/降順でリストをソート
    val sortedItems = remember(items, sortOrder) {
        if (sortOrder == SortOrder.ASC) {
            items.sortedWith(String.CASE_INSENSITIVE_ORDER)
        } else {
            items.sortedWith(String.CASE_INSENSITIVE_ORDER).reversed()
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("項目数: ${items.size}")
            Spacer(modifier = Modifier.weight(1f))
            // ソート順を切り替えるボタン
            Button(onClick = { sortOrder = if (sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC }) {
                Text(if (sortOrder == SortOrder.ASC) "昇順" else "降順")
            }
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(sortedItems) { item ->
                    Column(modifier = Modifier.clickable { onItemClick(item) }) {
                        // "Unknown" のような項目は「不明」と表示
                        Text(item.ifBlank { "不明" }, Modifier.fillMaxWidth().padding(16.dp))
                        HorizontalDivider()
                    }
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                listState = listState
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistFolderTab(
    songList: List<Song>, 
    onSongClick: (Song, List<Song>) -> Unit,
    targetArtist: String? = null,
    onTargetConsume: () -> Unit = {}
) {
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }

    // 外部からの遷移リクエストを処理
    LaunchedEffect(targetArtist) {
        if (targetArtist != null) {
            selectedArtist = normalizeArtistName(targetArtist)
            selectedAlbum = null
            onTargetConsume()
        }
    }

    // 戻るボタンのハンドリング
    BackHandler(enabled = selectedArtist != null) {
        if (selectedAlbum != null) {
            selectedAlbum = null
        } else {
            selectedArtist = null
        }
    }

    when {
        // 3. アーティストとアルバムが選択されたら、曲リストを表示
        selectedArtist != null && selectedAlbum != null -> {
            val songs = remember(songList, selectedArtist, selectedAlbum) {
                // 正規化されたアーティスト名でフィルタリング
                songList.filter { normalizeArtistName(it.artist) == selectedArtist && it.album == selectedAlbum }
                    // 修正: 曲順ソートを改善。トラック番号がある曲を優先し、その後タイトルでソート
                    .sortedWith(
                        compareBy<Song> { if (it.trackNumber > 0) 0 else 1 }
                            .thenBy { it.trackNumber }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    )
            }
            Column {
                TopAppBar(
                    title = { Text(selectedAlbum!!.ifBlank { "不明なアルバム" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { selectedAlbum = null }) { // アルバム選択に戻る
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
                SongList(
                    songs = songs, 
                    currentSong = null, 
                    onSongClick = onSongClick,
                    onPlayAll = { songList -> onSongClick(songList.first(), songList) },
                    onShuffleAll = { songList -> 
                        val shuffled = songList.shuffled()
                        onSongClick(shuffled.first(), shuffled)
                    }
                )
            }
        }
        // 2. アーティストが選択されたら、そのアーティストのアルバムリストを表示
        selectedArtist != null -> {
            val albums = remember(songList, selectedArtist) {
                // 正規化されたアーティスト名でフィルタリング
                songList.filter { normalizeArtistName(it.artist) == selectedArtist }
                    .map { it.album }.distinct()
            }
            Column {
                TopAppBar(
                    title = { Text(selectedArtist!!.ifBlank { "不明なアーティスト" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { selectedArtist = null }) { // アーティスト選択に戻る
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
                // 共通のFolderListを使用してアルバム一覧を表示
                FolderList(items = albums, onItemClick = { selectedAlbum = it })
            }
        }
        // 1. 最初はアーティストリストを表示（正規化してfeat.を統一）
        else -> {
            val artists = remember(songList) {
                songList.map { normalizeArtistName(it.artist) }.distinct().sorted()
            }
            // 共通のFolderListを使用してアーティスト一覧を表示
            FolderList(items = artists, onItemClick = { selectedArtist = it })
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumFolderTab(
    songList: List<Song>, 
    onSongClick: (Song, List<Song>) -> Unit,
    targetAlbum: String? = null,
    onTargetConsume: () -> Unit = {}
) {
    var selectedAlbum by remember { mutableStateOf<String?>(null) }

    // 外部からの遷移リクエストを処理
    LaunchedEffect(targetAlbum) {
        if (targetAlbum != null) {
            selectedAlbum = targetAlbum
            onTargetConsume()
        }
    }

    // 戻るボタンのハンドリング
    BackHandler(enabled = selectedAlbum != null) {
        selectedAlbum = null
    }

    if (selectedAlbum != null) {
        val songsByAlbum = remember(songList, selectedAlbum) {
            songList.filter { it.album == selectedAlbum }
                // 修正: 曲順ソートを改善。
                .sortedWith(
                    compareBy<Song> { if (it.trackNumber > 0) 0 else 1 }
                        .thenBy { it.trackNumber }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
        }
        Column {
            TopAppBar(
                title = { Text(selectedAlbum!!.ifBlank { "不明なアルバム" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { selectedAlbum = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
            SongList(
                songs = songsByAlbum, 
                currentSong = null, 
                onSongClick = onSongClick,
                onPlayAll = { songs -> onSongClick(songs.first(), songs) },
                onShuffleAll = { songs -> 
                    val shuffled = songs.shuffled()
                    onSongClick(shuffled.first(), shuffled)
                }
            )
        }
    } else {
        // 最初にアルバム一覧を表示
        val albums = remember(songList) { songList.map { it.album }.distinct() }
        FolderList(items = albums, onItemClick = { selectedAlbum = it })
    }
}

@Composable
fun SongList(
    songs: List<Song>, 
    currentSong: Song?, 
    onSongClick: (Song, List<Song>) -> Unit,
    onPlayAll: ((List<Song>) -> Unit)? = null,
    onShuffleAll: ((List<Song>) -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null
) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            // ヘッダー（並び替えボタンなど）- スクロールと一緒に動く
            if (headerContent != null) {
                item { headerContent() }
            }
            
            // 再生ボタンとシャッフルボタン - スクロールと一緒に動く
            if (songs.isNotEmpty() && (onPlayAll != null || onShuffleAll != null)) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onPlayAll != null) {
                            Button(
                                onClick = { onPlayAll(songs) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("再生", maxLines = 1)
                            }
                        }
                        if (onShuffleAll != null) {
                            OutlinedButton(
                                onClick = { onShuffleAll(songs) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("シャッフル", maxLines = 1)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            
            // 曲リスト
            // 曲リスト
            items(songs, key = { it.uri.toString() }) { song -> SongListItem(song, (song == currentSong)) { onSongClick(it, songs) } }
        }
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            listState = listState
        )
    }
}

@Composable
fun SongListItem(song: Song, isCurrentlyPlaying: Boolean, onSongClick: (Song) -> Unit) {
    val context = LocalContext.current
    
    // アルバムアートを非同期で取得
    // アルバムアートを非同期で取得 (キャッシュ対応 v2.4.5)
    var albumArtBitmap by remember(song.uri) { mutableStateOf<android.graphics.Bitmap?>(AlbumArtCache.get(context, song.uri.toString())) }
    LaunchedEffect(song.uri) {
        if (albumArtBitmap == null) {
            withContext(Dispatchers.IO) {
                try {
                    // キャッシュ再確認
                    val cached = AlbumArtCache.get(context, song.uri.toString())
                    if (cached != null) {
                        albumArtBitmap = cached
                    } else {
                        val retriever = MediaMetadataRetriever()
                        context.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            val art = retriever.embeddedPicture
                            if (art != null) {
                                // 最適化: ダウンサンプリングして読み込み (メモリ節約)
                                val options = android.graphics.BitmapFactory.Options()
                                options.inJustDecodeBounds = true
                                android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, options)
                                
                                // ターゲットサイズ: 144x144 (48dp * 3 density)
                                options.inSampleSize = calculateInSampleSize(options, 144, 144)
                                options.inJustDecodeBounds = false
                                
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size, options)
                                AlbumArtCache.put(context, song.uri.toString(), bitmap)
                                albumArtBitmap = bitmap
                            }
                        }
                        retriever.release()
                    }
                } catch (e: Exception) {
                    // アート取得失敗は無視
                }
            }
        }
    }
    
    // 存在しないファイルはグレーアウト表示
    val textColor = if (song.exists) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }
    val subTextColor = if (song.exists) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSongClick(song) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // アルバムアートサムネイル（48dp）
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (albumArtBitmap != null) {
                Image(
                    bitmap = albumArtBitmap!!.asImageBitmap(),
                    contentDescription = "Album Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = "No Art",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // 曲情報
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${song.artist} - ${song.album}" + if (song.exists) " (再生: ${song.playCount}回)" else " [ファイルなし]",
                style = MaterialTheme.typography.bodySmall,
                color = subTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    HorizontalDivider()
}

// --- Logic Functions ---

/**
 * Content URI からファイル名を取得する。取得できない場合はパスから推測する。
 */
private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (columnIndex != -1) {
                    result = cursor.getString(columnIndex)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path
        result?.lastIndexOf('/')?.let {
            if (it != -1) {
                result = result?.substring(it + 1)
            }
        }
    }
    return result
}

private fun getTabOrder(context: Context): List<TabType> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val orderString = prefs.getString(KEY_TAB_ORDER, null)
    return if (orderString != null) {
        orderString.split(',').mapNotNull { try { TabType.valueOf(it) } catch (e: IllegalArgumentException) { null } }
    } else {
        listOf(TabType.SONGS, TabType.PLAYLISTS, TabType.ARTISTS, TabType.ALBUMS)
    }
}
private fun saveTabOrder(context: Context, order: List<TabType>) {
    val orderString = order.joinToString(",") { it.name }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_TAB_ORDER, orderString).apply()
}

// プレイリスト用ベースパスの保存・取得関数
private fun savePlaylistBasePath(context: Context, basePath: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_PLAYLIST_BASE_PATH, basePath).apply()
}
private fun getPlaylistBasePath(context: Context): String {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_PLAYLIST_BASE_PATH, "") ?: ""
}

// MediaSession設定の保存・取得関数
private fun saveUseSystemMediaController(context: Context, use: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_USE_SYSTEM_MEDIA_CONTROLLER, use).apply()
}
private fun getUseSystemMediaController(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_SYSTEM_MEDIA_CONTROLLER, false)
}

// 存在しないファイル表示設定の保存・取得関数
private fun saveShowMissingFiles(context: Context, show: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_SHOW_MISSING_FILES, show).apply()
}
private fun getShowMissingFiles(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_SHOW_MISSING_FILES, true) // デフォルトは表示
}

private fun saveUriList(context: Context, key: String, uris: List<Uri>) {
    val uriStrings = uris.map { it.toString() }.toSet()
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putStringSet(key, uriStrings).apply()
}
private fun getUriList(context: Context, key: String): List<Uri> {
    val uriStrings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()
    return uriStrings.map { Uri.parse(it) }
}
private suspend fun saveLibraryToFile(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
    val jsonArray = JSONArray()
    songs.forEach { song ->
        val jsonObject = JSONObject().apply {
            put("uri", song.uri.toString())
            put("displayName", song.displayName)
            put("title", song.title)
            put("artist", song.artist)
            put("album", song.album)
            put("playCount", song.playCount)
            put("trackNumber", song.trackNumber)
            put("duration", song.duration)
            put("sourceFolderUri", song.sourceFolderUri.toString())
        }
        jsonArray.put(jsonObject)
    }
    File(context.filesDir, LIBRARY_CACHE_FILE).writeText(jsonArray.toString())
}
private suspend fun loadLibraryFromFile(context: Context): List<Song>? = withContext(Dispatchers.IO) {
    val file = File(context.filesDir, LIBRARY_CACHE_FILE)
    if (!file.exists()) return@withContext null
    try {
        val jsonString = file.readText()
        val jsonArray = JSONArray(jsonString)
        val songs = mutableListOf<Song>()
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            songs.add(Song(
                uri = Uri.parse(jsonObject.getString("uri")),
                displayName = jsonObject.getString("displayName"),
                title = jsonObject.getString("title"),
                artist = jsonObject.getString("artist"),
                album = jsonObject.getString("album"),
                playCount = jsonObject.getInt("playCount"),
                trackNumber = jsonObject.optInt("trackNumber", 0),
                duration = jsonObject.optLong("duration", 0L),
                sourceFolderUri = Uri.parse(jsonObject.optString("sourceFolderUri"))
            ))
        }
        return@withContext songs
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    }
}
private suspend fun savePlaylistsToFile(context: Context, playlists: List<Playlist>) = withContext(Dispatchers.IO) {
    val jsonArray = JSONArray()
    playlists.forEach { playlist ->
        val jsonObject = JSONObject().apply {
            put("name", playlist.name)
            put("songUris", JSONArray(playlist.songs.map { it.uri.toString() }))
        }
        jsonArray.put(jsonObject)
    }
    File(context.filesDir, PLAYLIST_CACHE_FILE).writeText(jsonArray.toString())
}
private suspend fun loadPlaylistsFromFile(context: Context, allSongs: List<Song>): List<Playlist>? = withContext(Dispatchers.IO) {
    val file = File(context.filesDir, PLAYLIST_CACHE_FILE)
    if (!file.exists()) return@withContext null
    try {
        val jsonString = file.readText()
        val jsonArray = JSONArray(jsonString)
        val playlists = mutableListOf<Playlist>()
        val songMap = allSongs.associateBy { it.uri.toString() }
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            val name = jsonObject.getString("name")
            val songUris = jsonObject.getJSONArray("songUris")
            val songsInPlaylist = mutableListOf<Song>()
            for (j in 0 until songUris.length()) {
                val uriString = songUris.getString(j)
                val existingSong = songMap[uriString]
                if (existingSong != null) {
                    songsInPlaylist.add(existingSong)
                } else {
                    // 存在しないファイル: プレースホルダーSongを作成 (exists=false)
                    val uri = Uri.parse(uriString)
                    val fileName = uriString.substringAfterLast("/").substringAfterLast("%2F")
                    songsInPlaylist.add(Song(
                        uri = uri,
                        displayName = fileName,
                        title = fileName.substringBeforeLast("."),
                        artist = "Unknown Artist",
                        album = "Unknown Album",
                        playCount = 0,
                        trackNumber = 0,
                        duration = 0L,
                        sourceFolderUri = Uri.EMPTY,
                        exists = false
                    ))
                }
            }
            playlists.add(Playlist(name, songsInPlaylist))
        }
        return@withContext playlists
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    }
}
private suspend fun countFilesInDirectory(context: Context, directoryUri: Uri): Int = withContext(Dispatchers.IO) {
    var count = 0

    // 高速化: Uriから実際のファイルパスを推測してjava.io.Fileを使用する
    // SAFでのファイルカウント
    val contentResolver = context.contentResolver
    val documentsTree = DocumentsContract.buildDocumentUriUsingTree(directoryUri, DocumentsContract.getTreeDocumentId(directoryUri))
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )
    
    fun countInDirectory(currentUri: Uri): Int {
        var localCount = 0
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(currentUri))
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || mimeCol == -1) continue
                    val docId = cursor.getString(idCol) ?: continue
                    val mimeType = cursor.getString(mimeCol) ?: ""

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        localCount += countInDirectory(DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId))
                    } else {
                        val name = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (mimeType.startsWith("audio/") || mimeType == "application/ogg" || 
                            setOf("mp3", "m4a", "flac", "wav", "aac", "ogg").contains(ext)) {
                            localCount++
                        }
                    }
                }
            }
        } catch (e: Exception) { /* 無視 */ }
        return localCount
    }
    
    count = countInDirectory(documentsTree)
    return@withContext count
}
private suspend fun getAudioFilesFromDirectory(context: Context, directoryUri: Uri, totalFiles: Int, onProgress: (Float) -> Unit): List<Song> = withContext(Dispatchers.IO) {
    val songList = mutableListOf<Song>()
    var processedCount = 0

    val contentResolver = context.contentResolver
    val documentsTree = DocumentsContract.buildDocumentUriUsingTree(directoryUri, DocumentsContract.getTreeDocumentId(directoryUri))
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME
    )
    
    // 文字化け検出関数
    fun isGarbled(s: String?): Boolean {
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

    // 文字化け修復関数 (Latin-1 to Shift_JIS) (v2.5.1)
    fun fixEncoding(original: String?): String? {
        if (original == null) return null
        try {
            val bytes = original.toByteArray(Charsets.ISO_8859_1)
            val sjis = String(bytes, java.nio.charset.Charset.forName("Shift_JIS"))
            val originalJpCount = original.count { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
            val sjisJpCount = sjis.count { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
            // SJIS変換で日本語が増えれば採用
            if (sjisJpCount > originalJpCount) return sjis
        } catch (e: Exception) {}
        return original
    }

    // 再帰的にディレクトリを探索する関数
    suspend fun traverseDirectory(currentUri: Uri, parentName: String) {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, DocumentsContract.getDocumentId(currentUri))
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    if (idCol == -1 || mimeCol == -1) continue
                    val docId = cursor.getString(idCol) ?: continue
                    val mimeType = cursor.getString(mimeCol) ?: ""
                    val name = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                    
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        traverseDirectory(DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId), name)
                    } else {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        
                        // 拡張子チェック
                        if (mimeType.startsWith("audio/") || mimeType == "application/ogg" || 
                            setOf("mp3", "m4a", "flac", "wav", "aac", "ogg").contains(ext)) {
                            processedCount++
                            val progress = if (totalFiles > 0) processedCount.toFloat() / totalFiles.toFloat() else 0f
                            onProgress(progress)
                            
                            var songTitle = name.substringBeforeLast('.')
                            var songArtist = "Unknown Artist"
                            var songAlbum = parentName
                            var trackNumber = 0
                            var duration = 0L
                            
                            try {
                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId)
                                val localRetriever = MediaMetadataRetriever()
                                try {
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
                                            songAlbum = if (isGarbled(fixedAlbum)) parentName else (fixedAlbum ?: parentName)
                                            
                                            val trackString = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                                            trackNumber = trackString?.substringBefore("/")?.toIntOrNull() ?: 0
                                            
                                            val durationStr = localRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                            duration = durationStr?.toLongOrNull() ?: 0L
                                        }
                                    }
                                } finally {
                                    try { localRetriever.release() } catch (ignored: Exception) {}
                                }
                            } catch (e: Exception) {
                             // メタデータ取得失敗時はデフォルト値（ファイル名、親フォルダ名）を使用
                            }

                            songList.add(Song(DocumentsContract.buildDocumentUriUsingTree(directoryUri, docId), name, songTitle, songArtist, songAlbum, 0, trackNumber, directoryUri, duration))
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    val rootName = getFileName(context, directoryUri) ?: "Unknown Album"
    traverseDirectory(documentsTree, rootName)
    
    // 最後にソート（デフォルト順）
    return@withContext songList.sortedBy { it.title }
}

/**
 * M3Uプレイリストを安全に解析する。
 * Windowsパスから相対パスを抽出し、アーティスト/アルバム/ファイル名でマッチングする。
 * クラッシュを防ぎ、失敗した場合はnullを返す。
 * @param basePath WindowsパスのベースパスM3Uファイル内のパスから除去する部分
 */
private suspend fun parseM3uPlaylist(context: Context, playlistUri: Uri, allSongs: List<Song>, basePath: String): Playlist? = withContext(Dispatchers.IO) {
    return@withContext runCatching {
        val playlistName = getFileName(context, playlistUri)?.replace(".m3u8", "")?.replace(".m3u", "") ?: "プレイリスト"
        val lines = context.contentResolver.openInputStream(playlistUri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readLines()
        }.orEmpty()
        matchM3uLines(playlistName, lines, allSongs, basePath)
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()
}

/**
 * M3U の行を曲に読み替えてプレイリストにする（ファイル選択からの読み込みと、PC からの同期の両方で使う）。
 * 読み替えの順: ファイル名が一意ならそれ → 同名が複数なら末尾 3 段のパス → ベースパスを外したパス → fallback。
 * どれでも見つからない行は、存在しない曲（exists=false）として残す。
 * @param fallback 読み替えで見つからなかった行（前後の空白を除いたもの）から曲を探す追加の手段。無ければ null を返す
 */
private fun matchM3uLines(
    playlistName: String,
    lines: List<String>,
    allSongs: List<Song>,
    basePath: String,
    fallback: (String) -> Song? = { null }
): Playlist {
    val playlistSongs = mutableListOf<Song>()

    // マッチング用のインデックスを作成
    // ファイル名でグループ化し、重複があるかどうかを判定
    val songsByDisplayName = allSongs.groupBy { it.displayName.lowercase() }
    
    // パスベースのマッチング用（フォルダ名/アルバムフォルダ/ファイル名）
    val songsByPath = mutableMapOf<String, Song>()
    allSongs.forEach { song ->
        // URIからパスを抽出してキーを作成
        val uriPath = song.uri.path ?: song.uri.toString()
        val pathParts = uriPath.split("/").filter { it.isNotBlank() }
        if (pathParts.size >= 3) {
            // 最後の3パーツ（親フォルダ/アルバムフォルダ/ファイル名）でマッチング
            val key = "${pathParts[pathParts.size - 3]}/${pathParts[pathParts.size - 2]}/${pathParts.last()}".lowercase()
            songsByPath[key] = song
        }
    }

    lines.forEach { line ->
        if (line.isNotBlank() && !line.startsWith("#")) {
            val trimmedLine = line.trim()
            var matchedSong: Song? = null
            
            // M3Uファイルからファイル名を抽出
            val fileNameFromM3u = trimmedLine.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
            
            // 方法1: ファイル名でマッチング（ユニークな場合のみ）
            val candidatesByName = songsByDisplayName[fileNameFromM3u]
            if (candidatesByName != null && candidatesByName.size == 1) {
                // ファイル名がユニークなら直接マッチ
                matchedSong = candidatesByName.first()
            } else if (candidatesByName != null && candidatesByName.size > 1) {
                // 同名ファイルが複数ある場合はパスで絞り込む
                val pathParts = trimmedLine.replace("\\", "/").split("/").filter { it.isNotBlank() }
                if (pathParts.size >= 3) {
                    val key = "${pathParts[pathParts.size - 3]}/${pathParts[pathParts.size - 2]}/${pathParts.last()}".lowercase()
                    matchedSong = songsByPath[key]
                }
            }
            
            // 方法2: ベースパスを使ったパスマッチング（フォールバック）
            if (matchedSong == null && basePath.isNotBlank() && trimmedLine.startsWith(basePath, ignoreCase = true)) {
                val relativePath = trimmedLine.substring(basePath.length).replace("\\", "/")
                val pathParts = relativePath.split("/").filter { it.isNotBlank() }
                if (pathParts.size >= 3) {
                    val key = "${pathParts[pathParts.size - 3]}/${pathParts[pathParts.size - 2]}/${pathParts.last()}".lowercase()
                    matchedSong = songsByPath[key]
                }
            }
            
            // 方法3: 呼び出し側の補い（同期では PC が計算した置き換え先。形式違いの MP3 など）(v3.1.0)
            if (matchedSong == null) {
                matchedSong = fallback(trimmedLine)
            }

            if (matchedSong != null) {
                playlistSongs.add(matchedSong)
            } else {
                // マッチしなかった場合はプレースホルダーを作成
                val fileName = trimmedLine.substringAfterLast('/').substringAfterLast('\\').trim()
                playlistSongs.add(Song(
                    uri = Uri.parse("file://$trimmedLine"),
                    displayName = fileName,
                    title = fileName.substringBeforeLast("."),
                    artist = "不明",
                    album = "不明",
                    playCount = 0,
                    trackNumber = 0,
                    duration = 0L,
                    sourceFolderUri = Uri.EMPTY,
                    exists = false
                ))
            }
        }
    }
    return Playlist(playlistName, playlistSongs)
}

/**
 * PC からの同期の後、同期先フォルダを再スキャンした結果をライブラリとプレイリストに反映する (v3.0.0)。
 * - 曲: 同期先フォルダ（root）由来の曲をスキャン結果で入れ替える（削除された曲が残らないように）。再生回数は URI が同じなら引き継ぐ
 * - プレイリスト: PC から届いたものを、.m3u の読み替え（v3.1.0）または相対パス → 曲 で結び付ける。同じ名前のプレイリストは置き換え、Android にしか無いものは残す
 * スキャン結果が 0 曲のとき（スキャン失敗など）は、曲の入れ替えはしない（ライブラリが消えないように）。
 */
private suspend fun applySyncResult(context: Context, root: Uri, oldSongs: List<Song>, scanned: List<Song>): Pair<List<Song>, List<Playlist>> {
    val songs = if (scanned.isEmpty()) oldSongs else {
        val playCounts = oldSongs.associate { it.uri.toString() to it.playCount }
        (oldSongs.filterNot { it.sourceFolderUri == root } +
            scanned.map { it.copy(playCount = playCounts[it.uri.toString()] ?: it.playCount) })
            .distinctBy { it.uri }
            .sortedBy { it.title }
    }

    val incoming = SyncStore.loadPendingPlaylists(context)
    // メモリ上ではなくファイルから読む（起動直後でまだ読み込んでいないときに、Android だけのプレイリストを消さないため）
    var playlists = loadPlaylistsFromFile(context, songs) ?: emptyList()
    if (incoming.isNotEmpty()) {
        // 相対パス（小文字）→ 曲
        val byRelativePath = HashMap<String, Song>()
        songs.filter { it.sourceFolderUri == root }.forEach { song ->
            SyncPaths.relativePath(root, song.uri)?.let { byRelativePath[it.lowercase()] = song }
        }
        val basePath = getPlaylistBasePath(context)
        val converted = incoming.map { p ->
            val content = p.content
            if (content != null) {
                // v3.1.0: PC の .m3u の中身を「プレイリスト(.m3u)を追加」と同じ読み替えで曲に結び付ける。
                // 読み替えで見つからない行（同期しない形式の曲など）は、PC が計算した置き換え先（相対パス）で補う
                val lines = String(content, Charsets.UTF_8).removePrefix("﻿").lines()
                matchM3uLines(p.name, lines, songs, basePath) { line ->
                    p.replacements[line.lowercase()]?.let { byRelativePath[it.lowercase()] }
                }
            } else {
                // 中身が届かなかったとき（古い PC 版・ファイルが読めなかった）は、相対パスの並びで結び付ける
                Playlist(p.name, p.entries.mapNotNull { byRelativePath[it.lowercase()] })
            }
        }
        playlists = playlists.map { existing -> converted.firstOrNull { it.name == existing.name } ?: existing } +
            converted.filter { c -> playlists.none { it.name == c.name } }
    }

    saveLibraryToFile(context, songs)
    savePlaylistsToFile(context, playlists)
    SyncStore.clearPendingPlaylists(context)
    SyncStore.setPendingApply(context, false)
    return songs to playlists
}

// GitHub APIから最新リリースを確認する関数
// GitHubリポジトリ名を設定してください
private const val GITHUB_REPO = "kazu-1234/MusicPlayer" // ユーザー名/リポジトリ名

private suspend fun checkForUpdate(): Triple<String?, String?, String?> = withContext(Dispatchers.IO) {
    return@withContext try {
        val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
        val connection = url.openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        // GitHub APIはUser-Agentが必須の場合があるため追加
        connection.setRequestProperty("User-Agent", "MusicPlayerAndroid")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        
        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val tagName = json.optString("tag_name").ifEmpty { null }
            val htmlUrl = json.optString("html_url").ifEmpty { null }
            val body = json.optString("body", "") // リリースノート本文
            
            // APKアセットのダウンロードURLを探す
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url").ifEmpty { null }
                        break
                    }
                }
            }
            
            Triple(tagName, apkUrl ?: htmlUrl, body)
        } else {
            // エラー時はnullを返すが、ログには出す
            println("GitHub API Error: $responseCode")
            Triple(null, null, null)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Triple(null, null, null)
    }
}

private fun downloadAndInstallApk(context: Context, url: String) {
    try {
        val request = DownloadManager.Request(Uri.parse(url))
        request.setTitle("Music Player Update")
        request.setDescription("Downloading update...")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MusicPlayer_update.apk")
        request.setMimeType("application/vnd.android.package-archive")
        // Android 12以降のセキュリティ対策
        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "ダウンロードを開始しました。完了通知をタップしてインストールしてください。", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "ダウンロードエラー: ${e.message}", Toast.LENGTH_LONG).show()
    }
}




@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MusicPlayerTheme {
        MusicPlayerScreen(songList = emptyList(), playlists = emptyList(), onSongListChange = {}, onPlaylistsChange = {}, onNavigateToSettings = {})
    }
}

// --- 共通コンポーネント ---
// --- 共通コンポーネント ---

@Composable
fun VerticalScrollbar(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    thumbWidth: Dp = 8.dp,
    minThumbHeight: Dp = 48.dp
) {
    val coroutineScope = rememberCoroutineScope()
    
    val layoutInfo = listState.layoutInfo
    val totalItemsCount = layoutInfo.totalItemsCount
    val visibleItemsInfo = layoutInfo.visibleItemsInfo
    
    // アイテムがない、または全て画面内に収まっている場合は表示しない
    if (totalItemsCount == 0 || visibleItemsInfo.isEmpty() || visibleItemsInfo.size >= totalItemsCount) return
    
    BoxWithConstraints(
        modifier = modifier.width(thumbWidth * 4) // タッチ領域を確保
    ) {
        val barHeight = maxHeight
        val barHeightPx = with(LocalDensity.current) { barHeight.toPx() }
        
        // つまみのサイズ計算
        val scrollWindowSize = visibleItemsInfo.size.toFloat() / totalItemsCount.toFloat()
        val thumbHeight = (barHeight * scrollWindowSize).coerceAtLeast(minThumbHeight)
        val thumbHeightPx = with(LocalDensity.current) { thumbHeight.toPx() }
        val scrollableHeightPx = barHeightPx - thumbHeightPx
        
        // 現在のスクロール進捗（表示用）- ドラッグ計算と同じロジックを使用
        val firstVisibleItemIndex = listState.firstVisibleItemIndex
        // ドラッグと同じ比率: index / totalItemsCount = thumbY / barHeight
        val scrollProgress = firstVisibleItemIndex.toFloat() / totalItemsCount.toFloat()
        // つまみの位置 = 進捗 * (バー高さ - つまみ高さ) ではなく、進捗 * バー高さ（つまみの上端）
        val thumbOffsetPx = scrollProgress * barHeightPx
        val thumbOffset = with(LocalDensity.current) { thumbOffsetPx.toDp() }
        
        // タッチ操作を受け付ける透明なBox
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(totalItemsCount) {
                    // ドラッグ開始時のタッチY座標を記録
                    var startTouchY = 0f
                    // ドラッグ開始時のスクロール位置（アイテムインデックス）を記録
                    var startScrollIndex = 0
                    
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            // タッチ開始位置と現在のスクロール位置を記録
                            startTouchY = offset.y
                            startScrollIndex = listState.firstVisibleItemIndex
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            
                            // ドラッグ量（ピクセル）
                            val dragDelta = change.position.y - startTouchY
                            
                            // 1ピクセルあたりのアイテム数を計算
                            // バー全体をドラッグすると全アイテムをスクロールする
                            val itemsPerPixel = totalItemsCount.toFloat() / barHeightPx
                            
                            // ドラッグ量をアイテム数に変換
                            val itemDelta = (dragDelta * itemsPerPixel).toInt()
                            
                            // 新しいスクロール位置を計算
                            val newIndex = (startScrollIndex + itemDelta).coerceIn(0, totalItemsCount - 1)
                            
                            coroutineScope.launch {
                                listState.scrollToItem(newIndex)
                            }
                        }
                    )
                }
        ) {
            // 実際のバー（右端に表示、上端から配置）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(thumbWidth)
                    .height(thumbHeight)
                    .offset(y = thumbOffset)
                    .background(thumbColor.copy(alpha = 0.6f), RoundedCornerShape(thumbWidth / 2))
            )
        }
    }
}

/**
 * 通知からのアクションを受け取るBroadcastReceiver
 * アプリ内にブロードキャストを転送する
 */
class MusicNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        // アプリ内にブロードキャストを送信（Composable内のreceiverがキャッチ）
        val broadcastIntent = Intent(action)
        broadcastIntent.setPackage(context.packageName)
        context.sendBroadcast(broadcastIntent)
    }
}

