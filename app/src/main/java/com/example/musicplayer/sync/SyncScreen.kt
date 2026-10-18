package com.example.musicplayer.sync

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PC（Windows 版）からの同期の画面。
 * 同期先フォルダを選び、「受信待機」をオンにすると、PC の同期画面から見つけて送ってもらえる。
 * 初回だけ、ここに出る PIN を PC に入力してペアリングする。
 *
 * @param onFolderAdded 同期先として新しく選んだフォルダ（ライブラリにも追加済み）。呼び出し側でスキャンする。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onNavigateBack: () -> Unit, onFolderAdded: (Uri) -> Unit) {
    val context = LocalContext.current
    val state by SyncReceiverService.state.collectAsState()
    var writableFolders by remember { mutableStateOf(SyncStore.writableSourceFolders(context)) }
    var syncRoot by remember { mutableStateOf(SyncStore.syncRoot(context)) }
    var pairedPcs by remember { mutableStateOf(SyncStore.pairedPcs(context)) }
    var playlistFolder by remember { mutableStateOf(SyncStore.playlistFolder(context)) }

    // ペアリングはサービス側で行われ、そのたびに PIN が変わるので、PIN の変化を合図に一覧を読み直す
    LaunchedEffect(state.pin, state.completedCount) {
        pairedPcs = SyncStore.pairedPcs(context)
    }

    // 同期先にするフォルダを選ぶ（読み書きの権限を永続化し、ライブラリのフォルダにも加える）
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(context, "このフォルダには書き込めません", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val alreadySource = uri in writableFolders
        SyncStore.addSourceFolder(context, uri)
        SyncStore.setSyncRoot(context, uri)
        writableFolders = SyncStore.writableSourceFolders(context)
        syncRoot = SyncStore.syncRoot(context)
        if (!alreadySource) onFolderAdded(uri)
    }

    // PC から届いた .m3u を書き出すフォルダを選ぶ (v3.1.0)。書き込みの永続権限だけ取り、ライブラリのフォルダには加えない
    val playlistFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(context, "このフォルダには書き込めません", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        SyncStore.setPlaylistFolder(context, uri)
        playlistFolder = SyncStore.playlistFolder(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同期") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
            // ───── 同期先フォルダ ─────
            item {
                Text("同期先フォルダ", style = MaterialTheme.typography.titleLarge)
                Text("PC の音楽フォルダ（ミュージック）と同じ構成で保存します", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            if (writableFolders.isEmpty()) {
                item { Text("書き込めるフォルダがありません。下のボタンで Music フォルダを選んでください", style = MaterialTheme.typography.bodyMedium) }
            }
            items(writableFolders, key = { it.toString() }) { uri ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = uri == syncRoot,
                        enabled = !state.running, // 受信中に保存先が変わらないようにする
                        onClick = {
                            SyncStore.setSyncRoot(context, uri)
                            syncRoot = uri
                        }
                    )
                    Text(SyncStore.displayName(context, uri))
                }
            }
            item {
                Button(onClick = { folderPicker.launch(null) }, enabled = !state.running) { Text("フォルダを選ぶ") }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // ───── プレイリストの保存先 (v3.1.0) ─────
            item {
                Text("プレイリストの保存先", style = MaterialTheme.typography.titleLarge)
                Text("PC のプレイリスト（.m3u）をそのまま保存します。同じ名前のファイルは上書きします",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(playlistFolder?.let { SyncStore.displayName(context, it) } ?: "未指定（アプリ内のプレイリストだけ更新）")
                Spacer(Modifier.height(8.dp))
                // 受信中に保存先が変わらないようにする
                Button(onClick = { playlistFolderPicker.launch(null) }, enabled = !state.running) { Text("フォルダを選ぶ") }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // ───── 受信待機 ─────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("受信待機", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.running,
                        enabled = syncRoot != null,
                        onCheckedChange = { on ->
                            if (on) SyncReceiverService.start(context) else SyncReceiverService.stop(context)
                        }
                    )
                }
                Text("PC と同じ Wi-Fi につないでからオンにします", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (state.running) {
                    Spacer(Modifier.height(12.dp))
                    Text("IP アドレス: ${state.ipAddresses.joinToString(", ").ifEmpty { "不明" }}")
                    Spacer(Modifier.height(8.dp))
                    Text("PIN（初回のペアリング用）", style = MaterialTheme.typography.bodySmall)
                    Text(state.pin, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                if (state.currentFile.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("受信中: ${state.currentFile}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    LinearProgressIndicator(
                        progress = { if (state.currentTotal > 0) state.currentBytes.toFloat() / state.currentTotal else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
                if (state.receivedFiles > 0 || state.deletedFiles > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("受信 ${state.receivedFiles} ファイル・削除 ${state.deletedFiles} ファイル")
                }
                if (state.lastError.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // ───── 許可した PC ─────
            item {
                Text("ペアリング済みの PC", style = MaterialTheme.typography.titleLarge)
                if (pairedPcs.isEmpty()) {
                    Text("なし", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
            items(pairedPcs, key = { it.clientId }) { pc ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pc.name)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(pc.pairedAt)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 解除すると、その PC は次回 PIN の入力からやり直しになる
                    IconButton(onClick = {
                        SyncStore.removePairedPc(context, pc.clientId)
                        pairedPcs = SyncStore.pairedPcs(context)
                    }) { Icon(Icons.Default.Delete, "解除") }
                }
            }
        }
    }
}
