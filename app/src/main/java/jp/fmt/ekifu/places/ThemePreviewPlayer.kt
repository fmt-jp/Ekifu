package jp.fmt.ekifu.places

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import jp.fmt.ekifu.engine.Mood
import jp.fmt.ekifu.engine.MusicConstants
import jp.fmt.ekifu.engine.ThemePreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 地点のテーマの試聴（数秒の音をまとめて作って鳴らす） */
object ThemePreviewPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var track: AudioTrack? = null

    fun play(themeSeed: Int, mood: Mood) {
        job?.cancel()
        job = scope.launch {
            val pcm = withContext(Dispatchers.Default) { ThemePreview.render(themeSeed, mood) }
            stop()
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(MusicConstants.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
        }
    }

    fun stop() {
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
    }
}
