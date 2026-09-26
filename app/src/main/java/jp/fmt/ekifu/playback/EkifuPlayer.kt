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
import jp.fmt.ekifu.engine.CarryOver
import jp.fmt.ekifu.engine.MusicStyle
import jp.fmt.ekifu.engine.Phase
import jp.fmt.ekifu.engine.SoundEngine
import jp.fmt.ekifu.engine.StreamStatus
import jp.fmt.ekifu.engine.applyPlaceEvents
import jp.fmt.ekifu.engine.StyleEngines
import jp.fmt.ekifu.location.SceneSource
import jp.fmt.ekifu.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 自前の再生（StreamPlayer）を Media3 の Player として見せる。
 * MediaSession がこれを通して、ロック画面・通知・イヤホンのボタンから操作する。
 * メインスレッドで使う。
 */
@OptIn(UnstableApi::class)
class EkifuPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appContext = context.applicationContext
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
    @Volatile private var mode = PlaybackMode.OMAKASE
    /** 鳴らしている曲調（停止中は次の再生で使う曲調） */
    @Volatile private var style = MusicStyle.HEALING
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scenes: SceneSource? = null
    private var subtitle = defaultSubtitle()
    @Volatile private var postedSubtitle = subtitle
    private var positionMs = 0L
    private val interruptions = Interruptions(context) { onInterrupted() }

    init {
        // メディアボタンから画面を開かずに始まることもあるので、ここでも読み込む
        AppSettings.load(appContext)
        style = AppSettings.style.value
        subtitle = defaultSubtitle()
        postedSubtitle = subtitle
        scope.launch { AppSettings.style.drop(1).collect { onStyleChanged(it) } }
    }

    /** 位置を使って再生中か（フォアグラウンドサービスに location 種別を付けるかどうか） */
    val usesLocation: Boolean
        get() = playWhenReady && !ending && scenes?.hasPermission == true

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
            ensurePrepared()
            playbackState = Player.STATE_READY
            publish()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (ending) return Futures.immediateVoidFuture() // フェードアウト中は操作しない
        if (playWhenReady) {
            // 通話中などでオーディオフォーカスが取れなければ鳴らさない
            if (!interruptions.acquire()) return Futures.immediateVoidFuture()
            this.playWhenReady = true
            ensurePrepared()
            playbackState = Player.STATE_READY
            audio.play()
            scenes?.start()
        } else {
            pauseInternal()
        }
        publish()
        return Futures.immediateVoidFuture()
    }

    /** 一時停止中は合成も位置の取得も止める */
    private fun pauseInternal() {
        playWhenReady = false
        audio.pause()
        scenes?.pause()
        interruptions.release()
    }

    /** 着信・他アプリの再生・イヤホン抜け：一時停止（自動では再開しない） */
    private fun onInterrupted() {
        if (ending) {
            // フェードアウト中なら、そのまま終える
            releaseScenes()
            audio.release()
            interruptions.release()
            resetToIdle()
        } else if (playWhenReady) {
            pauseInternal()
            publish()
        }
        invalidateState()
    }

    /** 新しく再生を始めるときに、画面で選ばれた種類の曲を用意する */
    private fun ensurePrepared() {
        if (audio.isPrepared) return
        mode = PlaybackBus.requestedMode
        style = AppSettings.style.value
        subtitle = defaultSubtitle()
        postedSubtitle = subtitle
        when (mode) {
            PlaybackMode.DEMO -> audio.prepare { StyleEngines.demo(style) }
            PlaybackMode.OMAKASE -> {
                val src = SceneSource(
                    appContext,
                    onScene = { scene -> audio.post { it.setScene(scene) } },
                    onPlaceEvents = { events -> audio.post { applyPlaceEvents(events, it) } },
                    onUi = { PlaybackBus.updateScene(it) },
                )
                scenes = src
                audio.prepare {
                    // 位置を待つあいだは仮の場面で始め、最初の位置が取れたらその場所のモチーフをベルで鳴らす
                    StyleEngines.create(
                        style,
                        seed = Random.nextInt(),
                        demo = false,
                        carry = CarryOver(src.initialScene()),
                        playMotifAtStart = !src.hasPermission,
                    )
                }
            }
        }
    }

    /**
     * 曲調の切り替え（12.12）。再生中・一時停止中なら約2秒でフェードアウトして、新しい曲調の「始まり」から作り直す。
     * 場面と登録地点の判定は引き継ぐ（内側の円にいれば到着の演出なしで滞在、接近中なら始まりのあと接近）。
     * デモは台本の続きから。停止中は次の再生から。終わりのフェードアウト中は無視する
     */
    private fun onStyleChanged(newStyle: MusicStyle) {
        if (!audio.isPrepared || ending) return
        if (newStyle == style) return
        val src = scenes
        val demo = mode == PlaybackMode.DEMO
        val switched = audio.switchEngine { timelineSec ->
            createSwitched(newStyle, demo, timelineSec, if (demo) null else src?.carryOver())
        }
        if (!switched) return
        style = newStyle
        subtitle = defaultSubtitle()
        postedSubtitle = subtitle
        publish()
        invalidateState()
    }

    private fun createSwitched(newStyle: MusicStyle, demo: Boolean, timelineSec: Double, carry: CarryOver?): SoundEngine =
        if (demo) {
            StyleEngines.demo(newStyle, timelineSec)
        } else {
            // 場面は引き継ぐので、最初のモチーフはいまの場所のもの
            StyleEngines.create(newStyle, Random.nextInt(), demo = false, carry = carry)
        }

    override fun handleStop(): ListenableFuture<*> {
        when {
            ending || playbackState == Player.STATE_IDLE -> Unit
            playWhenReady -> {
                // I を鳴らして約12秒でフェードアウト。終わるまで通知は残す
                ending = true
                releaseScenes()
                audio.stop()
                subtitle = SUBTITLE_ENDING
                publish()
            }
            else -> {
                // 一時停止中の停止はすぐに終える
                releaseScenes()
                audio.release()
                interruptions.release()
                resetToIdle()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        releaseScenes()
        audio.release()
        interruptions.release()
        PlaybackBus.update(PlaybackBus.State.IDLE, mode, null)
        return Futures.immediateVoidFuture()
    }

    private fun onSubtitleChanged(sub: String, status: StreamStatus) {
        positionMs = (status.playSec * 1000).toLong()
        if (!ending) subtitle = sub
        invalidateState()
    }

    private fun releaseScenes() {
        scenes?.release()
        scenes = null
    }

    private fun onStreamFinished() {
        releaseScenes()
        audio.release()
        interruptions.release()
        resetToIdle()
        invalidateState()
    }

    private fun resetToIdle() {
        ending = false
        playWhenReady = false
        playbackState = Player.STATE_IDLE
        subtitle = defaultSubtitle()
        postedSubtitle = subtitle
        positionMs = 0
        PlaybackBus.update(PlaybackBus.State.IDLE, mode, null)
    }

    private fun publish() {
        val state = when {
            playbackState == Player.STATE_IDLE -> PlaybackBus.State.IDLE
            ending -> PlaybackBus.State.ENDING
            playWhenReady -> PlaybackBus.State.PLAYING
            else -> PlaybackBus.State.PAUSED
        }
        PlaybackBus.update(state, mode, audio.status())
    }

    private fun subtitleFor(status: StreamStatus): String {
        if (status.ending) return SUBTITLE_ENDING
        val c = status.engine?.composer ?: return defaultSubtitle()
        val place = c.place
        return when {
            place != null && c.phase == Phase.APPROACH -> "${place.name}に近づいています"
            else -> defaultSubtitle()
        }
    }

    /** 「おまかせ再生中・癒し」「デモ再生中・フュージョン」など、曲調を添える */
    private fun defaultSubtitle(): String {
        val m = when (mode) {
            PlaybackMode.OMAKASE -> SUBTITLE_OMAKASE
            PlaybackMode.DEMO -> SUBTITLE_DEMO
        }
        return "$m・${style.label}"
    }

    private companion object {
        const val TITLE = "ekifu"
        const val MEDIA_ID = "ekifu"
        const val SUBTITLE_OMAKASE = "おまかせ再生中"
        const val SUBTITLE_DEMO = "デモ再生中"
        const val SUBTITLE_ENDING = "終わり（フェードアウト中）"
    }
}
