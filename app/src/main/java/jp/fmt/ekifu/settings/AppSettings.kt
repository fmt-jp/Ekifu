package jp.fmt.ekifu.settings

import android.content.Context
import jp.fmt.ekifu.engine.MusicStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** アプリの設定（端末内に保存） */
object AppSettings {
    private const val PREFS = "ekifu_settings"
    private const val KEY_DEBUG = "debug_visible"
    private const val KEY_STYLE = "music_style"

    private val _debugVisible = MutableStateFlow(false)
    /** デバッグ表示と開発用（デモ再生）を出すか。既定はオフ */
    val debugVisible: StateFlow<Boolean> = _debugVisible

    private val _style = MutableStateFlow(MusicStyle.HEALING)
    /** 曲調（12章）。既定は癒し。再生中に変えると約2秒でフェードアウトして新しい曲調の「始まり」から */
    val style: StateFlow<MusicStyle> = _style

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        _debugVisible.value = p.getBoolean(KEY_DEBUG, false)
        _style.value = MusicStyle.entries.firstOrNull { it.name == p.getString(KEY_STYLE, null) } ?: MusicStyle.HEALING
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
