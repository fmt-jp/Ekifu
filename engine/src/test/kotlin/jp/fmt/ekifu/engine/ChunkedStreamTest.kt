package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.BufferConstants as B
import jp.fmt.ekifu.engine.MusicConstants as C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChunkedStreamTest {
    private val sr = C.SAMPLE_RATE
    private val readFrames = 4096

    private fun newEngine(seed: Int = 1): SoundEngine {
        val journey = DemoJourney.create()
        return SoundEngine(journey, seed)
    }

    /** 止めずに直接合成した、比較用の音。 */
    private fun directRender(seed: Int, totalFrames: Int): ShortArray {
        val engine = newEngine(seed)
        val out = ShortArray(totalFrames * C.CHANNELS)
        engine.render(out, 0, totalFrames)
        return out
    }

    /** 合成と再生を 1 スレッドで交互に回す（合成は必要なときだけ）。 */
    private fun play(
        stream: ChunkedStream,
        totalFrames: Int,
        onRead: (framesRead: Int) -> Unit = {},
    ): ShortArray {
        val out = ShortArray(totalFrames * C.CHANNELS)
        var done = 0
        while (done < totalFrames) {
            while (stream.produceOne()) Unit
            val n = minOf(readFrames, totalFrames - done)
            stream.read(out, done, n)
            done += n
            onRead(done)
        }
        return out
    }

    @Test
    fun engineCopyContinuesIdentically() {
        val a = newEngine(3)
        val warmup = ShortArray(sr * 40 * C.CHANNELS)
        a.render(warmup, 0, sr * 40)
        val b = a.copy()
        val outA = ShortArray(sr * 20 * C.CHANNELS)
        val outB = ShortArray(sr * 20 * C.CHANNELS)
        a.render(outA, 0, sr * 20)
        b.render(outB, 0, sr * 20)
        assertArrayEquals(outA, outB)
    }

    @Test
    fun chunkedPlaybackEqualsDirectRender() {
        val total = sr * 100
        val stream = ChunkedStream(newEngine(7))
        val chunked = play(stream, total)
        assertArrayEquals(directRender(7, total), chunked)
        assertEquals(0, stream.underrunCount)
    }

    @Test
    fun firstChunkIsShortSoPlaybackStartsQuickly() {
        val stream = ChunkedStream(newEngine())
        assertFalse(stream.readyToPlay)
        assertTrue(stream.produceOne())
        assertTrue(stream.readyToPlay)
        assertEquals((B.FIRST_CHUNK_SECONDS * sr).toLong(), stream.bufferedFrames)
    }

    @Test
    fun bufferStaysBetweenLowAndHighWater() {
        val stream = ChunkedStream(newEngine())
        val high = (B.HIGH_WATER_SECONDS * sr).toLong()
        val low = (B.LOW_WATER_SECONDS * sr).toLong()
        var maxBuffered = 0L
        var minBuffered = Long.MAX_VALUE
        play(stream, sr * 150) { done ->
            maxBuffered = maxOf(maxBuffered, stream.bufferedFrames)
            // 立ち上がりの 30 秒は除く
            if (done > sr * 30) minBuffered = minOf(minBuffered, stream.bufferedFrames)
        }
        assertTrue("max ${maxBuffered / sr}s", maxBuffered <= high)
        assertTrue("min ${minBuffered / sr}s", minBuffered >= low - readFrames)
    }

    @Test
    fun resyncRebuildsFromCheckpointWithoutChangingDeterministicDemo() {
        // デモは時刻だけで決まるので、作り直しても同じ音になるはず（チェックポイントの正しさの確認）
        val total = sr * 90
        val stream = ChunkedStream(newEngine(9))
        var requested = false
        val out = play(stream, total) { done ->
            if (!requested && done > sr * 20) {
                requested = true
                val before = stream.bufferedFrames
                stream.requestResync()
                stream.produceOne()
                assertTrue(stream.bufferedFrames <= before)
            }
        }
        assertArrayEquals(directRender(9, total), out)
    }

    @Test
    fun resyncKeepsOnlyTheNearChunks() {
        val stream = ChunkedStream(newEngine())
        while (stream.produceOne()) Unit
        assertTrue(stream.bufferedFrames > (B.LOW_WATER_SECONDS * sr))
        stream.requestResync()
        // 作り直しの適用だけを見るため、合成をいったん止める
        stream.setActive(false)
        stream.produceOne()
        stream.setActive(true)
        stream.produceOne()
        // 再生位置 0 から 10 秒ぶん（2 秒 + 10 秒の 2 チャンク）を残し、1 チャンク作り直した
        val expected = ((B.FIRST_CHUNK_SECONDS + B.CHUNK_SECONDS + B.CHUNK_SECONDS) * sr).toLong()
        assertEquals(expected, stream.bufferedFrames)
    }

    @Test
    fun underrunExtendsThePadInsteadOfSilence() {
        val stream = ChunkedStream(newEngine())
        stream.produceOne() // 最初の 2 秒
        stream.produceOne() // 次の 10 秒
        val out = ShortArray(sr * 14 * C.CHANNELS)
        stream.read(out, 0, sr * 12)
        assertEquals(0, stream.underrunCount)
        // 合成が止まったまま 2 秒読む → つなぎが入る
        stream.read(out, sr * 12, sr * 2)
        assertEquals(1, stream.underrunCount)
        val gap = out.copyOfRange(sr * 12 * C.CHANNELS, sr * 14 * C.CHANNELS)
        val rms = kotlin.math.sqrt(gap.sumOf { (it / 32768.0) * (it / 32768.0) } / gap.size)
        assertTrue("rms $rms", rms > 0.003)
        // つなぎ目で大きな段差がない
        val i = sr * 12 * C.CHANNELS
        assertTrue(abs(out[i] - out[i - 2]) < 2000)
        // 合成が戻れば、つなぎは終わる
        stream.produceOne()
        stream.read(out, 0, readFrames)
        assertEquals(1, stream.underrunCount)
    }

    @Test
    fun streamEndsAfterTheSong() {
        val stream = ChunkedStream(newEngine())
        play(stream, sr * 200)
        assertTrue(stream.ended)
        assertTrue(stream.playheadStatus.finished)
        assertFalse(stream.awaitWork(1))
    }
}
