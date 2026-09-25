package jp.fmt.ekifu.engine

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.MusicConstants as C

class ChunkStreamTest {

    private val sr = C.SAMPLE_RATE
    private val block = 2048

    /** 合成が常に間に合う条件で seconds 秒ぶん読み出す */
    private fun pump(stream: ChunkStream, seconds: Double, check: (StreamStatus) -> Unit = {}): ShortArray {
        val frames = (seconds * sr).toInt() / block * block
        val out = ShortArray(frames * 2)
        val buf = ShortArray(block * 2)
        var done = 0
        while (done < frames) {
            while (stream.needsWork()) stream.work()
            val n = stream.read(buf, block)
            assertEquals(block, n)
            System.arraycopy(buf, 0, out, done * 2, block * 2)
            done += block
            check(stream.status())
        }
        return out
    }

    private fun direct(engine: MusicEngine, seconds: Double): ShortArray {
        val frames = (seconds * sr).toInt() / block * block
        return ShortArray(frames * 2).also { engine.render(it, frames) }
    }

    private fun rms(a: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / (to - from)) / 32768
    }

    @Test
    fun copiedEngineContinuesIdentically() {
        val a = MusicEngine.demo()
        direct(a, 37.0)
        val b = a.copy()
        assertContentEquals(direct(a, 40.0), direct(b, 40.0))
    }

    @Test
    fun chunkedOutputEqualsDirectRenderWithoutUnderruns() {
        val stream = ChunkStream(MusicEngine.demo())
        var maxBuffered = 0.0
        val chunked = pump(stream, 150.0) { maxBuffered = maxOf(maxBuffered, it.bufferedSec) }
        assertContentEquals(direct(MusicEngine.demo(), 150.0), chunked)
        assertEquals(0, stream.status().underruns)
        assertTrue(maxBuffered <= C.BUFFER_HIGH_SEC, "再生待ちが多すぎる: $maxBuffered")
    }

    @Test
    fun bufferStaysBetweenLowAndHigh() {
        val stream = ChunkStream(MusicEngine.demo())
        pump(stream, 5.0)
        pump(stream, 200.0) {
            assertTrue(it.bufferedSec >= C.BUFFER_LOW_SEC - 1, "再生待ちが少ない: ${it.bufferedSec}")
            assertTrue(it.bufferedSec <= C.BUFFER_HIGH_SEC, "再生待ちが多い: ${it.bufferedSec}")
        }
    }

    @Test
    fun firstChunkIsShortSoPlaybackStartsQuickly() {
        val stream = ChunkStream(MusicEngine.demo())
        val buf = ShortArray(block * 2)
        assertEquals(0, stream.read(buf, block), "最初のチャンクができるまでは何も返さない")
        assertTrue(stream.work())
        assertEquals(C.FIRST_CHUNK_SEC, stream.status().bufferedSec, 1e-3)
        assertEquals(block, stream.read(buf, block))
    }

    @Test
    fun inputRebuildsFromNextChunkAfterMargin() {
        val stream = ChunkStream(MusicEngine.demo())
        val before = pump(stream, 100.0)
        val readFrame = before.size / 2
        val newScene = Scene("rewind", Speed.VEHICLE, Familiarity.NEW, SunLevel.NIGHT)
        stream.post { it.setScene(newScene) }
        val after = pump(stream, 60.0)
        val played = before + after

        // チャンクの頭は 2秒、12秒、22秒…。再生位置 + 1秒 以降の最初の頭から作り直される
        val first = sec(C.FIRST_CHUNK_SEC)
        val chunk = sec(C.CHUNK_SEC)
        var cut = first
        while (cut < readFrame + sec(C.REWIND_MARGIN_SEC)) cut += chunk
        assertTrue((cut - readFrame).toDouble() / sr <= C.REWIND_MARGIN_SEC + C.CHUNK_SEC)

        // 期待値：cut の位置で入力を入れて、そのまま続けたもの
        val ref = MusicEngine.demo()
        val refOut = ShortArray(played.size)
        ref.render(refOut, cut)
        ref.apply { it.setScene(newScene) }
        ref.render(refOut, played.size / 2 - cut, cut)
        assertContentEquals(refOut, played)

        // 入力がなければ違う曲になっていた（変化が反映されている）
        val baseline = direct(MusicEngine.demo(), played.size / 2.0 / sr)
        assertFalse(baseline.contentEquals(played))
        // cut より前は同じ
        assertContentEquals(baseline.copyOfRange(0, cut * 2), played.copyOfRange(0, cut * 2))
    }

    @Test
    fun underrunIsFilledWithPadAndCounted() {
        val stream = ChunkStream(MusicEngine.demo())
        stream.work() // 2秒ぶんだけ
        val buf = ShortArray(block * 2)
        val out = ArrayList<Short>()
        repeat((4.0 * sr / block).toInt()) {
            assertEquals(block, stream.read(buf, block))
            out += buf.toList()
        }
        val a = out.toShortArray()
        assertEquals(1, stream.status().underruns)
        // 2秒を過ぎても無音にならない（パッドでつないでいる）
        assertTrue(rms(a, sec(3.0) * 2, a.size) > 0.003, "つなぎが無音: ${rms(a, sec(3.0) * 2, a.size)}")
        // 再生位置は止まったまま（曲を飛ばさない）
        assertEquals(C.FIRST_CHUNK_SEC, stream.status().playSec, 0.05)

        // 合成が追いつけば元に戻り、回数は増えない
        pump(stream, 30.0)
        assertEquals(1, stream.status().underruns)
    }

    @Test
    fun stopPlaysIAndFadesOutInAboutTwelveSeconds() {
        val stream = ChunkStream(MusicEngine.demo())
        pump(stream, 30.0)
        stream.stop()
        stream.post { it.setScene(Scene.DEFAULT) } // 停止後の入力は無視
        assertFalse(stream.work())
        assertEquals(Phase.ENDING, stream.status().engine!!.composer.phase)

        val buf = ShortArray(block * 2)
        val out = ArrayList<Short>()
        while (true) {
            val n = stream.read(buf, block)
            if (n < 0) break
            out += buf.toList()
        }
        val a = out.toShortArray()
        val seconds = a.size / 2.0 / sr
        assertEquals(C.ENDING_FADE_SEC, seconds, 0.1)
        val head = rms(a, 0, sec(2.0) * 2)
        val tail = rms(a, a.size - sec(0.5) * 2, a.size)
        assertTrue(head > 0.01, "停止直後に音が消えている: $head")
        // 直線のフェードなので最後の0.5秒は元の数%以下
        assertTrue(tail < head * 0.05, "最後まで音が残っている: $tail / $head")
        assertTrue(kotlin.math.abs(a.last().toInt()) < 100)
        assertTrue(stream.status().finished)
    }

    @Test
    fun thirtyMinutesWithoutUnderrun() {
        val stream = ChunkStream(MusicEngine.demo())
        var maxBuffered = 0.0
        pump(stream, 30 * 60.0) { maxBuffered = maxOf(maxBuffered, it.bufferedSec) }
        assertEquals(0, stream.status().underruns)
        assertTrue(maxBuffered <= C.BUFFER_HIGH_SEC)
    }

    private fun sec(s: Double) = (s * sr).toInt()
}
