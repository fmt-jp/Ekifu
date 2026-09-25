package jp.fmt.ekifu.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import jp.fmt.ekifu.MainActivity

/**
 * 画面オフ・他アプリ使用中も再生を続けるフォアグラウンドサービス。
 * 再生中は Media3 が通知を出してフォアグラウンドにし、止まれば通常のサービスに戻す。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var controller: PlaybackController

    override fun onCreate() {
        super.onCreate()
        controller = PlaybackController.get(this)
        val player = SoundscapePlayer(mainLooper, controller)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Media3 は「メディア再生」の種別だけでフォアグラウンドにする。ルートなしモードでは画面オフ中も
     * 位置を取るため、同じ通知のまま「位置情報」の種別を足す。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        if (startInForegroundRequired && controller.state.value.usesLocation) addLocationType()
    }

    private fun addLocationType() {
        val active = getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID } ?: return
        try {
            ServiceCompat.startForeground(
                this,
                active.id,
                active.notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: RuntimeException) {
            // アプリが裏にある状態（ロック画面からの再開など）では位置の種別を付けられないことがある。
            // そのときは最後に取れた場所のまま鳴らし続ける
            Log.w(TAG, "位置情報のフォアグラウンドサービスにできませんでした", e)
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Ekifu"
    }
}
