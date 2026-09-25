package jp.fmt.ekifu.playback

import jp.fmt.ekifu.engine.StreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 再生サービスから画面へ状態を渡す（同じプロセス内） */
object PlaybackBus {

    enum class State { IDLE, PLAYING, PAUSED, ENDING }

    data class Ui(
        val state: State = State.IDLE,
        val stream: StreamStatus? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /** 画面が見ているときだけ細かい更新を流す（画面オフ中は止める） */
    val hasSubscribers: Boolean get() = _ui.subscriptionCount.value > 0

    fun update(state: State, stream: StreamStatus?) {
        _ui.value = Ui(state, stream)
    }

    fun updateStream(stream: StreamStatus) {
        _ui.value = _ui.value.copy(stream = stream)
    }
}
