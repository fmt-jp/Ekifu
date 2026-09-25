package jp.fmt.ekifu.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import jp.fmt.ekifu.MainActivity
import jp.fmt.ekifu.R

/**
 * 再生中はフォアグラウンドサービスになり、画面オフ・他アプリ使用中も鳴らし続ける。
 * 通知・ロック画面・Bluetooth イヤホンのボタンは MediaSession 経由で EkifuPlayer に届く。
 *
 * Media3 は種別 mediaPlayback だけでフォアグラウンドにするので、おまかせ再生で位置の許可があるときは、
 * そのたびに直後で location を付け足す（画面オフ中も「アプリ使用中」の許可で位置を受け取るため）。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: EkifuPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifications: CapturingNotificationProvider

    override fun onCreate() {
        super.onCreate()
        val p = EkifuPlayer(this)
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // 停止したらフォアグラウンドサービスを終える（画面がつながっている間は生き続ける）
                if (playbackState == Player.STATE_IDLE) stopSelf()
            }
        })
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p)
            .setSessionActivity(openApp)
            .build()
        notifications = CapturingNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also {
                it.setSmallIcon(R.drawable.ic_notification)
            },
        )
        setMediaNotificationProvider(notifications)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        // Media3 のフォアグラウンド化はメインスレッドに積まれるので、その後ろに積む
        if (startInForegroundRequired) handler.post { addLocationType() }
    }

    private fun addLocationType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (player?.usesLocation != true || !isPlaybackOngoing) return
        val n = notifications.last ?: return
        try {
            ServiceCompat.startForeground(
                this,
                n.notificationId,
                n.notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // ロック画面から再開したときなど、アプリが裏にいると付けられないことがある。
            // その場合は位置が更新されず、直前の場面が続く
            Log.w(TAG, "location 種別を付けられなかった", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    /** Media3 の通知を作りつつ、最後に作った通知を覚えておく */
    private class CapturingNotificationProvider(
        private val base: MediaNotification.Provider,
    ) : MediaNotification.Provider {
        var last: MediaNotification? = null
            private set

        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            val n = base.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { updated ->
                last = updated
                onNotificationChangedCallback.onNotificationChanged(updated)
            }
            last = n
            return n
        }

        override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
            base.handleCustomCommand(session, action, extras)
    }

    private companion object {
        const val TAG = "ekifu"
    }
}
