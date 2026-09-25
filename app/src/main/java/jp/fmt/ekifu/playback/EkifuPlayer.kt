package jp.fmt.ekifu.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import jp.fmt.ekifu.engine.Phase
import jp.fmt.ekifu.engine.StreamStatus

/**
 * 自前の再生（StreamPlayer）を Media3 の Player として見せる。
 * MediaSession がこれを通して、ロック画面・通知・イヤホンのボタンから操作する。
 * メインスレッドで使う。
 */
@OptIn(UnstableApi::class)
class EkifuPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val handler = Handler(Looper.getMainLooper())
    private val audio = StreamPlayer(context.applicationContext, object : StreamPlayer.Listener {
        override fun onStatus(status: StreamStatus) {
            val sub = subtitleFor(status)
            if (PlaybackBus.hasSubscribers) PlaybackBus.updateStream(status)
            if (sub != postedSubtitle) {
                postedSubtitle = sub
                handler.post { onSubtitleChanged(sub, status) }
            }
        }

        override fun onFinished() {
            handler.post { onStreamFinished() }
        }
    })

    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var ending = false
    private var subtitle = SUBTITLE_DEFAULT
    @Volatile private var postedSubtitle = SUBTITLE_DEFAULT
    private var positionMs = 0L

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(TITLE)
            .setDisplayTitle(TITLE)
            .setArtist(subtitle)
            .setSubtitle(subtitle)
            .build()
        val item = MediaItemData.Builder(MEDIA_ID)
            .setMediaItem(MediaItem.Builder().setMediaId(MEDIA_ID).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .setIsDynamic(true)
            .build()
        val playing = playWhenReady && playbackState == Player.STATE_READY
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_RELEASE,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE,
                    )
                    .build(),
            )
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaylist(listOf(item))
            .setContentPositionMs(PositionSupplier.getExtrapolating(positionMs, if (playing) 1f else 0f))
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!ending) {
            audio.prepare()
            playbackState = Player.STATE_READY
            publish()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (ending) return Futures.immediateVoidFuture() // フェードアウト中は操作しない
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            playbackState = Player.STATE_READY
            audio.play()
        } else {
            audio.pause()
        }
        publish()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        when {
            ending || playbackState == Player.STATE_IDLE -> Unit
            playWhenReady -> {
                // I を鳴らして約12秒でフェードアウト。終わるまで通知は残す
                ending = true
                audio.stop()
                subtitle = SUBTITLE_ENDING
                publish()
            }
            else -> {
                // 一時停止中の停止はすぐに終える
                audio.release()
                resetToIdle()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        audio.release()
        PlaybackBus.update(PlaybackBus.State.IDLE, null)
        return Futures.immediateVoidFuture()
    }

    private fun onSubtitleChanged(sub: String, status: StreamStatus) {
        positionMs = (status.playSec * 1000).toLong()
        if (!ending) subtitle = sub
        invalidateState()
    }

    private fun onStreamFinished() {
        audio.release()
        resetToIdle()
        invalidateState()
    }

    private fun resetToIdle() {
        ending = false
        playWhenReady = false
        playbackState = Player.STATE_IDLE
        subtitle = SUBTITLE_DEFAULT
        postedSubtitle = SUBTITLE_DEFAULT
        positionMs = 0
        PlaybackBus.update(PlaybackBus.State.IDLE, null)
    }

    private fun publish() {
        val state = when {
            playbackState == Player.STATE_IDLE -> PlaybackBus.State.IDLE
            ending -> PlaybackBus.State.ENDING
            playWhenReady -> PlaybackBus.State.PLAYING
            else -> PlaybackBus.State.PAUSED
        }
        PlaybackBus.update(state, audio.status())
    }

    private fun subtitleFor(status: StreamStatus): String {
        if (status.ending) return SUBTITLE_ENDING
        val c = status.engine?.composer ?: return SUBTITLE_DEFAULT
        val place = c.place
        return when {
            place != null && c.phase == Phase.APPROACH -> "${place.name}に近づいています"
            else -> SUBTITLE_DEFAULT
        }
    }

    private companion object {
        const val TITLE = "ekifu"
        const val MEDIA_ID = "ekifu-demo"
        /** 段階3の「おまかせ再生」ができるまではデモ再生 */
        const val SUBTITLE_DEFAULT = "デモ再生中"
        const val SUBTITLE_ENDING = "終わり（フェードアウト中）"
    }
}
