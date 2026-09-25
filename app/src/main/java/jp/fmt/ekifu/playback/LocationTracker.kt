package jp.fmt.ekifu.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import jp.fmt.ekifu.engine.LocationSample

/**
 * ルートなしモード用に、約 5 分ごとに位置を取る。
 * 精度は駅や街区の単位で足りるので省電力の設定にし、高精度 GPS は使わない。
 */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private var callback: LocationCallback? = null
    private var firstFix: CancellationTokenSource? = null

    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** 位置の取得を始める。許可がなければ何もせず false。コールバックはメインスレッドで呼ばれる。 */
    @SuppressLint("MissingPermission")
    fun start(onSample: (LocationSample) -> Unit): Boolean {
        stop()
        if (!hasPermission()) return false
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onSample(it.toSample()) }
            }
        }
        callback = cb
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .build()
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())

        // 最初の 1 回は 5 分待たずにすぐ取る
        val token = CancellationTokenSource()
        firstFix = token
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location != null && callback === cb) onSample(location.toSample())
            }
        return true
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        firstFix?.cancel()
        firstFix = null
    }

    private fun Location.toSample() = LocationSample(
        // 端末の時計が変わっても狂わないよう、起動からの経過時間を使う
        timeMillis = elapsedRealtimeNanos / 1_000_000,
        lat = latitude,
        lng = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else UNKNOWN_ACCURACY_METERS,
    )

    private companion object {
        /** 位置を取る間隔（約 5 分）。 */
        const val INTERVAL_MS = 5 * 60 * 1000L
        const val UNKNOWN_ACCURACY_METERS = 100.0
    }
}
