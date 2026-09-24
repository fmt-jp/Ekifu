package jp.fmt.ekifu

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import jp.fmt.ekifu.playback.PlaybackController
import jp.fmt.ekifu.playback.PlaybackService
import jp.fmt.ekifu.ui.EkifuTheme
import jp.fmt.ekifu.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 許可がなくても再生はできる */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playback = PlaybackController.get(this)
        setContent {
            EkifuTheme {
                PlayerScreen(
                    playback = playback,
                    connected = mediaController != null,
                    onPlayPause = { playing ->
                        mediaController?.let { if (playing) it.pause() else startPlayback(it) }
                    },
                    onRestart = {
                        mediaController?.let {
                            requestNotificationPermission()
                            it.seekToDefaultPosition()
                        }
                    },
                    onModeChange = { playback.setMode(it) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // MediaController でサービスにつなぐ。再生の操作はサービス経由で行い、フォアグラウンドに上げてもらう
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ mediaController = future.get() }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun startPlayback(controller: MediaController) {
        requestNotificationPermission()
        controller.prepare()
        controller.play()
    }

    /** Android 13 以上では、再生中の通知を出すために許可が要る。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
