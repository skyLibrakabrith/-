package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.data.TimerPause
import at.cwiesner.android.visualtimer.ext.data.TimerPauseDao
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.data.TimerSessionDao

/**
 * 一次计时在归档里的唯一写入者。
 *
 * ## 为什么要有这个类
 *
 * 原来写 `timer_sessions` 的地方有 4 处（到点自动、正计时暂停、点「结束」、
 * 应用内直接插），每处各写一份算式和门槛判断。结果是同一个概念在不同路径上
 * 算法不一致 —— 之前就出现过「归档时长没算超时」和「同一次计时记了两条」。
 *
 * 现在所有路径都走这里，语义只有一份。
 *
 * ## 会话的生命周期（用户确认的语义）
 *
 * ```
 * 开始 ──► 运行 ──暂停──► [写一条 ongoing=1] + 开一个未封口的暂停区间
 *            ▲                    │
 *            └────恢复────────────┘（封口暂停区间，推进 endedAt）
 *
 * 重置 / 停止 / 结束 ──► 把那条 ongoing 原地更新为完整记录（不新增行）
 * ```
 *
 * 关键点：**一次计时在归档里始终只有一条**。暂停时先占位是为了"进程被杀也不丢"，
 * 结束时更新同一条是为了"不会变成两次计时"。
 *
 * ## 太短的怎么办
 *
 * 按 [ArchiveRules]：倒计时 < 计划时长 1/10 不计，正计时 < 30 秒不计。
 * 但如果已经写了占位行，收尾时发现太短，要把那条删掉 —— 不能留一条
 * "进行了 3 秒"的记录在归档里。
 */
class SessionArchiver(
    private val dao: SessionDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** 直接用数据库构造（生产代码走这个）。 */
    constructor(
        database: AppDatabase,
        now: () -> Long = { System.currentTimeMillis() },
    ) : this(
        // 用 provider 函数而不是匿名 object：匿名 object 里的 `this` 会和
        // SessionArchiver 的初始化顺序打架（Kotlin 报
        // "Cannot access '<this>' before the instance has been initialized"）。
        dao = LambdaSessionDao(
            sessionsProvider = database::timerSessionDao,
            pausesProvider = database::timerPauseDao,
        ),
        now = now,
    )


    /** 每个计时器当前那条进行中会话的 id（timerId → sessionId）。 */
    private val ongoingSessionOf = mutableMapOf<Long, Long>()

    /**
     * 已经归档过的轮次（timerId → 该轮的 `startedAt`）。
     *
     * 用来让 [finish] **幂等**：结束一个计时器的路径有多条
     * （「结束」按钮自己归档一次，`deleteTimerRows` 删除前又调 `finishIfNeeded`），
     * 同一轮被处理两次不能产生两条记录。
     *
     * 用 `startedAt` 而不是 bool：重置后重新开始是一轮新的，必须允许再归档一次。
     */
    private val archivedRoundOf = mutableMapOf<Long, Long>()

    /**
     * 暂停：写一条进行中的记录，并开一个未封口的暂停区间。
     *
     * 已经有一条进行中记录时**不重复写**（重复点暂停不该刷出多条）。
     *
     * @return 是否新建了一条
     */
    suspend fun onPause(rt: TimerRuntime): Boolean {
        val existing = ongoingSessionOf[rt.timerId]
        if (existing != null) {
            // 已有占位行：确认它属于**同一轮**计时（startedAt 一致）才复用。
            //
            // 为什么要判断：`PanelRunner.reset()` 会把 remainingMillis 复位、
            // 用户接着再跑一轮（`start()` 会重写 startedAt）。如果这里不比较，
            // 新一轮的暂停会挂到上一轮那条已经收尾的会话上 —— 表现为
            // "重置过一次之后，暂停就不产生新记录了"。
            val row = sessionOf(existing)
            if (row != null && row.startedAt == rt.startedAt && row.ongoing) {
                touch(rt)
                return false
            }
            // 是上一轮的残留：丢掉映射，下面按新一轮处理
            ongoingSessionOf.remove(rt.timerId)
        }
        val startedAt = rt.startedAt.takeIf { it > 0L } ?: return false
        val t = now()
        val elapsed = elapsedMillisForArchive(
            totalMillis = rt.totalMillis,
            remainingMillis = rt.remainingMillis,
            overdueMillis = rt.overdueMillis,
            stopwatch = rt.stopwatch,
            stopwatchSegmentMillis = rt.stopwatchSegmentMillis,
        )
        val sessionId = dao.sessions().insert(
            TimerSession(
                label = rt.label,
                startedAt = startedAt,
                endedAt = startedAt + elapsed.coerceAtLeast(0L),
                plannedMillis = if (rt.stopwatch) elapsed else rt.totalMillis,
                kind = kindNameOf(rt),
                // 还没结束，先不算"正常到点"
                completed = false,
                ongoing = true,
            ),
        )
        ongoingSessionOf[rt.timerId] = sessionId
        dao.pauses().insert(
            TimerPause(sessionId = sessionId, fromMillis = t, toMillis = 0L),
        )
        return true
    }

    /**
     * 恢复：封口当前那段未结束的暂停区间。
     */
    suspend fun onResume(timerId: Long) {
        val sessionId = ongoingSessionOf[timerId] ?: return
        val open = dao.pauses().openPause(sessionId) ?: return
        val t = now()
        // 避免 0 长度区间（点暂停又立刻继续）
        if (t > open.fromMillis) {
            dao.pauses().close(open.id, t)
        }
    }

    /**
     * 重置 / 停止 / 结束 / 到点：把会话收尾成完整记录。
     *
     * @param completed 正常到点（含超时）传 true；被用户提前停下传 false
     * @param removeTimer 是否要在归档后把计时器从面板移除（未固定的行为）
     * @return 真正落库的 sessionId；太短没记时返回 null
     */
    suspend fun finish(
        rt: TimerRuntime,
        completed: Boolean,
    ): Long? {
        val t = now()
        val existing = ongoingSessionOf[rt.timerId]

        // ---- 幂等 ----
        //
        // 多条入口都会走到 finish（「结束」按钮 → archiveAndEndTimer 一次，
        // 紧接着 deleteTimerRows 里的 finishIfNeeded 又一次）。没有幂等，
        // 每次结束都会写两条。真机上确认过：(id=10 与 id=11 区间完全相同)。
        //
        // **幂等键不能用 `rt.startedAt`** —— 正计时 `resume()` 会把它挪到"现在"
        // （为了让每一段的区间不重叠），于是恢复之后再结束，第二次调用的
        // `startedAt` 与第一次不同，检查不命中 → 又插一条。
        //
        // 正确的分层：
        // 1. **有占位行**（暂停过）→ 占位行就是这一轮的锚，处理完 `ongoingSessionOf`
        //    就被清空；所以"清空前拿到它 = 第一次，拿不到 = 已经收尾过"。
        //    这条路径下 `startedAt` 怎么变都不影响。
        // 2. **没有占位行**（从未暂停过）→ 用 `startedAt` 当锚（它没被挪过）。
        if (existing == null && rt.startedAt > 0L && archivedRoundOf[rt.timerId] == rt.startedAt) {
            return null
        }
        if (existing != null && !isOngoingSession(existing)) {
            // 占位行已被收尾过（映射残留），同样不再处理
            ongoingSessionOf.remove(rt.timerId)
            return null
        }

        val startedAt = rt.startedAt.takeIf { it > 0L }
            ?: sessionOf(existing)?.startedAt
            ?: t
        // 有占位行时，归档起点以**占位行**为准：正计时 resume 会把 rt.startedAt 挪到
        // 最后一次恢复的时刻，用它算结束时刻会让区间变短（那段时间明明在计时）。
        val archiveStart = sessionOf(existing)?.startedAt ?: startedAt
        val elapsed = elapsedMillisForArchive(
            totalMillis = rt.totalMillis,
            remainingMillis = rt.remainingMillis,
            overdueMillis = rt.overdueMillis,
            stopwatch = rt.stopwatch,
            stopwatchSegmentMillis = rt.stopwatchSegmentMillis,
        )
        val planned = if (rt.stopwatch) elapsed else rt.totalMillis

        // 起点合理性兜底：`elapsed` 是从**运行时**算的（计划−剩余+超时），
        // 而 `startedAt` 是墙钟。两者应当自洽，但若运行时是被陈旧快照恢复出来的
        // （真机上见过：起点是昨天、只跑了 25 秒却算出 7 小时），
        // 这段记录就是垃圾数据 —— 宁可丢掉也不写进归档。
        //
        // 判据：起点不应早于「现在 − 已跑时长」太多（容差吸收 tick 与恢复误差）。
        if (startedAt < t - elapsed - START_TOLERANCE_MILLIS) {
            existing?.let {
                dao.sessions().deleteById(it)
                dao.pauses().deleteForSession(it)
                ongoingSessionOf.remove(rt.timerId)
            }
            return null
        }

        // 太短的：把占位行删掉，归档里不留痕迹
        if (!ArchiveRules.shouldArchive(rt.stopwatch, elapsed, planned)) {
            existing?.let {
                dao.sessions().deleteById(it)
                dao.pauses().deleteForSession(it)
                ongoingSessionOf.remove(rt.timerId)
            }
            return null
        }

        val endedAt = (archiveStart + elapsed).coerceAtLeast(archiveStart)
        if (existing != null) {
            // 封口仍未结束的暂停区间（如果这次是"暂停中就重置"）
            dao.pauses().openPause(existing)?.let { open ->
                if (t > open.fromMillis) dao.pauses().close(open.id, t)
            }
            dao.sessions().finishOngoing(
                id = existing,
                endedAt = endedAt,
                plannedMillis = planned,
                completed = completed,
            )
            ongoingSessionOf.remove(rt.timerId)
            if (rt.startedAt > 0L) archivedRoundOf[rt.timerId] = rt.startedAt
            return existing
        }

        // 没有占位行（例如直接就绪到点、或从未暂停过）：直接写一条完整的
        val id = dao.sessions().insert(
            TimerSession(
                label = rt.label,
                startedAt = startedAt,
                endedAt = endedAt,
                plannedMillis = planned,
                kind = kindNameOf(rt),
                completed = completed,
                ongoing = false,
            ),
        )
        if (rt.startedAt > 0L) archivedRoundOf[rt.timerId] = rt.startedAt
        return id
    }

    /** 推进进行中记录的 `endedAt`（恢复时调用，让归档页看到进度在动）。 */
    private suspend fun touch(rt: TimerRuntime) {
        val sessionId = ongoingSessionOf[rt.timerId] ?: return
        val startedAt = sessionOf(sessionId)?.startedAt ?: return
        val elapsed = elapsedMillisForArchive(
            totalMillis = rt.totalMillis,
            remainingMillis = rt.remainingMillis,
            overdueMillis = rt.overdueMillis,
            stopwatch = rt.stopwatch,
            stopwatchSegmentMillis = rt.stopwatchSegmentMillis,
        )
        dao.sessions().touchOngoing(sessionId, startedAt + elapsed)
    }

    /** [TimerRuntime] 只有 `stopwatch` 标志，归档里要存 [TimerKind] 的名字。 */
    private fun kindNameOf(rt: TimerRuntime): String =
        if (rt.stopwatch) TimerKind.Stopwatch.name else TimerKind.Countdown.name

    /** 该 sessionId 是否存在且仍是 `ongoing = 1`。 */
    private suspend fun isOngoingSession(sessionId: Long): Boolean =
        sessionOf(sessionId)?.ongoing == true

    private suspend fun sessionOf(id: Long?): TimerSession? {
        if (id == null) return null
        return dao.sessions().allOngoing().firstOrNull { it.id == id }
    }

    /**
     * 「重新开始」前的收尾：如果上一轮还没归档，补一条。
     *
     * 为什么需要：`PanelRunner.start(timer)` 会重建整个运行时（`clear` +
     * `start`），上一轮的进度直接消失。多条入口会走到这里 ——
     * 全屏页的「开始」（非暂停态）、列表视图的「开始」、首页快速开始。
     * 不补的话，用户跑了 20 分钟没暂停就点「开始 」，那 20 分钟凭空消失。
     *
     * 没有占位行且从未运行过（`startedAt == 0`）时什么也不做，
     * 所以对"第一次开始"是零影响。
     */
    suspend fun finishIfNeeded(rt: TimerRuntime?) {
        if (rt == null) return
        if (rt.startedAt <= 0L) return
        // 从没跑过（remaining 满格且没有超时、也不是正计时）就没东西可记
        val hasProgress = rt.stopwatch || rt.overdueMillis > 0L ||
            rt.remainingMillis < rt.totalMillis
        if (!hasProgress) return
        runCatching { finish(rt, completed = false) }
    }

    /** 计时器被彻底删除（卡片消失）时，清掉内存里的映射。 */
    fun forget(timerId: Long) {
        ongoingSessionOf.remove(timerId)
        // 轮次记录也要清：计时器删了之后这个 id 不会再用，
        // 留着就是一条永久的泄漏（长时间用下来 map 会一直涨）。
        archivedRoundOf.remove(timerId)
    }

    /**
     * 进程重启后的清理：把残留的 `ongoing = 1` 记录收尾。
     *
     * 不做的话归档页会一直挂着一条"进行中"，而对应的计时器可能早就不在了。
     */
    suspend fun settleLeftovers(hasLiveRuntime: (Long) -> Boolean) {
        // 先清孤儿区间（会话被删了但区间还留着）
        dao.pauses().deleteOrphans()

        val leftovers = dao.sessions().allOngoing()
        if (leftovers.isEmpty()) return

        for (s in leftovers) {
            // **只有对应的计时器已经不在运行时里才收尾。**
            //
            // 为什么必须判断：进程正常退出时，跑着的计时器会由 runtime_snapshots
            // 恢复回来接着走 —— 那条会话还没结束，绝不能在这里把它标成完成，
            // 否则用户重启后再暂停/结束，就会在归档里多出一条重复记录。
            if (hasLiveRuntime(s.id)) continue

            // 封口还没结束的暂停区间
            dao.pauses().openPause(s.id)?.let { open ->
                if (s.endedAt > open.fromMillis) {
                    dao.pauses().close(open.id, s.endedAt)
                } else {
                    dao.pauses().deleteById(open.id)
                }
            }
            val elapsed = (s.endedAt - s.startedAt).coerceAtLeast(0L)
            val planned = s.plannedMillis
            if (!ArchiveRules.shouldArchive(s.kind == "Stopwatch", elapsed, planned)) {
                // 太短的残留不进归档
                dao.sessions().deleteById(s.id)
                dao.pauses().deleteForSession(s.id)
            } else {
                dao.sessions().finishOngoing(
                    id = s.id,
                    endedAt = s.endedAt,
                    plannedMillis = planned,
                    completed = false,
                )
            }
        }
    }

}

/**
 * [SessionArchiver] 真正需要的两个 DAO。
 *
 * 抽这层不是为了"架构好看"，是为了**能测**：项目不引入 mockk，
 * 而 `AppDatabase` 是 Room 生成类、在纯 JVM 单测里造不出来。
 * 有了这个接口，测试可以直接塞一份内存实现，把状态机完整跑一遍。
 */
/**
 * 起点合理性容差。
 *
 * 归档区间 = [startedAt, startedAt + elapsed)。若 `startedAt` 比「现在 − elapsed」
 * 还早出这个容差，说明两者不自洽（典型原因：运行时被陈旧快照恢复）。
 */
private const val START_TOLERANCE_MILLIS: Long = 5 * 60_000L

interface SessionDao {
    fun sessions(): TimerSessionDao
    fun pauses(): TimerPauseDao
}

/** [SessionDao] 的函数式实现，只用两个 provider。 */
internal class LambdaSessionDao(
    private val sessionsProvider: () -> TimerSessionDao,
    private val pausesProvider: () -> TimerPauseDao,
) : SessionDao {
    override fun sessions(): TimerSessionDao = sessionsProvider()
    override fun pauses(): TimerPauseDao = pausesProvider()
}
