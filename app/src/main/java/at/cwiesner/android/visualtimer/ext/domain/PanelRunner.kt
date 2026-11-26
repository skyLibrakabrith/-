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
    /** 该计时器已触发过的提前提醒（分钟），避免重复提醒。 */
    val firedLeads: Set<Long> = emptySet(),
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

    /** 是否已经到过点。暂停后仍为 true —— 这是历史事实。 */
    val expired: Boolean
        get() = overdueMillis > 0L || (remainingMillis <= 0L && totalMillis > 0L)

    /**
     * 是否正处在超时的「运行」状态。
     *
     * 与 [expired] 的区别：暂停后 `expired` 仍为 true，但 `isOverdue` 为 false。
     * 界面与点击逻辑必须用这个来判断「现在是否在超时」，
     * 否则一暂停就永远是重置，暂停按钮形同虚设。
     */
    val isOverdue: Boolean get() = expired && running
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
    /** 提前提醒事件：计时器 + 触发的提前分钟数。 */
    private val _leadAlerts =
        kotlinx.coroutines.flow.MutableSharedFlow<Pair<TimerRuntime, Long>>(
            extraBufferCapacity = 16,
        )
    val leadAlerts: kotlinx.coroutines.flow.SharedFlow<Pair<TimerRuntime, Long>> =
        _leadAlerts.asSharedFlow()

    /**
     * 提前提醒事件的附带信息：这次是哪条 `deadline_reminders` 行触发的。
     *
     * `_leadAlerts` 的载荷只有 `(runtime, leadMinutes)`，**没有行 id** ——
     * 而要把 `fired` 列写回库里 `避免重启后再响一遍`，就必须知道是哪一行。
     * 所以按「计时器 id → (提醒分钟 → 行 id)」再存一份映射。
     * 行 id 只可能来自 [setLeadMinutes] 的调用方（它握住 DAO），domain 层不碰数据库。
     */
    private val leadRowIdsOf = mutableMapOf<Long, Map<Long, Long>>()

    /** 计时器 id → 提前提醒分钟列表。由调用方在创建 / 从库里恢复时写入。 */
    private val leadMinutesOf = mutableMapOf<Long, List<Long>>()

    fun setLeadMinutes(timerId: Long, minutes: List<Long>) {
        leadMinutesOf[timerId] = minutes.sortedDescending()
    }

    /**
     * 写入提前提醒档位，并登记每一档对应的 `deadline_reminders` 行 id。
     *
     * 与 [setLeadMinutes] 的区别：这个版本让 `_leadAlerts` 的订阅者
     * （[at.cwiesner.android.visualtimer.ext.ExtApplication]）能把「已触发」写回库里。
     * 调度逻辑完全复用 [setLeadMinutes]，不重复实现。
     */
    fun attachLeadMinutes(timerId: Long, minutes: List<Long>, rowIds: Map<Long, List<Long>>) {
        setLeadMinutes(timerId, minutes)
        // 同一条提醒行理论上可能有重复档位，取第一个稳定的 id 即可
        leadRowIdsOf[timerId] = rowIds.mapValues { (_, ids) -> ids.first() }
    }

    /**
     * 某一档提前提醒对应的库行 id；没有登记（比如刚建好还没回读）时为 null。
     *
     * `internal`：只给 `ExtApplication` 的 leadAlerts 订阅者用，不是公开 API。
     */
    internal fun leadRowIdOf(timerId: Long, leadMinutes: Long): Long? =
        leadRowIdsOf[timerId]?.get(leadMinutes)

    /**
     * 设定提醒后立即把「已经过去的档位」标为已触发。
     *
     * 否则第一个 tick 会把所有 `lead <= 当前剩余分钟` 的档位一次性全部触发，
     * 用户刚选完就收到一串重复提醒。
     */
    fun markLeadsBaseline(timerId: Long, remainingMillis: Long) {
        val r = _runtimes.value[timerId] ?: return
        val remainMin = remainingMillis / 60_000L
        val leads = leadMinutesOf[timerId].orEmpty()
        val alreadyPast = leads.filter { it <= remainMin }.toSet()
        if (alreadyPast.isNotEmpty()) {
            _runtimes.value = _runtimes.value + (
                timerId to r.copy(firedLeads = r.firedLeads + alreadyPast)
                )
        }
    }

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

    /**
     * 从暂停处继续。
     *
     * **这是之前「暂停后点击却变成重置」的真正原因**：
     * 点击恢复走的是 [start]，而 `start` 会用一个全新的 TimerRuntime
     * 覆盖掉旧状态 —— remainingMillis 回到 totalMillis、overdueMillis 归零，
     * 等价于重置。继续必须只翻转 running，保留剩余时长与超时计数。
     */
    fun resume(timerId: Long) {
        val r = _runtimes.value[timerId] ?: return
        // 继续时把提醒静音，避免震动一直响
        acknowledge(timerId)
        _runtimes.value = _runtimes.value + (timerId to r.copy(running = true))
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
                // 提前提醒：跨过某个 leadMinutes 阈值时发一次
                val remainMin = remaining / 60_000L
                val leads = leadMinutesOf[k].orEmpty()
                val due = leads.filter { it <= remainMin && it !in r.firedLeads }
                if (due.isNotEmpty()) {
                    val updated = r.copy(firedLeads = r.firedLeads + due)
                    next[k] = updated.copy(remainingMillis = remaining, overdueMillis = 0L)
                    changed = true
                    due.forEach { lead -> _leadAlerts.tryEmit(updated to lead) }
                } else if (remaining != r.remainingMillis) {
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
     * 改名：只更新运行时里缓存的 label。
     *
     * 为什么需要：[TimerRuntime.label] 是**创建运行时那一刻**从库里复制来的。
     * 用户在计时过程中改名，如果只写数据库，运行时里仍是旧名字 ——
     * 悬浮窗、通知、卡片标题都会继续显示旧名，看起来像改名没生效。
     *
     * 运行时不存在时直接返回（没在跑的计时器不需要同步，下次 start 会从库里取新名）。
     */
    fun updateLabel(timerId: Long, label: String?) {
        val r = _runtimes.value[timerId] ?: return
        if (r.label == label) return
        _runtimes.value = _runtimes.value + (timerId to r.copy(label = label))
    }

    /**
     * 时长被用户改了：作废现有的「到点」结论与已触发的提前提醒，保留运行状态。
     *
     * 为什么不能简单 `clear()`（原来列表编辑就是这么干的）：
     * `clear` 把整条运行时删掉，正在跑的倒计时**归零**，正在响的提醒也被 acknowledge ——
     * 用户只是改了个名字，计时器却被重置了。
     *
     * 为什么不能只 `updateLabel`：`totalMillis` 是创建运行时那一刻的旧时长，
     * 旧时长下已经跨过的提前提醒（`firedLeads`）会挡住新时长的提醒（见 [tick] 的
     * `it !in r.firedLeads`），旧时长下已经判过的「到点」也会让 `overdueMillis` 继续累加。
     * 所以：换上新时长、回落到「未开始」态、清空 firedLeads，
     * 再由调用方按新的剩余时长调 [markLeadsBaseline] —— 用户刚改完不该马上被连响一串。
     */
    fun replaceTotal(timerId: Long, totalMillis: Long) {
        val r = _runtimes.value[timerId] ?: return
        _runtimes.value = _runtimes.value + (
            timerId to r.copy(
                totalMillis = totalMillis,
                remainingMillis = totalMillis,
                overdueMillis = 0L,
                firedLeads = emptySet(),
            )
            )
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
        // 运行时都没了，再留着 lead 登记只会在 id 被数据库复用（AUTOINCREMENT 不会，
        // 但备份导入会按新 id 重写）时串到别的计时器上，所以一并清掉。
        leadMinutesOf.remove(timerId)
        leadRowIdsOf.remove(timerId)
        _runtimes.value = _runtimes.value - timerId
    }

    fun clearAll() {
        _alerting.value = emptySet()
        leadMinutesOf.clear()
        leadRowIdsOf.clear()
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
