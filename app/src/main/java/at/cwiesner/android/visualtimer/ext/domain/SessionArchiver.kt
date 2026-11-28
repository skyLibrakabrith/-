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
     * 暂停：写一条进行中的记录，并开一个未封口的暂停区间。
     *
     * 已经有一条进行中记录时**不重复写**（重复点暂停不该刷出多条）。
     *
     * @return 是否新建了一条
     */
    suspend fun onPause(rt: TimerRuntime): Boolean {
        if (ongoingSessionOf.containsKey(rt.timerId)) {
            // 已经有占位行了，只把暂停区间推进（理论上不该发生，防御一下）
            touch(rt)
            return false
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
        val startedAt = rt.startedAt.takeIf { it > 0L }
            ?: sessionOf(existing)?.startedAt
            ?: t
        val elapsed = elapsedMillisForArchive(
            totalMillis = rt.totalMillis,
            remainingMillis = rt.remainingMillis,
            overdueMillis = rt.overdueMillis,
            stopwatch = rt.stopwatch,
            stopwatchSegmentMillis = rt.stopwatchSegmentMillis,
        )
        val planned = if (rt.stopwatch) elapsed else rt.totalMillis

        // 太短的：把占位行删掉，归档里不留痕迹
        if (!ArchiveRules.shouldArchive(rt.stopwatch, elapsed, planned)) {
            existing?.let {
                dao.sessions().deleteById(it)
                dao.pauses().deleteForSession(it)
                ongoingSessionOf.remove(rt.timerId)
            }
            return null
        }

        val endedAt = (startedAt + elapsed).coerceAtLeast(startedAt)
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

    private suspend fun sessionOf(id: Long?): TimerSession? {
        if (id == null) return null
        return dao.sessions().allOngoing().firstOrNull { it.id == id }
    }

    /** 计时器被彻底删除（卡片消失）时，清掉内存里的映射。 */
    fun forget(timerId: Long) {
        ongoingSessionOf.remove(timerId)
    }

    /**
     * 进程重启后的清理：把残留的 `ongoing = 1` 记录收尾。
     *
     * 不做的话归档页会一直挂着一条"进行中"，而对应的计时器可能早就不在了。
     */
    suspend fun settleLeftovers(hadRuntime: (Long) -> Boolean) {
        dao.pauses().deleteOrphans()
        val leftovers = dao.sessions().allOngoing()
        if (leftovers.isEmpty()) return
        for (s in leftovers) {
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
