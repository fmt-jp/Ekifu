package jp.fmt.ekifu.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import jp.fmt.ekifu.engine.ChunkedStream
import jp.fmt.ekifu.engine.EngineStatus
import jp.fmt.ekifu.engine.JourneySource
import jp.fmt.ekifu.engine.MusicConstants
import jp.fmt.ekifu.engine.Route
import jp.fmt.ekifu.engine.SoundEngine
import jp.fmt.ekifu.engine.seed

/** 再生側から見た状態（画面・通知・デバッグ表示用）。 */
data class PipelineSnapshot(
    val status: EngineStatus,
    val bufferedSeconds: Double,
    val underruns: Int,
    val ended: Boolean,
)

/**
 * チャンク生成（合成スレッド）と AudioTrack への書き込み（再生スレッド）をつなぐ。
 * 合成はバッファが 30 秒を切ったときだけ動き、それ以外と一時停止中は休む。
 */
class AudioPipeline(
    route: Route,
    journey: JourneySource,
    private val onUpdate: (PipelineSnapshot) -> Unit,
) {
    private val stream = ChunkedStream(SoundEngine(route, journey, route.seed()))
    private val track = createTrack()
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var playing = false
    private var reportedUnderruns = 0

    private val producer = Thread({ produceLoop() }, "ekifu-synth")
    private val writer = Thread({ writeLoop() }, "ekifu-audio")

    init {
        producer.start()
        writer.start()
    }

    fun play() {
        synchronized(lock) {
            playing = true
            stream.setActive(true)
            track.play()
            lock.notifyAll()
        }
    }

    fun pause() {
        synchronized(lock) {
            playing = false
            stream.setActive(false)
            track.pause()
        }
    }

    /** 旅程の状態が大きく変わったとき、先読みぶんを作り直す。 */
    fun requestResync() = stream.requestResync()

    fun release() {
        synchronized(lock) {
            running = false
            playing = false
            lock.notifyAll()
        }
        stream.wakeUp()
        // stop() で書き込み待ちのスレッドを起こしてから待つ
        track.pause()
        track.flush()
        track.stop()
        producer.join(JOIN_TIMEOUT_MS)
        writer.join(JOIN_TIMEOUT_MS)
        track.release()
    }

    private fun produceLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        while (running) {
            if (stream.awaitWork(IDLE_WAIT_MS)) stream.produceOne()
        }
    }

    private fun writeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ShortArray(WRITE_FRAMES * MusicConstants.CHANNELS)
        while (running) {
            synchronized(lock) {
                // 最初のチャンク（2 秒）ができるまでは待つ。これは音切れに数えない
                while (running && (!playing || !stream.readyToPlay)) lock.wait(START_POLL_MS)
            }
            if (!running) break
            stream.read(buffer, 0, WRITE_FRAMES)
            var written = 0
            while (running && written < buffer.size) {
                val n = track.write(buffer, written, buffer.size - written)
                if (n < 0) break
                written += n
            }
            publish()
            if (stream.ended) {
                synchronized(lock) { playing = false }
                break
            }
        }
    }

    private fun publish() {
        val underruns = stream.underrunCount
        if (underruns != reportedUnderruns) {
            reportedUnderruns = underruns
            Log.w(TAG, "バッファ不足（つなぎを挿入）: 累計 $underruns 回")
        }
        onUpdate(
            PipelineSnapshot(
                status = stream.playheadStatus,
                bufferedSeconds = stream.bufferedFrames.toDouble() / MusicConstants.SAMPLE_RATE,
                underruns = underruns,
                ended = stream.ended,
            ),
        )
    }

    private fun createTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(MusicConstants.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            MusicConstants.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val targetBytes = (TRACK_BUFFER_SECONDS * MusicConstants.SAMPLE_RATE).toInt() * MusicConstants.CHANNELS * 2
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, targetBytes))
            .build()
    }

    private companion object {
        const val TAG = "Ekifu"
        /** AudioTrack へ 1 回に書く長さ（約 93ms）。 */
        const val WRITE_FRAMES = 4096
        const val TRACK_BUFFER_SECONDS = 0.5
        const val IDLE_WAIT_MS = 1000L
        const val START_POLL_MS = 10L
        const val JOIN_TIMEOUT_MS = 2000L
    }
}
