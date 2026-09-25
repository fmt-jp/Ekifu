package jp.fmt.ekifu.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import jp.fmt.ekifu.data.EkifuDatabase
import jp.fmt.ekifu.data.Visit
import jp.fmt.ekifu.engine.Geo
import jp.fmt.ekifu.engine.LocationFix
import jp.fmt.ekifu.engine.MusicConstants
import jp.fmt.ekifu.engine.Scene
import jp.fmt.ekifu.engine.SceneDecider
import jp.fmt.ekifu.engine.SceneDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.TimeZone
import kotlin.coroutines.resume

/** 画面のデバッグ表示用 */
data class SceneUi(
    val hasPermission: Boolean,
    val decision: SceneDecision? = null,
    /** 次の場面の区切りの時刻 */
    val nextSceneAtMs: Long? = null,
    /** 最後に位置が取れた時刻 */
    val lastFixAtMs: Long? = null,
)

/**
 * おまかせ再生の場面を約5分ごとに決める（5章）。
 * - 位置の許可あり：Fused Location Provider（省電力の精度）で位置を受け取り、5分ごとに最新の位置で場面を決める
 * - 許可なし：5分ごとに架空の場所へ移る。太陽の高さはタイムゾーンから概算
 * 一時停止中は位置の取得を止める。メインスレッドで使う。
 */
class SceneSource(
    context: Context,
    private val onScene: (Scene) -> Unit,
    private val onUi: (SceneUi) -> Unit,
) {
    private val appContext = context.applicationContext
    val hasPermission: Boolean = hasLocationPermission(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)
    private val visits = EkifuDatabase.get(appContext).visits()
    private val decider = SceneDecider()
    private val sessionId = System.currentTimeMillis()
    private var pseudoIndex = 0
    private var latest: LocationFix? = null
    private var ticker: Job? = null
    private var ui = SceneUi(hasPermission)

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { latest = LocationFix(it.latitude, it.longitude, it.time) }
        }
    }

    /** 再生開始時の仮の場面（最初の位置が取れるまで）。許可がなければこれが最初の場面 */
    fun initialScene(): Scene {
        val now = System.currentTimeMillis()
        return SceneDecider.withoutLocation(sessionId, pseudoIndex, now, TimeZone.getDefault().getOffset(now))
    }

    /** 再生・再開 */
    fun start() {
        if (ticker != null) return
        val intervalMs = (MusicConstants.SCENE_INTERVAL_SEC * 1000).toLong()
        ticker = scope.launch {
            if (hasPermission) {
                requestUpdates(intervalMs)
                currentFix()?.let { latest = it }
                while (isActive) {
                    decide()
                    publish(nextSceneAtMs = System.currentTimeMillis() + intervalMs)
                    delay(intervalMs)
                }
            } else {
                publish(nextSceneAtMs = System.currentTimeMillis() + intervalMs)
                while (isActive) {
                    delay(intervalMs)
                    val now = System.currentTimeMillis()
                    val scene = SceneDecider.withoutLocation(
                        sessionId, ++pseudoIndex, now, TimeZone.getDefault().getOffset(now),
                    )
                    onScene(scene)
                    ui = ui.copy(decision = SceneDecision(scene, null, located = false))
                    publish(nextSceneAtMs = now + intervalMs)
                }
            }
        }
    }

    /** 一時停止：位置の取得も止める */
    fun pause() {
        ticker?.cancel()
        ticker = null
        fused.removeLocationUpdates(callback)
        publish(nextSceneAtMs = null)
    }

    fun release() {
        pause()
        scope.cancel()
    }

    private suspend fun decide() {
        val now = System.currentTimeMillis()
        val maxAgeMs = (MusicConstants.LOCATION_MAX_AGE_SEC * 1000).toLong()
        val fix = latest?.takeIf { now - it.timeMs <= maxAgeMs }
        var days = 0
        if (fix != null) {
            // 保存するのはマスIDと日付だけ
            val grid = Geo.geohash(fix.lat, fix.lng)
            days = withContext(Dispatchers.IO) {
                visits.insert(Visit(grid, LocalDate.now().toString()))
                visits.countDays(grid)
            }
        }
        val decision = decider.decide(fix, now) { days } ?: return
        onScene(decision.scene)
        ui = ui.copy(decision = decision, lastFixAtMs = fix?.timeMs ?: ui.lastFixAtMs)
    }

    private fun publish(nextSceneAtMs: Long?) {
        ui = ui.copy(nextSceneAtMs = nextSceneAtMs)
        onUi(ui)
    }

    @SuppressLint("MissingPermission") // hasPermission を確かめてから呼ぶ
    private fun requestUpdates(intervalMs: Long) {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs).build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "位置の許可が取り消された", e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFix(): LocationFix? = withTimeoutOrNull(FIRST_FIX_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationTokenSource()
            cont.invokeOnCancellation { cancel.cancel() }
            try {
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancel.token)
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) cont.resume(loc?.let { LocationFix(it.latitude, it.longitude, it.time) })
                    }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    companion object {
        private const val TAG = "ekifu"
        /** 再生・再開時に最初の位置を待つ上限 */
        private const val FIRST_FIX_TIMEOUT_MS = 20_000L

        fun hasLocationPermission(context: Context): Boolean =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }
}
