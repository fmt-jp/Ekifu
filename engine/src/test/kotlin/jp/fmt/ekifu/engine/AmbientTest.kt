package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.BandSounds
import jp.fmt.ekifu.engine.sf2.SampleBank
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.AmbientConstants as A
import jp.fmt.ekifu.engine.MusicConstants as C

/** 曲調「アンビエント」段階8a：パッド・ドローン・粒・ピアノと場面による違い */
class AmbientTest {

    private val sr = C.SAMPLE_RATE

    private val sounds: BandSounds by lazy {
        val f = listOf(File("../app/src/main/assets/lofi.ekb"), File("app/src/main/assets/lofi.ekb")).first { it.exists() }
        BandSounds(f.inputStream().use { SampleBank.read(it) }, LofiConstants.KIT)
    }

    private fun scene(speed: Speed = Speed.WALK, f: Familiarity = Familiarity.NORMAL, sun: SunLevel = SunLevel.DAY) =
        Scene("xn76ur", speed, f, sun)

    private fun compose(c: AmbientComposer, sec: Double): List<AmbientEvent> {
        val events = ArrayList<AmbientEvent>()
        var t = 0.0
        while (t < sec) {
            events += c.composeUntil(t)
            t += 0.5
        }
        return events
    }

    @Test
    fun chordLengthAndGrainDensityFollowSpeed() {
        val grains = Speed.entries.associateWith { speed ->
            val c = AmbientComposer(1).also { it.setScene(scene(speed)) }
            val events = compose(c, 240.0)
            assertEquals(A.chordSec(speed), c.chordAt(100.0)!!.durSec, 1e-9)
            events.count { it is AmbGrain && it.timeSec < 200.0 } / 200.0
        }
        assertTrue(grains[Speed.STILL]!! < grains[Speed.WALK]!! && grains[Speed.WALK]!! < grains[Speed.VEHICLE]!!, "$grains")
        assertTrue(A.chordSec(Speed.STILL) > A.chordSec(Speed.WALK) && A.chordSec(Speed.WALK) > A.chordSec(Speed.VEHICLE))
    }

    @Test
    fun sunSetsRegisterAndBrightness() {
        fun avgPad(sun: SunLevel): Double {
            val c = AmbientComposer(2).also { it.setScene(scene(sun = sun)) }
            return compose(c, 120.0).filterIsInstance<AmbPad>().map { it.midi }.average()
        }
        assertTrue(avgPad(SunLevel.NIGHT) < avgPad(SunLevel.DAY))
        val c = AmbientComposer(2).also { it.setScene(scene(sun = SunLevel.NIGHT)) }
        assertEquals(A.brightnessHz(SunLevel.NIGHT), compose(c, 5.0).filterIsInstance<AmbControl>().first().brightnessHz)
        assertTrue(A.brightnessHz(SunLevel.NIGHT) < A.brightnessHz(SunLevel.DAY))
    }

    @Test
    fun familiarityChoosesHowFarTheChordsWander() {
        for ((f, pool) in mapOf(
            Familiarity.FAMILIAR to AmbientHarmony.FAMILIAR,
            Familiarity.NORMAL to AmbientHarmony.NORMAL,
            Familiarity.NEW to AmbientHarmony.NEW,
        )) {
            val c = AmbientComposer(3).also { it.setScene(scene(f = f)) }
            compose(c, 300.0)
            val used = (0 until 20).mapNotNull { c.chordAt(it * 12.0 + 1)?.chord }.toSet()
            assertTrue(pool.toSet().containsAll(used), "$f：$used")
        }
    }

    @Test
    fun droneNeverStopsAndPianoPlaysTheMotif() {
        val c = AmbientComposer(4).also { it.setScene(scene()) }
        val events = compose(c, 200.0)
        val drones = events.filterIsInstance<AmbDrone>().filter { it.midi == A.DRONE_MIDI }
        // どの時刻でもドローンが鳴っている（立ち上がりのぶんを除く）
        for (t in 10 until 180 step 5) assertTrue(drones.any { it.timeSec <= t && it.timeSec + it.durSec >= t }, "$t 秒にドローンがない")
        val piano = events.filterIsInstance<AmbPiano>()
        assertTrue(piano.isNotEmpty())
        assertTrue(piano.all { it.midi in A.PIANO_MIN_MIDI..A.PIANO_MAX_MIDI })
    }

    @Test
    fun previewCyclesTheScenes() {
        val c = AmbientComposer(1, AmbientEngine.PREVIEW_SCENES)
        compose(c, 600.0)
        val seen = (0 until 600 step 3).mapNotNull { c.chordAt(it.toDouble())?.scene }.toSet()
        assertTrue(seen.containsAll(AmbientEngine.PREVIEW_SCENES.take(5)))
    }

    @Test
    fun deterministicAcrossChunkingAndCopies() {
        val n = 6 * sr
        val whole = ShortArray(n * 2)
        AmbientEngine(9, sounds, sampleRate = sr).render(whole, n, 0)
        val parts = ShortArray(n * 2)
        val e = AmbientEngine(9, sounds, sampleRate = sr)
        var done = 0
        var copy: SoundEngine? = null
        while (done < n) {
            val k = minOf(1700 + done % 500, n - done)
            if (done > n / 2 && copy == null) copy = e.copy()
            (copy ?: e).render(parts, k, done)
            done += k
        }
        assertContentEquals(whole, parts)
    }

    @Test
    fun levelIsReasonableAndDoesNotClip() {
        val n = 40 * sr
        val out = ShortArray(n * 2)
        AmbientEngine.preview(sounds, sr).render(out, n, 0)
        val tail = out.copyOfRange(10 * sr * 2, out.size)
        val clipped = tail.count { abs(it.toInt()) >= 32767 }
        val rms = sqrt(tail.sumOf { (it / 32768.0) * (it / 32768.0) } / tail.size)
        println("AMBIENT RMS ${"%.1f".format(20 * log10(rms))} dB")
        assertTrue(clipped < tail.size / 5000, "音割れ $clipped")
        assertTrue(rms in 0.02..0.3, "音量 $rms")
    }
}
