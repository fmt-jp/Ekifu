package jp.fmt.ekifu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import jp.fmt.ekifu.engine.DemoScript
import jp.fmt.ekifu.engine.EngineStatus
import jp.fmt.ekifu.engine.Phase
import jp.fmt.ekifu.engine.PlaceTracker
import jp.fmt.ekifu.engine.StreamStatus
import jp.fmt.ekifu.playback.PlaybackBus
import jp.fmt.ekifu.playback.PlaybackMode
import jp.fmt.ekifu.settings.AppSettings
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    onPlay: () -> Unit,
    onPlayDemo: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by PlaybackBus.ui.collectAsStateWithLifecycle()
    val debugVisible by AppSettings.debugVisible.collectAsStateWithLifecycle()
    val stream = ui.stream
    val status = stream?.engine
    val active = ui.state != PlaybackBus.State.IDLE
    val nowMs = rememberNowMs()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ekifu",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "場所が楽譜になる",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onOpenPlaces) { Text("地点") }
            TextButton(onClick = onOpenSettings) { Text("設定") }
        }

        NowPlayingCard(ui, status, nowMs)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val (label, action) = when (ui.state) {
                PlaybackBus.State.IDLE -> "再生" to onPlay
                PlaybackBus.State.PLAYING -> "一時停止" to onPause
                PlaybackBus.State.PAUSED -> "再開" to onResume
                PlaybackBus.State.ENDING -> "フェードアウト中…" to {}
            }
            Button(
                onClick = action,
                enabled = ui.state != PlaybackBus.State.ENDING,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(2f).height(64.dp),
            ) {
                // 長い表示（フェードアウト中…）は少し小さくし、折り返さない
                Text(
                    label,
                    fontSize = if (label.length > 4) 16.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            OutlinedButton(
                onClick = onStop,
                enabled = ui.state == PlaybackBus.State.PLAYING || ui.state == PlaybackBus.State.PAUSED,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("停止", fontSize = 18.sp) }
        }

        if (debugVisible) {
            if (active && stream != null && status != null) DebugCard(ui, stream, status, nowMs)
            DeveloperCard(ui, status, onPlayDemo)
        }
    }
}

@Composable
private fun NowPlayingCard(ui: PlaybackBus.Ui, status: EngineStatus?, nowMs: Long) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status == null || ui.state == PlaybackBus.State.IDLE) {
                Text("「再生」を押すと、いまいる場所から曲を作ります。約5分ごとに場面が変わり、同じ場所では同じメロディが鳴ります。画面を消しても鳴り続けます。")
                Text(
                    "位置は約1km四方のマスを決めるためだけに使い、訪れたマスと日付のほかは保存しません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(ui.stream?.playSec ?: 0.0),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                PhaseChip(status.composer.phase)
                if (ui.state == PlaybackBus.State.PAUSED) {
                    Spacer(Modifier.width(8.dp))
                    Text("（一時停止中）", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.mode == PlaybackMode.DEMO) {
                Text("デモ再生", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                status.demoCue?.let { cue ->
                    Text(cue.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "聞きどころ：${cue.hint}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                status.composer.scene.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ui.scene?.nextSceneAtMs?.let { next ->
                Text(
                    "次の場面まで ${formatTime(((next - nowMs) / 1000.0).coerceAtLeast(0.0))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val approachText = placeText(status)
            if (approachText != null) {
                Text(approachText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            } else {
                ui.scene?.let { sc ->
                    if (sc.nearestName != null && sc.nearestDistanceM != null) {
                        Text(
                            "一番近い登録地点：${sc.nearestName}（${formatDistance(sc.nearestDistanceM)}）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (ui.scene?.hasPermission == false) {
                Text(
                    "位置の許可がないため、太陽の高さ（タイムゾーンから概算）と経過時間だけで曲を作っています。登録地点の演出は使えません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun PhaseChip(phase: Phase) {
    val highlight = phase == Phase.APPROACH || phase == Phase.ARRIVE
    Text(
        phase.label,
        color = if (highlight) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(
                if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

@Composable
private fun DebugCard(ui: PlaybackBus.Ui, stream: StreamStatus, status: EngineStatus, nowMs: Long) {
    val c = status.composer
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("デバッグ表示", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            DebugRow("フェーズ", c.phase.label)
            DebugRow("和音", c.chord.label)
            DebugRow("1拍", "%.3f 秒".format(c.beatSec))
            DebugRow("発音確率", "%.2f".format(c.noteProb))
            DebugRow("全体ローパス", "%.0f Hz".format(c.masterCutoffHz))
            DebugRow("マス", c.scene.gridId)
            ui.scene?.let { sc ->
                DebugRow("速さ", sc.decision?.speedKmh?.let { "%.1f km/h".format(it) } ?: "—")
                DebugRow(
                    "位置",
                    when {
                        !sc.hasPermission -> "許可なし"
                        sc.decision?.located == false -> "取れず（直前の場面を継続）"
                        sc.lastFixAtMs != null -> "${formatTime((nowMs - sc.lastFixAtMs) / 1000.0)} 前に取得"
                        else -> "取得待ち"
                    },
                )
            }
            DebugRow("地点", c.place?.let { "${it.name}（${it.mood.label}）" } ?: "なし")
            ui.scene?.let { sc ->
                DebugRow(
                    "一番近い地点",
                    if (sc.nearestName != null && sc.nearestDistanceM != null) {
                        "${sc.nearestName} ${formatDistance(sc.nearestDistanceM)}"
                    } else {
                        "—"
                    },
                )
                DebugRow(
                    "判定",
                    when (sc.trackedState) {
                        PlaceTracker.State.NONE -> "なし"
                        PlaceTracker.State.APPROACHING -> "${sc.trackedName}に接近中"
                        PlaceTracker.State.ARRIVED -> "${sc.trackedName}に到着"
                    },
                )
                DebugRow("位置の取得間隔", sc.locationIntervalSec?.let { "${it / 60}分" } ?: "—")
            }
            DebugRow("鳴っている音", "${status.activeVoices}")
            DebugRow("バッファ残量", "%.1f 秒".format(stream.bufferedSec))
            DebugRow("バッファ不足", "${stream.underruns} 回")
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** 開発用：デモ再生（5分の場面を30秒に縮めた架空の通勤） */
@Composable
private fun DeveloperCard(ui: PlaybackBus.Ui, status: EngineStatus?, onPlayDemo: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("開発用", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onPlayDemo, enabled = ui.state == PlaybackBus.State.IDLE) {
                Text("デモ再生（自宅 → 公園 → 駅 → 職場）")
            }
            if (ui.mode != PlaybackMode.DEMO || ui.state == PlaybackBus.State.IDLE) return@Column
            DemoScript.COMMUTE.cues.forEach { cue ->
                val current = status?.demoCue === cue
                Row {
                    Text(
                        formatTime(cue.atSec),
                        modifier = Modifier.width(52.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        cue.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 画面が見えているあいだだけ1秒ごとに進む現在時刻 */
@Composable
private fun rememberNowMs(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    return now
}

private fun placeText(status: EngineStatus): String? {
    val place = status.composer.place ?: return null
    return when (status.composer.phase) {
        Phase.APPROACH -> "${place.name}に近づいています"
        Phase.ARRIVE -> "${place.name}に着きました"
        Phase.STAY -> "${place.name}に滞在中"
        else -> null
    }
}

private fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()}m" else "%.1fkm".format(m / 1000)

private fun formatTime(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
