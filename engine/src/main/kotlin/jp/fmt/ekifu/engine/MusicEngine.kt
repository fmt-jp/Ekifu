package jp.fmt.ekifu.engine

import java.util.concurrent.ConcurrentLinkedQueue
import jp.fmt.ekifu.engine.MusicConstants as C

data class EngineStatus(
    val elapsedSec: Double,
    val composer: ComposerStatus,
    /** デモ再生中の現在の区間 */
    val demoCue: DemoCue?,
    val activeVoices: Int,
    val stopping: Boolean,
    val finished: Boolean,
)

/**
 * 作曲と合成をつなぎ、PCM を順番に作る。render は1つのスレッド（再生スレッド）からだけ呼ぶ。
 * 他のスレッドからの操作（post, requestStop）は次の render の頭で反映する。
 */
class MusicEngine(
    seed: Int,
    config: ComposerConfig = ComposerConfig(),
    private val script: DemoScript? = null,
    val sampleRate: Int = C.SAMPLE_RATE,
) {
    private val composer = Composer(seed, config)
    private val synth = Synth(sampleRate)
    private val commands = ConcurrentLinkedQueue<(Composer) -> Unit>()
    @Volatile private var stopRequested = false
    private var stopping = false
    private var cueIndex = 0
    private var currentCue: DemoCue? = null

    @Volatile
    var status: EngineStatus = makeStatus()
        private set

    val elapsedSec: Double get() = synth.frame.toDouble() / sampleRate

    val isFinished: Boolean
        get() = composer.endTimeSec?.let { elapsedSec >= it } ?: false

    /** 作曲への入力（場面・地点）を送る */
    fun post(action: (Composer) -> Unit) {
        commands += action
    }

    /** 停止ボタン：I を鳴らして約12秒でフェードアウトする */
    fun requestStop() {
        stopRequested = true
    }

    /** frames 個のステレオフレーム（16bit、L/R 交互）を out に書く */
    fun render(out: ShortArray, frames: Int) {
        val now = elapsedSec
        if (stopRequested && !stopping) {
            stopping = true
            synth.clearScheduled()
            synth.schedule(composer.stop(now))
        }
        if (!stopping) {
            while (true) commands.poll()?.invoke(composer) ?: break
            script?.let { s ->
                while (cueIndex < s.cues.size && s.cues[cueIndex].atSec <= now) {
                    val cue = s.cues[cueIndex++]
                    cue.action(composer)
                    currentCue = cue
                }
            }
            synth.schedule(composer.composeUntil(now + frames.toDouble() / sampleRate + C.COMPOSE_LOOKAHEAD_SEC))
        }
        synth.render(out, frames)
        status = makeStatus()
    }

    private fun makeStatus() = EngineStatus(
        elapsedSec = elapsedSec,
        composer = composer.status(),
        demoCue = currentCue,
        activeVoices = synth.activeVoices,
        stopping = stopping,
        finished = isFinished,
    )

    companion object {
        /** デモ再生用のエンジン */
        fun demo(sampleRate: Int = C.SAMPLE_RATE) = MusicEngine(
            seed = C.DEMO_SEED,
            config = ComposerConfig(interludeEverySec = C.DEMO_INTERLUDE_EVERY_SEC),
            script = DemoScript.COMMUTE,
            sampleRate = sampleRate,
        )
    }
}
