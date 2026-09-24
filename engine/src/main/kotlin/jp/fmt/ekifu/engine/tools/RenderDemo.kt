package jp.fmt.ekifu.engine.tools

import jp.fmt.ekifu.engine.DemoJourney
import jp.fmt.ekifu.engine.MusicConstants as C
import jp.fmt.ekifu.engine.SoundEngine
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * デモ再生を WAV に書き出す（PC で音を確認する用）。
 * 使い方: ./gradlew :engine:renderDemoWav   → engine/build/demo.wav
 */
fun main(args: Array<String>) {
    val output = File(args.getOrElse(0) { "demo.wav" })
    val seed = args.getOrNull(1)?.toInt() ?: 1
    val journey = DemoJourney.create()
    val engine = SoundEngine(journey.route, journey, seed)
    val chunkFrames = C.SAMPLE_RATE
    val chunk = ShortArray(chunkFrames * C.CHANNELS)
    val pcm = java.io.ByteArrayOutputStream()
    while (!engine.finished) {
        engine.render(chunk, 0, chunkFrames)
        for (s in chunk) {
            pcm.write(s.toInt() and 0xFF)
            pcm.write((s.toInt() shr 8) and 0xFF)
        }
    }
    writeWav(output, pcm.toByteArray())
    println("wrote ${output.absolutePath} (${"%.1f".format(engine.status.audioElapsedSeconds)} s)")
}

private fun writeWav(file: File, data: ByteArray) {
    DataOutputStream(FileOutputStream(file)).use { out ->
        fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
        fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        val byteRate = C.SAMPLE_RATE * C.CHANNELS * 2
        out.writeBytes("RIFF"); le32(36 + data.size); out.writeBytes("WAVE")
        out.writeBytes("fmt "); le32(16); le16(1); le16(C.CHANNELS); le32(C.SAMPLE_RATE); le32(byteRate)
        le16(C.CHANNELS * 2); le16(16)
        out.writeBytes("data"); le32(data.size); out.write(data)
    }
}
