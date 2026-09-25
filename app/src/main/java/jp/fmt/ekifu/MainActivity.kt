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
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.fmt.ekifu.data.RouteRepository
import jp.fmt.ekifu.ui.RouteEditScreen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import jp.fmt.ekifu.playback.PlaybackController
import jp.fmt.ekifu.playback.PlaybackMode
import jp.fmt.ekifu.playback.PlaybackService
import jp.fmt.ekifu.ui.EkifuTheme
import jp.fmt.ekifu.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)

    private lateinit var playback: PlaybackController
    /** 許可の確認が終わったら行う操作。 */
    private var afterPermissions: (() -> Unit)? = null

    // 許可がなくても再生はできる（通知が出ない、位置を使わない）ので、結果によらず続ける
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            afterPermissions?.invoke()
            afterPermissions = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playback = PlaybackController.get(this)
        val routes = RouteRepository.get(this)
        setContent {
            EkifuTheme {
                var editing by rememberSaveable { mutableStateOf(false) }
                // 開くたびに保存済みのルートから編集をやり直す（前回の下書きを持ち越さない）
                var editSession by rememberSaveable { mutableIntStateOf(0) }
                val storedRoute by routes.route.collectAsStateWithLifecycle()
                if (editing) {
                    RouteEditScreen(
                        onClose = { editing = false },
                        viewModel = viewModel(key = "route-edit-$editSession"),
                    )
                } else {
                    PlayerScreen(
                        playback = playback,
                        storedRoute = storedRoute,
                        connected = mediaController != null,
                        onPlayPause = { playing ->
                            mediaController?.let { controller ->
                                if (playing) {
                                    controller.pause()
                                } else {
                                    withPermissions {
                                        controller.prepare()
                                        controller.play()
                                    }
                                }
                            }
                        },
                        onRestart = {
                            mediaController?.let { controller -> withPermissions { controller.seekToDefaultPosition() } }
                        },
                        onModeChange = { playback.setMode(it) },
                        onEditRoute = {
                            editSession++
                            editing = true
                        },
                    )
                }
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

    /**
     * 再生の前に必要な許可を求めてから [action] を行う。
     * - 通知（Android 13 以上）：再生中の通知とロック画面の操作のため
     * - 位置情報（ルートなしモードのみ）：アプリ使用中の許可で足りる（再生はボタン操作から始まるため）
     */
    private fun withPermissions(action: () -> Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (playback.state.value.mode == PlaybackMode.WANDER &&
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (needed.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            permissionRequest.launch(needed.toTypedArray())
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
