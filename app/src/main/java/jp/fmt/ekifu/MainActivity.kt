package jp.fmt.ekifu

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import jp.fmt.ekifu.location.SceneSource
import jp.fmt.ekifu.playback.PlaybackBus
import jp.fmt.ekifu.playback.PlaybackMode
import jp.fmt.ekifu.playback.PlaybackService
import jp.fmt.ekifu.ui.EkifuTheme
import jp.fmt.ekifu.ui.PlayerScreen

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    // 位置（アプリ使用中）と通知（Android 13 以上）の許可。許可されなくても再生はする
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller?.play() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EkifuTheme {
                PlayerScreen(
                    onPlay = { start(PlaybackMode.OMAKASE) },
                    onPlayDemo = { start(PlaybackMode.DEMO) },
                    onResume = { controller?.play() },
                    onPause = { controller?.pause() },
                    onStop = { controller?.stop() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun start(mode: PlaybackMode) {
        PlaybackBus.requestedMode = mode
        val missing = buildList {
            // 位置は「おおよそ」だけ許可されていても十分（約1km四方のマスを決めるため）
            if (mode == PlaybackMode.OMAKASE && !SceneSource.hasLocationPermission(this@MainActivity)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) controller?.play() else permissions.launch(missing.toTypedArray())
    }
}
