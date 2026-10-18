package com.example.musicplayer

// v2.4.0: バックグラウンド再生用Foreground Service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever

/**
 * 音楽再生用Foreground Service
 * スリープ時やバックグラウンドでも再生を継続する
 */
class MusicPlaybackService : Service() {
    
    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1001
        
        // アクション
        const val ACTION_PLAY = "com.example.musicplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.musicplayer.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.musicplayer.ACTION_RESUME"
        const val ACTION_STOP = "com.example.musicplayer.ACTION_STOP"
        const val ACTION_NEXT = "com.example.musicplayer.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.musicplayer.ACTION_PREVIOUS"
        
        // Settings
        private const val PREFS_NAME = "MusicPlayerPrefs"
        private const val KEY_USE_SYSTEM_MEDIA_CONTROLLER = "useSystemMediaController"
    }
    
    // バインダー
    private val binder = LocalBinder()
    
    // MediaPlayer
    private var mediaPlayer: MediaPlayer? = null
    
    // MediaSession
    private lateinit var mediaSession: MediaSessionCompat
    
    // 再生状態
    var isPlaying = false
        private set
    var currentSong: Song? = null
        private set
    var currentIndex = -1
        private set
    var playingQueue: List<Song> = emptyList()
        private set
    var originalQueue: List<Song> = emptyList()
        private set
    
    // 再生モード
    var repeatMode: RepeatMode = RepeatMode.OFF
    var isShuffleEnabled = false
    
    // コールバック
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onSongChanged: ((Song?) -> Unit)? = null
    var onPositionChanged: ((Int, Int) -> Unit)? = null
    var onQueueChanged: ((List<Song>) -> Unit)? = null
    var onPlayCountUpdated: ((Song) -> Unit)? = null
    
    // WakeLock（スリープ中も再生を維持）
    private var wakeLock: PowerManager.WakeLock? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // MediaSession初期化 (v2.5.6)
        mediaSession = MediaSessionCompat(this, "MusicPlaybackSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
            })
            isActive = true
        }

        createNotificationChannel()
        
        // WakeLock取得（CPU維持）
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MusicPlayer::PlaybackWakeLock"
        )
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> {
                stop()
                stopSelf()
            }
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release() // MediaSession解放
        mediaPlayer?.release()
        mediaPlayer = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
    
    // --- 通知チャンネル作成 ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音楽再生",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音楽再生中の通知"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    // --- 通知作成 ---
    private fun createNotification(): Notification {
        val song = currentSong
        
        // アプリを開くIntent
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        // 再生/一時停止ボタン
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "一時停止",
                createPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "再生",
                createPendingIntent(ACTION_RESUME)
            )
        }
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song?.title ?: "再生中")
            .setContentText(song?.artist ?: "Unknown Artist")
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_media_previous,
                "前へ",
                createPendingIntent(ACTION_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next,
                "次へ",
                createPendingIntent(ACTION_NEXT)
            )
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 設定を確認してMediaSessionを適用
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val useSystemMediaController = prefs.getBoolean(KEY_USE_SYSTEM_MEDIA_CONTROLLER, true)
        
        if (useSystemMediaController) {
            builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
        } else {
            builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2))
        }
            
        return builder.build()
    }
    
    private fun updateMediaSessionState() {
        if (!::mediaSession.isInitialized) return
        
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val playbackSpeed = if (isPlaying) 1.0f else 0.0f
        
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, playbackSpeed)
                .build()
        )
    }

    private fun updateMediaMetadata() {
        if (!::mediaSession.isInitialized) return
        val song = currentSong ?: return
        
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
        
        // アルバムアートは非同期読み込みが必要なため、ここでは簡易的に省略するか、
        // 必要に応じてBitmapFactoryで読み込む（時間かかるので注意）
        // いったんメタデータのみ更新
        
        mediaSession.setMetadata(metadataBuilder.build())
    }
    
    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    


    // --- Foreground開始 ---
    private fun startForegroundService() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    // --- 通知更新 ---
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    // --- 再生制御 ---
    
    /**
     * 指定したインデックスの曲を再生
     */
    fun playSongAtIndex(index: Int, queue: List<Song>) {
        if (index < 0 || index >= queue.size) return
        
        val song = queue[index]
        if (!song.exists) return
        
        currentIndex = index
        currentSong = song
        playingQueue = queue
        
        // WakeLock取得
        wakeLock?.let {
            if (!it.isHeld) it.acquire(10 * 60 * 1000L) // 10分
        }
        
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, song.uri)?.apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            start()
            setOnCompletionListener {
                handleSongCompletion()
            }
        }
        
        isPlaying = true
        
        // Foreground Service開始
        startForegroundService()
        
        // MediaSession更新 (v2.5.6)
        updateMediaMetadata()
        updateMediaSessionState()
        
        // コールバック
        onPlaybackStateChanged?.invoke(true)
        onSongChanged?.invoke(song)
        onQueueChanged?.invoke(queue)
        onPlayCountUpdated?.invoke(song)
        
        // 位置更新ループ開始
        startPositionUpdates()
    }
    
    /**
     * 新しいコンテキストで曲を再生
     */
    fun playSong(song: Song, contextList: List<Song>) {
        originalQueue = contextList
        
        val queue = if (isShuffleEnabled) {
            val remainder = contextList.filter { it.uri != song.uri }.shuffled()
            listOf(song) + remainder
        } else {
            contextList
        }
        
        val index = queue.indexOfFirst { it.uri == song.uri }.takeIf { it != -1 } ?: 0
        playSongAtIndex(index, queue)
    }
    
    /**
     * 一時停止
     */
    fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
        onPlaybackStateChanged?.invoke(false)
        onPlaybackStateChanged?.invoke(false)
        updateNotification()
        updateMediaSessionState() // MediaSession更新
        
        // WakeLock解放
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
    
    /**
     * 再開
     */
    fun resume() {
        mediaPlayer?.start()
        isPlaying = true
        onPlaybackStateChanged?.invoke(true)
        onPlaybackStateChanged?.invoke(true)
        updateNotification()
        updateMediaSessionState() // MediaSession更新
        
        // WakeLock再取得
        wakeLock?.let {
            if (!it.isHeld) it.acquire(10 * 60 * 1000L)
        }
        startPositionUpdates()
    }
    
    /**
     * 停止
     */
    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        currentSong = null
        currentIndex = -1
        
        onPlaybackStateChanged?.invoke(false)
        onSongChanged?.invoke(null)
        
        // WakeLock解放
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        
        mediaSession.isActive = false // MediaSession無効化
        updateMediaSessionState()
        
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    
    /**
     * シーク
     */
    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        updateMediaSessionState() // 位置更新
    }
    
    /**
     * 次の曲
     */
    fun playNext() {
        if (playingQueue.isEmpty()) return
        
        val nextIndex = when (repeatMode) {
            RepeatMode.ONE -> currentIndex
            RepeatMode.ALL -> (currentIndex + 1) % playingQueue.size
            RepeatMode.OFF -> {
                if (currentIndex + 1 < playingQueue.size) currentIndex + 1 else return
            }
        }
        playSongAtIndex(nextIndex, playingQueue)
    }
    
    /**
     * 前の曲
     */
    fun playPrevious() {
        if (playingQueue.isEmpty()) return
        
        // 3秒以上再生していたら最初に戻る
        val position = mediaPlayer?.currentPosition ?: 0
        if (position > 3000) {
            seekTo(0)
            return
        }
        
        val prevIndex = when (repeatMode) {
            RepeatMode.ONE -> currentIndex
            RepeatMode.ALL -> if (currentIndex > 0) currentIndex - 1 else playingQueue.size - 1
            RepeatMode.OFF -> if (currentIndex > 0) currentIndex - 1 else 0
        }
        playSongAtIndex(prevIndex, playingQueue)
    }
    
    /**
     * シャッフル切り替え
     */
    fun toggleShuffle() {
        isShuffleEnabled = !isShuffleEnabled
        
        if (currentSong != null && originalQueue.isNotEmpty()) {
            if (isShuffleEnabled) {
                val remainder = originalQueue.filter { it.uri != currentSong!!.uri }.shuffled()
                playingQueue = listOf(currentSong!!) + remainder
                currentIndex = 0
            } else {
                playingQueue = originalQueue
                currentIndex = originalQueue.indexOfFirst { it.uri == currentSong!!.uri }
                    .takeIf { it != -1 } ?: 0
            }
            onQueueChanged?.invoke(playingQueue)
        }
    }
    
    /**
     * キュー内の曲順入れ替え
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex < 0 || fromIndex >= playingQueue.size) return
        if (toIndex < 0 || toIndex >= playingQueue.size) return
        
        val mutableQueue = playingQueue.toMutableList()
        val movedItem = mutableQueue.removeAt(fromIndex)
        mutableQueue.add(toIndex, movedItem)
        playingQueue = mutableQueue
        
        // 現在再生中の曲のインデックスを更新
        if (currentIndex == fromIndex) {
            currentIndex = toIndex
        } else if (fromIndex < currentIndex && toIndex >= currentIndex) {
            currentIndex--
        } else if (fromIndex > currentIndex && toIndex <= currentIndex) {
            currentIndex++
        }
        
        onQueueChanged?.invoke(playingQueue)
    }
    
    // --- 内部処理 ---
    
    private fun handleSongCompletion() {
        when (repeatMode) {
            RepeatMode.ONE -> {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
            }
            RepeatMode.ALL -> {
                val nextIndex = if (currentIndex + 1 >= playingQueue.size) 0 else currentIndex + 1
                playSongAtIndex(nextIndex, playingQueue)
            }
            RepeatMode.OFF -> {
                if (currentIndex + 1 < playingQueue.size) {
                    playSongAtIndex(currentIndex + 1, playingQueue)
                } else {
                    isPlaying = false
                    onPlaybackStateChanged?.invoke(false)
                    updateNotification()
                }
            }
        }
    }
    
    private var positionUpdateThread: Thread? = null
    
    private fun startPositionUpdates() {
        positionUpdateThread?.interrupt()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        positionUpdateThread = Thread {
            try {
                while (isPlaying && !Thread.interrupted()) {
                    // MediaPlayerへのアクセスも競合の可能性があるため、必要ならここで同期化するが
                    // 単純なgetなのでtry-catchで囲んでおくのが無難
                    try {
                        val position = mediaPlayer?.currentPosition ?: 0
                        val dur = mediaPlayer?.duration ?: 0
                        handler.post {
                            onPositionChanged?.invoke(position, dur)
                        }
                    } catch (e: Exception) {
                        // MediaPlayerが解放されている場合など
                    }
                    Thread.sleep(200)
                }
            } catch (e: InterruptedException) {
                // 終了
            }
        }.also { it.start() }
    }
    
    /**
     * 現在の再生位置を取得
     */
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    
    /**
     * 曲の長さを取得
     */
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
}
