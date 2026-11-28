package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerPause
import at.cwiesner.android.visualtimer.ext.data.TimerPauseDao
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.data.TimerSessionDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionArchiver] 的状态机测试。
 *
 * ## 为什么用内存实现而不是 Room 内存库
 *
 * `SessionArchiver` 只依赖两个 DAO 接口（`timerSessionDao()` / `timerPauseDao()`），
 * 不碰任何 Android API。用一份内存版 DAO 就能把**全部状态迁移**跑一遍，
 * 而且能精确断言"归档里到底有几条"——这正是最容易出错的地方：
 *
 * > 暂停写一条、结束又写一条 → 同一次计时变成两条记录，合计翻倍。
 *
 * 真机验证被设备断开挡住时，这个测试至少能证明状态机本身是对的。
 */
class SessionArchiverTest {

    /** 内存版 timer_sessions。 */
    private class FakeSessionDao : TimerSessionDao {
        val rows = mutableListOf<TimerSession>()
        private var nextId = 1L

        override suspend fun insert(s: TimerSession): Long {
            val id = nextId++
            rows += s.copy(id = id)
            return id
        }

        override suspend fun allOnce(): List<TimerSession> = rows.toList()

        override fun observeBetween(from: Long, to: Long) = throw UnsupportedOperationException()

        override suspend fun between(from: Long, to: Long) =
            rows.filter { it.startedAt >= from && it.startedAt < to }

        override suspend fun earliest(): Long? = rows.minOfOrNull { it.startedAt }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun finishOngoing(id: Long, endedAt: Long, plannedMillis: Long, completed: Boolean) {
            val i = rows.indexOfFirst { it.id == id }
            if (i < 0) return
            rows[i] = rows[i].copy(
                endedAt = endedAt,
                plannedMillis = plannedMillis,
                completed = completed,
                ongoing = false,
            )
        }

        override suspend fun touchOngoing(id: Long, endedAt: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i < 0) return
            rows[i] = rows[i].copy(endedAt = endedAt)
        }

        override suspend fun allOngoing(): List<TimerSession> = rows.filter { it.ongoing }

        override suspend fun betweenFinished(from: Long, to: Long) =
            rows.filter { it.startedAt >= from && it.startedAt < to && !it.ongoing }
    }

    /** 内存版 timer_pauses。 */
    private class FakePauseDao : TimerPauseDao {
        val rows = mutableListOf<TimerPause>()
        private var nextId = 1L

        override suspend fun insert(p: TimerPause): Long {
            val id = nextId++
            rows += p.copy(id = id)
            return id
        }

        override suspend fun close(id: Long, toMillis: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(toMillis = toMillis)
        }

        override suspend fun openPause(sessionId: Long): TimerPause? =
            rows.firstOrNull { it.sessionId == sessionId && it.toMillis == 0L }

        override suspend fun forSessions(sessionIds: List<Long>): List<TimerPause> =
            rows.filter { it.sessionId in sessionIds }.sortedBy { it.fromMillis }

        override suspend fun deleteOrphans() {
            // 内存版里没有"别的表"，孤儿 = 全部（由下面的 settleLeftovers 场景自己覆盖）
        }

        override suspend fun deleteForSession(sessionId: Long) {
            rows.removeAll { it.sessionId == sessionId }
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }
    }

    /** 用两个内存 DAO 拼出 [SessionDao]。 */
    private fun dao(session: FakeSessionDao, pause: FakePauseDao) = object : SessionDao {
        override fun sessions() = session
        override fun pauses() = pause
    }

    private fun rt(
        timerId: Long = 1L,
        stopwatch: Boolean = false,
        totalMillis: Long = 25 * 60_000L,
        remainingMillis: Long = 25 * 60_000L,
        overdueMillis: Long = 0L,
        startedAt: Long = 1_000_000L,
        label: String? = "测试计时器",
    ) = TimerRuntime(
        timerId = timerId,
        label = label,
        totalMillis = totalMillis,
        remainingMillis = remainingMillis,
        running = false,
        deadlineEpochMillis = null,
        stopwatch = stopwatch,
        overdueMillis = overdueMillis,
        startedAt = startedAt,
        // 正计时的 `stopwatchSegmentMillis` 是计算属性 = remaining - base，
        // 这里 base 取 0，于是本段净计时就等于 remainingMillis。
        stopwatchBaseMillis = 0L,
    )

    // ---------------- 核心：暂停 + 结束 只能有一条 ----------------

    @Test
    fun `暂停后再结束 归档里只有一条`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 1_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        // 跑了 10 分钟 → 暂停
        clock = 1_000_000L + 600_000L
        val paused = rt(remainingMillis = 25 * 60_000L - 600_000L)
        archiver.onPause(paused)

        assertEquals("暂停应写一条占位行", 1, sessions.rows.size)
        assertTrue("占位行必须是 ongoing", sessions.rows[0].ongoing)
        assertEquals("暂停应开一个未封口区间", 1, pauses.rows.size)
        assertEquals(0L, pauses.rows[0].toMillis)

        // 继续跑 5 分钟 → 恢复
        clock += 300_000L
        archiver.onResume(1L)
        assertEquals("恢复应封口区间", clock, pauses.rows[0].toMillis)

        // 再跑 5 分钟 → 结束
        clock += 300_000L
        archiver.finish(
            rt(remainingMillis = 25 * 60_000L - 1_200_000L),
            completed = false,
        )

        assertEquals("**关键**：同一次计时只能有一条归档", 1, sessions.rows.size)
        val row = sessions.rows[0]
        assertTrue("结束后不再是 ongoing", !row.ongoing)
        assertEquals("实际时长应含全部跑过的时间", 1_200_000L, row.endedAt - row.startedAt)
        assertEquals("计划时长保持原值", 25 * 60_000L, row.plannedMillis)
    }

    @Test
    fun `暂停后重置 也归档一条完整记录`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 2_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 600_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 2_000_000L))

        // 暂停中直接重置
        clock += 60_000L
        archiver.finish(
            rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 2_000_000L),
            completed = false,
        )

        assertEquals(1, sessions.rows.size)
        assertTrue("重置后归档已完成", !sessions.rows[0].ongoing)
        assertEquals(
            "仍在暂停中的区间要封口，不能留 toMillis=0",
            0,
            pauses.rows.count { it.toMillis == 0L },
        )
    }

    @Test
    fun `从未暂停过 结束时写一条完整记录`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 3_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 900_000L
        val id = archiver.finish(
            rt(remainingMillis = 25 * 60_000L - 900_000L, startedAt = 3_000_000L),
            completed = true,
        )

        assertNotNull(id)
        assertEquals(1, sessions.rows.size)
        assertEquals(900_000L, sessions.rows[0].endedAt - sessions.rows[0].startedAt)
        assertTrue("到点应标 completed", sessions.rows[0].completed)
        assertEquals("没有暂停就不该有区间", 0, pauses.rows.size)
    }

    // ---------------- 太短不计 ----------------

    @Test
    fun `倒计时跑不到十分之一 连占位行一起删掉`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 4_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        // 25 分钟计时只跑了 60 秒（< 150 秒门槛）就暂停
        clock += 60_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 60_000L, startedAt = 4_000_000L))
        assertEquals("暂停时先占位", 1, sessions.rows.size)

        // 立刻结束
        clock += 1_000L
        val id = archiver.finish(
            rt(remainingMillis = 25 * 60_000L - 60_000L, startedAt = 4_000_000L),
            completed = false,
        )

        assertNull("太短不该留下归档", id)
        assertEquals("**关键**：占位行也要删掉，不然归档里会有一条 60 秒的记录", 0, sessions.rows.size)
        assertEquals("暂停区间一并清理", 0, pauses.rows.size)
    }

    @Test
    fun `正计时不到 30 秒不计`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 5_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 29_000L
        val id = archiver.finish(
            rt(
                stopwatch = true,
                totalMillis = 0L,
                remainingMillis = 29_000L,
                startedAt = 5_000_000L,
            ),
            completed = false,
        )

        assertNull(id)
        assertEquals(0, sessions.rows.size)
    }

    @Test
    fun `正计时超过 30 秒计入`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 6_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 45_000L
        val id = archiver.finish(
            rt(
                stopwatch = true,
                totalMillis = 0L,
                remainingMillis = 45_000L,
                startedAt = 6_000_000L,
            ),
            completed = false,
        )

        assertNotNull(id)
        assertEquals(1, sessions.rows.size)
        assertEquals("正计时的 planned 应等于实际", 45_000L, sessions.rows[0].plannedMillis)
    }

    // ---------------- 重复暂停不刷多条 ----------------

    @Test
    fun `重复暂停不会写出第二条占位行`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 7_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 600_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 7_000_000L))
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 7_000_000L))

        assertEquals("重复暂停只应有一条占位行", 1, sessions.rows.size)
        assertEquals("也只应有一个暂停区间", 1, pauses.rows.size)
    }

    // ---------------- 进程重启后的清理 ----------------

    @Test
    fun `残留的进行中记录会被收尾`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 8_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 1_000_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 1_000_000L, startedAt = 8_000_000L))
        assertEquals(1, sessions.rows.count { it.ongoing })

        // 模拟进程被杀后重启：新实例看到残留的 ongoing
        val archiver2 = SessionArchiver(dao(sessions, pauses)) { clock }
        archiver2.settleLeftovers { false }

        assertEquals("重启后不该还有 ongoing", 0, sessions.rows.count { it.ongoing })
        assertEquals("**关键**：收尾不能新增记录", 1, sessions.rows.size)
        assertEquals("未封口区间要关掉", 0, pauses.rows.count { it.toMillis == 0L })
    }

    /**
     * **这条是重点**：进程正常退出时，跑着的计时器会被 runtime_snapshots 恢复回来
     * 继续走。这条会话还没结束，`settleLeftovers` 绝不能把它标成完成 ——
     * 否则用户重启后再暂停/结束，归档里会多出一条重复记录。
     */
    @Test
    fun `仍在运行的会话不会被收尾`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 9_500_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 600_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 9_500_000L))
        assertEquals(1, sessions.rows.count { it.ongoing })

        // 模拟重启：该计时器的运行时被快照恢复，仍然存在
        val archiver2 = SessionArchiver(dao(sessions, pauses)) { clock }
        archiver2.settleLeftovers { timerId -> timerId == 1L }

        assertEquals("**关键**：还在跑的会话不能被收尾", 1, sessions.rows.count { it.ongoing })
        assertEquals("也不该新增记录", 1, sessions.rows.size)
        assertEquals("暂停区间也不该被封口", 1, pauses.rows.count { it.toMillis == 0L })
    }

    /**
     * **跨轮复用的坑**：`PanelRunner.reset()` 把计时复位，用户接着再跑一轮
     * （`start()` 会重写 `startedAt`）。如果 `onPause` 只按 timerId 查占位行，
     * 新一轮的暂停会挂到上一轮那条**已经收尾**的会话上 ——
     * 表现为"重置过一次之后，暂停就不产生新记录了"。
     */
    @Test
    fun `重置后新一轮的暂停会新建记录而不是复用旧会话`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 10_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        // 第一轮：跑 10 分钟 → 暂停 → 重置（收尾）
        clock += 600_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 10_000_000L))
        clock += 1_000L
        archiver.finish(
            rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 10_000_000L),
            completed = false,
        )
        assertEquals("第一轮一条", 1, sessions.rows.size)

        // 第二轮：重置后重新开始，startedAt 变了
        clock += 120_000L
        val secondStart = clock
        clock += 600_000L
        archiver.onPause(
            rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = secondStart),
        )

        assertEquals("**关键**：第二轮要新建一条，不能复用第一轮", 2, sessions.rows.size)
        assertTrue("新一轮那条应是 ongoing", sessions.rows[1].ongoing)
        assertEquals("新一轮的 startedAt 是新的", secondStart, sessions.rows[1].startedAt)
        assertTrue("第一轮那条保持已完成", !sessions.rows[0].ongoing)
    }

    /**
     * 真实路径：全屏页的「开始」在已暂停状态下走的是 `clear + start`，
     * **不会先调 finish**。于是上一轮的占位行留在库里、映射也还在，
     * 新一轮暂停若按 timerId 直接复用，就会把新数据写进旧会话。
     *
     * 防护靠 onPause 比较 `startedAt`：不一致就丢弃映射、新建一条。
     */
    @Test
    fun `未收尾就重新开始时 新一轮暂停不复用旧会话`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 20_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        // 第一轮：暂停（写占位行），但**不 finish**
        clock += 600_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = 20_000_000L))
        assertEquals(1, sessions.rows.size)
        assertTrue(sessions.rows[0].ongoing)

        // 用户没点结束，直接「开始」→ clear + start，startedAt 变了
        clock += 60_000L
        val newStart = clock
        clock += 600_000L
        archiver.onPause(
            rt(remainingMillis = 25 * 60_000L - 600_000L, startedAt = newStart),
        )

        assertEquals("必须新建一条，而不是复用第一轮", 2, sessions.rows.size)
        assertEquals("新记录的 startedAt 是新的", newStart, sessions.rows[1].startedAt)
        assertEquals(
            "旧那条仍是第一轮的区间",
            20_000_000L,
            sessions.rows[0].startedAt,
        )
    }

    /**
     * `deleteTimerRows` 会在删除前统一调 `finishIfNeeded`，而「结束」按钮在那之前
     * 已经调过一次 `finish`。两次都跑不能产生两条记录 —— 否则每次结束都会重复归档。
     */
    @Test
    fun `finish 之后再 finishIfNeeded 不会多出一条`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 30_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 900_000L
        val rt = rt(remainingMillis = 25 * 60_000L - 900_000L, startedAt = 30_000_000L)
        archiver.finish(rt, completed = false)
        assertEquals(1, sessions.rows.size)

        // deleteTimerRows 里会再调一次
        archiver.finishIfNeeded(rt)
        assertEquals("**关键**：不应产生第二条", 1, sessions.rows.size)
    }

    /**
     * 反过来：没归档就直接删（`deleteTimerRows` 的其余 4 个入口），
     * 必须补出一条，否则跑了多久都白跑。
     */
    @Test
    fun `没归档就删除时 finishIfNeeded 会补一条`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 40_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        clock += 900_000L
        val rt = rt(remainingMillis = 25 * 60_000L - 900_000L, startedAt = 40_000_000L)
        archiver.finishIfNeeded(rt)

        assertEquals("跑了 15 分钟就删，应该留下一条", 1, sessions.rows.size)
        assertEquals(900_000L, sessions.rows[0].endedAt - sessions.rows[0].startedAt)
    }

    @Test
    fun `从未开始过不补归档`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        val archiver = SessionArchiver(dao(sessions, pauses)) { 50_000_000L }

        // startedAt = 0：运行时还没真正开始
        archiver.finishIfNeeded(rt(startedAt = 0L))
        assertEquals(0, sessions.rows.size)

        // 满格未跑：也没有可记的
        archiver.finishIfNeeded(rt(remainingMillis = 25 * 60_000L, startedAt = 1_000L))
        assertEquals("满格未跑不该产生记录", 0, sessions.rows.size)
    }

    /**
     * **真机抓到的重复归档**。
     *
     * 正计时暂停 → 恢复 → 结束，归档里出现了两条区间**完全相同**的记录
     * （id=10 与 id=11）。根因：`PanelRunner.resume()` 会把 `startedAt` 挪到"现在"
     * （为了让每一段的区间不重叠），于是「结束」按钮与 `deleteTimerRows` 里的
     * `finishIfNeeded` 两次调用拿到的是**不同**的 `startedAt`，
     * 原来用 `startedAt` 当幂等键的检查不命中 → 又插一条。
     *
     * 现在：有占位行时以**占位行**为准，`startedAt` 怎么变都不影响。
     */
    @Test
    fun `恢复后 startedAt 被挪动 结束时仍然只有一条`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        var clock = 60_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { clock }

        // 第一段：跑 200 秒 → 暂停（必须超过 25 分钟的 1/10 = 150 秒门槛，
        // 否则会被 ArchiveRules 判为"太短"而删掉，测不到幂等）
        clock += 200_000L
        val roundStart = 60_000_000L
        archiver.onPause(rt(remainingMillis = 25 * 60_000L - 200_000L, startedAt = roundStart))
        assertEquals(1, sessions.rows.size)

        // 恢复：真实代码里正计时 resume() 会把 startedAt 挪到"现在"。
        // 这里直接构造一个"startedAt 已被挪动"的运行时 —— 关键点是
        // 两次 finish 调用拿到**不同**的 startedAt 时不能再插一条。
        clock += 30_000L
        archiver.onResume(1L)
        val resumedStart = clock
        clock += 100_000L

        // 结束（第一次：archiveAndEndTimer）
        archiver.finish(
            rt(remainingMillis = 25 * 60_000L - 300_000L, startedAt = resumedStart),
            completed = false,
        )
        assertEquals("结束后仍只有一条", 1, sessions.rows.size)
        assertFalse("不再进行中", sessions.rows[0].ongoing)

        // 结束（第二次：deleteTimerRows → finishIfNeeded）
        // 注意 startedAt 与第一次相同（真实代码里同一个 runtime 对象），
        // 但即便不同也必须幂等 —— 有占位行时以占位行为准。
        archiver.finishIfNeeded(
            rt(remainingMillis = 25 * 60_000L - 300_000L, startedAt = resumedStart),
        )
        assertEquals("**关键**：第二次调用不能新增记录", 1, sessions.rows.size)
        assertEquals(
            "归档起点应是最初那一轮，不是 resume 之后的时刻",
            roundStart,
            sessions.rows[0].startedAt,
        )
    }

    /**
     * **真机抓到的荒谬时长**：起点是昨天、只跑了 25 秒却算出 7 小时。
     *
     * 成因：运行时被陈旧快照恢复时把旧 `startedAt` 灌了进来。
     * 即使源头修了，历史数据里仍可能有这种组合，所以归档器要有一道兜底：
     * 起点比「现在 − 已跑时长」还早太多 → 这条数据不自洽，宁可不写。
     */
    @Test
    fun `起点远早于实际开始时刻的记录不写入`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        val now = 1_800_000_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { now }

        // 昨天开始的起点，但这轮只跑了 25 秒（计划 60 秒倒计时）
        val yesterday = now - 24 * 3_600_000L
        val id = archiver.finish(
            rt(
                totalMillis = 60_000L,
                remainingMillis = 60_000L - 25_000L,
                startedAt = yesterday,
            ),
            completed = false,
        )

        assertNull("荒谬区间不该落库", id)
        assertEquals("归档里不该出现这条", 0, sessions.rows.size)
    }

    @Test
    fun `起点与已跑时长自洽时正常写入`() = runTest {
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        val now = 1_900_000_000_000L
        val archiver = SessionArchiver(dao(sessions, pauses)) { now }

        // 正常情况：25 秒前开始、跑了 25 秒
        val id = archiver.finish(
            rt(
                totalMillis = 60_000L,
                remainingMillis = 60_000L - 25_000L,
                startedAt = now - 25_000L,
            ),
            completed = false,
        )
        assertNotNull(id)
        assertEquals(1, sessions.rows.size)
    }

    @Test
    fun `残留记录太短则删除`() = runTest {
        // 直接塞一条只有 3 秒的 ongoing（模拟异常退出）
        val sessions = FakeSessionDao()
        val pauses = FakePauseDao()
        sessions.insert(
            TimerSession(
                label = "闪退的",
                startedAt = 9_000_000L,
                endedAt = 9_003_000L,
                plannedMillis = 25 * 60_000L,
                kind = "Countdown",
                completed = false,
                ongoing = true,
            ),
        )
        val archiver = SessionArchiver(dao(sessions, pauses)) { 9_010_000L }
        archiver.settleLeftovers { false }

        assertEquals("3 秒的残留不该进归档", 0, sessions.rows.size)
    }
}
