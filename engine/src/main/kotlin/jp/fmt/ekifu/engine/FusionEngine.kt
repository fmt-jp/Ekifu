package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/**
 * 曲調「フュージョン」のエンジン。作曲（FusionComposer）→ 合成（FusionSynth）をつなぎ、ChunkStream から使う。
 * 場面・登録地点の入力は次のブロックの頭で反映する。
 */
class FusionEngine private constructor(
    private var composer: FusionComposer,
    private var synth: FusionSynth,
    private val script: DemoScript?,
    override val sampleRate: Int,
    /** デモ台本のどこから始めたか（曲調の切り替えで続きから鳴らすとき） */
    private val scriptStartSec: Double,
) : SoundEngine {

    constructor(
        seed: Int,
        config: FusionComposerConfig = FusionComposerConfig(),
        script: DemoScript? = null,
        sampleRate: Int = C.SAMPLE_RATE,
        scriptStartSec: Double = 0.0,
    ) : this(FusionComposer(seed, config), FusionSynth(sampleRate, seed), script, sampleRate, scriptStartSec)

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
                currentCue = cue
            }
        }
        val blockEnd = now + C.RENDER_BLOCK_FRAMES.toDouble() / sampleRate
        synth.schedule(composer.composeUntil(blockEnd + C.COMPOSE_LOOKAHEAD_SEC))
    }

    override fun copy(): FusionEngine = FusionEngine(composer.copy(), synth.copy(), script, sampleRate, scriptStartSec).also {
        it.cueIndex = cueIndex
        it.currentCue = currentCue
    }

    override fun status(): EngineStatus {
        val t = elapsedSec
        val block = composer.blockAt(t)
        val chord = block?.chordAt(t)
        return EngineStatus(
            elapsedSec = t,
            composer = ComposerStatus(
                phase = block?.phase ?: Phase.START,
                chord = null,
                chordLabel = if (block != null && chord != null) "${block.section.name}・${chord.name}${barLabel(block, t)}" else "—",
                beatSec = (block?.stepSec ?: composer.stepSec) * 4,
                noteProb = block?.heat ?: 0.0,
                masterCutoffHz = block?.cutoffHz ?: F.DAY_CUTOFF_HZ,
                scene = block?.scene ?: Scene.DEFAULT,
                place = block?.place,
                heat = block?.heat,
                fusionChord = chord,
                gridStartSec = block?.startSec,
            ),
            demoCue = currentCue,
            activeVoices = synth.activeVoices,
            stopping = false,
            finished = false,
        )
    }

    /** デバッグ表示：いまの小節がキメ・つなぎならその型 */
    private fun barLabel(block: FusionBlock, t: Double): String {
        val bar = block.barAt(t)
        if (bar == F.BARS_PER_BLOCK - 1) composer.transitionOf(block)?.let { return "（つなぎ：${it.label}）" }
        return block.kimes[bar]?.let { "（キメ：${it.label}）" } ?: ""
    }

    override fun apply(action: (ComposerInput) -> Unit) = action(composer)

    /** 終わり：次の16分からキメを2小節 → DM9 を鳴らす。全体は停止から約12秒でフェードアウト */
    override fun endingRenderer(status: EngineStatus, nowSec: Double): Renderer {
        val c = status.composer
        val step = c.beatSec / 4
        val grid = c.gridStartSec ?: nowSec
        val sinceGrid = ((nowSec - grid) % step + step) % step
        val offset = if (sinceGrid < 1e-6) 0.0 else step - sinceGrid
        val chord = c.fusionChord ?: FusionChord.TONIC
        val events = ArrayList<FusionEvent>()
        events += FusionControl(0.0, c.masterCutoffHz, F.LEAD_FILTER_BASE_HZ, step * F.DELAY_STEPS)
        for (bar in 0 until F.ENDING_KIME_BARS) {
            FusionComposer.KIME_HITS.forEachIndexed { i, k ->
                val t = offset + (bar * F.STEPS_PER_BAR + k) * step
                chord.keysVoicing.forEachIndexed { j, midi ->
                    events += PolyNote(t + j * F.KEYS_STAGGER_SEC, PolyPart.BRASS, midi, 0.6 * step, 0.8)
                }
                events += PolyNote(t, PolyPart.BASS, if (i % 2 == 1) chord.bassRoot + 12 else chord.bassRoot, step, 0.95)
                events += DrumHit(t, DrumKind.KICK, 1.0)
                events += DrumHit(t, DrumKind.SNARE, 0.9)
            }
        }
        val land = offset + F.ENDING_KIME_BARS * F.STEPS_PER_BAR * step
        val hold = F.ENDING_FADE_SEC
        FusionChord.TONIC.keysVoicing.forEachIndexed { j, midi ->
            events += PolyNote(land + j * F.KEYS_STAGGER_SEC, PolyPart.BRASS, midi, hold, 0.85)
        }
        events += PolyNote(land, PolyPart.BASS, FusionChord.TONIC.bassRoot, hold, 1.0)
        events += DrumHit(land, DrumKind.KICK, 1.0)
        events += DrumHit(land, DrumKind.CRASH, 0.9)
        events += FusionFadeOut(0.0, F.ENDING_FADE_SEC)
        return FusionSynth(sampleRate).also { it.schedule(events) }
    }

    /** バッファ不足のあいだ、いまの和音をシンセブラスとベースで伸ばす */
    override fun fillerRenderer(status: EngineStatus): Renderer = FusionSynth(sampleRate).also { s ->
        val chord = status.composer.fusionChord ?: FusionChord.TONIC
        val sec = F.FILLER_HOLD_SEC
        val events = ArrayList<FusionEvent>()
        events += FusionControl(0.0, status.composer.masterCutoffHz, F.LEAD_FILTER_BASE_HZ, status.composer.beatSec / 4 * F.DELAY_STEPS)
        chord.keysVoicing.forEachIndexed { i, midi ->
            events += PolyNote(i * F.KEYS_STAGGER_SEC, PolyPart.BRASS, midi, sec, 0.75)
        }
        events += PolyNote(0.0, PolyPart.BASS, chord.bassRoot, sec, 0.9)
        s.schedule(events)
    }

    companion object {
        /** デモ再生（癒しと同じ台本：自宅 → 公園 → 駅 → 職場）。区切りは道中2分ごと */
        fun demo(sampleRate: Int = C.SAMPLE_RATE) = FusionEngine(
            seed = F.DEMO_SEED,
            config = FusionComposerConfig(interludeEverySec = C.DEMO_INTERLUDE_EVERY_SEC),
            script = DemoScript.COMMUTE,
            sampleRate = sampleRate,
        )
    }
}
