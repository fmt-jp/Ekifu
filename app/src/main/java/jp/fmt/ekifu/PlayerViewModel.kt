package jp.fmt.ekifu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.fmt.ekifu.audio.DemoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    private val player = DemoPlayer()
    private val _state = MutableStateFlow(player.snapshot())
    val state: StateFlow<DemoPlayer.Snapshot> = _state

    init {
        viewModelScope.launch {
            while (isActive) {
                _state.value = player.snapshot()
                delay(if (player.isRunning) 200 else 500)
            }
        }
    }

    fun play() = player.start()

    fun stop() = player.stop()

    override fun onCleared() {
        player.release()
    }
}
