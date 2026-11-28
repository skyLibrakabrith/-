package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.TimerPause
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.data.TimerKind

/**
 * 序列（多阶段计时）在归档里的写入者。
 *
 * ## 为什么单独一个类，而不是塞进 [SessionArchiver]
 *
 * [SessionArchiver] 的键是 `timerId`，而序列的键是 `sequenceId` —— **两者的
 * 数字空间重叠**（计时器 8 和序列 8 是两条完全不同的东西）。混在一个 map 里
 * 必然串号，表现为「暂停序列把计时器的归档改坏了」。
 *
 * 所以这里用**独立的映射表**，数据库层面则靠 `timer_sessions` 的 `label`
 * 前缀区分（序列行统一为 `序列名 · 阶段名`）。
 *
 * ## 语义（与计时器保持一致）
 *
 * ```
 * 暂停 ──► 写一条 ongoing=1 占位行 + 开未封口暂停区间
 * 继续 ──► 封口暂停区间
 * 结束 ──► onSequenceEnded 已写各阶段记录；这里只负责把占位行收尾
 * ```
 *
 * 关键约束：**一次序列运行在归档里最多一条 ongoing 占位行**。
 * `onSequenceEnded` 写阶段记录时，如果有占位行就**更新它**而不是新增。
 */
class SequenceSessionArchiver(
    private val dao: SessionDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** 生产构造：直接用数据库。 */
    constructor(
        database: AppDatabase,
        now: () -> Long = { System.currentTimeMillis() },
    ) : this(
        dao = LambdaSessionDao(
            sessionsProvider = database::timerSessionDao,
            pausesProvider = database::timerPauseDao,
        ),
        now = now,
    )

    /** sequenceId → 那条 ongoing 占位行的 session id。 */
    private val ongoingSessionOf = mutableMapOf<Long, Long>()

    /**
     * 暂停：写 ongoing 占位行 + 开未封口暂停区间。
     *
     * 序列暂停时**当前阶段**才跑了一部分，所以占位行的区间是
     * `[now - stepElapsed, now)`，代表"这个阶段已经跑了多久"。
     *
     * @return 是否新建了一条（已有占位行时只推进，返回 false）
     */
    suspend fun onPause(p: SequencePause): Boolean {
        val t = now()
        val existing = ongoingSessionOf[p.sequenceId]
        if (existing != null) {
            // 同一次运行里重复暂停：只确保区间是开着的
            val open = dao.pauses().openPause(existing)
            if (open == null) {
                dao.pauses().insert(
                    TimerPause(sessionId = existing, fromMillis = t, toMillis = 0L),
                )
            }
            return false
        }
        val label = "${p.sequenceName} · ${p.step.label ?: "第${p.stepIndex + 1}阶段"}"
        val startedAt = (t - p.stepElapsedMillis).coerceAtLeast(0L)
        val sessionId = dao.sessions().insert(
            TimerSession(
                label = label,
                startedAt = startedAt,
                endedAt = t,
                plannedMillis = p.step.durationMillis,
                kind = TimerKind.Countdown.name,
                completed = false,
                ongoing = true,
            ),
        )
        ongoingSessionOf[p.sequenceId] = sessionId
        dao.pauses().insert(
            TimerPause(sessionId = sessionId, fromMillis = t, toMillis = 0L),
        )
        return true
    }

    /** 继续：封口当前那段未结束的暂停区间。 */
    suspend fun onResume(sequenceId: Long) {
        val sessionId = ongoingSessionOf[sequenceId] ?: return
        val open = dao.pauses().openPause(sessionId) ?: return
        val t = now()
        if (t > open.fromMillis) dao.pauses().close(open.id, t)
    }

    /**
     * 序列结束（跑完 / 被停止）时收尾那条占位行。
     *
     * 调用方（`ExtApplication`）会**先**走 `onSequenceEnded` 写各阶段记录，
     * 再调这里。这里只做两件事：封口未结束的暂停区间、把 ongoing 标记清掉。
     *
     * 为什么不在阶段记录那边复用这条占位行：阶段记录的区间由
     * `layOutSequentialIntervals` 按串行铺开，而占位行是"暂停那一刻"的
     * 快照，两者语义不同。占位行收尾成独立的一条完整记录，不去和阶段记录
     * 抢同一行 —— 保持"阶段记录 = 每个阶段一条"这个不变量。
     */
    suspend fun finish(sequenceId: Long) {
        val sessionId = ongoingSessionOf.remove(sequenceId) ?: return
        val t = now()
        dao.pauses().openPause(sessionId)?.let { open ->
            if (t > open.fromMillis) dao.pauses().close(open.id, t)
        }
        val row = dao.sessions().allOngoing().firstOrNull { it.id == sessionId }
            ?: return
        val elapsed = (row.endedAt - row.startedAt).coerceAtLeast(0L)
        // 太短的占位行不值得留（与计时器侧 ArchiveRules 一致）
        if (!ArchiveRules.shouldArchive(false, elapsed, row.plannedMillis)) {
            dao.sessions().deleteById(sessionId)
            dao.pauses().deleteForSession(sessionId)
            return
        }
        dao.sessions().finishOngoing(
            id = sessionId,
            endedAt = row.endedAt,
            plannedMillis = row.plannedMillis,
            completed = false,
        )
    }

    /** 序列被删除时清掉内存映射。 */
    fun forget(sequenceId: Long) {
        ongoingSessionOf.remove(sequenceId)
    }

    /**
     * 进程重启后的清理：把残留的序列 ongoing 行收尾。
     *
     * 与 `SessionArchiver.settleLeftovers` 同样的取舍：只有对应序列
     * **已经不在运行**时才收尾，否则会把还在跑的会话误标成结束。
     */
    suspend fun settleLeftovers(hasLiveSequence: (Long) -> Boolean) {
        val leftovers = dao.sessions().allOngoing().filter { it.label?.contains(" · ") == true }
        for (row in leftovers) {
            // 序列行的 label 是「序列名 · 阶段名」，但它没有 sequenceId 列，
            // 所以这里无法精确判断"哪个序列还在跑"。保守做法：
            // 只有当**没有任何序列在运行**时才清理（由调用方传全 false）。
            if (hasLiveSequence(-1L)) continue
            dao.pauses().openPause(row.id)?.let { open ->
                if (row.endedAt > open.fromMillis) {
                    dao.pauses().close(open.id, row.endedAt)
                } else {
                    dao.pauses().deleteById(open.id)
                }
            }
            val elapsed = (row.endedAt - row.startedAt).coerceAtLeast(0L)
            if (!ArchiveRules.shouldArchive(false, elapsed, row.plannedMillis)) {
                dao.sessions().deleteById(row.id)
                dao.pauses().deleteForSession(row.id)
            } else {
                dao.sessions().finishOngoing(
                    id = row.id,
                    endedAt = row.endedAt,
                    plannedMillis = row.plannedMillis,
                    completed = false,
                )
            }
        }
    }
}
