package jp.fmt.ekifu.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.fmt.ekifu.data.EkifuDatabase
import jp.fmt.ekifu.engine.GpsMode
import jp.fmt.ekifu.engine.LocationPolicy
import jp.fmt.ekifu.engine.LocationPreset
import jp.fmt.ekifu.engine.MusicStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val debugVisible by AppSettings.debugVisible.collectAsStateWithLifecycle()
    val style by AppSettings.style.collectAsStateWithLifecycle()
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
                Text("曲調", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "再生中に変えると、約2秒でフェードアウトして新しい曲調の「始まり」から鳴らします。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.selectableGroup()) {
                    MusicStyle.entries.forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = s == style,
                                    onClick = { AppSettings.setStyle(context, s) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = s == style, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(s.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    styleDescription(s),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            LocationCard()

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
                    "生音フュージョンの音源：GeneralUser GS v2.0.3（S. Christian Collins）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 位置の取り方：おまかせ（電池優先・標準・精度優先）か、カスタムで項目ごとに */
@Composable
private fun LocationCard() {
    val context = LocalContext.current
    val preset by AppSettings.locationPreset.collectAsStateWithLifecycle()
    val custom by AppSettings.customPolicy.collectAsStateWithLifecycle()
    SettingCard {
        Text("位置の取り方", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "GPS は正確ですが電池を使います。再生中に変えても、すぐ新しい取り方に切り替わります。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(Modifier.selectableGroup()) {
            LocationPreset.entries.forEach { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = p == preset,
                            onClick = { AppSettings.setLocationPreset(context, p) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = p == preset, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(p.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            p.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (preset != LocationPreset.CUSTOM) return@SettingCard
        HorizontalDivider()
        fun set(p: LocationPolicy) = AppSettings.setCustomPolicy(context, p)
        ChoiceRow("GPS の使い方", GpsMode.entries, custom.gpsMode, { it.label }) { set(custom.copy(gpsMode = it)) }
        ChoiceRow(
            "地点の近くとみなす範囲（乗り物のときはその2倍）",
            LocationPolicy.NEAR_DISTANCE_CHOICES_M,
            custom.nearDistanceM,
            ::formatDistance,
        ) { set(custom.copy(nearDistanceM = it)) }
        ChoiceRow("ふだん（歩き・静止）の間隔", LocationPolicy.NORMAL_INTERVAL_CHOICES_SEC, custom.normalIntervalSec, ::formatSec) {
            set(custom.copy(normalIntervalSec = it))
        }
        ChoiceRow("乗り物の間隔", LocationPolicy.VEHICLE_INTERVAL_CHOICES_SEC, custom.vehicleIntervalSec, ::formatSec) {
            set(custom.copy(vehicleIntervalSec = it))
        }
        ChoiceRow("地点の近くの間隔", LocationPolicy.NEAR_INTERVAL_CHOICES_SEC, custom.nearIntervalSec, ::formatSec) {
            set(custom.copy(nearIntervalSec = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("到着したあとも GPS を続ける", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "オフなら、到着したら省電力で1分ごとに戻します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = custom.keepGpsAfterArrival, onCheckedChange = { set(custom.copy(keepGpsAfterArrival = it)) })
        }
    }
}

@Composable
private fun <T> ChoiceRow(label: String, choices: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            choices.forEach { c -> FilterChip(selected = c == selected, onClick = { onSelect(c) }, label = { Text(text(c)) }) }
        }
    }
}

private fun formatSec(sec: Double): String = if (sec < 60) "${sec.toInt()}秒" else "${(sec / 60).toInt()}分"

private fun formatDistance(m: Double): String = if (m < 1000) "${m.toInt()}m" else "%skm".format((m / 1000).toString().removeSuffix(".0"))

private fun styleDescription(style: MusicStyle) = when (style) {
    MusicStyle.HEALING -> "パッドとベルの穏やかなインストゥルメンタル"
    MusicStyle.FUSION -> "明るいバンド編成のフュージョン。移動の速さで盛り上がりが変わる"
    MusicStyle.BAND -> "フュージョンを生楽器（アルトサックス・エレピ・ベース・ドラム）で"
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
