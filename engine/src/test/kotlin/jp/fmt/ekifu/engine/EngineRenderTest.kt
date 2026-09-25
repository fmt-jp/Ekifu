package jp.fmt.ekifu.engine

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** デモ再生を最後まで（停止とフェードアウトまで）オフラインで合成して確かめる */
class EngineRenderTest {

    @Test
    fun demoRendersWithoutGapsOrClippingAndFasterThanRealtime() {
        val engine = MusicEngine.demo()
        val sr = engine.sampleRate
        val block = 2048
        val buf = ShortArray(block * 2)
        val stopAt = 600.0
        val all = ArrayList<ShortArray>()
        val wavPath = System.getenv("EKIFU_WAV")

        val windowFrames = sr * 5
        var windowSum = 0.0
        var windowCount = 0
        val windowRms = ArrayList<Double>()
        var peak = 0
        var hardClipped = 0

        val started = System.nanoTime()
        var lastPhase: Phase? = null
        while (!engine.isFinished) {
            if (engine.elapsedSec >= stopAt) engine.requestStop()
            engine.render(buf, block)
            val st = engine.status
            if (st.composer.phase != lastPhase) {
                lastPhase = st.composer.phase
                println("%6.1f秒 %s（%s）".format(st.elapsedSec, lastPhase!!.label, st.demoCue?.title))
            }
            if (wavPath != null) all += buf.copyOf()
            for (i in 0 until block) {
                val l = buf[i * 2].toInt()
                val r = buf[i * 2 + 1].toInt()
                peak = maxOf(peak, abs(l), abs(r))
                if (abs(l) >= 32700 || abs(r) >= 32700) hardClipped++
                windowSum += (l.toDouble() * l + r.toDouble() * r) / 2
                if (++windowCount == windowFrames) {
                    windowRms += sqrt(windowSum / windowCount) / 32768
                    windowSum = 0.0
                    windowCount = 0
                }
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val audioSec = engine.elapsedSec
        println("合成 ${"%.0f".format(audioSec)}秒ぶん / ${"%.1f".format(seconds)}秒（${"%.0f".format(audioSec / seconds)}倍速）")
        println("ピーク ${peak}、5秒ごとのRMS 最小 ${"%.4f".format(windowRms.dropLast(3).min())} 最大 ${"%.4f".format(windowRms.max())}")

        if (wavPath != null) writeWav(File(wavPath), all, sr)

        assertTrue(audioSec in stopAt..stopAt + MusicConstants.ENDING_FADE_SEC + 1)
        // 始まりから停止まで、どの5秒も無音にならない（フェードアウト部分は除く）
        val playing = windowRms.take((stopAt / 5).toInt())
        assertTrue(playing.all { it > 0.005 }, "音が途切れた区間がある: ${playing.withIndex().filter { it.value <= 0.005 }}")
        // 音割れしない
        assertTrue(hardClipped < sr / 100, "クリップしたサンプル数: $hardClipped")
        // 終わりはフェードアウトしている
        assertTrue(abs(buf[buf.size - 2].toInt()) < 200)
        // スマホでも余裕を持って実時間より速く作れること（JVM で10倍速以上を目安）
        assertTrue(audioSec / seconds > 10, "合成が遅い: ${audioSec / seconds}倍速")
    }

    private fun writeWav(file: File, blocks: List<ShortArray>, sr: Int) {
        val samples = blocks.sumOf { it.size }
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4)
                .putShort(4).putShort(16)
            header.put("data".toByteArray()).putInt(samples * 2)
            f.write(header.array())
            val bb = java.nio.ByteBuffer.allocate(blocks.first().size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (b in blocks) {
                bb.clear()
                bb.asShortBuffer().put(b)
                f.write(bb.array(), 0, b.size * 2)
            }
        }
    }
}
