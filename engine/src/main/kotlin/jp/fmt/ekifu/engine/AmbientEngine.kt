package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.BandSounds
import jp.fmt.ekifu.engine.AmbientConstants as A
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/**
 * 曲調「アンビエント」のエンジン（段階8）。作曲（AmbientComposer）→ 合成（AmbientSynth）。
 * ピアノはローファイと同じ音源の Grand Piano（音源が読めなければ粒の音で代わりに鳴らす）。
 */
class AmbientEngine private constructor(
    private var composer: AmbientComposer,
    private var synth: AmbientSynth,
    private val script: DemoScript?,
    override val sampleRate: Int,
    /** デモ台本のどこから始めたか（曲調の切り替えで続きから鳴らすとき） */
    private val scriptStartSec: Double,
    private val sounds: BandSounds?,
) : SoundEngine {

    constructor(
        seed: Int,
        sounds: BandSounds?,
        config: AmbientComposerConfig = AmbientComposerConfig(),
        script: DemoScript? = null,
        sampleRate: Int = C.SAMPLE_RATE,
        scriptStartSec: Double = 0.0,
    ) : this(AmbientComposer(seed, config), AmbientSynth(sampleRate, sounds), script, sampleRate, scriptStartSec, sounds)

    // それより前の台本は引き継いだ状態（CarryOver）として反映済み
    private var cueIndex = script?.firstCueAfter(scriptStartSec) ?: 0
    private var currentCue: DemoCue? = script?.cueAt(scriptStartSec)

    override val frame: Long get() = synth.frame

    val elapsedSec: Double get() = synth.frame.toDouble() / sampleRate

    override val endingCrossfadeSec: Double get() = F.ENDING_CROSSFADE_SEC

    override fun render(out: ShortArray, frames: Int, offsetFrames: Int) {
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
        val now = elapsedSec
        script?.let { s ->
            while (cueIndex < s.cues.size && s.cues[cueIndex].atSec <= now + scriptStartSec) {
                val cue = s.cues[cueIndex++]
                cue.action(composer)
                composer.react(now)
                currentCue = cue
            }
        }
        val blockEnd = now + C.RENDER_BLOCK_FRAMES.toDouble() / sampleRate
        synth.schedule(composer.composeUntil(blockEnd + C.COMPOSE_LOOKAHEAD_SEC))
    }

    override fun copy(): AmbientEngine = AmbientEngine(composer.copy(), synth.copy(), script, sampleRate, scriptStartSec, sounds).also {
        it.cueIndex = cueIndex
        it.currentCue = currentCue
    }

    override fun status(): EngineStatus {
        val t = elapsedSec
        val c = composer.chordAt(t)
        val scene = c?.scene ?: Scene.DEFAULT
        return EngineStatus(
            elapsedSec = t,
            composer = ComposerStatus(
                phase = c?.phase ?: Phase.START,
                chord = null,
                chordLabel = c?.chord?.name ?: "—",
                beatSec = c?.durSec ?: A.chordSec(scene.speed),
                noteProb = A.grainsPerSec(scene.speed),
                masterCutoffHz = c?.brightnessHz ?: A.brightnessHz(scene.sun),
                scene = scene,
                place = c?.place,
                gridStartSec = c?.startSec,
                arrival = c?.arrival,
            ),
            demoCue = currentCue,
            activeVoices = synth.activeVoices,
            stopping = false,
            finished = false,
        )
    }

    /** 入力を作曲に渡す。地点の判定なら鳴っている和音を切り上げて、数秒で次の和音（演出）へ */
    override fun apply(action: (ComposerInput) -> Unit) {
        action(composer)
        composer.react(elapsedSec)
    }

    /** 終わり：主和音（Fadd9）とドローンを伸ばして約12秒でフェードアウト */
    override fun endingRenderer(status: EngineStatus, nowSec: Double): Renderer =
        AmbientSynth(sampleRate, sounds).also { s ->
            val events = hold(status, AmbChord.FADD9, F.ENDING_FADE_SEC)
            events += AmbFadeOut(0.0, F.ENDING_FADE_SEC)
            s.schedule(events)
        }

    /** バッファ不足のあいだ、いまの和音を伸ばす */
    override fun fillerRenderer(status: EngineStatus): Renderer = AmbientSynth(sampleRate, sounds).also { s ->
        s.schedule(hold(status, AmbChord.byName(status.composer.chordLabel) ?: AmbChord.FADD9, F.FILLER_HOLD_SEC))
    }

    private fun hold(status: EngineStatus, chord: AmbChord, sec: Double): MutableList<AmbientEvent> {
        val events = ArrayList<AmbientEvent>()
        events += AmbControl(0.0, status.composer.masterCutoffHz)
        val base = (A.PAD_MIN_MIDI..A.PAD_MAX_MIDI).first { (it - chord.root) % 12 == 0 }
        chord.tones.forEachIndexed { i, iv ->
            events += AmbPad(i * 0.1, base + iv, sec, 1.0, A.padCutoffHz(status.composer.scene.sun), -0.4 + 0.8 * i / chord.tones.size)
        }
        events += AmbDrone(0.0, A.DRONE_MIDI, sec)
        return events
    }

    companion object {
        /** デモ再生（ほかの曲調と同じ台本：自宅 → 公園 → 駅 → 職場）。区切りは道中2分ごと */
        fun demo(sounds: BandSounds?, scriptStartSec: Double = 0.0, sampleRate: Int = C.SAMPLE_RATE) = AmbientEngine(
            A.DEMO_SEED,
            sounds,
            AmbientComposerConfig(interludeEverySec = C.DEMO_INTERLUDE_EVERY_SEC),
            DemoScript.COMMUTE,
            sampleRate,
            scriptStartSec,
        )
    }
}
