package jp.fmt.ekifu.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** アプリの設定（端末内に保存） */
object AppSettings {
    private const val PREFS = "ekifu_settings"
    private const val KEY_DEBUG = "debug_visible"

    private val _debugVisible = MutableStateFlow(false)
    /** デバッグ表示と開発用（デモ再生）を出すか。既定はオフ */
    val debugVisible: StateFlow<Boolean> = _debugVisible

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _debugVisible.value = prefs(context).getBoolean(KEY_DEBUG, false)
    }

    fun setDebugVisible(context: Context, visible: Boolean) {
        _debugVisible.value = visible
        prefs(context).edit().putBoolean(KEY_DEBUG, visible).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
