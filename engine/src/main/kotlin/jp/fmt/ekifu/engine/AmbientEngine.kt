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
    override val sampleRate: Int,
    private val sounds: BandSounds?,
) : SoundEngine {

    constructor(
        seed: Int,
        sounds: BandSounds?,
        previewScenes: List<Scene>? = null,
        sampleRate: Int = C.SAMPLE_RATE,
    ) : this(AmbientComposer(seed, previewScenes), AmbientSynth(sampleRate, sounds), sampleRate, sounds)

    override val frame: Long get() = synth.frame

    val elapsedSec: Double get() = synth.frame.toDouble() / sampleRate

    override val endingCrossfadeSec: Double get() = F.ENDING_CROSSFADE_SEC

    override fun render(out: ShortArray, frames: Int, offsetFrames: Int) {
        var done = 0
        while (done < frames) {
            // 作曲の先読みの区切りをフレーム位置で固定し、分け方によらず同じ結果にする
            val toBlockEnd = C.RENDER_BLOCK_FRAMES - (synth.frame % C.RENDER_BLOCK_FRAMES).toInt()
            val n = minOf(frames - done, toBlockEnd)
            if (synth.frame % C.RENDER_BLOCK_FRAMES == 0L) {
                val blockEnd = elapsedSec + C.RENDER_BLOCK_FRAMES.toDouble() / sampleRate
                synth.schedule(composer.composeUntil(blockEnd + C.COMPOSE_LOOKAHEAD_SEC))
            }
            synth.render(out, n, offsetFrames + done)
            done += n
        }
    }

    override fun copy(): AmbientEngine = AmbientEngine(composer.copy(), synth.copy(), sampleRate, sounds)

    override fun status(): EngineStatus {
        val t = elapsedSec
        val c = composer.chordAt(t)
        val scene = c?.scene ?: Scene.DEFAULT
        return EngineStatus(
            elapsedSec = t,
            composer = ComposerStatus(
                phase = Phase.JOURNEY,
                chord = null,
                chordLabel = c?.chord?.name ?: "—",
                beatSec = c?.durSec ?: A.chordSec(scene.speed),
                noteProb = A.grainsPerSec(scene.speed),
                masterCutoffHz = c?.brightnessHz ?: A.brightnessHz(scene.sun),
                scene = scene,
                place = null,
                gridStartSec = c?.startSec,
            ),
            demoCue = null,
            activeVoices = synth.activeVoices,
            stopping = false,
            finished = false,
        )
    }

    override fun apply(action: (ComposerInput) -> Unit) = action(composer)

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
        /**
         * 段階8aの音色デモ：場面を3和音ごとに順に切り替えて、速さ・太陽・なじみ度による違いを聞き比べる
         * （歩き・昼 → 静止・昼 → 乗り物・昼 → 歩き・薄明 → 歩き・夜 → なじみ → 初めて）
         */
        val PREVIEW_SCENES = listOf(
            Scene("xn76ur", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY),
            Scene("xn76ur", Speed.STILL, Familiarity.NORMAL, SunLevel.DAY),
            Scene("xn76fv", Speed.VEHICLE, Familiarity.NORMAL, SunLevel.DAY),
            Scene("xn76fv", Speed.WALK, Familiarity.NORMAL, SunLevel.TWILIGHT),
            Scene("xn77h0", Speed.WALK, Familiarity.NORMAL, SunLevel.NIGHT),
            Scene("xn77h0", Speed.WALK, Familiarity.FAMILIAR, SunLevel.DAY),
            Scene("xn7e2t", Speed.WALK, Familiarity.NEW, SunLevel.DAY),
        )

        fun preview(sounds: BandSounds?, sampleRate: Int = C.SAMPLE_RATE) =
            AmbientEngine(A.DEMO_SEED, sounds, PREVIEW_SCENES, sampleRate)
    }
}
