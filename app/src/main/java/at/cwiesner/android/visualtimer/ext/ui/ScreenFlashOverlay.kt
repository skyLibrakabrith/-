package at.cwiesner.android.visualtimer.ext.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.StateFlow
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
    alerting: StateFlow<Set<Long>>,
    modifier: Modifier = Modifier,
    /** 单次亮起的持续时间（毫秒）。越长越不刺眼。 */
    pulseOnMillis: Int = 220,
    /** 两次闪烁之间的暗场时长（毫秒）。由警报页的「闪烁间隔」控制。 */
    pulseOffMillis: Int = 420,
    /** 自增即触发一次预览（警报页试听用），不依赖是否有到点计时器。 */
    previewTick: Int = 0,
) {
    val alpha = remember { Animatable(0f) }

    // 试听：单独跑三轮就收手。
    //
    // key 只放 previewTick：之前把两个时长也放进 key，拖动滑杆时会反复
    // 重启这个 effect、意外重放三次闪烁，看起来像「调了没反应」。
    // 时长通过 rememberUpdatedState 取当前值。
    val curOn by rememberUpdatedState(pulseOnMillis)
    val curOff by rememberUpdatedState(pulseOffMillis)
    LaunchedEffect(previewTick) {
        if (previewTick <= 0) return@LaunchedEffect
        repeat(3) {
            alpha.animateTo(0.55f, animationSpec = tween(durationMillis = curOn))
            alpha.animateTo(0f, animationSpec = tween(durationMillis = curOff))
        }
    }

    // 只要还有未处理的到点计时器，就一直闪；
    // 用户重置 / 关闭提醒后集合清空，闪烁自然停止。
    // 节奏参数进 key：在警报页调完间隔，回到计时界面立刻按新节奏闪。
    LaunchedEffect(alerting, pulseOnMillis, pulseOffMillis) {
        alerting.collect { ids ->
            if (ids.isEmpty()) {
                alpha.animateTo(0f, animationSpec = tween(durationMillis = 120))
            } else {
                // 与震动节奏大致同步：亮一下、暗一段，循环到用户处理为止。
                // 之前 120ms 亮 / 180ms 灭太快，像在狂闪，视觉上很刺眼。
                while (isActive && alerting.value.isNotEmpty()) {
                    alpha.animateTo(
                        0.55f,
                        animationSpec = tween(durationMillis = pulseOnMillis),
                    )
                    alpha.animateTo(
                        0f,
                        animationSpec = tween(durationMillis = pulseOffMillis),
                    )
                }
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
