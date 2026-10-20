package jp.fmt.ekifu.settings

import android.content.Context
import jp.fmt.ekifu.engine.GpsMode
import jp.fmt.ekifu.engine.LocationPolicy
import jp.fmt.ekifu.engine.LocationPreset
import jp.fmt.ekifu.engine.MusicStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** アプリの設定（端末内に保存） */
object AppSettings {
    private const val PREFS = "ekifu_settings"
    private const val KEY_DEBUG = "debug_visible"
    private const val KEY_STYLE = "music_style"
    private const val KEY_LOC_PRESET = "location_preset"
    private const val KEY_LOC_GPS = "location_gps_mode"
    private const val KEY_LOC_NEAR_M = "location_near_m"
    private const val KEY_LOC_NORMAL_S = "location_normal_s"
    private const val KEY_LOC_VEHICLE_S = "location_vehicle_s"
    private const val KEY_LOC_NEAR_S = "location_near_s"
    private const val KEY_LOC_KEEP_GPS = "location_keep_gps"

    private val _debugVisible = MutableStateFlow(false)
    /** デバッグ表示と開発用（デモ再生）を出すか。既定はオフ */
    val debugVisible: StateFlow<Boolean> = _debugVisible

    private val _style = MutableStateFlow(MusicStyle.HEALING)
    /** 曲調（12章）。既定は癒し。再生中に変えると約2秒でフェードアウトして新しい曲調の「始まり」から */
    val style: StateFlow<MusicStyle> = _style

    private val _locationPreset = MutableStateFlow(LocationPreset.STANDARD)
    /** 位置の取り方の「おまかせ」。既定は標準 */
    val locationPreset: StateFlow<LocationPreset> = _locationPreset

    private val _customPolicy = MutableStateFlow(LocationPolicy.STANDARD)
    /** カスタムのときの位置の取り方 */
    val customPolicy: StateFlow<LocationPolicy> = _customPolicy

    private val _locationPolicy = MutableStateFlow(LocationPolicy.STANDARD)
    /** いま使う位置の取り方（おまかせの値か、カスタムの値）。再生中に変えても次の位置から反映する */
    val locationPolicy: StateFlow<LocationPolicy> = _locationPolicy

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        _debugVisible.value = p.getBoolean(KEY_DEBUG, false)
        _style.value = MusicStyle.entries.firstOrNull { it.name == p.getString(KEY_STYLE, null) } ?: MusicStyle.HEALING
        _locationPreset.value = LocationPreset.entries.firstOrNull { it.name == p.getString(KEY_LOC_PRESET, null) }
            ?: LocationPreset.STANDARD
        val d = LocationPolicy.STANDARD
        _customPolicy.value = LocationPolicy(
            gpsMode = GpsMode.entries.firstOrNull { it.name == p.getString(KEY_LOC_GPS, null) } ?: d.gpsMode,
            nearDistanceM = p.getFloat(KEY_LOC_NEAR_M, d.nearDistanceM.toFloat()).toDouble(),
            normalIntervalSec = p.getFloat(KEY_LOC_NORMAL_S, d.normalIntervalSec.toFloat()).toDouble(),
            vehicleIntervalSec = p.getFloat(KEY_LOC_VEHICLE_S, d.vehicleIntervalSec.toFloat()).toDouble(),
            nearIntervalSec = p.getFloat(KEY_LOC_NEAR_S, d.nearIntervalSec.toFloat()).toDouble(),
            keepGpsAfterArrival = p.getBoolean(KEY_LOC_KEEP_GPS, d.keepGpsAfterArrival),
        )
        updateLocationPolicy()
    }

    /** おまかせを選ぶ。カスタムを初めて選んだときは、それまでのおまかせの値から始める */
    fun setLocationPreset(context: Context, preset: LocationPreset) {
        val p = prefs(context)
        if (preset == LocationPreset.CUSTOM && !p.contains(KEY_LOC_GPS)) {
            setCustomPolicy(context, _locationPolicy.value)
        }
        _locationPreset.value = preset
        p.edit().putString(KEY_LOC_PRESET, preset.name).apply()
        updateLocationPolicy()
    }

    fun setCustomPolicy(context: Context, policy: LocationPolicy) {
        _customPolicy.value = policy
        prefs(context).edit()
            .putString(KEY_LOC_GPS, policy.gpsMode.name)
            .putFloat(KEY_LOC_NEAR_M, policy.nearDistanceM.toFloat())
            .putFloat(KEY_LOC_NORMAL_S, policy.normalIntervalSec.toFloat())
            .putFloat(KEY_LOC_VEHICLE_S, policy.vehicleIntervalSec.toFloat())
            .putFloat(KEY_LOC_NEAR_S, policy.nearIntervalSec.toFloat())
            .putBoolean(KEY_LOC_KEEP_GPS, policy.keepGpsAfterArrival)
            .apply()
        updateLocationPolicy()
    }

    private fun updateLocationPolicy() {
        _locationPolicy.value = _locationPreset.value.policy ?: _customPolicy.value
    }

    fun setDebugVisible(context: Context, visible: Boolean) {
        _debugVisible.value = visible
        prefs(context).edit().putBoolean(KEY_DEBUG, visible).apply()
    }

    fun setStyle(context: Context, style: MusicStyle) {
        _style.value = style
        prefs(context).edit().putString(KEY_STYLE, style.name).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
