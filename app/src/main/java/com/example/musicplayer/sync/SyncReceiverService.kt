package com.example.musicplayer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/** 同期画面に出す受信待機の状態。 */
data class SyncReceiverState(
    val running: Boolean = false,
    val ipAddresses: List<String> = emptyList(),
    val pin: String = "",
    /** 今受け取っているファイル（相対パス）。無ければ空。 */
    val currentFile: String = "",
    val currentBytes: Long = 0L,
    val currentTotal: Long = 0L,
    /** この受信待機の間に受け取ったファイル数・削除したファイル数。 */
    val receivedFiles: Int = 0,
    val deletedFiles: Int = 0,
    val lastError: String = "",
    /** PC から「同期完了」を受け取るたびに増える（画面側はこれを見てライブラリを読み直す）。 */
    val completedCount: Int = 0
)

/**
 * PC（Windows 版）からの同期を受け取る Foreground Service。
 *
 * 受信待機をオンにしている間だけ動く:
 * - HTTP サーバー（NanoHTTPD、ポート SyncConfig.HTTP_PORT）: PC から一覧取得・ファイル受信・削除・プレイリスト受信
 * - UDP 応答（ポート SyncConfig.DISCOVERY_PORT）: PC の探索ブロードキャストに自分の ID・名前を返す
 * 画面を消しても止まらないよう、WakeLock と WifiLock を持つ。
 */
class SyncReceiverService : Service() {

    companion object {
        private const val TAG = "SyncReceiver"
        const val ACTION_START = "com.example.musicplayer.sync.START"
        const val ACTION_STOP = "com.example.musicplayer.sync.STOP"

        private val _state = MutableStateFlow(SyncReceiverState())
        /** 同期画面とライブラリの反映処理が見る状態。 */
        val state: StateFlow<SyncReceiverState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, SyncReceiverService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncReceiverService::class.java).setAction(ACTION_STOP))
        }
    }

    private var server: ReceiverServer? = null
    private var discoverySocket: DatagramSocket? = null
    private var discoveryThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val random = SecureRandom()
    private var pinFailures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopReceiving()
                return START_NOT_STICKY
            }
            else -> startReceiving()
        }
        // 強制終了されても勝手に再開しない（受信待機はユーザーが明示的にオンにしたときだけ）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ───────── 開始・停止 ─────────

    private fun startReceiving() {
        createChannel()
        ServiceCompat.startForeground(
            this, SyncConfig.NOTIFICATION_ID, buildNotification("PC からの同期を待っています"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (server != null) return // すでに待機中

        acquireLocks()
        try {
            server = ReceiverServer().also { it.start(SyncConfig.SOCKET_READ_TIMEOUT_MS, false) }
        } catch (e: Exception) {
            Log.e(TAG, "server start failed", e)
            _state.value = SyncReceiverState(lastError = "受信を開始できません: ${e.message}")
            stopReceiving()
            return
        }
        startDiscoveryResponder()
        pinFailures = 0
        _state.value = SyncReceiverState(running = true, ipAddresses = localIpAddresses(), pin = newPin(),
            completedCount = _state.value.completedCount)
    }

    private fun stopReceiving() {
        shutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown() {
        server?.stop()
        server = null
        discoverySocket?.close() // 受信待ちのスレッドは close で例外になって抜ける
        discoverySocket = null
        discoveryThread = null
        releaseLocks()
        _state.update { it.copy(running = false, pin = "", currentFile = "") }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicPlayer:SyncReceiver").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // 画面オフでも Wi-Fi が省電力で遅くならないようにする
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicPlayer:SyncReceiver").apply {
            setReferenceCounted(false)
            acquire()
        }
        // 端末によっては、これを持たないと画面オフ時にブロードキャストのパケットを捨ててしまう
        multicastLock = wifi.createMulticastLock("MusicPlayer:SyncDiscovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    private fun newPin(): String {
        val max = Math.pow(10.0, SyncConfig.PIN_LENGTH.toDouble()).toInt()
        return random.nextInt(max).toString().padStart(SyncConfig.PIN_LENGTH, '0')
    }

    /** 画面に出す自分の IP（Wi-Fi などの IPv4、ループバック以外）。 */
    private fun localIpAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    // ───────── 探索への応答 ─────────

    private fun startDiscoveryResponder() {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(SyncConfig.DISCOVERY_PORT))
            }
        }.getOrElse {
            // 探索が使えなくても、IP の手入力で同期できるので受信は続ける
            Log.w(TAG, "discovery bind failed", it)
            return
        }
        discoverySocket = socket
        val reply = JSONObject()
            .put("app", "MusicPlayer")
            .put("deviceId", SyncStore.deviceId(this))
            .put("name", Build.MODEL)
            .put("port", SyncConfig.HTTP_PORT)
            .toString().toByteArray(Charsets.UTF_8)

        discoveryThread = Thread {
            val buffer = ByteArray(512)
            while (!socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (message == SyncConfig.DISCOVERY_MESSAGE) {
                        // 送ってきた PC のアドレス・ポートへそのまま返す
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                } catch (e: Exception) {
                    if (!socket.isClosed) Log.w(TAG, "discovery error", e)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    // ───────── 通知 ─────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(SyncConfig.NOTIFICATION_CHANNEL_ID, "PC からの同期", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, SyncConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("同期の受信待機中")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SyncConfig.NOTIFICATION_ID, buildNotification(text))
    }

    // ───────── HTTP サーバー ─────────

    /**
     * PC からの要求を受ける。NanoHTTPD は接続ごとに別スレッドで serve を呼ぶ。
     * PC は 1 本ずつ順番に送ってくるので、ファイル操作（SafTree）は synchronized で 1 つずつ処理する。
     */
    private inner class ReceiverServer : NanoHTTPD(SyncConfig.HTTP_PORT) {
        private var tree: SafTree? = null
        private var treeRoot: android.net.Uri? = null

        override fun serve(session: IHTTPSession): Response = try {
            val path = session.uri
            when {
                session.method == Method.GET && path == "/info" -> handleInfo()
                session.method == Method.POST && path == "/pair" -> handlePair(session)
                authorized(session) == null -> text(Response.Status.UNAUTHORIZED, "unauthorized")
                session.method == Method.GET && path == "/files" -> synchronized(this) { handleFiles() }
                session.method == Method.PUT && path == "/file" -> synchronized(this) { handleFile(session) }
                session.method == Method.POST && path == "/delete" -> synchronized(this) { handleDelete(session) }
                session.method == Method.PUT && path == "/playlists" -> handlePlaylists(session)
                session.method == Method.POST && path == "/done" -> handleDone()
                else -> text(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: IllegalArgumentException) {
            text(Response.Status.BAD_REQUEST, e.message ?: "bad request")
        } catch (e: Exception) {
            Log.e(TAG, "request failed", e)
            _state.update { it.copy(lastError = e.message ?: e.javaClass.simpleName, currentFile = "") }
            text(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }

        private fun text(status: Response.Status, body: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", body)
        private fun json(body: Any) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString())

        /** NanoHTTPD はヘッダ名を小文字にして渡す。 */
        private fun authorized(session: IHTTPSession): PairedPc? =
            SyncStore.findByToken(this@SyncReceiverService, session.headers["x-token"].orEmpty())

        /** 本文を Content-Length の分だけ読む（JSON の要求用）。 */
        private fun readBody(session: IHTTPSession): String {
            val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
            if (length <= 0) return ""
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = session.inputStream.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                offset += read
            }
            return String(bytes, 0, offset, Charsets.UTF_8)
        }

        /** 同期先フォルダの SafTree。設定で同期先を変えたら作り直す。未設定なら例外。 */
        private fun currentTree(): SafTree {
            val root = SyncStore.syncRoot(this@SyncReceiverService)
                ?: throw IllegalStateException("同期先フォルダが設定されていません（Android の同期画面で選んでください）")
            if (tree == null || treeRoot != root) {
                tree = SafTree(this@SyncReceiverService, root)
                treeRoot = root
            }
            return tree!!
        }

        private fun handleInfo(): Response = json(
            JSONObject()
                .put("app", "MusicPlayer")
                .put("deviceId", SyncStore.deviceId(this@SyncReceiverService))
                .put("name", Build.MODEL)
                .put("ready", SyncStore.syncRoot(this@SyncReceiverService) != null)
        )

        /**
         * PIN が合えば新しいトークンを渡す。まちがいが続いたら PIN を作り直す（総当たり対策）。
         */
        private fun handlePair(session: IHTTPSession): Response {
            val body = JSONObject(readBody(session).ifEmpty { "{}" })
            val pin = body.optString("pin")
            val current = _state.value.pin
            if (current.isEmpty() || pin != current) {
                pinFailures++
                if (pinFailures >= SyncConfig.PIN_MAX_FAILURES) {
                    pinFailures = 0
                    _state.update { it.copy(pin = newPin()) }
                }
                return text(Response.Status.FORBIDDEN, "wrong pin")
            }
            pinFailures = 0
            val tokenBytes = ByteArray(32).also { random.nextBytes(it) }
            val token = Base64.encodeToString(tokenBytes, Base64.NO_WRAP or Base64.URL_SAFE)
            val clientId = body.optString("clientId").ifEmpty { token.take(8) }
            SyncStore.addPairedPc(this@SyncReceiverService,
                PairedPc(clientId, body.optString("clientName").ifEmpty { "PC" }, token, System.currentTimeMillis()))
            // 使った PIN は捨てる（同じ PIN で別の PC がペアリングできないように）
            _state.update { it.copy(pin = newPin()) }
            return json(JSONObject().put("token", token))
        }

        private fun handleFiles(): Response {
            val files = JSONArray()
            for ((path, size) in currentTree().listAudioFiles()) {
                files.put(JSONObject().put("path", path).put("size", size))
            }
            return json(JSONObject().put("files", files))
        }

        private fun handleFile(session: IHTTPSession): Response {
            val relativePath = session.parameters["path"]?.firstOrNull()
                ?: throw IllegalArgumentException("path がありません")
            val length = session.headers["content-length"]?.toLongOrNull()
                ?: throw IllegalArgumentException("Content-Length がありません")
            _state.update { it.copy(currentFile = relativePath, currentBytes = 0, currentTotal = length, lastError = "") }
            notify(relativePath)

            var lastReport = 0L
            currentTree().writeFile(relativePath, session.inputStream, length) { written ->
                // 画面の更新が多すぎないよう、1MB ごとに知らせる
                if (written - lastReport >= 1024 * 1024 || written == length) {
                    lastReport = written
                    _state.update { it.copy(currentBytes = written) }
                }
            }
            _state.update { it.copy(receivedFiles = it.receivedFiles + 1, currentFile = "") }
            return json(JSONObject().put("ok", true))
        }

        private fun handleDelete(session: IHTTPSession): Response {
            val paths = JSONObject(readBody(session)).optJSONArray("paths") ?: JSONArray()
            val tree = currentTree()
            var deleted = 0
            for (i in 0 until paths.length()) {
                if (runCatching { tree.deleteFile(paths.getString(i)) }.getOrDefault(false)) deleted++
            }
            _state.update { it.copy(deletedFiles = it.deletedFiles + deleted) }
            return json(JSONObject().put("deleted", deleted))
        }

        /**
         * 届いたプレイリストは保存だけしておき、アプリの画面側で再スキャン後に曲へ結び付ける。
         * プレイリストの保存先が指定されていれば、PC の .m3u をそのままそこへ書き出す（同名は上書き） (v3.1.0)。
         */
        private fun handlePlaylists(session: IHTTPSession): Response {
            val body = readBody(session)
            val array = JSONArray(body) // 形が正しいかも兼ねて確かめる（壊れていれば例外で 500）
            SyncStore.savePendingPlaylists(this@SyncReceiverService, body)
            writePlaylistFiles(array)
            return json(JSONObject().put("ok", true))
        }

        /**
         * 各プレイリストの中身（Base64）を、保存先フォルダに PC と同じファイル名で書く。
         * 書けなかったものがあっても同期自体は止めず、画面にエラーとして出すだけにする（アプリ内の反映は保留 JSON から行えるため）。
         */
        private fun writePlaylistFiles(array: JSONArray) {
            val folder = SyncStore.playlistFolder(this@SyncReceiverService) ?: return
            val playlistTree = SafTree(this@SyncReceiverService, folder)
            val failed = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                // ファイル名だけを使う（"/" や "\" が入っていても保存先の外や下のフォルダに書かないように）
                val fileName = o.optString("fileName").substringAfterLast('/').substringAfterLast('\\')
                val content = o.optString("contentBase64")
                if (fileName.isBlank() || content.isEmpty()) continue
                runCatching {
                    val bytes = Base64.decode(content, Base64.DEFAULT)
                    // writeFile は「.mpsync-part に書いてから名前を変える」ので、同名の古いファイルは書き終えてから入れ替わる
                    playlistTree.writeFile(fileName, bytes.inputStream(), bytes.size.toLong()) { }
                }.onFailure {
                    Log.w(TAG, "playlist write failed: $fileName", it)
                    failed += fileName
                }
            }
            if (failed.isNotEmpty()) {
                _state.update { it.copy(lastError = "プレイリストを保存できませんでした: ${failed.joinToString("、")}") }
            }
        }

        private fun handleDone(): Response {
            SyncStore.setPendingApply(this@SyncReceiverService, true)
            // 次の同期のために、フォルダの一覧を覚え直させる（アプリ側の再スキャンと食い違わないように）
            tree = null
            _state.update { it.copy(completedCount = it.completedCount + 1, currentFile = "") }
            notify("同期が完了しました（${_state.value.receivedFiles} ファイル受信）")
            return json(JSONObject().put("ok", true))
        }
    }
}
