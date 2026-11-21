package at.cwiesner.android.visualtimer.ext.data

/**
 * 内置振动波形。
 *
 * 这些只是「关-开」时长数组，不含任何来自第三方应用的名称或文案；
 * UI 层一律用波形条与几何名称呈现。
 */
object VibrationPresets {

    /** 单次短脉冲。 */
    val PULSE = "Pulse" to longArrayOf(0L, 400L)

    /** 两拍一组，间歇较长。 */
    val DOUBLE = "Double" to longArrayOf(0L, 200L, 120L, 200L, 900L, 200L, 120L, 200L)

    /** 密集连续脉冲。 */
    val STACCATO = "Staccato" to longArrayOf(
        0L, 250L, 100L, 500L, 250L,
    )

    /** 渐强式两拍。 */
    val ACCENT = "Accent" to longArrayOf(0L, 50L, 200L, 500L, 250L)

    /** 均匀长间隔脉冲，适合整点提醒。 */
    val HEARTBEAT = "Beat" to longArrayOf(0L, 1000L)

    /** 蜂鸣式高频短脉冲。 */
    val BURST = "Burst" to longArrayOf(
        0L, 100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L,
        100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L,
    )

    /** 快速连击。 */
    val RAPID = "Rapid" to longArrayOf(0L, 120L, 80L, 120L, 80L, 120L, 80L, 120L)

    val all: List<Pair<String, LongArray>> = listOf(
        PULSE, DOUBLE, STACCATO, ACCENT, HEARTBEAT, BURST, RAPID,
    )

    /** 默认警报：只震动，不闪屏、不开手电筒、不出声。 */
    fun defaultProfile(): AlertProfile = AlertProfile(
        name = "Default",
        vibrationPattern = PULSE.second.toList(),
        vibrationRepeatIndex = -1,
        screenFlash = true,
        torchFlash = false,
        soundEnabled = false,
    )
}
