package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerItem

/**
 * 单计时器的运行时状态。
 *
 * 与 [StepSequenceEngine] 的区别：这里只管一个计时器，且支持
 * 「DDL 模式」——到期时刻固定，剩余时长随现在流逝而变化。
 */
data class TimerRuntime(
    val timerId: Long,
    val label: String?,
    val totalMillis: Long,
    val remainingMillis: Long,
    val running: Boolean,
    val deadlineEpochMillis: Long?,
) {
    val progress: Float
        get() = if (totalMillis <= 0L) 0f
        else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    val expired: Boolean get() = remainingMillis <= 0L && totalMillis > 0L
}

/**
 * 面板内多个计时器的并行调度器。
 *
 * 所有计时器共享一个 tick，各自独立倒计时。支持两种启动方式：
 * - 相对时长：start(timer) 以 timer.durationMillis 倒计时
 * - DDL 倒推：startUntil(timer, deadlineEpoch, now) 以固定截止时刻倒计时
 */
/**
 * 纯内存调度器：不持有数据库引用，timer 数据由 UI 层从 Room 读取后传入。
 */
class PanelRunner {
    private val runtimes = mutableMapOf<Long, TimerRuntime>()

    fun snapshot(): List<TimerRuntime> = runtimes.values.sortedBy { it.timerId }

    /** 开始一个计时器（相对时长）。 */
    fun start(timer: TimerItem) {
        runtimes[timer.id] = TimerRuntime(
            timerId = timer.id,
            label = timer.label,
            totalMillis = timer.durationMillis,
            remainingMillis = timer.durationMillis,
            running = true,
            deadlineEpochMillis = null,
        )
    }

    /** 以 DDL 方式开始：剩余时长 = 截止时刻 - 现在。 */
    fun startUntil(timer: TimerItem, deadlineEpochMillis: Long, now: Long) {
        val remaining = (deadlineEpochMillis - now).coerceAtLeast(0L)
        runtimes[timer.id] = TimerRuntime(
            timerId = timer.id,
            label = timer.label,
            totalMillis = remaining,
            remainingMillis = remaining,
            running = true,
            deadlineEpochMillis = deadlineEpochMillis,
        )
    }

    /** 每次 tick 推进所有正在跑的计时器。 */
    fun tick(deltaMillis: Long, now: Long) {
        val keys = runtimes.keys.toList()
        for (k in keys) {
            val r = runtimes[k] ?: continue
            if (!r.running) continue
            val next = if (r.deadlineEpochMillis != null) {
                (r.deadlineEpochMillis - now).coerceAtLeast(0L)
            } else {
                (r.remainingMillis - deltaMillis).coerceAtLeast(0L)
            }
            runtimes[k] = r.copy(
                remainingMillis = next,
                running = next > 0L,   // 到点自动停
            )
        }
    }

    fun stop(timerId: Long) {
        runtimes[timerId]?.let { runtimes[timerId] = it.copy(running = false) }
    }

    fun reset(timerId: Long) {
        runtimes[timerId]?.let {
            runtimes[timerId] = it.copy(
                remainingMillis = it.totalMillis,
                running = false,
            )
        }
    }

    fun clear(timerId: Long) {
        runtimes.remove(timerId)
    }

    fun clearAll() = runtimes.clear()

    /** 已到点、尚未处理的计时器（用于触发警报）。 */
    fun expiredIds(): List<Long> =
        runtimes.values.filter { it.expired }.map { it.timerId }
}
