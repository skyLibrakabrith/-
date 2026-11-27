package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
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
    /**
     * 这一轮计时的**开始时刻**（epoch millis）。0 表示未知（例如从旧状态恢复）。
     *
     * 只给归档用：到点时写一条 `TimerSession(startedAt, endedAt)`，
     * 归档页据此画出"每天什么时候开始、什么时候结束"的 24 小时图。
     *
     * 暂停不清它 —— 归档记的是**墙钟区间**（含暂停时间），
     * 因为用户问的是"那段时间我在做什么"，而不是"净计时多久"。
     */
    val startedAt: Long = 0L,
    /**
     * 正计时「本次继续」那一刻的累计时长基准（毫秒）。
     *
     * `remainingMillis` 对正计时是**从最初开始到现在的累计值**，会跨过多次暂停/继续。
     * 但归档要按"每一段"记录，所以需要知道这一段是从哪个累计值起步的：
     *
     * ```
     * 这一段的时长 = remainingMillis - stopwatchBaseMillis
     * ```
     *
     * 由 [PanelRunner.resume] 在每次继续时刷新为当时的 `remainingMillis`。
     * 倒计时用不到（恒为 0）。
     */
    val stopwatchBaseMillis: Long = 0L,
) {
    val progress: Float
        get() = if (totalMillis <= 0L) 0f
        else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    /**
     * 正计时**这一段**（最近一次 start/resume 之后）的净计时时长。
     *
     * 和 [remainingMillis] 的区别：后者是累计值，跨过多次暂停/继续；
     * 归档要按段记录，所以要用这个减掉上次继续时的基准。
     * 倒计时返回 [remainingMillis] 的语义没有意义，调用方不该用它 —— 这里直接给 0。
     */
    val stopwatchSegmentMillis: Long
        get() = if (!stopwatch) 0L
        else (remainingMillis - stopwatchBaseMillis).coerceAtLeast(0L)

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
     * 而要把 `fired` 列写回库里（避免重启后再响一遍），就必须知道是哪一行。
     * 所以按「计时器 id → (提醒分钟 → 行 id)」再存一份映射。
     * 行 id 只可能来自 [setLeadMinutes] 的调用方（它握住 DAO），domain 层不碰数据库。
     */
    private val leadRowIdsOf = mutableMapOf<Long, Map<Long, Long>>()

    /** 计时器 id → 提前提醒分钟列表。由调用方在创建 / 从库里恢复时写入。 */
    private val leadMinutesOf = mutableMapOf<Long, List<Long>>()

    /**
     * **运行时状态需要落盘**的信号流。
     *
     * ## 为什么需要
     *
     * `_runtimes` 纯内存，进程一死就空 —— 用户看到的是「所有卡片退出重进都丢失进度」。
     * 把状态写进 `runtime_snapshots` 表才能在冷启动时接着走。
     *
     * ## 为什么用事件流
     *
     * 同 `pauseEvents`：`PanelRunner` 是纯 domain 层，**不持数据库引用**。
     * 这里只发"该存了"的信号 + 快照数据，由 `ExtApplication` 订阅后写库。
     *
     * ## 为什么载荷是整批而不是单条
     *
     * tick 每次推进所有正在跑的计时器，一次性发出整张表比逐条发更省
     * （订阅侧可以一次事务写多行）。
     */
    private val _snapshotRequests =
        kotlinx.coroutines.flow.MutableSharedFlow<List<TimerRuntime>>(
            // 1 够用：快照是"当前状态"而不是"事件序列"，中间帧丢了无所谓，
            // 但要留 1 个缓冲避免 tick 恰好撞上订阅侧忙于写库时被丢弃。
            extraBufferCapacity = 1,
        )
    val snapshotRequests: kotlinx.coroutines.flow.SharedFlow<List<TimerRuntime>> =
        _snapshotRequests.asSharedFlow()

    /** 上次发出快照请求的时刻，用于 tick 节流（避免每 200ms 写一次库）。 */
    private var lastSnapshotAt = 0L

    /** tick 里快照的节流间隔。2 秒足够精确，又不会把数据库写爆。 */
    private val snapshotIntervalMillis = 2_000L

    /**
     * 立即请求写一次快照（不节流）。**状态刚发生变化时**用这个：
     * start / stop / resume / reset / clear / setDuration —— 这些是关键边界，
     * 不能等下一次节流窗口。
     */
    fun requestSnapshotNow(now: Long = System.currentTimeMillis()) {
        lastSnapshotAt = now
        _snapshotRequests.tryEmit(_runtimes.value.values.toList())
    }


    /**
     * **正计时被暂停**的事件流。
     *
     * ## 为什么需要
     *
     * 正计时永远不到点，所以它永远走不到 `_alerts` 那条「到点 → 写归档」的路径 ——
     * 结果就是正计时**完全进不了归档**，用户在归档页看不到任何正计时记录。
     *
     * 而正计时唯一的"结束"方式就是用户主动按暂停，所以暂停才是最该落一条历史的时刻。
     *
     * ## 为什么用事件流而不是直接在这里写数据库
     *
     * `PanelRunner` 是纯 domain 层，手握的是 `StateFlow`，**不持数据库引用**
     * （见 `leadRowIdsOf` 的注释："domain 层不碰数据库"）。
     * 写归档需要 DAO，所以这里只发事件，由 `ExtApplication` 订阅后落库 ——
     * 和 `alerts` / `leadAlerts` 走同一套模式。
     *
     * ## 载荷
     *
     * 发出的是**暂停那一刻的快照**（`running` 已置 false），
     * 调用方拿 `startedAt` / `remainingMillis` 就能算出这段计时的区间与时长，
     * 不需要再回查运行时。
     */
    private val _pauseEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<TimerRuntime>(
            extraBufferCapacity = 16,
        )
    val pauseEvents: kotlinx.coroutines.flow.SharedFlow<TimerRuntime> =
        _pauseEvents.asSharedFlow()

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

    /**
     * 冷启动：把**从快照恢复出来的**运行时装进去。
     *
     * 与 [restoreInactive] 的区别：
     * - `restoreInactive` 只能造"待命"状态（满格 / 0），因为手上没有历史进度
     * - 这个方法接收**已经算好进度**的运行时（调用方读了 `runtime_snapshots`，
     *   用 `computeRestoredProgress` 补算了离线流逝），所以能把进度接上
     *
     * 已有的运行时**不覆盖**：这次进程里可能已经跑着了（比如从通知点进来先触发了 start）。
     *
     * 装完立刻落一次快照，让"恢复后的状态"也成为新的基准 ——
     * 否则下次启动会以更早的 `savedAt` 重新补算一遍离线时间，越算越离谱。
     */
    fun applyRestored(runtimes: List<TimerRuntime>) {
        if (runtimes.isEmpty()) return
        val current = _runtimes.value
        val add = runtimes.filter { !current.containsKey(it.timerId) }.associateBy { it.timerId }
        if (add.isEmpty()) return
        _runtimes.value = current + add
        requestSnapshotNow()
    }

    /**
     * 冷启动恢复：根据**持久化的计时器类型**重建运行时。
     *
     * ## 为什么需要它
     *
     * `_runtimes` 是纯内存的 StateFlow，进程一死就空了。
     * 之前只有 DDL 有恢复路径（`ExtApplication.restoreDeadlines`），
     * 普通倒计时和正计时**都没有** —— 退出重进后它们没有任何运行时，
     * 卡片只能按 `TimerItem.durationMillis` 回退到"满格倒计时"的样子。
     *
     * 用户看到的就是「正计时退出重进以后变成了倒计时」：
     * 正计时的 `durationMillis` 是 0，回退后显示成 0:00 的倒计时，
     * 而且点一下会走 `start()`（从头开始），之前计的时长看起来全没了。
     *
     * ## 各类型怎么恢复
     *
     * - **Stopwatch（正计时）**：没有任何终点，只能从 0 重新累加。
     *   这是**固有取舍** —— 时间只在内存里走，没有落库，
     *   进程死了就不知道之前计了多久。至少它现在还是"正计时"，
     *   不会伪装成倒计时。想要真正跨进程延续，得把已计时长定期写库。
     * - **Countdown（普通倒计时）**：从完整时长重新开始。
     *   同样因为剩余量没有落库，只能重来。
     * - **Deadline**：**不在这里处理** —— 它的剩余量由 `deadlineEpochMillis`
     *   决定，是**可精确恢复**的，走 `restoreDeadlines()` 那条更精细的路径
     *   （还要处理提醒行、过期判定）。这里明确跳过，避免两处打架。
     *
     * ## 为什么标成 running = false
     *
     * 恢复出来的运行时是"准备好了但没跑"的状态：
     * 正计时从 0、倒计时满格，用户点一下才开始。
     * 如果直接 `running = true`，冷启动瞬间一堆计时器自己跑起来，
     * 而用户根本没按过开始 —— 那比"没恢复"更糟。
     *
     * @param timers 数据库里的全部计时器
     */
    fun restoreInactive(timers: List<TimerItem>) {
        val current = _runtimes.value
        val add = LinkedHashMap<Long, TimerRuntime>()
        for (t in timers) {
            // DDL 走 restoreDeadlines；已存在运行时的（这次进程里已经跑着）不要覆盖
            if (t.kind == TimerKind.Deadline) continue
            if (current.containsKey(t.id)) continue
            add[t.id] = when (t.kind) {
                TimerKind.Stopwatch -> TimerRuntime(
                    timerId = t.id,
                    label = t.label,
                    totalMillis = 0L,
                    remainingMillis = 0L,
                    running = false,
                    deadlineEpochMillis = null,
                    alertProfileId = t.alertProfileId,
                    stopwatch = true,
                )
                else -> TimerRuntime(
                    timerId = t.id,
                    label = t.label,
                    totalMillis = t.durationMillis,
                    remainingMillis = t.durationMillis,
                    running = false,
                    deadlineEpochMillis = null,
                    alertProfileId = t.alertProfileId,
                )
            }
        }
        if (add.isEmpty()) return
        _runtimes.value = current + add
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
                // 归档用：记录这一轮的起点
                startedAt = System.currentTimeMillis(),
            )
            )
        requestSnapshotNow()
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
                startedAt = System.currentTimeMillis(),
            )
            )
        requestSnapshotNow()
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
        // 正计时继续：把 startedAt 挪到现在，并**记下此刻已累计的时长**。
        //
        // 为什么两件事都要做：
        // - `startedAt` 不挪 → 下一次暂停写出的归档区间从最初起点开始，
        //   和上一条完全重叠，且包含了根本没在计时的暂停期。
        // - `stopwatchBaseMillis` 不记 → 下一次暂停算这一段时长时，
        //   只能拿到 `remainingMillis`，那是**从开始到现在的累计值**（含之前的几段），
        //   归档里每一段都会越来越大。
        //
        // 所以：这一段的时长 = `remainingMillis - stopwatchBaseMillis`。
        _runtimes.value = _runtimes.value + (
            timerId to if (r.stopwatch) {
                r.copy(
                    running = true,
                    startedAt = System.currentTimeMillis(),
                    stopwatchBaseMillis = r.remainingMillis,
                )
            } else {
                r.copy(running = true)
            }
            )
        // 继续也是关键边界：立即落盘，保证"继续后马上杀进程"也不丢
        requestSnapshotNow()
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
                // 归档用：DDL 的起点就是它创建/恢复的时刻
                startedAt = now,
            )
            )
        requestSnapshotNow()
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

        // 运行中的进度要定期落盘，否则进程被杀时最后一段就丢了。
        //
        // 节流 2 秒：tick 是 200ms 一拍，不节流会每 200ms 写一次库
        // （10 个计时器就是 50 次/秒的写入，纯属浪费）。
        // 节流的代价是"最多丢 2 秒进度"，对计时器场景完全够用。
        if (now - lastSnapshotAt >= snapshotIntervalMillis) {
            lastSnapshotAt = now
            _snapshotRequests.tryEmit(next.values.toList())
        }
    }

    fun stop(timerId: Long) {
        val r = _runtimes.value[timerId] ?: return
        // 已经是暂停态就别再发事件：否则重复点暂停会刷出多条一模一样的归档
        val wasRunning = r.running
        val stopped = r.copy(running = false)
        _runtimes.value = _runtimes.value + (timerId to stopped)

        // 正计时暂停 = 这一轮"结束"了（它永远不到点，走不到 alerts）。
        // 用停止后的快照发事件，调用方据此写一条归档。
        //
        // 倒计时**不**在这里归档：它暂停后还能继续走完，到点时 alerts 会写，
        // 提前暂停不该算"发生过一次完整计时"，否则归档会被半途暂停刷满。
        if (wasRunning && r.stopwatch) {
            _pauseEvents.tryEmit(stopped)
        }
        // 暂停是"状态变了"的关键边界，立即落盘，不节流：
        // 用户很可能暂停完就杀进程，等 2 秒的节流窗口会丢掉这次进度。
        requestSnapshotNow()
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
     * 截止时刻被用户改了（只可能发生在「未锁定的历史 DDL」上）。
     *
     * 为什么需要：DDL 的剩余量是 `deadlineEpochMillis - now` **现算**的
     * （见 [tick]），运行时里那份 `deadlineEpochMillis` 才是真正的终点。
     * 只改库不同步运行时，界面会继续按旧时刻走完 —— 「改了截止时间却不变」。
     *
     * 与 [replaceTotal] 一样换掉 firedLeads：旧截止时刻下算出的「已过档位」
     * 对新截止时刻不成立，不清掉会挡住新时刻该响的提醒。
     */
    fun replaceDeadline(timerId: Long, deadlineEpochMillis: Long, now: Long) {
        val r = _runtimes.value[timerId] ?: return
        val remaining = (deadlineEpochMillis - now).coerceAtLeast(0L)
        _runtimes.value = _runtimes.value + (
            timerId to r.copy(
                deadlineEpochMillis = deadlineEpochMillis,
                totalMillis = remaining,
                remainingMillis = remaining,
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
        requestSnapshotNow()
    }

    fun clear(timerId: Long) {
        acknowledge(timerId)
        // 运行时都删了，提前提醒的登记（档位 + 行 id）也没有意义，
        // 一起清掉，避免一个已删除的 id 残留映射
        leadMinutesOf.remove(timerId)
        leadRowIdsOf.remove(timerId)
        _runtimes.value = _runtimes.value - timerId
        // 运行时没了，快照也要跟着删（订阅侧会按"库里多出来的行"清理）
        requestSnapshotNow()
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

/**
 * 一次计时**实际持续了多久**（归档用）。
 *
 * ## 为什么要单独一个函数
 *
 * 这个算式有三处语义容易各写各的：
 * - **超时要算进去**：5 分钟计划 + 3 分钟超时 = 8 分钟。
 *   只算 `total - remaining` 会得到 5 分钟，归档里就看不到超时那一段。
 * - **正计时**没有"计划/剩余"概念，只有"这一段累计了多少"。
 * - **暂停结束**（remaining > 0）只算走过的那部分，不能当满时长。
 *
 * 两条写入路径（`ExtApplication` 的到点归档、`PanelsScreen` 的「结束」按钮）
 * 都用它，避免其中一处以后被改坏而另一处没跟上。
 *
 * @param stopwatchSegmentMillis 正计时**本段**的净计时（见 [TimerRuntime.stopwatchSegmentMillis]）
 */
fun elapsedMillisForArchive(
    totalMillis: Long,
    remainingMillis: Long,
    overdueMillis: Long,
    stopwatch: Boolean,
    stopwatchSegmentMillis: Long,
): Long = if (stopwatch) {
    stopwatchSegmentMillis.coerceAtLeast(0L)
} else {
    // 计划 − 剩余 = 已经走过；再加超时 = 实际持续
    (totalMillis - remainingMillis + overdueMillis).coerceAtLeast(0L)
}
