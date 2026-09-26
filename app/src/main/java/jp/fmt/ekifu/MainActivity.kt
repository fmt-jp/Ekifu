package jp.fmt.ekifu

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import jp.fmt.ekifu.location.SceneSource
import jp.fmt.ekifu.places.PlaceEditScreen
import jp.fmt.ekifu.places.PlaceListScreen
import jp.fmt.ekifu.places.PlacesViewModel
import jp.fmt.ekifu.settings.AppSettings
import jp.fmt.ekifu.settings.SettingsScreen
import jp.fmt.ekifu.playback.PlaybackBus
import jp.fmt.ekifu.playback.PlaybackMode
import jp.fmt.ekifu.playback.PlaybackService
import jp.fmt.ekifu.ui.EkifuTheme
import jp.fmt.ekifu.ui.PlayerScreen

class MainActivity : ComponentActivity() {

    private val placesViewModel: PlacesViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    // 位置（アプリ使用中）と通知（Android 13 以上）の許可。許可されなくても再生はする
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller?.play() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        setContent {
            EkifuTheme {
                var screen by rememberSaveable { mutableStateOf<String?>(null) }
                val places by placesViewModel.places.collectAsStateWithLifecycle()
                when (val s = screen) {
                    null -> PlayerScreen(
                        onPlay = { start(PlaybackMode.OMAKASE) },
                        onPlayDemo = { start(PlaybackMode.DEMO) },
                        onResume = { controller?.play() },
                        onPause = { controller?.pause() },
                        onStop = { controller?.stop() },
                        onOpenPlaces = { screen = SCREEN_PLACES },
                        onOpenSettings = { screen = SCREEN_SETTINGS },
                    )
                    SCREEN_SETTINGS -> SettingsScreen(onBack = { screen = null })
                    SCREEN_PLACES -> PlaceListScreen(
                        places = places,
                        onBack = { screen = null },
                        onAdd = { screen = SCREEN_NEW_PLACE },
                        onEdit = { screen = it.id },
                    )
                    else -> {
                        // SCREEN_NEW_PLACE か、編集する地点の id
                        val existing = places.firstOrNull { it.id == s }
                        // 編集する地点の読み込みを待ってから入力欄を作る
                        if (s != SCREEN_NEW_PLACE && existing == null) return@EkifuTheme
                        key(s) { PlaceEditScreen(
                            existing = existing,
                            onBack = { screen = SCREEN_PLACES },
                            onSave = {
                                placesViewModel.save(it)
                                screen = SCREEN_PLACES
                            },
                            onDelete = {
                                placesViewModel.delete(it)
                                screen = SCREEN_PLACES
                            },
                        ) }
                    }
                }
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

    private companion object {
        const val SCREEN_PLACES = "places"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_NEW_PLACE = "new-place"
    }
}
