package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** 绑定的警报配置 id；到点时按它取振动波形。 */
    val alertProfileId: Long? = null,
    /** 正计时模式：从 0 往上累加，不会到点。 */
    val stopwatch: Boolean = false,
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

    /**
     * 尚未被用户处理的到点计时器。
     *
     * 只要里面还有 id，震动和闪屏就应该**持续**进行 ——
     * 之前只震一下、闪 6 次就停，等于没有提醒作用。
     * 用户重置 / 停止 / 关闭悬浮窗时才会移出。
     */
    private val _alerting = MutableStateFlow<Set<Long>>(emptySet())
    val alerting: StateFlow<Set<Long>> = _alerting.asStateFlow()

    /** 用户已处理某个计时器，停止它的持续提醒。 */
    fun acknowledge(timerId: Long) {
        _alerting.value = _alerting.value - timerId
    }

    fun acknowledgeAll() {
        _alerting.value = emptySet()
    }

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
        acknowledge(timer.id)
        _runtimes.value = _runtimes.value + (
            timer.id to TimerRuntime(
                // 全新开始：超时计数归零
                timerId = timer.id,
                label = timer.label,
                totalMillis = timer.durationMillis,
                remainingMillis = timer.durationMillis,
                running = true,
                deadlineEpochMillis = null,
                alertProfileId = timer.alertProfileId,
            )
            )
    }

    /**
     * 以正计时方式开始：从 0 往上累加。
     *
     * 实现上复用 remainingMillis 字段存「已过时长」，
     * totalMillis 置 0 表示没有终点，tick 里走另一条累加分支。
     */
    fun startStopwatch(timer: TimerItem) {
        _runtimes.value = _runtimes.value + (
            timer.id to TimerRuntime(
                timerId = timer.id,
                label = timer.label,
                totalMillis = 0L,
                remainingMillis = 0L,
                running = true,
                deadlineEpochMillis = null,
                alertProfileId = timer.alertProfileId,
                stopwatch = true,
            )
            )
    }

    /** 以 DDL 方式开始：剩余时长 = 截止时刻 − 现在。 */
    fun startUntil(timer: TimerItem, deadlineEpochMillis: Long, now: Long) {
        acknowledge(timer.id)
        val remaining = (deadlineEpochMillis - now).coerceAtLeast(0L)
        _runtimes.value = _runtimes.value + (
            timer.id to TimerRuntime(
                timerId = timer.id,
                label = timer.label,
                totalMillis = remaining,
                remainingMillis = remaining,
                running = true,
                deadlineEpochMillis = deadlineEpochMillis,
                alertProfileId = timer.alertProfileId,
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
            // 正计时：一直往上加，永不结束
            if (r.stopwatch) {
                changed = true
                next[k] = r.copy(remainingMillis = r.remainingMillis + deltaMillis)
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
                if (justExpired) {
                    _alerting.value = _alerting.value + k
                    _alerts.tryEmit(updated)
                }
            }
        }
        if (changed) _runtimes.value = next
    }

    fun stop(timerId: Long) {
        val r = _runtimes.value[timerId] ?: return
        _runtimes.value = _runtimes.value + (timerId to r.copy(running = false))
    }

    /**
     * 重置到原始时长。
     *
     * 必须同时清掉 [TimerRuntime.overdueMillis] —— 否则「超时后重置」会把
     * 剩余时长还原、却仍然停在超时态，界面继续显示 +MM:SS，看起来像没重置。
     */
    fun reset(timerId: Long) {
        acknowledge(timerId)
        val r = _runtimes.value[timerId] ?: return
        _runtimes.value = _runtimes.value + (
            timerId to r.copy(
                remainingMillis = r.totalMillis,
                overdueMillis = 0L,
                running = false,
            )
            )
    }

    fun clear(timerId: Long) {
        acknowledge(timerId)
        _runtimes.value = _runtimes.value - timerId
    }

    fun clearAll() {
        _alerting.value = emptySet()
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
