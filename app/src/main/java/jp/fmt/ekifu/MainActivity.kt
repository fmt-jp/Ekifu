package jp.fmt.ekifu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import jp.fmt.ekifu.ui.EkifuTheme
import jp.fmt.ekifu.ui.PlayerScreen

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EkifuTheme {
                PlayerScreen(viewModel)
            }
        }
    }
}
