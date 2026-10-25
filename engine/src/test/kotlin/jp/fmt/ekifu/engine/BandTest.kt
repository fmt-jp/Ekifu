package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.BandSounds
import jp.fmt.ekifu.engine.dsp.SampleLead
import jp.fmt.ekifu.engine.dsp.SampleVoice
import jp.fmt.ekifu.engine.sf2.SampleBank
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.BandConstants as B
import jp.fmt.ekifu.engine.MusicConstants as C

/** 曲調「生音フュージョン」：サウンドフォントから抜き出した音源と、それを鳴らす声部 */
class BandTest {

    private val sr = C.SAMPLE_RATE

    private val bank: SampleBank by lazy {
        val f = listOf(File("../app/src/main/assets/band.ekb"), File("app/src/main/assets/band.ekb")).first { it.exists() }
        f.inputStream().use { SampleBank.read(it) }
    }
    private val sounds by lazy { BandSounds(bank) }

    private fun rms(a: DoubleArray) = sqrt(a.sumOf { it * it } / a.size)

    /** 自己相関で音の高さ（Hz）を見積もる */
    private fun pitchOf(x: DoubleArray, minHz: Double, maxHz: Double): Double {
        val from = (sr / maxHz).toInt()
        val to = (sr / minHz).toInt()
        val n = x.size - to
        var best = from
        var bestR = Double.NEGATIVE_INFINITY
        for (lag in from..to) {
            var r = 0.0
            for (i in 0 until n) r += x[i] * x[i + lag]
            if (r > bestR) {
                bestR = r
                best = lag
            }
        }
        return sr.toDouble() / best
    }

    private fun render(v: () -> Double, seconds: Double) = DoubleArray((seconds * sr).toInt()) { v() }

    @Test
    fun bankHasTheFourInstruments() {
        assertNotNull(bank.preset(B.LEAD_BANK, B.LEAD_PROGRAM))
        assertNotNull(bank.preset(B.KEYS_BANK, B.KEYS_PROGRAM))
        assertNotNull(bank.preset(B.BASS_BANK, B.BASS_PROGRAM))
        val drums = bank.preset(B.DRUM_BANK, B.DRUM_PROGRAM)!!
        for (k in B.DRUM_KEYS) assertTrue(drums.zonesFor(k, 100).isNotEmpty(), "ドラムの鍵盤 $k がない")
        // 音域：リード（フュージョンのリードの音域）・鍵盤・ベース
        for (k in FusionConstants.LEAD_MIN_MIDI..FusionConstants.LEAD_MAX_MIDI) {
            assertTrue(sounds.lead.zones.any { k in it.keyLo..it.keyHi }, "リードの $k がない")
        }
        for (k in 28..80) assertTrue(sounds.keys.zones.any { k in it.keyLo..it.keyHi }, "鍵盤の $k がない")
        for (k in 28..60) assertTrue(sounds.bass.zones.any { k in it.keyLo..it.keyHi }, "ベースの $k がない")
        assertTrue(File("../app/src/main/assets/band.ekb").length() < 4 * 1024 * 1024, "音源が大きすぎる")
    }

    @Test
    fun voicesPlayAtTheRightPitch() {
        // A3（220Hz）・A4（440Hz）
        val keys = SampleVoice(sounds, sounds.keys, 57, 0.8, sr, 1.0, sr.toLong())
        val ep = render(keys::next, 0.6).copyOfRange(sr / 10, sr / 2)
        assertEquals(220.0, pitchOf(ep, 150.0, 300.0), 220.0 * 0.03)
        val bass = SampleVoice(sounds, sounds.bass, 45, 0.8, sr, 1.0, sr.toLong())
        val b = render(bass::next, 0.6).copyOfRange(sr / 10, sr / 2)
        assertEquals(110.0, pitchOf(b, 70.0, 160.0), 110.0 * 0.03)
        val lead = SampleLead(sr, sounds)
        lead.noteOn(69.0, 0.0, 0.8, 0.0, 0.1)
        val l = render(lead::next, 0.6).copyOfRange(sr / 10, sr / 2)
        assertEquals(440.0, pitchOf(l, 300.0, 600.0), 440.0 * 0.03)
    }

    @Test
    fun notesReleaseAndFinish() {
        val v = SampleVoice(sounds, sounds.keys, 60, 0.8, sr, 1.0, (0.3 * sr).toLong())
        var frames = 0
        while (!v.finished && frames < 20 * sr) {
            v.next()
            frames++
        }
        assertTrue(v.finished, "鍵盤の音が終わらない")
        val kick = SampleVoice(sounds, sounds.drums, B.KICK_KEY, 1.0, sr, 1.0, Long.MAX_VALUE)
        val k = render(kick::next, 0.3)
        assertTrue(rms(k) > 0.01, "キックが鳴らない")
    }

    @Test
    fun copiedVoicesContinueIdentically() {
        val lead = SampleLead(sr, sounds)
        lead.noteOn(70.0, 0.0, 0.9, 20.0, 0.2)
        repeat(sr / 3) { lead.next() }
        lead.noteOn(74.0, 0.05, 0.9, 20.0, 0.2)
        repeat(1000) { lead.next() }
        val copy = lead.copy()
        assertContentEquals(render(lead::next, 0.5), render(copy::next, 0.5))
    }

    @Test
    fun bandDemoIsAudibleWithoutClippingAndCopiesIdentically() {
        val e = FusionEngine.demo(sounds = sounds)
        val n = 40 * sr
        val out = ShortArray(n * 2)
        val started = System.nanoTime()
        e.render(out, n, 0)
        val speed = 40.0 / ((System.nanoTime() - started) / 1e9)
        println("生音フュージョン：%.0f倍速".format(speed))
        assertTrue(speed > 10, "合成が遅い: ${speed}倍速")
        var peak = 0
        var sum = 0.0
        for (s in out) {
            peak = maxOf(peak, abs(s.toInt()))
            sum += s.toDouble() * s
        }
        val r = sqrt(sum / out.size) / 32768
        println("生音フュージョンのデモ：RMS %.3f、ピーク %d".format(r, peak))
        assertTrue(r > 0.03, "小さすぎる: $r")
        assertTrue(peak < 32767, "割れている")
        val c = e.copy()
        val a = ShortArray(sr * 4)
        val b = ShortArray(sr * 4)
        e.render(a, sr * 2, 0)
        c.render(b, sr * 2, 0)
        assertContentEquals(a, b)
    }

    @Test
    fun styleFallsBackToSynthWithoutTheBank() {
        // 音源を読めないときもエンジンは作れる（シンセの音で鳴る）
        val e = FusionEngine(seed = 1, sounds = null)
        val out = ShortArray(sr * 2)
        e.render(out, sr, 0)
        assertEquals(MusicStyle.BAND.label, "生音フュージョン")
    }
}
