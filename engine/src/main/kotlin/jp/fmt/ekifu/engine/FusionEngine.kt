package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/**
 * 曲調「フュージョン」のエンジン（段階6a：音色デモ）。作曲 → 合成をつなぎ、ChunkStream から使う。
 * 場面・登録地点の入力は段階6bで作曲につなぐ（いまは受け取っても何もしない）。
 */
class FusionEngine private constructor(
    private var composer: FusionDemoComposer,
    private var synth: FusionSynth,
    override val sampleRate: Int,
) : SoundEngine {

    override val frame: Long get() = synth.frame

    val elapsedSec: Double get() = synth.frame.toDouble() / sampleRate

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

    override fun copy(): FusionEngine = FusionEngine(composer.copy(), synth.copy(), sampleRate)

    override fun status(): EngineStatus {
        val t = elapsedSec
        val block = composer.blockAt(t)
        val chord = block?.chordAt(t)
        return EngineStatus(
            elapsedSec = t,
            composer = ComposerStatus(
                phase = Phase.JOURNEY,
                chord = null,
                chordLabel = if (block != null && chord != null) "${block.section.name}・${chord.name}" else "—",
                beatSec = composer.stepSec * 4,
                noteProb = block?.heat ?: 0.0,
                masterCutoffHz = F.DAY_CUTOFF_HZ,
                scene = Scene.DEFAULT,
                place = null,
                heat = block?.heat,
                fusionChord = chord,
            ),
            demoCue = null,
            activeVoices = synth.activeVoices,
            stopping = false,
            finished = false,
        )
    }

    /** 段階6a のデモは場面・地点の入力を使わない */
    override fun apply(action: (ComposerInput) -> Unit) = Unit

    /** 終わり：トニック DM9 を鳴らして約12秒でフェードアウト（キメは段階6bで足す） */
    override fun endingRenderer(status: EngineStatus): Renderer =
        holdChord(FusionChord.TONIC, F.ENDING_FADE_SEC, status.composer.masterCutoffHz).also {
            it.schedule(listOf(FusionFadeOut(0.0, F.ENDING_FADE_SEC)))
        }

    /** バッファ不足のあいだ、いまの和音をシンセブラスとベースで伸ばす */
    override fun fillerRenderer(status: EngineStatus): Renderer =
        holdChord(status.composer.fusionChord ?: FusionChord.TONIC, F.FILLER_HOLD_SEC, status.composer.masterCutoffHz)

    private fun holdChord(chord: FusionChord, sec: Double, cutoffHz: Double) = FusionSynth(sampleRate).also { s ->
        val events = ArrayList<FusionEvent>()
        events += FusionControl(0.0, cutoffHz, F.LEAD_FILTER_BASE_HZ, composer.stepSec * F.DELAY_STEPS)
        chord.keysVoicing.forEachIndexed { i, midi ->
            events += PolyNote(i * F.KEYS_STAGGER_SEC, PolyPart.BRASS, midi, sec, 0.75)
        }
        events += PolyNote(0.0, PolyPart.BASS, chord.bassRoot, sec, 0.9)
        s.schedule(events)
    }

    companion object {
        /** 段階6a の音色デモ（132 BPM、A→A→B→B、熱量 0.3→0.6→0.9） */
        fun demo(sampleRate: Int = C.SAMPLE_RATE) =
            FusionEngine(FusionDemoComposer(), FusionSynth(sampleRate, F.DEMO_SEED), sampleRate)
    }
}
