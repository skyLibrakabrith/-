package at.cwiesner.android.visualtimer.ext.domain

/**
 * 什么时候该启动「保活前台服务」。
 *
 * ## 背景（真机实测）
 *
 * 把 App 退到后台后 `am kill`：
 * - pid 变为空
 * - `dumpsys alarm` 里 **0 条**属于本应用的闹钟
 * - 等 10 秒也不会自动重启
 *
 * 也就是：**进程被杀后到点不会响**。前台服务能让「用户主动切走」这类场景
 * 不被系统优先回收（Android 的回收顺序里，带前台服务的进程排得靠后）。
 *
 * ## 判据
 *
 * 与 `shouldKeepScreenOn` 同构：用户开关 + 是否有计时在跑 + 序列相位。
 * 抽成纯函数是为了能单测（`MainActivity`/`ExtApplication` 里测不到）。
 */
fun shouldKeepAlive(
    backgroundEnabled: Boolean,
    anyTimerRunning: Boolean,
    sequencePhase: StepSequenceEngine.Phase,
): Boolean {
    if (!backgroundEnabled) return false
    if (anyTimerRunning) return true
    return sequencePhase is StepSequenceEngine.Phase.Running ||
        sequencePhase is StepSequenceEngine.Phase.AwaitingConfirm
}
