package jp.fmt.ekifu.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.PowerManager
import android.os.Process
import android.util.Log
import jp.fmt.ekifu.engine.ChunkStream
import jp.fmt.ekifu.engine.ComposerInput
import jp.fmt.ekifu.engine.MusicConstants
import jp.fmt.ekifu.engine.SoundEngine
import jp.fmt.ekifu.engine.StreamStatus

/**
 * 2本のスレッドで再生する。
 * - 合成スレッド：ChunkStream に10秒のチャンクを先回りして作らせる。再生待ちが十分なら休む
 * - 書き込みスレッド：ChunkStream から PCM を取り出して AudioTrack に隙間なく書く
 *
 * 画面オフでも止まらないよう、再生中は CPU のウェイクロックを持つ。
 */
class StreamPlayer(context: Context, private val listener: Listener) {

    interface Listener {
        /** 書き込みスレッドから約0.25秒ごとに呼ばれる */
        fun onStatus(status: StreamStatus)

        /** 停止のフェードアウトが終わった（書き込みスレッドから） */
        fun onFinished()
    }

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ekifu:playback")
        .apply { setReferenceCounted(false) }

    private val pauseLock = Object()
    @Volatile private var stream: ChunkStream? = null
    @Volatile private var paused = true
    @Volatile private var abort = false
    /** 受け入れ基準（再生開始1秒以内）の計測用：play を押した時刻 */
    @Volatile private var playRequestedAtMs = 0L
    private var synthThread: Thread? = null
    private var writerThread: Thread? = null

    val isPrepared: Boolean get() = stream != null

    fun status(): StreamStatus? = stream?.status()

    /** 合成を始める（音はまだ出さない） */
    fun prepare(engine: () -> SoundEngine) {
        if (stream != null) return
        val s = ChunkStream(engine())
        stream = s
        abort = false
        paused = true
        synthThread = Thread({ synthLoop(s) }, "ekifu-synth").apply { start() }
        writerThread = Thread({ writeLoop(s) }, "ekifu-audio").apply { start() }
    }

    /** 作曲への入力（場面など）。次に合成するチャンクから反映される */
    fun post(action: (ComposerInput) -> Unit) {
        stream?.post(action)
    }

    /**
     * 曲調の切り替え：約2秒でフェードアウトして、create で作るエンジンで作り直す。
     * create には切り替える位置でのデモ台本の時刻を渡す。終わりのフェードアウト中は無視して false
     */
    fun switchEngine(create: (timelineSec: Double) -> SoundEngine): Boolean = stream?.switchEngine(create) ?: false

    /** prepare のあとで呼ぶ */
    fun play() {
        if (stream == null) return
        playRequestedAtMs = System.currentTimeMillis()
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
    }

    fun pause() {
        paused = true
        if (wakeLock.isHeld) wakeLock.release()
    }

    /** 停止ボタン：I を鳴らして約12秒でフェードアウト。終わったら Listener.onFinished */
    fun stop() {
        stream?.stop()
    }

    /** すぐに止めてスレッドを片付ける */
    fun release() {
        abort = true
        synchronized(pauseLock) { pauseLock.notifyAll() }
        stream?.post { } // 合成スレッドを起こす
        listOf(synthThread, writerThread).forEach {
            if (it != null && it !== Thread.currentThread()) it.join(2000)
        }
        synthThread = null
        writerThread = null
        stream = null
        paused = true
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun synthLoop(s: ChunkStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        while (!abort) {
            if (!s.work()) s.awaitWork(1000)
        }
    }

    private fun writeLoop(s: ChunkStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sr = MusicConstants.SAMPLE_RATE
        val minBytes = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
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
            .setBufferSizeInBytes(maxOf(minBytes, sr * BYTES_PER_FRAME * TRACK_BUFFER_MS / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val buf = ShortArray(BLOCK_FRAMES * MusicConstants.CHANNELS)
        var trackPlaying = false
        var volume = 0f
        var lastStatusMs = 0L
        var finished = false
        try {
            while (!abort) {
                if (paused) {
                    if (trackPlaying) {
                        fadeOut(track)
                        track.pause()
                        trackPlaying = false
                    }
                    synchronized(pauseLock) { if (paused && !abort) pauseLock.wait(500) }
                    continue
                }
                if (!trackPlaying) {
                    volume = 0f
                    track.setVolume(volume)
                    track.play()
                    trackPlaying = true
                }

                val n = s.read(buf, BLOCK_FRAMES)
                if (n < 0) {
                    finished = true
                    break
                }
                if (n == 0) {
                    Thread.sleep(5) // 最初のチャンク（2秒ぶん）を待つ
                    continue
                }
                var offset = 0
                while (offset < n * 2 && !abort) {
                    val w = track.write(buf, offset, n * 2 - offset)
                    if (w < 0) {
                        Log.e(TAG, "AudioTrack.write failed: $w")
                        return
                    }
                    offset += w
                }
                if (playRequestedAtMs != 0L) {
                    Log.i(TAG, "再生開始まで ${System.currentTimeMillis() - playRequestedAtMs} ms")
                    playRequestedAtMs = 0L
                }
                if (volume < 1f) {
                    volume = minOf(1f, volume + VOLUME_STEP)
                    track.setVolume(volume)
                }

                val now = System.currentTimeMillis()
                if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
                    lastStatusMs = now
                    val st = s.status()
                    if (st.underruns > 0) Log.w(TAG, "バッファ不足 ${st.underruns} 回")
                    listener.onStatus(st)
                }
            }
        } finally {
            if (finished) {
                track.stop() // 書き込み済みの最後まで鳴らす
            } else {
                track.pause()
                track.flush()
            }
            track.release()
        }
        if (finished) listener.onFinished()
    }

    /** 一時停止の前に音量を短く下げて、プツッという音を防ぐ */
    private fun fadeOut(track: AudioTrack) {
        for (i in FADE_STEPS - 1 downTo 0) {
            track.setVolume(i.toFloat() / FADE_STEPS)
            Thread.sleep(FADE_STEP_MS)
        }
    }

    private companion object {
        const val TAG = "ekifu"
        const val BLOCK_FRAMES = 2048
        const val BYTES_PER_FRAME = 4
        const val TRACK_BUFFER_MS = 500
        const val STATUS_INTERVAL_MS = 250L
        const val VOLUME_STEP = 0.2f
        const val FADE_STEPS = 8
        const val FADE_STEP_MS = 20L
        /** 付け忘れ防止の上限（再生を続けるあいだは play() のたびに取り直す） */
        const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
