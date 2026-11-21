package at.cwiesner.android.visualtimer.ext.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6FBF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FA),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF4F5B6E),
    tertiary = Color(0xFFC4700A),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF17457C),
    onPrimaryContainer = Color(0xFFD6E3FA),
    secondary = Color(0xFFB7C3D9),
    tertiary = Color(0xFFF0A94B),
    error = Color(0xFFFFB4AB),
)

/**
 * 应用主题。
 *
 * 刻意保持克制的中性配色：计时器场景下彩色过多会干扰对
 * 圆环/波形这类图形信息素的判读。
 */
@Composable
fun ExtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
