package jp.fmt.ekifu.playback

import android.content.Context
import android.os.PowerManager
import jp.fmt.ekifu.engine.DemoJourney
import jp.fmt.ekifu.engine.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArraySet

enum class DemoMode(val label: String, val playbackMinutes: Double) {
    SHORT("デモ 3分", DemoJourney.SHORT_PLAYBACK_MINUTES),
    LONG("長時間テスト 30分", DemoJourney.LONG_PLAYBACK_MINUTES),
}

data class PlaybackState(
    val mode: DemoMode = DemoMode.SHORT,
    /** 再生の準備ができたか（通知・ロック画面に出すかどうか）。 */
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val status: EngineStatus? = null,
    val bufferedSeconds: Double = 0.0,
    val underruns: Int = 0,
)

/**
 * アプリ全体で 1 つの再生の窓口。画面（Activity）とサービス（MediaSession）の両方から使う。
 * 状態の変化は [state] と [addListener] で知らせる（リスナーは任意のスレッドから呼ばれる）。
 */
class PlaybackController private constructor(context: Context) {
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ekifu:playback")
        .apply { setReferenceCounted(false) }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var pipeline: AudioPipeline? = null

    var journey: DemoJourney = DemoJourney.create(DemoMode.SHORT.playbackMinutes)
        private set

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    @Synchronized
    fun setMode(mode: DemoMode) {
        if (mode == _state.value.mode) return
        stop()
        journey = DemoJourney.create(mode.playbackMinutes)
        setState { PlaybackState(mode = mode) }
    }

    @Synchronized
    fun prepare() {
        setState { it.copy(prepared = true) }
    }

    @Synchronized
    fun play() {
        val current = pipeline
        val p = if (current == null || _state.value.ended) {
            current?.release()
            newPipeline().also { pipeline = it }
        } else {
            current
        }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        p.play()
        setState { it.copy(prepared = true, playing = true, ended = false) }
    }

    @Synchronized
    fun pause() {
        pipeline?.pause()
        releaseWakeLock()
        setState { it.copy(playing = false) }
    }

    /** 最初から再生し直す。 */
    @Synchronized
    fun restart() {
        pipeline?.release()
        pipeline = null
        setState { PlaybackState(mode = it.mode) }
        play()
    }

    @Synchronized
    fun stop() {
        pipeline?.release()
        pipeline = null
        releaseWakeLock()
        setState { PlaybackState(mode = it.mode) }
    }

    private fun newPipeline(): AudioPipeline {
        lateinit var created: AudioPipeline
        created = AudioPipeline(journey.route, journey) { snapshot ->
            // 作り直し前の古いパイプラインからの通知は無視する
            if (pipeline !== created) return@AudioPipeline
            val endedNow = snapshot.ended && !_state.value.ended
            _state.update {
                it.copy(
                    status = snapshot.status,
                    bufferedSeconds = snapshot.bufferedSeconds,
                    underruns = snapshot.underruns,
                    ended = snapshot.ended,
                    playing = it.playing && !snapshot.ended,
                )
            }
            if (endedNow) {
                releaseWakeLock()
                notifyListeners()
            }
        }
        return created
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun setState(transform: (PlaybackState) -> PlaybackState) {
        _state.update(transform)
        notifyListeners()
    }

    private fun notifyListeners() {
        for (l in listeners) l()
    }

    companion object {
        /** 念のための上限（最長の確認用デモより長く）。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        @Volatile private var instance: PlaybackController? = null

        fun get(context: Context): PlaybackController =
            instance ?: synchronized(this) {
                instance ?: PlaybackController(context.applicationContext).also { instance = it }
            }
    }
}
