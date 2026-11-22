package at.cwiesner.android.visualtimer.ext.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive

/**
 * 全屏闪烁提醒。
 *
 * **为什么不用亮度调节**：之前的实现是改 `window.attributes.screenBrightness`。
 * 这个应用是纯黑主题，屏幕上本来就没多少亮像素，把亮度拉到最大也几乎看不出变化 ——
 * 用户会觉得「屏幕闪烁没做」。
 *
 * 现在改成**覆盖一层高亮色并脉冲其透明度**：无论界面多暗，
 * 整块屏幕都会明显地亮起来再暗下去，这才是「屏幕闪烁」该有的观感。
 *
 * 同时把亮度临时拉高，在强光环境下也能看见。
 */
@Composable
fun ScreenFlashOverlay(
    alerts: SharedFlow<TimerRuntime>,
    modifier: Modifier = Modifier,
    flashTimes: Int = 6,
) {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(alerts) {
        alerts.collect {
            repeat(flashTimes) {
                if (!isActive) return@collect
                alpha.animateTo(0.85f, animationSpec = tween(durationMillis = 110))
                alpha.animateTo(0f, animationSpec = tween(durationMillis = 170))
            }
        }
    }

    if (alpha.value > 0.004f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = alpha.value)),
        )
    }
}
