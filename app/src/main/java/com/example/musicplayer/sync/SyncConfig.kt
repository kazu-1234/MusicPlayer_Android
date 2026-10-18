package com.example.musicplayer.sync

/**
 * PC（Windows 版）からの同期の調整値。
 * ポート・合言葉は Windows 版の AppConfig.cs（「Android 同期」の項目）と必ずそろえる。
 */
object SyncConfig {
    /** 受信サーバー（HTTP）のポート。PC はここへファイルを送ってくる。 */
    const val HTTP_PORT = 51515

    /** 端末探索の UDP ポート。PC のブロードキャストをここで待ち、自分の情報を返す。 */
    const val DISCOVERY_PORT = 51516

    /** PC が探索で送ってくる合言葉。これ以外のパケットには返事をしない。 */
    const val DISCOVERY_MESSAGE = "MPSYNC_DISCOVER"

    /** ペアリング用 PIN の桁数。 */
    const val PIN_LENGTH = 6

    /** PIN をこの回数まちがえたら、総当たりを防ぐため PIN を作り直す。 */
    const val PIN_MAX_FAILURES = 5

    /**
     * 受信サーバーのソケット読み取りタイムアウト（ミリ秒）。
     * NanoHTTPD の既定 5 秒だと、PC 側が次のファイルを用意している間に接続が切れることがあるので長めにする。
     */
    const val SOCKET_READ_TIMEOUT_MS = 60_000

    /** 受信したファイルを書き込むときのバッファ（byte）。 */
    const val COPY_BUFFER_SIZE = 256 * 1024

    /** 書き込み途中のファイルに付ける拡張子（書き終えたら本来の名前に変える）。 */
    const val PART_SUFFIX = ".mpsync-part"

    /** /files で PC に返す音楽ファイルの拡張子（PC 版がライブラリに入れる拡張子と同じ）。 */
    val AUDIO_EXTENSIONS = setOf("flac", "mp3", "wav", "m4a", "aac", "ogg", "opus", "wma", "aiff", "aif")

    const val NOTIFICATION_CHANNEL_ID = "sync_receiver_channel"
    const val NOTIFICATION_ID = 3
}
