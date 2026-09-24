package jp.fmt.ekifu.playback

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

/**
 * 自前の合成エンジンを Media3 の Player として見せる。
 * これを MediaSession に渡すことで、通知・ロック画面・イヤホンのボタンから操作できる。
 */
@OptIn(UnstableApi::class)
class SoundscapePlayer(
    looper: Looper,
    private val controller: PlaybackController,
) : SimpleBasePlayer(looper) {

    private val handler = Handler(looper)
    private val onControllerChanged: () -> Unit = { handler.post { invalidateState() } }

    init {
        controller.addListener(onControllerChanged)
    }

    override fun getState(): SimpleBasePlayer.State {
        val s = controller.state.value
        val plan = s.plan
        val builder = SimpleBasePlayer.State.Builder().setAvailableCommands(COMMANDS)
        // 再生できるルートがないときは空のプレイリスト（通知にも出さない）
        if (plan == null) return builder.setPlaybackState(Player.STATE_IDLE).build()

        val stations = plan.route.stations
        val metadata = MediaMetadata.Builder()
            .setTitle(TITLE)
            .setArtist("${stations.first().name} → ${stations.last().name}")
            .build()
        val item = SimpleBasePlayer.MediaItemData.Builder(MEDIA_ID)
            .setMediaItem(MediaItem.Builder().setMediaId(MEDIA_ID).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .build()
        val playbackState = when {
            s.ended -> Player.STATE_ENDED
            s.prepared -> Player.STATE_READY
            else -> Player.STATE_IDLE
        }
        val positionMs = ((s.status?.audioElapsedSeconds ?: 0.0) * 1000).toLong()
        return builder
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaylist(listOf(item))
            .setContentPositionMs(positionMs)
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        controller.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    /** 「最初から」。 */
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        controller.restart()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        controller.removeListener(onControllerChanged)
        controller.stop()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val TITLE = "通勤のサウンドスケープ"
        const val MEDIA_ID = "commute"
        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
