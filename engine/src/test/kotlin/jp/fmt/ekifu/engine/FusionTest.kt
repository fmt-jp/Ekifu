package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.Envelope
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

class FusionTest {

    private val sr = C.SAMPLE_RATE
    private val block = 2048

    private fun render(engine: SoundEngine, seconds: Double): ShortArray {
        val frames = (seconds * sr).toInt() / block * block
        return ShortArray(frames * 2).also { engine.render(it, frames) }
    }

    @Test
    fun copiedEngineContinuesIdentically() {
        val a = FusionEngine.demo()
        render(a, 23.0)
        val b = a.copy()
        assertContentEquals(render(a, 30.0), render(b, 30.0))
    }

    @Test
    fun chunkedOutputEqualsDirectRenderWithoutUnderruns() {
        val stream = ChunkStream(FusionEngine.demo())
        val frames = 90 * sr / block * block
        val out = ShortArray(frames * 2)
        val buf = ShortArray(block * 2)
        var done = 0
        while (done < frames) {
            while (stream.needsWork()) stream.work()
            assertEquals(block, stream.read(buf, block))
            System.arraycopy(buf, 0, out, done * 2, block * 2)
            done += block
        }
        assertContentEquals(render(FusionEngine.demo(), 90.0), out)
        assertEquals(0, stream.status().underruns)
    }

    @Test
    fun demoIsAudibleWithoutClippingAndFastEnough() {
        val engine = FusionEngine.demo()
        val started = System.nanoTime()
        // 6ブロック（A, A, B, B, A, A）で熱量 0.3 / 0.6 / 0.9 をすべて通る
        val seconds = 6 * F.STEPS_PER_BLOCK * 60.0 / F.DEMO_BPM / 4
        val pcm = render(engine, seconds)
        val elapsed = (System.nanoTime() - started) / 1e9
        println("フュージョン：${"%.0f".format(seconds)}秒ぶん / ${"%.1f".format(elapsed)}秒（${"%.0f".format(seconds / elapsed)}倍速）")
        System.getenv("EKIFU_FUSION_WAV")?.let { writeWav(File(it), pcm) }

        var clipped = 0
        pcm.forEach { if (abs(it.toInt()) >= 32700) clipped++ }
        assertTrue(clipped < sr / 100, "音割れ: $clipped")
        val window = sr * 2 * 5
        var i = 0
        while (i + window <= pcm.size) {
            var s = 0.0
            for (j in i until i + window) s += pcm[j].toDouble() * pcm[j]
            val rms = sqrt(s / window) / 32768
            assertTrue(rms > 0.02, "${i / 2 / sr}秒あたりが小さすぎる: $rms")
            i += window
        }
        // 端末（JVM より遅い）でも余裕を持てるよう、JVM で実時間の10倍以上
        assertTrue(seconds / elapsed > 10, "合成が遅い: ${seconds / elapsed}倍速")
    }

    @Test
    fun stopFadesToSilenceAndFillerHoldsChord() {
        val engine = FusionEngine.demo()
        render(engine, 10.0)
        val st = engine.status()
        val ending = engine.endingRenderer(st)
        val out = ShortArray((F.ENDING_FADE_SEC * sr).toInt() * 2)
        ending.render(out, out.size / 2, 0)
        val head = out.copyOfRange(0, sr * 2 * 2).maxOf { abs(it.toInt()) }
        val tail = out.copyOfRange(out.size - sr, out.size).maxOf { abs(it.toInt()) }
        assertTrue(head > 1000, "終わりの和音が聞こえない: $head")
        // 直線のフェードなので、最後の0.5秒は鳴り始めの数%以下
        assertTrue(tail < head * 0.05, "フェードアウトしきっていない: $tail / $head")

        val filler = engine.fillerRenderer(st)
        val f = ShortArray(sr * 3 * 2)
        filler.render(f, sr * 3, 0)
        assertTrue(f.copyOfRange(sr * 2 * 2, f.size).maxOf { abs(it.toInt()) } > 500, "つなぎの和音が伸びていない")
    }

    @Test
    fun voicingFollowsReferenceFormula() {
        for (section in FusionSection.entries) {
            for (ch in section.chords) {
                val v = ch.keysVoicing
                val base = v[0] - ch.quality.voice[0]
                // 参照実装：ルート+48、54を超えたら−12、45未満なら+12（G・G♯ は範囲に収まらず 55・56 になる）
                var expected = ch.rootPc + 48
                if (expected > 54) expected -= 12
                if (expected < 45) expected += 12
                assertEquals(expected, base, ch.name)
                assertTrue(ch.bassRoot <= F.BASS_BASE_MAX, ch.name)
            }
        }
    }

    @Test
    fun leadStaysInRangeAndNeverOverlaps() {
        val c = FusionDemoComposer()
        val events = c.composeUntil(6 * F.STEPS_PER_BLOCK * c.stepSec)
        val lead = events.filterIsInstance<LeadNote>().sortedBy { it.timeSec }
        assertTrue(lead.size > 100)
        lead.forEach { assertTrue(it.midi in F.LEAD_MIN_MIDI..F.LEAD_MAX_MIDI, "音域外: ${it.midi}") }
        lead.zipWithNext().forEach { (a, b) ->
            assertTrue(b.timeSec >= a.timeSec + F.LEAD_MIN_ONSET_GAP_SEC - 1e-9)
            // 次の音の3ms以上前に離す
            assertTrue(a.timeSec + a.durSec <= b.timeSec - 0.003 + 1e-9, "重なり: $a / $b")
        }
    }

    @Test
    fun accompanimentFollowsHeat() {
        val c = FusionDemoComposer()
        val events = c.composeUntil(3 * F.STEPS_PER_BLOCK * c.stepSec)
        val step = c.stepSec
        fun inBlock(i: Int, e: FusionEvent) = e.timeSec >= i * 128 * step - 1e-9 && e.timeSec < (i + 1) * 128 * step - 1e-9
        fun hatsInBar0(i: Int) = events.filterIsInstance<DrumHit>()
            .count { it.kind == DrumKind.HAT && inBlock(i, it) && it.timeSec < i * 128 * step + 16 * step - 1e-9 }
        fun kicksInBar0(i: Int) = events.filterIsInstance<DrumHit>()
            .count { it.kind == DrumKind.KICK && inBlock(i, it) && it.timeSec < i * 128 * step + 16 * step - 1e-9 }
        // 熱量 0.3：8分のハイハット・キック3つ／0.6：16分・4つ／0.9：16分・6つ
        assertEquals(8, hatsInBar0(0))
        assertEquals(3, kicksInBar0(0))
        assertEquals(16, hatsInBar0(1))
        assertEquals(4, kicksInBar0(1))
        assertEquals(16, hatsInBar0(2))
        assertEquals(6, kicksInBar0(2))
    }

    @Test
    fun envelopeRetriggerNeverJumps() {
        val env = Envelope(sr, F.LEAD_AMP_ENV)
        val maxStep = 1.0 / (F.LEAD_AMP_ENV.attackSec * sr) + 1e-9
        var prev = 0.0
        val rng = Mulberry32(3)
        repeat(200_000) { i ->
            if (i % 997 == 0) env.gateOn()
            if (i % 1531 == 0 || rng.nextDouble() < 0.0005) env.gateOff()
            val v = env.next()
            assertTrue(abs(v - prev) <= maxStep, "エンベロープが跳んだ: $prev → $v")
            prev = v
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size * 2)
            f.write(h.array())
            val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcm)
            f.write(bb.array())
        }
    }
}
