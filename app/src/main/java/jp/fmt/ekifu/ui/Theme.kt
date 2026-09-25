package jp.fmt.ekifu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 9章の配色
private val Background = Color(0xFFE4E9E7)
private val Text = Color(0xFF25323B)
private val Accent = Color(0xFF6F9A8D)
private val Highlight = Color(0xFFD39A2E)
private val DarkBackground = Color(0xFF1B2328)
private val DarkText = Color(0xFFE2E8E5)
private val DarkHighlight = Color(0xFFE3B45A)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    tertiary = Highlight,
    onTertiary = Color.White,
    background = Background,
    onBackground = Text,
    surface = Color(0xFFF1F4F3),
    onSurface = Text,
    surfaceVariant = Color(0xFFD5DDDA),
    onSurfaceVariant = Text.copy(alpha = 0.75f),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    tertiary = DarkHighlight,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkText,
    surface = Color(0xFF243037),
    onSurface = DarkText,
    surfaceVariant = Color(0xFF2E3B43),
    onSurfaceVariant = DarkText.copy(alpha = 0.75f),
)

@Composable
fun EkifuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
