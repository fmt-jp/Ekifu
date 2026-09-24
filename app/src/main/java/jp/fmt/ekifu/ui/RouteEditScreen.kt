package jp.fmt.ekifu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

/**
 * ルート登録画面（SPEC 8章）：駅の追加・並べ替え・削除、地図タップで座標指定、区間ごとの地下フラグ、予定所要時間。
 */
@Composable
fun RouteEditScreen(onClose: () -> Unit, viewModel: RouteEditViewModel = viewModel()) {
    val colors = LocalRouteColors.current
    var mapTarget by rememberSaveable { mutableStateOf<Int?>(null) }

    BackHandler(onBack = onClose)

    Surface(color = colors.background, contentColor = colors.text, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← 戻る") }
                Text(
                    if (viewModel.isNew) "通勤ルートの登録" else "通勤ルートの編集",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!viewModel.loaded) return@Column

            OutlinedTextField(
                value = viewModel.name,
                onValueChange = { viewModel.name = it },
                label = { Text("ルート名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = viewModel.minutesText,
                onValueChange = { viewModel.minutesText = it },
                label = { Text("予定所要時間（分）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("駅（乗る駅から降りる駅まで順に）", style = MaterialTheme.typography.titleMedium)
            viewModel.stations.forEachIndexed { index, station ->
                key(station.id) {
                    StationCard(
                        index = index,
                        station = station,
                        count = viewModel.stations.size,
                        onNameChange = { name -> viewModel.updateStation(index) { it.copy(name = name) } },
                        onPickOnMap = { mapTarget = index },
                        onMoveUp = { viewModel.moveStation(index, index - 1) },
                        onMoveDown = { viewModel.moveStation(index, index + 1) },
                        onRemove = { viewModel.removeStation(index) },
                    )
                }
                if (index < viewModel.stations.lastIndex) {
                    SegmentRow(
                        from = station.name,
                        to = viewModel.stations[index + 1].name,
                        underground = viewModel.segmentUnderground.getOrElse(index) { false },
                        onChange = { viewModel.setUnderground(index, it) },
                    )
                }
            }
            OutlinedButton(onClick = { viewModel.addStation() }) { Text("＋ 駅を追加") }

            for (problem in viewModel.problems) {
                Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { viewModel.save(onSaved = onClose) }, modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
        }
    }

    val target = mapTarget
    if (target != null && target in viewModel.stations.indices) {
        val station = viewModel.stations[target]
        // 直前の駅を最後に置くと、地図がそこから始まる
        val landmarks = viewModel.stations.withIndex()
            .filter { it.index != target && it.value.lat != null && it.value.lng != null }
            .sortedBy { if (it.index == target - 1) 1 else 0 }
            .map { MapLandmark(it.value.name, it.value.lat!!, it.value.lng!!) }
        MapPickerDialog(
            stationName = station.name,
            initialLat = station.lat,
            initialLng = station.lng,
            landmarks = landmarks,
            onPick = { lat, lng ->
                viewModel.updateStation(target) { it.copy(lat = lat, lng = lng) }
                mapTarget = null
            },
            onDismiss = { mapTarget = null },
        )
    }
}

@Composable
private fun StationCard(
    index: Int,
    station: StationDraft,
    count: Int,
    onNameChange: (String) -> Unit,
    onPickOnMap: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalRouteColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.text.copy(alpha = 0.05f),
        contentColor = colors.text,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = station.name,
                onValueChange = onNameChange,
                label = { Text("${index + 1} 番目の駅名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val located = station.lat != null && station.lng != null
                Text(
                    if (located) "%.5f, %.5f".format(Locale.US, station.lat, station.lng) else "位置が未指定",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (located) Color.Unspecified else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickOnMap) { Text("地図で指定") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = index > 0, onClick = onMoveUp) { Text("↑ 上へ") }
                TextButton(enabled = index < count - 1, onClick = onMoveDown) { Text("↓ 下へ") }
                TextButton(enabled = count > 1, onClick = onRemove) { Text("削除") }
            }
        }
    }
}

/** 駅と駅の間の区間。地下かどうかを切り替える。 */
@Composable
private fun SegmentRow(from: String, to: String, underground: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalRouteColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (underground) colors.undergroundBand else Color.Transparent,
        contentColor = colors.text,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                "｜ ${from.ifBlank { "？" }} → ${to.ifBlank { "？" }}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("地下区間", style = MaterialTheme.typography.bodySmall)
            Switch(checked = underground, onCheckedChange = onChange, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
