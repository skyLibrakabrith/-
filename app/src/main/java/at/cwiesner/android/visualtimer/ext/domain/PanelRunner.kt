package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 单计时器的运行时状态。
 */
data class TimerRuntime(
    val timerId: Long,
    val label: String?,
    val totalMillis: Long,
    val remainingMillis: Long,
    val running: Boolean,
    val deadlineEpochMillis: Long?,
    /**
     * 到点之后已经超时多久（正计时）。
     * 倒计时走完不会停下，而是转入这个字段继续累加，
     * 这样用户能看到「已经超时多久」，而不是时间冻结在 00:00。
     */
    val overdueMillis: Long = 0L,
) {
    val progress: Float
        get() = if (totalMillis <= 0L) 0f
        else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    /** 是否已经到点（进入超时正计时）。 */
    val expired: Boolean get() = overdueMillis > 0L || (remainingMillis <= 0L && totalMillis > 0L)
}

/**
 * 计时器运行时调度器。
 *
 * **生命周期**：由 [at.cwiesner.android.visualtimer.ext.ExtApplication] 持有，
 * 不随界面销毁。这一点很关键 —— 之前它建在 Composable 的 `remember` 里，
 * 一旦切走底部标签，组合被销毁，所有倒计时就被清空了。
 *
 * 内部自带 tick 循环，并把结果暴露成 [runtimes] StateFlow，
 * 界面只负责收集，不负责驱动时间。
 */
class PanelRunner(private val scope: CoroutineScope) {

    private val _runtimes = MutableStateFlow<Map<Long, TimerRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<Long, TimerRuntime>> = _runtimes.asStateFlow()

    /**
     * 到点事件流：每个计时器**每次**到点只发一次。
     * 界面和常驻服务都订阅它来触发震动 / 闪烁 / 通知，
     * 这样即使没有界面，倒计时到点也能提醒。
     */
    private val _alerts = kotlinx.coroutines.flow.MutableSharedFlow<TimerRuntime>(
        extraBufferCapacity = 16,
    )
    val alerts: kotlinx.coroutines.flow.SharedFlow<TimerRuntime> = _alerts.asSharedFlow()

    private var tickJob: Job? = null

    /** 启动内部 tick 循环。重复调用无副作用。 */
    fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(TICK_MILLIS)
                val now = System.currentTimeMillis()
                tick(now - last, now)
                last = now
            }
        }
    }

    fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    fun snapshot(): List<TimerRuntime> =
        _runtimes.value.values.sortedBy { it.timerId }

    fun get(timerId: Long): TimerRuntime? = _runtimes.value[timerId]

    /** 开始一个计时器（相对时长）。 */
    fun start(timer: TimerItem) {
        _runtimes.value = _runtimes.value + (
            timer.id to TimerRuntime(
                timerId = timer.id,
                label = timer.label,
                totalMillis = timer.durationMillis,
                remainingMillis = timer.durationMillis,
                running = true,
                deadlineEpochMillis = null,
            )
            )
    }

    /** 以 DDL 方式开始：剩余时长 = 截止时刻 − 现在。 */
    fun startUntil(timer: TimerItem, deadlineEpochMillis: Long, now: Long) {
        val remaining = (deadlineEpochMillis - now).coerceAtLeast(0L)
        _runtimes.value = _runtimes.value + (
            timer.id to TimerRuntime(
                timerId = timer.id,
                label = timer.label,
                totalMillis = remaining,
                remainingMillis = remaining,
                running = true,
                deadlineEpochMillis = deadlineEpochMillis,
            )
            )
    }

    /** 每次 tick 推进所有正在跑的计时器。 */
    fun tick(deltaMillis: Long, now: Long) {
        val current = _runtimes.value
        if (current.isEmpty()) return
        var changed = false
        val next = HashMap<Long, TimerRuntime>(current.size)
        for ((k, r) in current) {
            if (!r.running) {
                next[k] = r
                continue
            }
            val remaining = if (r.deadlineEpochMillis != null) {
                (r.deadlineEpochMillis - now).coerceAtLeast(0L)
            } else {
                (r.remainingMillis - deltaMillis).coerceAtLeast(0L)
            }

            if (remaining > 0L) {
                if (remaining != r.remainingMillis) {
                    changed = true
                    next[k] = r.copy(remainingMillis = remaining, overdueMillis = 0L)
                } else {
                    next[k] = r
                }
            } else {
                // 到点：剩余归零，转入正计时，保持 running 以便继续累加
                val justExpired = r.overdueMillis == 0L
                changed = true
                val updated = r.copy(
                    remainingMillis = 0L,
                    overdueMillis = r.overdueMillis + deltaMillis,
                )
                next[k] = updated
                if (justExpired) _alerts.tryEmit(updated)
            }
        }
        if (changed) _runtimes.value = next
    }

    fun stop(timerId: Long) {
        val r = _runtimes.value[timerId] ?: return
        _runtimes.value = _runtimes.value + (timerId to r.copy(running = false))
    }

    fun reset(timerId: Long) {
        val r = _runtimes.value[timerId] ?: return
        _runtimes.value = _runtimes.value + (
            timerId to r.copy(remainingMillis = r.totalMillis, running = false)
            )
    }

    fun clear(timerId: Long) {
        _runtimes.value = _runtimes.value - timerId
    }

    fun clearAll() {
        _runtimes.value = emptyMap()
    }

    /** 到点但还没被用户处理的计时器。 */
    fun expiredIds(): List<Long> =
        _runtimes.value.values.filter { it.expired }.map { it.timerId }

    companion object {
        /** 刷新粒度。200ms 足够顺滑，也不会过分耗电。 */
        const val TICK_MILLIS: Long = 200L
    }
}
