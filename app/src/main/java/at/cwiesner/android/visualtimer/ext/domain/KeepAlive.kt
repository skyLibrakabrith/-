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

/**
 * 这个运行时「到点」的墙钟时刻；正计时没有终点，返回 null。
 *
 * 三种计时器语义完全不同：
 * - **DDL**：终点由**绝对时刻**决定（「今晚 8 点前交」），与剩余时间无关
 * - **倒计时**：现在 + 剩余（关机期间也过去了，`remainingMillis` 才准）
 * - **正计时**：永远不到点
 *
 * @param nowMillis 允许注入，便于单测
 */
fun dueAtMillisOf(rt: TimerRuntime, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (rt.stopwatch) return null
    rt.deadlineEpochMillis?.let { return it }
    if (rt.remainingMillis <= 0L) return null
    return nowMillis + rt.remainingMillis
}
