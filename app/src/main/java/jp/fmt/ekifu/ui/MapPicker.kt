package jp.fmt.ekifu.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale

/** 地図に目印として出す、ほかの駅。 */
data class MapLandmark(val name: String, val lat: Double, val lng: Double)

/**
 * 地図をタップして駅の座標を決めるダイアログ（OpenStreetMap、API キー不要）。
 */
@Composable
fun MapPickerDialog(
    stationName: String,
    initialLat: Double?,
    initialLng: Double?,
    landmarks: List<MapLandmark>,
    onPick: (lat: Double, lng: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(if (initialLat != null && initialLng != null) GeoPoint(initialLat, initialLng) else null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "「${stationName.ifBlank { "駅" }}」の位置を地図でタップ",
                    style = MaterialTheme.typography.titleMedium,
                )
                val initial = picked
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context -> createMapView(context, initial, landmarks) { picked = it } },
                    onRelease = { it.onDetach() },
                )
                Text(
                    picked?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) } ?: "まだ指定されていません",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("キャンセル") }
                    Button(
                        enabled = picked != null,
                        onClick = { picked?.let { onPick(it.latitude, it.longitude) } },
                    ) { Text("この位置にする") }
                }
            }
        }
    }
}

/** [initial] があればその位置に印を置く。なければ最後の目印（直前の駅など）か東京駅付近から始める。 */
private fun createMapView(
    context: Context,
    initial: GeoPoint?,
    landmarks: List<MapLandmark>,
    onTap: (GeoPoint) -> Unit,
): MapView {
    val center = initial ?: landmarks.lastOrNull()?.let { GeoPoint(it.lat, it.lng) } ?: DEFAULT_CENTER
    val zoomIn = initial != null || landmarks.isNotEmpty()
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        controller.setZoom(if (zoomIn) STATION_ZOOM else CITY_ZOOM)
        controller.setCenter(center)

        for (landmark in landmarks) {
            overlays += Marker(this).apply {
                position = GeoPoint(landmark.lat, landmark.lng)
                title = landmark.name
                setAlpha(LANDMARK_ALPHA)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
        }
        val target = Marker(this).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            position = center
        }
        val mapView = this
        overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                target.position = p
                if (target !in overlays) overlays += target
                mapView.invalidate()
                onTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        if (initial != null) overlays += target
        onResume()
    }
}

private val DEFAULT_CENTER = GeoPoint(35.681, 139.767) // 東京駅付近
private const val STATION_ZOOM = 16.0
private const val CITY_ZOOM = 11.0
private const val LANDMARK_ALPHA = 0.45f
