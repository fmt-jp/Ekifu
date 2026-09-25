package jp.fmt.ekifu.engine

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
 * 作曲と合成をつなぎ、PCM を順番に作る。1つのスレッドからだけ使う。
 * copy() で作曲・合成の状態をまるごと複製できる（チャンクの作り直しに使う）。
 */
class MusicEngine private constructor(
    private var composer: Composer,
    private var synth: Synth,
    private val script: DemoScript?,
    val sampleRate: Int,
) {
    constructor(
        seed: Int,
        config: ComposerConfig = ComposerConfig(),
        script: DemoScript? = null,
        sampleRate: Int = C.SAMPLE_RATE,
    ) : this(Composer(seed, config), Synth(sampleRate), script, sampleRate)

    private var stopping = false
    private var cueIndex = 0
    private var currentCue: DemoCue? = null

    /** これまでに合成したフレーム数 */
    val frame: Long get() = synth.frame

    val elapsedSec: Double get() = synth.frame.toDouble() / sampleRate

    val isFinished: Boolean
        get() = composer.endTimeSec?.let { elapsedSec >= it } ?: false

    /** 作曲への入力（場面・地点）。次の和音の切り替えで反映される */
    fun apply(action: (Composer) -> Unit) {
        if (!stopping) action(composer)
    }

    /** 停止：今の位置で I を鳴らして約12秒でフェードアウトする */
    fun requestStop() {
        if (stopping) return
        stopping = true
        synth.clearScheduled()
        synth.schedule(composer.stop(elapsedSec))
    }

    fun copy(): MusicEngine = MusicEngine(composer.copy(), synth.copy(), script, sampleRate).also {
        it.stopping = stopping
        it.cueIndex = cueIndex
        it.currentCue = currentCue
    }

    fun status() = EngineStatus(
        elapsedSec = elapsedSec,
        composer = composer.status(),
        demoCue = currentCue,
        activeVoices = synth.activeVoices,
        stopping = stopping,
        finished = isFinished,
    )

    /**
     * frames 個のステレオフレーム（16bit、L/R 交互）を out の offsetFrames 以降に書く。
     * 何回に分けて呼んでも、フレーム位置が同じなら同じ波形になる。
     */
    fun render(out: ShortArray, frames: Int, offsetFrames: Int = 0) {
        var done = 0
        while (done < frames) {
            // 作曲の先読みの区切りをフレーム位置で固定し、分け方によらず同じ結果にする
            val toBlockEnd = C.RENDER_BLOCK_FRAMES - (synth.frame % C.RENDER_BLOCK_FRAMES).toInt()
            val n = minOf(frames - done, toBlockEnd)
            if (synth.frame % C.RENDER_BLOCK_FRAMES == 0L) beginBlock()
            synth.render(out, n, offsetFrames + done)
            done += n
        }
    }

    private fun beginBlock() {
        if (stopping) return
        val now = elapsedSec
        script?.let { s ->
            while (cueIndex < s.cues.size && s.cues[cueIndex].atSec <= now) {
                val cue = s.cues[cueIndex++]
                cue.action(composer)
                currentCue = cue
            }
        }
        val blockEnd = now + C.RENDER_BLOCK_FRAMES.toDouble() / sampleRate
        synth.schedule(composer.composeUntil(blockEnd + C.COMPOSE_LOOKAHEAD_SEC))
    }

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
