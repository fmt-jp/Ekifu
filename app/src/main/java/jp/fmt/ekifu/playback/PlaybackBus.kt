package jp.fmt.ekifu.playback

import jp.fmt.ekifu.engine.StreamStatus
import jp.fmt.ekifu.location.SceneUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 再生の種類 */
enum class PlaybackMode {
    OMAKASE,
    DEMO,
    /** 段階6a：フュージョンの音色デモ（開発用）。段階6cで「曲調」の設定に置き換える */
    FUSION_DEMO,
}

/** 再生サービスと画面のあいだで状態を渡す（同じプロセス内） */
object PlaybackBus {

    enum class State { IDLE, PLAYING, PAUSED, ENDING }

    data class Ui(
        val state: State = State.IDLE,
        val mode: PlaybackMode = PlaybackMode.OMAKASE,
        val stream: StreamStatus? = null,
        /** おまかせ再生の場面（デモ再生では null） */
        val scene: SceneUi? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /** 次に再生を始めるときの種類（画面で選んでから MediaController.play を呼ぶ） */
    @Volatile var requestedMode = PlaybackMode.OMAKASE

    /** 画面が見ているときだけ細かい更新を流す（画面オフ中は止める） */
    val hasSubscribers: Boolean get() = _ui.subscriptionCount.value > 0

    fun update(state: State, mode: PlaybackMode, stream: StreamStatus?) {
        _ui.update { it.copy(state = state, mode = mode, stream = stream, scene = if (state == State.IDLE) null else it.scene) }
    }

    fun updateStream(stream: StreamStatus) {
        _ui.update { it.copy(stream = stream) }
    }

    fun updateScene(scene: SceneUi?) {
        _ui.update { it.copy(scene = scene) }
    }
}
