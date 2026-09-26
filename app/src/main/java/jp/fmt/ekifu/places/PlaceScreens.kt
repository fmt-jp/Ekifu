package jp.fmt.ekifu.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import jp.fmt.ekifu.data.PlaceEntity
import jp.fmt.ekifu.engine.Mood
import jp.fmt.ekifu.engine.PlaceConstants as P
import jp.fmt.ekifu.location.SceneSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.util.GeoPoint
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------- 地点の一覧 ----------------

@Composable
fun PlaceListScreen(
    places: List<PlaceEntity>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (PlaceEntity) -> Unit,
) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { onDispose { ThemePreviewPlayer.stop() } }
    ScreenColumn {
        Header("登録地点", onBack)
        Text(
            "自宅・職場・好きな公園などを登録すると、近づいたときにその地点のテーマが聞こえ、着くと曲が着地します（最大${P.MAX_PLACES}件）。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onAdd,
            enabled = places.size < P.MAX_PLACES,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (places.size < P.MAX_PLACES) "地点を追加" else "これ以上登録できません（${P.MAX_PLACES}件）") }

        if (places.isEmpty()) {
            Text("まだ登録された地点はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        places.forEach { place ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clickable { onEdit(place) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${place.moodValue.label}・接近 ${place.approachRadiusM}m・到着 ${place.arriveRadiusM}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { ThemePreviewPlayer.play(place.themeSeed, place.moodValue) }) { Text("試聴") }
                    TextButton(onClick = { onEdit(place) }) { Text("編集") }
                }
            }
        }
    }
}

// ---------------- 地点の登録・編集 ----------------

@Composable
fun PlaceEditScreen(
    existing: PlaceEntity?,
    onBack: () -> Unit,
    onSave: (PlaceEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { onDispose { ThemePreviewPlayer.stop() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var lat by rememberSaveable { mutableStateOf(existing?.lat) }
    var lng by rememberSaveable { mutableStateOf(existing?.lng) }
    var mood by rememberSaveable { mutableStateOf(existing?.moodValue ?: Mood.CALM) }
    var approach by rememberSaveable { mutableIntStateOf(existing?.approachRadiusM ?: P.APPROACH_RADIUS_DEFAULT_M) }
    var arrive by rememberSaveable { mutableIntStateOf(existing?.arriveRadiusM ?: P.ARRIVE_RADIUS_DEFAULT_M) }
    var themeSeed by rememberSaveable { mutableIntStateOf(existing?.themeSeed ?: Random.nextInt()) }
    var locating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val selected = if (lat != null && lng != null) GeoPoint(lat!!, lng!!) else null
    // 新規なら最後に分かっている現在地のあたりから地図を開く
    val initialCenter = remember { selected ?: lastKnownPoint(context) ?: DEFAULT_CENTER }

    fun locateHere() {
        locating = true
        message = null
        scope.launch {
            val p = currentPoint(context)
            locating = false
            if (p == null) {
                message = "現在地が取れませんでした。地図をタップして位置を決めてください。"
            } else {
                lat = p.latitude
                lng = p.longitude
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SceneSource.hasLocationPermission(context)) {
            locateHere()
        } else {
            message = "位置の許可がないため現在地は使えません。地図をタップして位置を決めてください。"
        }
    }

    ScreenColumn {
        Header(if (existing == null) "地点を追加" else "地点を編集", onBack)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名前（例：いつもの公園）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("位置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = {
                if (SceneSource.hasLocationPermission(context)) {
                    locateHere()
                } else {
                    permission.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            },
            enabled = !locating,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (locating) "現在地を取得中…" else "現在地で登録") }
        Text(
            "または地図をタップして位置を決める（地図の表示中だけ地図画像をネットから取ります）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlaceMap(
            initialCenter = initialCenter,
            selected = selected,
            approachRadiusM = approach,
            arriveRadiusM = arrive,
            accent = MaterialTheme.colorScheme.primary,
            highlight = MaterialTheme.colorScheme.tertiary,
            onTap = {
                lat = it.latitude
                lng = it.longitude
            },
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }

        Text("雰囲気", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mood.entries.forEach { m ->
                FilterChip(selected = mood == m, onClick = { mood = m }, label = { Text(m.label) })
            }
        }

        RadiusSlider(
            label = "接近（外側の円）",
            value = approach,
            min = P.APPROACH_RADIUS_MIN_M,
            max = P.APPROACH_RADIUS_MAX_M,
            step = P.APPROACH_RADIUS_STEP_M,
            onChange = { approach = it },
        )
        RadiusSlider(
            label = "到着（内側の円）",
            value = arrive,
            min = P.ARRIVE_RADIUS_MIN_M,
            max = P.ARRIVE_RADIUS_MAX_M,
            step = P.ARRIVE_RADIUS_STEP_M,
            onChange = { arrive = it },
        )
        val radiusOk = arrive < approach
        if (!radiusOk) {
            Text(
                "到着の円は接近の円より小さくしてください",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("テーマ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ThemePreviewPlayer.play(themeSeed, mood) }) { Text("試聴") }
            OutlinedButton(onClick = {
                themeSeed = Random.nextInt()
                ThemePreviewPlayer.play(themeSeed, mood)
            }) { Text("別のメロディにする") }
        }

        val canSave = name.isNotBlank() && selected != null && radiusOk
        Button(
            onClick = {
                onSave(
                    PlaceEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        lat = lat!!,
                        lng = lng!!,
                        approachRadiusM = approach,
                        arriveRadiusM = arrive,
                        mood = mood.name,
                        themeSeed = themeSeed,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("保存") }
        if (!canSave) {
            Text(
                when {
                    name.isBlank() -> "名前を入れてください"
                    selected == null -> "位置を決めてください（現在地か地図のタップ）"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (existing != null) {
            TextButton(onClick = { onDelete(existing.id) }, modifier = Modifier.fillMaxWidth()) {
                Text("この地点を削除", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun RadiusSlider(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("${value}m")
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) / step - 1,
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← 戻る") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

// ---------------- 現在地 ----------------

/** 登録のときだけ高い精度で1回取る */
@SuppressLint("MissingPermission")
private suspend fun currentPoint(context: Context): GeoPoint? {
    if (!SceneSource.hasLocationPermission(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    return withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationTokenSource()
            cont.invokeOnCancellation { cancel.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc?.let { GeoPoint(it.latitude, it.longitude) }) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }
}

/** 地図を開く位置の目安（端末が最後に知っている位置）。取れなければ null */
@SuppressLint("MissingPermission")
private fun lastKnownPoint(context: Context): GeoPoint? {
    if (!SceneSource.hasLocationPermission(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    return lm.getProviders(true)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { GeoPoint(it.latitude, it.longitude) }
}

private const val LOCATE_TIMEOUT_MS = 30_000L
/** 位置が分からないときの地図の中心（東京駅） */
private val DEFAULT_CENTER = GeoPoint(35.681236, 139.767125)
