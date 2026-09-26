package jp.fmt.ekifu.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.fmt.ekifu.data.EkifuDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val debugVisible by AppSettings.debugVisible.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 戻る") }
                Text("設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            SettingCard {
                Text("訪問履歴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "訪れたマス（約1km四方）と日付だけを端末内に記録し、「なじみの場所」の判定に使っています。消去すると、どの場所も初めての場所に戻ります。登録地点は消えません。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { confirmClear = true }) { Text("訪問履歴を消去") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }

            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("デバッグ表示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "再生画面に、フェーズ・和音・バッファ残量・位置の取得状況などと、開発用のデモ再生を出します。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = debugVisible, onCheckedChange = { AppSettings.setDebugVisible(context, it) })
                }
            }

            SettingCard {
                Text("プライバシー", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "位置は曲を作るためだけに端末の中で使います。正確な緯度経度や移動の軌跡は保存せず、登録地点と訪問履歴も外部に送りません。" +
                        "通信するのは、地点登録の画面で地図を表示しているあいだに地図の画像を取るときだけです。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "地図：© OpenStreetMap contributors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("訪問履歴を消去しますか？") },
            text = { Text("どの場所も「初めての場所」に戻ります。元には戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { EkifuDatabase.get(context).visits().clear() }
                        message = "訪問履歴を消去しました"
                    }
                }) { Text("消去") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
