package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerPause
import at.cwiesner.android.visualtimer.ext.data.TimerPauseDao
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.data.TimerSessionDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
