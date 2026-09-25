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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.fmt.ekifu.engine.DemoScript
import jp.fmt.ekifu.engine.EngineStatus
import jp.fmt.ekifu.engine.Phase
import jp.fmt.ekifu.engine.StreamStatus
import jp.fmt.ekifu.playback.PlaybackBus

@Composable
fun PlayerScreen(onPlay: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    val ui by PlaybackBus.ui.collectAsStateWithLifecycle()
    val stream = ui.stream
    val status = stream?.engine

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                "ekifu",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "段階2：バックグラウンド再生（デモ再生）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }

        NowPlayingCard(ui.state, stream?.playSec ?: 0.0, status)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val (label, action) = when (ui.state) {
                PlaybackBus.State.IDLE -> "デモ再生" to onPlay
                PlaybackBus.State.PLAYING -> "一時停止" to onPause
                PlaybackBus.State.PAUSED -> "再開" to onPlay
                PlaybackBus.State.ENDING -> "フェードアウト中…" to {}
            }
            Button(
                onClick = action,
                enabled = ui.state != PlaybackBus.State.ENDING,
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(label, fontSize = 18.sp) }
            OutlinedButton(
                onClick = onStop,
                enabled = ui.state == PlaybackBus.State.PLAYING || ui.state == PlaybackBus.State.PAUSED,
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text("停止", fontSize = 18.sp) }
        }

        if (stream != null && status != null) DebugCard(stream, status)
        TimelineCard(status)
    }
}

@Composable
private fun NowPlayingCard(state: PlaybackBus.State, playSec: Double, status: EngineStatus?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == null || state == PlaybackBus.State.IDLE) {
                Text("「デモ再生」を押すと、架空の通勤（自宅 → 公園 → 駅 → 職場）に合わせて曲が変わっていきます。約9分で職場に着き、そのあとは滞在が続きます。画面を消しても、ほかのアプリを使っていても鳴り続けます。")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(playSec),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                PhaseChip(status.composer.phase)
                if (state == PlaybackBus.State.PAUSED) {
                    Spacer(Modifier.width(8.dp))
                    Text("（一時停止中）", style = MaterialTheme.typography.bodySmall)
                }
            }
            status.demoCue?.let { cue ->
                Text(cue.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "聞きどころ：${cue.hint}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("場面：${status.composer.scene.label}", style = MaterialTheme.typography.bodyMedium)
            placeText(status)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
private fun DebugCard(stream: StreamStatus, status: EngineStatus) {
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
            DebugRow("地点", c.place?.let { "${it.name}（${it.mood.label}）" } ?: "なし")
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

@Composable
private fun TimelineCard(status: EngineStatus?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("デモの流れ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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

private fun placeText(status: EngineStatus): String? {
    val place = status.composer.place ?: return null
    return when (status.composer.phase) {
        Phase.APPROACH -> "${place.name}に近づいています"
        Phase.ARRIVE -> "${place.name}に着きました"
        Phase.STAY -> "${place.name}に滞在中"
        else -> null
    }
}

private fun formatTime(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
