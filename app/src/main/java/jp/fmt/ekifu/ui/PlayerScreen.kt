package jp.fmt.ekifu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.fmt.ekifu.engine.DemoJourney
import jp.fmt.ekifu.engine.EngineStatus
import jp.fmt.ekifu.engine.Phase
import jp.fmt.ekifu.playback.DemoMode
import jp.fmt.ekifu.playback.PlaybackController
import jp.fmt.ekifu.playback.PlaybackState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    playback: PlaybackController,
    connected: Boolean,
    onPlayPause: (playing: Boolean) -> Unit,
    onRestart: () -> Unit,
    onModeChange: (DemoMode) -> Unit,
) {
    // 画面が見えていない間は集めない（画面オフ中は UI の更新を止める）
    val state by playback.state.collectAsStateWithLifecycle()
    var showDebug by rememberSaveable { mutableStateOf(false) }
    val colors = LocalRouteColors.current
    val journey = playback.journey

    Surface(color = colors.background, contentColor = colors.text, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("通勤のサウンドスケープ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(journey.route.name, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in DemoMode.entries) {
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }

            RouteMap(journey, state.status)

            StatusPanel(journey, state.status)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = connected, onClick = { onPlayPause(state.playing) }) {
                    Text(if (state.playing) "一時停止" else "デモ再生")
                }
                OutlinedButton(enabled = connected, onClick = onRestart) {
                    Text("最初から")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showDebug, onCheckedChange = { showDebug = it })
                Spacer(Modifier.padding(4.dp))
                Text("デバッグ表示", style = MaterialTheme.typography.bodyMedium)
            }
            if (showDebug) DebugPanel(state)
        }
    }
}

/** 縦の路線図：駅、地下区間の帯、現在地の点。 */
@Composable
private fun RouteMap(demo: DemoJourney, status: EngineStatus?) {
    val colors = LocalRouteColors.current
    val textMeasurer = rememberTextMeasurer()
    val stations = demo.route.stations
    val fractions = remember(demo) { demo.stationMinutes.map { it / demo.route.expectedMinutes } }
    val labelStyle = TextStyle(color = colors.text, fontSize = 16.sp)
    val bandLabelStyle = TextStyle(color = colors.text.copy(alpha = 0.6f), fontSize = 12.sp)
    val progress = status?.journey?.progress?.coerceIn(0.0, 1.0)
    val lastPassed = status?.journey?.lastPassedStationIndex ?: -1

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height((stations.size * 56).dp),
    ) {
        val lineX = 40.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        fun yFor(p: Double): Float {
            val i = fractions.indexOfLast { it <= p }.coerceIn(0, fractions.size - 2)
            val local = ((p - fractions[i]) / (fractions[i + 1] - fractions[i])).coerceIn(0.0, 1.0)
            val step = (bottom - top) / (fractions.size - 1)
            return top + step * (i + local.toFloat())
        }

        // 地下区間の帯
        val bandTop = yFor(demo.undergroundRange.start)
        val bandBottom = yFor(demo.undergroundRange.endInclusive)
        drawRoundRect(
            color = colors.undergroundBand,
            topLeft = Offset(lineX - 18.dp.toPx(), bandTop),
            size = Size(size.width - lineX + 18.dp.toPx(), bandBottom - bandTop),
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
        val bandLabel = textMeasurer.measure("地下", bandLabelStyle)
        drawText(bandLabel, topLeft = Offset(size.width - bandLabel.size.width - 12.dp.toPx(), bandTop + 6.dp.toPx()))

        drawLine(colors.line, Offset(lineX, top), Offset(lineX, bottom), strokeWidth = 8.dp.toPx())

        stations.forEachIndexed { i, station ->
            val y = yFor(fractions[i])
            val passed = i <= lastPassed
            drawCircle(if (passed) colors.line else colors.background, radius = 9.dp.toPx(), center = Offset(lineX, y))
            drawCircle(colors.line, radius = 9.dp.toPx(), center = Offset(lineX, y), style = Stroke(3.dp.toPx()))
            val label = textMeasurer.measure(station.name, labelStyle)
            drawText(label, topLeft = Offset(lineX + 28.dp.toPx(), y - label.size.height / 2f))
        }

        if (progress != null) {
            val y = yFor(progress)
            drawCircle(colors.background, radius = 13.dp.toPx(), center = Offset(lineX, y))
            drawCircle(colors.current, radius = 10.dp.toPx(), center = Offset(lineX, y))
        }
    }
}

@Composable
private fun StatusPanel(demo: DemoJourney, status: EngineStatus?) {
    val journey = status?.journey
    val phase = status?.phase ?: Phase.DEPARTURE
    val elapsed = journey?.routeElapsedSeconds ?: 0.0
    val remaining = journey?.remainingSeconds ?: demo.route.expectedMinutes * 60
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "経過 ${formatTime(elapsed)}　到着まで ${formatTime(remaining)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "${phaseName(phase)}　${phaseDescription(phase)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        val underground = (status?.undergroundMix ?: 0.0) >= 0.5
        Text(if (underground) "地下を走行中" else "地上を走行中", style = MaterialTheme.typography.bodyMedium)
        val lastIndex = journey?.lastPassedStationIndex ?: -1
        val lastName = demo.route.stations.getOrNull(lastIndex)?.name
        Text("直前に鳴った駅：${lastName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DebugPanel(state: PlaybackState) {
    val status = state.status
    val text = if (status == null) {
        "未再生"
    } else {
        listOf(
            "バッファ残量 = %.1f 秒".format(Locale.US, state.bufferedSeconds),
            "バッファ不足 = ${state.underruns} 回",
            "p = %.3f".format(Locale.US, status.journey.progress),
            "phase = ${status.phase}",
            "underground = ${status.journey.underground} (mix %.2f)".format(Locale.US, status.undergroundMix),
            "音声の経過 = ${formatTime(status.audioElapsedSeconds)}",
            "finished = ${status.finished}",
        ).joinToString("\n")
    }
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

private fun phaseName(phase: Phase) = when (phase) {
    Phase.DEPARTURE -> "出発"
    Phase.MIDDLE -> "道中"
    Phase.PRE_ARRIVAL -> "到着前"
    Phase.ARRIVAL -> "到着"
}

private fun phaseDescription(phase: Phase) = when (phase) {
    Phase.DEPARTURE -> "静かに走り出す"
    Phase.MIDDLE -> "景色が流れていく"
    Phase.PRE_ARRIVAL -> "もうすぐ到着"
    Phase.ARRIVAL -> "おつかれさまでした"
}

private fun formatTime(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
}
