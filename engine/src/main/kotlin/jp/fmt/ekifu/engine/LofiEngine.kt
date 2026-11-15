package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.BandSounds
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.LofiConstants as L
import jp.fmt.ekifu.engine.MusicConstants as C

/**
 * 曲調「ローファイ」のエンジン（段階7）。作曲（LofiComposer）→ 合成（FusionSynth をローファイの楽器と加工で使う）。
 * 楽器の音源が読めないときはフュージョンのシンセの音で鳴らす（加工はかける）。
 */
class LofiEngine private constructor(
    private var composer: LofiComposer,
    private var synth: FusionSynth,
    override val sampleRate: Int,
    private val sounds: BandSounds?,
) : SoundEngine {

    constructor(
        seed: Int,
        sounds: BandSounds?,
        previewScenes: List<Scene>? = null,
        sampleRate: Int = C.SAMPLE_RATE,
    ) : this(LofiComposer(seed, previewScenes), FusionSynth(sampleRate, seed, sounds, lofi = true), sampleRate, sounds)

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

    override fun copy(): LofiEngine = LofiEngine(composer.copy(), synth.copy(), sampleRate, sounds)

    override fun status(): EngineStatus {
        val t = elapsedSec
        val bar = composer.barAt(t)
        val scene = bar?.scene ?: Scene.DEFAULT
        val stepSec = bar?.stepSec ?: composer.currentStepSec
        return EngineStatus(
            elapsedSec = t,
            composer = ComposerStatus(
                phase = Phase.JOURNEY,
                chord = null,
                chordLabel = bar?.chord?.name ?: "—",
                beatSec = stepSec * 4,
                noteProb = 0.0,
                masterCutoffHz = L.cutoffHz(scene.sun),
                scene = scene,
                place = null,
                gridStartSec = bar?.startSec,
            ),
            demoCue = null,
            activeVoices = synth.activeVoices,
            stopping = false,
            finished = false,
        )
    }

    override fun apply(action: (ComposerInput) -> Unit) = action(composer)

    /** 終わり：いまの拍の頭から主和音（Fmaj9）を伸ばして約12秒でフェードアウト */
    override fun endingRenderer(status: EngineStatus, nowSec: Double): Renderer {
        val events = ArrayList<FusionEvent>()
        events += FusionControl(0.0, status.composer.masterCutoffHz, F.LEAD_FILTER_BASE_HZ, status.composer.beatSec / 4 * L.DELAY_STEPS, L.DELAY_FEEDBACK)
        events += LofiControl(0.0, L.noiseFactor(status.composer.scene.sun))
        hold(events, LofiChord.FMAJ9, F.ENDING_FADE_SEC)
        events += DrumHit(0.0, DrumKind.KICK, 0.8)
        events += FusionFadeOut(0.0, F.ENDING_FADE_SEC)
        return FusionSynth(sampleRate, sounds = sounds, lofi = true).also { it.schedule(events) }
    }

    /** バッファ不足のあいだ、いまの和音を伸ばす */
    override fun fillerRenderer(status: EngineStatus): Renderer = FusionSynth(sampleRate, sounds = sounds, lofi = true).also { s ->
        val events = ArrayList<FusionEvent>()
        events += FusionControl(0.0, status.composer.masterCutoffHz, F.LEAD_FILTER_BASE_HZ, status.composer.beatSec / 4 * L.DELAY_STEPS, L.DELAY_FEEDBACK)
        hold(events, LofiChord.byName(status.composer.chordLabel) ?: LofiChord.FMAJ9, F.FILLER_HOLD_SEC)
        s.schedule(events)
    }

    private fun hold(events: MutableList<FusionEvent>, chord: LofiChord, sec: Double) {
        val base = (L.KEYS_MIN_MIDI..L.KEYS_MAX_MIDI).first { it % 12 == chord.root }
        chord.tones.forEachIndexed { i, iv ->
            events += PolyNote(i * L.KEYS_STRUM_SEC, PolyPart.BRASS, base + iv, sec, 0.55)
        }
        events += PolyNote(0.0, PolyPart.BASS, L.BASS_MIN_MIDI + ((chord.root - L.BASS_MIN_MIDI) % 12 + 12) % 12, sec, 0.8)
    }

    companion object {
        /**
         * 段階7aの音色デモ：場面を8小節ごとに順に切り替えて、速さ・太陽・なじみ度による違いを聞き比べる
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
            LofiEngine(L.DEMO_SEED, sounds, PREVIEW_SCENES, sampleRate)
    }
}
