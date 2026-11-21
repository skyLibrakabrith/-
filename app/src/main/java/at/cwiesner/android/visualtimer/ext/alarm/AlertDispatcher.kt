package at.cwiesner.android.visualtimer.ext.alarm

import android.app.Activity
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import kotlinx.coroutines.CoroutineScope

/**
 * 警报统一出口：把一个 [AlertProfile] 展开成具体动作。
 *
 * 分发规则：
 * - `vibrationPattern` 非空 → 震动
 * - `screenFlash` → 屏幕闪烁
 * - `torchFlash` → 手电筒闪烁（默认关，需用户显式开启）
 * - `soundEnabled` → 声音（默认关；用户选择了「震动和闪烁代替声音」）
 *
 * UI 层只调用 [dispatch] / [stopAll]，不直接接触各个 Alerter。
 */
class AlertDispatcher(
    private val activity: Activity,
    private val scope: CoroutineScope,
) {
    private val vibration = VibrationAlerter(activity)
    private val screenFlash = ScreenFlashAlerter(activity)
    private val torch = TorchAlerter(activity)

    val hasVibrator: Boolean get() = vibration.hasVibrator
    val hasTorch: Boolean get() = torch.isAvailable()

    /**
     * 触发一次警报。
     *
     * @param profile 配置；null 时回退到「只震动一次」
     * @param loop 是否循环（计时器到期通常循环，直到用户处理）
     */
    fun dispatch(profile: AlertProfile?, loop: Boolean = true) {
        val p = profile ?: AlertProfile(
            name = "fallback",
            vibrationPattern = listOf(0L, 400L),
            screenFlash = true,
            torchFlash = false,
            soundEnabled = false,
        )

        if (p.vibrationPattern.isNotEmpty()) {
            vibration.play(
                pattern = p.vibrationPattern.toLongArray(),
                repeatIndex = if (loop) p.vibrationRepeatIndex.takeIf { it >= 0 } ?: 0 else -1,
            )
        }
        if (p.screenFlash) {
            screenFlash.start(scope, times = if (loop) 0 else 3)
        }
        if (p.torchFlash) {
            torch.start(scope, times = if (loop) 0 else 3)
        }
        // soundEnabled 默认 false；即使为 true，本版本也不实现音频播放，
        // 与「用震动和屏幕闪烁代替声音」的需求一致。
    }

    fun stopAll() {
        vibration.cancel()
        screenFlash.stop()
        torch.stop()
    }

    /** UI 里试听某个振动波形。 */
    fun previewVibration(pattern: List<Long>) {
        if (pattern.isEmpty()) return
        vibration.preview(pattern.toLongArray())
    }

    /** 播放一段固定闪烁，用于用户预览「屏幕闪烁」效果。 */
    fun previewScreenFlash() {
        screenFlash.start(scope, times = 3)
    }
}
