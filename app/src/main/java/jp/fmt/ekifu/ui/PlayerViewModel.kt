package jp.fmt.ekifu.ui

import androidx.lifecycle.ViewModel
import jp.fmt.ekifu.audio.DemoPlayer
import jp.fmt.ekifu.engine.DemoJourney

class PlayerViewModel : ViewModel() {
    val demo: DemoJourney = DemoJourney.create()
    val player = DemoPlayer(demo.route, demo)

    override fun onCleared() {
        player.release()
    }
}
