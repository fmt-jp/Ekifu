package jp.fmt.ekifu.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import jp.fmt.ekifu.engine.EngineStatus
import jp.fmt.ekifu.engine.MusicConstants
import jp.fmt.ekifu.engine.MusicEngine

/**
 * デモ再生（段階1：画面オンのみ）。
 * 専用スレッドでエンジンに PCM を作らせ、AudioTrack（ストリーミング）に隙間なく書き込む。
 * チャンク生成とバッファ、フォアグラウンドサービスは段階2で作る。
 */
class DemoPlayer {

    data class Snapshot(
        val running: Boolean,
        val status: EngineStatus?,
        val underruns: Int,
    )

    @Volatile private var engine: MusicEngine? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var abort = false
    @Volatile private var underruns = 0

    val isRunning: Boolean get() = thread?.isAlive == true

    fun snapshot() = Snapshot(isRunning, engine?.status, underruns)

    fun start() {
        if (isRunning) return
        val e = MusicEngine.demo()
        engine = e
        abort = false
        underruns = 0
        thread = Thread({ playLoop(e) }, "ekifu-audio").apply { start() }
    }

    /** 停止ボタン：I を鳴らして約12秒でフェードアウトしてから止まる */
    fun stop() {
        engine?.requestStop()
    }

    /** 画面を閉じたときなど：すぐに止める */
    fun release() {
        abort = true
        thread?.join(1000)
        thread = null
    }

    private fun playLoop(e: MusicEngine) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sr = MusicConstants.SAMPLE_RATE
        val minBytes = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, sr * BYTES_PER_FRAME * BUFFER_MS / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val buf = ShortArray(BLOCK_FRAMES * MusicConstants.CHANNELS)
        try {
            track.play()
            while (!abort && !e.isFinished) {
                e.render(buf, BLOCK_FRAMES)
                var offset = 0
                while (offset < buf.size && !abort) {
                    val n = track.write(buf, offset, buf.size - offset)
                    if (n < 0) return
                    offset += n
                }
                underruns = track.underrunCount
            }
        } finally {
            if (abort) {
                track.pause()
                track.flush()
            } else {
                // 書き込み済みの最後まで鳴らしてから止める
                track.stop()
            }
            track.release()
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 2048
        const val BYTES_PER_FRAME = 4
        const val BUFFER_MS = 500
    }
}
