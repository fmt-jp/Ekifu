package jp.fmt.ekifu.places

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/**
 * 地図（OpenStreetMap）。タップした位置を地点にし、接近・到着の円を描く。
 * 地図を表示しているあいだだけ、表示範囲の地図画像をネットから取る。
 */
@Composable
fun PlaceMap(
    initialCenter: GeoPoint,
    selected: GeoPoint?,
    approachRadiusM: Int,
    arriveRadiusM: Int,
    accent: Color,
    highlight: Color,
    onTap: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestOnTap by rememberUpdatedState(onTap)
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(INITIAL_ZOOM)
            controller.setCenter(initialCenter)
            // 画面のスクロールに指を取られず、地図を動かせるようにする
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            mv.overlays.clear()
            mv.overlays += MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    latestOnTap(p)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
            if (selected != null) {
                mv.overlays += circle(mv, selected, approachRadiusM, accent, 0.12f)
                mv.overlays += circle(mv, selected, arriveRadiusM, highlight, 0.25f)
                mv.overlays += Marker(mv).apply {
                    position = selected
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setInfoWindow(null)
                }
            }
            mv.overlays += CopyrightOverlay(mv.context)
            mv.invalidate()
        },
    )
}

private fun circle(mv: MapView, center: GeoPoint, radiusM: Int, color: Color, fillAlpha: Float) =
    Polygon(mv).apply {
        points = Polygon.pointsAsCircle(center, radiusM.toDouble())
        fillPaint.color = color.copy(alpha = fillAlpha).toArgb()
        outlinePaint.color = color.toArgb()
        outlinePaint.strokeWidth = 3f
        setInfoWindow(null)
    }

/** 地図画像の保存先をアプリ専用の領域にし、OpenStreetMap の利用規約どおりアプリを名乗る */
private fun configureOsmdroid(context: Context) {
    val conf = Configuration.getInstance()
    conf.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    conf.userAgentValue = context.packageName
    conf.osmdroidBasePath = File(context.filesDir, "osmdroid")
    conf.osmdroidTileCache = File(context.cacheDir, "osmdroid")
}

private const val INITIAL_ZOOM = 15.5
