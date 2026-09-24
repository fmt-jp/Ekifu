package jp.fmt.ekifu.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import jp.fmt.ekifu.engine.EngineStatus
import jp.fmt.ekifu.engine.JourneySource
import jp.fmt.ekifu.engine.MusicConstants
import jp.fmt.ekifu.engine.Route
import jp.fmt.ekifu.engine.SoundEngine
import jp.fmt.ekifu.engine.seed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val playing: Boolean = false,
    val status: EngineStatus? = null,
)

/**
 * 段階1の再生器：合成した PCM を専用スレッドから AudioTrack（ストリーミング）へ書き続ける。
 * 画面オンでの再生のみを想定。チャンク先読みとフォアグラウンドサービスは段階2で置き換える。
 */
class DemoPlayer(
    private val route: Route,
    private val journey: JourneySource,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val lock = Object()
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var playing = false
    @Volatile private var running = false

    fun play() {
        synchronized(lock) {
            if (playing) return
            if (worker == null || _state.value.status?.finished == true) startSession()
            playing = true
            track?.play()
            lock.notifyAll()
        }
        _state.value = _state.value.copy(playing = true)
    }

    fun pause() {
        synchronized(lock) {
            playing = false
            track?.pause()
        }
        _state.value = _state.value.copy(playing = false)
    }

    /** 最初から再生し直す。 */
    fun restart() {
        stopSession()
        _state.value = PlayerState()
        play()
    }

    fun release() {
        stopSession()
    }

    private fun startSession() {
        stopSessionLocked()
        val engine = SoundEngine(route, journey, route.seed())
        val newTrack = createTrack()
        track = newTrack
        running = true
        worker = Thread({ renderLoop(engine, newTrack) }, "ekifu-audio").also { it.start() }
    }

    private fun stopSession() {
        val thread: Thread?
        synchronized(lock) {
            thread = worker
            stopSessionLocked()
        }
        thread?.join(JOIN_TIMEOUT_MS)
    }

    private fun stopSessionLocked() {
        running = false
        playing = false
        // stop() で書き込み待ちのスレッドを起こす
        track?.let {
            it.pause()
            it.flush()
            it.stop()
            it.release()
        }
        track = null
        worker = null
        lock.notifyAll()
    }

    private fun renderLoop(engine: SoundEngine, audioTrack: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ShortArray(RENDER_FRAMES * MusicConstants.CHANNELS)
        while (running) {
            synchronized(lock) {
                while (running && !playing) lock.wait()
            }
            if (!running) break
            engine.render(buffer, 0, RENDER_FRAMES)
            var written = 0
            while (running && written < buffer.size) {
                val n = audioTrack.write(buffer, written, buffer.size - written)
                if (n < 0) {
                    running = false
                    break
                }
                written += n
            }
            val status = engine.status
            val finished = status.finished
            _state.value = PlayerState(playing = playing && !finished, status = status)
            if (finished) {
                synchronized(lock) { playing = false }
                break
            }
        }
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
        /** 1 回に合成する長さ（約 93ms）。 */
        const val RENDER_FRAMES = 4096
        const val TRACK_BUFFER_SECONDS = 0.5
        const val JOIN_TIMEOUT_MS = 1000L
    }
}
