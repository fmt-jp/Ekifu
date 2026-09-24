package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SoundEngineTest {

    private fun renderDemo(seed: Int, chunkFrames: Int): ShortArray {
        val journey = DemoJourney.create()
        val engine = SoundEngine(journey.route, journey, seed)
        val out = ArrayList<ShortArray>()
        val chunk = ShortArray(chunkFrames * C.CHANNELS)
        while (!engine.finished) {
            engine.render(chunk, 0, chunkFrames)
            out += chunk.copyOf()
        }
        return ShortArray(out.sumOf { it.size }).also { all ->
            var i = 0
            for (c in out) { c.copyInto(all, i); i += c.size }
        }
    }

    @Test
    fun demoPlaysToTheEndWithoutGaps() {
        val pcm = renderDemo(seed = 1, chunkFrames = 4096)
        val seconds = pcm.size / C.CHANNELS / C.SAMPLE_RATE.toDouble()
        // 3 分 + 到着の和音待ち + フェードアウト 12 秒
        assertTrue("length $seconds", seconds in 190.0..205.0)

        // フェードアウト前は、どの 1 秒を取っても無音にならない（途切れない）
        val secondSamples = C.SAMPLE_RATE * C.CHANNELS
        val playedSeconds = (seconds - C.ARRIVAL_FADE_SECONDS - 1).toInt()
        for (s in 0 until playedSeconds) {
            val rms = rms(pcm, s * secondSamples, secondSamples)
            assertTrue("second $s rms $rms", rms > 0.003)
        }
        // 最後は無音で終わる
        assertTrue(rms(pcm, pcm.size - secondSamples / 10, secondSamples / 10) < 1e-4)
    }

    @Test
    fun chunkSizeDoesNotChangeTheSound() {
        val a = renderDemo(seed = 5, chunkFrames = 4096)
        val b = renderDemo(seed = 5, chunkFrames = 777)
        val n = minOf(a.size, b.size)
        for (i in 0 until n) assertEquals("sample $i", a[i], b[i])
    }

    @Test
    fun undergroundSectionIsDarker() {
        val pcm = renderDemo(seed = 2, chunkFrames = 4096)
        fun brightness(fromSec: Double, toSec: Double): Double {
            val from = (fromSec * C.SAMPLE_RATE).toInt() * C.CHANNELS
            val to = (toSec * C.SAMPLE_RATE).toInt() * C.CHANNELS
            var diff = 0.0
            var level = 0.0
            var i = from + C.CHANNELS
            while (i < to) {
                diff += abs(pcm[i] - pcm[i - C.CHANNELS].toDouble())
                level += abs(pcm[i].toDouble())
                i += C.CHANNELS
            }
            return diff / level
        }
        // 地下は 27%〜72%（48.6〜129.6 秒）。切り替えの時定数を避けて比べる
        val above = brightness(20.0, 45.0)
        val below = brightness(70.0, 120.0)
        assertTrue("above $above below $below", below < above * 0.6)
    }

    private fun rms(pcm: ShortArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (i in from until from + count) {
            val v = pcm[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count)
    }
}
