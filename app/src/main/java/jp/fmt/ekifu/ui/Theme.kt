package jp.fmt.ekifu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 路線図風の配色（SPEC 8章）。 */
@Immutable
data class RouteColors(
    val background: Color,
    val text: Color,
    val line: Color,
    val undergroundBand: Color,
    val current: Color,
)

private val LightRouteColors = RouteColors(
    background = Color(0xFFE4E9E7),
    text = Color(0xFF25323B),
    line = Color(0xFF6F9A8D),
    undergroundBand = Color(0xFFCCD6D8),
    current = Color(0xFFD39A2E),
)

// 仕様にない路線・地下の帯のダーク色は、背景に合わせて暫定で決めている
private val DarkRouteColors = RouteColors(
    background = Color(0xFF1B2328),
    text = Color(0xFFE2E8E5),
    line = Color(0xFF6F9A8D),
    undergroundBand = Color(0xFF2E3A40),
    current = Color(0xFFE3B45A),
)

val LocalRouteColors = staticCompositionLocalOf { LightRouteColors }

@Composable
fun EkifuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkRouteColors else LightRouteColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.line,
            onPrimary = colors.background,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.background,
            onSurface = colors.text,
        )
    } else {
        lightColorScheme(
            primary = colors.line,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.background,
            onSurface = colors.text,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalRouteColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
