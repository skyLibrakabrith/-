package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.TimerPause
import at.cwiesner.android.visualtimer.ext.data.TimerPauseDao
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.data.TimerSessionDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列暂停归档的状态机测试。
 *
 * 用户要求「序列暂停也归档」，并且要和计时器一致：**一次运行只有一条记录**。
 * 这里把「暂停 → 继续 → 结束」的全过程钉死。
 */
class SequenceSessionArchiverTest {

    private class FakeSessionDao : TimerSessionDao {
        val rows = mutableListOf<TimerSession>()
        private var nextId = 1L
        override suspend fun insert(s: TimerSession): Long {
            val id = nextId++; rows += s.copy(id = id); return id
        }
        override suspend fun allOnce() = rows.toList()
        override fun observeBetween(from: Long, to: Long) = throw UnsupportedOperationException()
        override suspend fun between(from: Long, to: Long) =
            rows.filter { it.startedAt >= from && it.startedAt < to }
        override suspend fun earliest(): Long? = rows.minOfOrNull { it.startedAt }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun finishOngoing(id: Long, endedAt: Long, plannedMillis: Long, completed: Boolean) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(
                endedAt = endedAt, plannedMillis = plannedMillis, completed = completed, ongoing = false,
            )
        }
        override suspend fun touchOngoing(id: Long, endedAt: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(endedAt = endedAt)
        }
        override suspend fun allOngoing() = rows.filter { it.ongoing }
        override suspend fun betweenFinished(from: Long, to: Long) =
            rows.filter { it.startedAt >= from && it.startedAt < to && !it.ongoing }
    }

    private class FakePauseDao : TimerPauseDao {
        val rows = mutableListOf<TimerPause>()
        private var nextId = 1L
        override suspend fun insert(p: TimerPause): Long { val id = nextId++; rows += p.copy(id = id); return id }
        override suspend fun close(id: Long, toMillis: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(toMillis = toMillis)
        }
        override suspend fun openPause(sessionId: Long) =
            rows.firstOrNull { it.sessionId == sessionId && it.toMillis == 0L }
        override suspend fun forSessions(sessionIds: List<Long>) =
            rows.filter { it.sessionId in sessionIds }.sortedBy { it.fromMillis }
        override suspend fun deleteOrphans() {}
        override suspend fun deleteForSession(sessionId: Long) { rows.removeAll { it.sessionId == sessionId } }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
    }

    private fun dao(s: FakeSessionDao, p: FakePauseDao) = object : SessionDao {
        override fun sessions() = s
        override fun pauses() = p
    }

    private fun pause(
        seqId: Long = 7L,
        stepIndex: Int = 0,
        durationMillis: Long = 60_000L,
        elapsed: Long = 20_000L,
        at: Long = 1_000_000L,
    ) = SequencePause(
        sequenceId = seqId,
        sequenceName = "写周报",
        stepIndex = stepIndex,
        step = Step(id = 1L, sequenceId = seqId, durationMillis = durationMillis, label = "收集", order = 0),
        stepElapsedMillis = elapsed,
        eventAtMillis = at,
    )

    @Test
    fun `暂停写一条 ongoing 占位行 + 未封口区间`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        val archiver = SequenceSessionArchiver(dao(s, p)) { 1_000_000L }

        assertTrue(archiver.onPause(pause()))

        assertEquals(1, s.rows.size)
        assertTrue("必须是 ongoing", s.rows[0].ongoing)
        assertTrue("label 应含序列名与阶段名", s.rows[0].label!!.contains("写周报"))
        assertEquals("区间起点 = 暂停时刻 − 已跑时长", 980_000L, s.rows[0].startedAt)
        assertEquals("区间终点 = 暂停时刻", 1_000_000L, s.rows[0].endedAt)
        assertEquals(1, p.rows.size)
        assertEquals("区间未封口", 0L, p.rows[0].toMillis)
    }

    @Test
    fun `重复暂停不会写出第二条占位行`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        val archiver = SequenceSessionArchiver(dao(s, p)) { 1_000_000L }

        archiver.onPause(pause())
        assertFalse("第二次应返回 false", archiver.onPause(pause(at = 1_010_000L)))

        assertEquals(1, s.rows.size)
    }

    @Test
    fun `继续会封口暂停区间`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        var clock = 1_000_000L
        val archiver = SequenceSessionArchiver(dao(s, p)) { clock }

        archiver.onPause(pause())
        assertEquals(0L, p.rows[0].toMillis)

        clock = 1_050_000L
        archiver.onResume(7L)

        assertEquals("区间应被封口", 1_050_000L, p.rows[0].toMillis)
        assertEquals("记录仍只有一条", 1, s.rows.size)
    }

    @Test
    fun `结束后占位行收尾且不再 ongoing`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        var clock = 1_000_000L
        val archiver = SequenceSessionArchiver(dao(s, p)) { clock }

        archiver.onPause(pause(elapsed = 40_000L))
        clock = 1_100_000L
        archiver.finish(7L)

        assertEquals(1, s.rows.size)
        assertFalse("不再是 ongoing", s.rows[0].ongoing)
        assertEquals("未封口区间要关掉", 0, p.rows.count { it.toMillis == 0L })
    }

    /**
     * **核心**：同一次序列运行，暂停 + 结束**不能**产生两条记录。
     *
     * 这是计时器侧踩过的坑的序列版本 —— 那里的成因是 resume 挪了 startedAt，
     * 这里则是「暂停写占位行」与「结束写阶段记录」可能各写各的。
     */
    @Test
    fun `暂停后结束 总共只有一条占位记录`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        var clock = 1_000_000L
        val archiver = SequenceSessionArchiver(dao(s, p)) { clock }

        archiver.onPause(pause())
        clock += 60_000L
        archiver.onResume(7L)
        clock += 60_000L
        archiver.finish(7L)

        assertEquals("占用行 + 收尾 应仍是一条", 1, s.rows.size)
        assertFalse(s.rows[0].ongoing)
    }

    @Test
    fun `太短的占位行在收尾时被删掉`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        var clock = 1_000_000L
        val archiver = SequenceSessionArchiver(dao(s, p)) { clock }

        // 60 秒的计划阶段只跑了 3 秒（< 6 秒门槛）
        archiver.onPause(pause(durationMillis = 60_000L, elapsed = 3_000L))
        assertEquals(1, s.rows.size)

        archiver.finish(7L)
        assertEquals("低于门槛的占位行应被删", 0, s.rows.size)
        assertEquals("暂停区间一并清理", 0, p.rows.size)
    }

    @Test
    fun `不同序列互不干扰`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        val archiver = SequenceSessionArchiver(dao(s, p)) { 1_000_000L }

        archiver.onPause(pause(seqId = 7L))
        archiver.onPause(pause(seqId = 8L))

        assertEquals("两个序列各一条", 2, s.rows.size)
        archiver.finish(7L)
        assertEquals("收尾 7 不影响 8", 1, s.rows.count { it.ongoing })
    }

    @Test
    fun `forget 清掉映射后可以重新开始`() = runTest {
        val s = FakeSessionDao(); val p = FakePauseDao()
        val archiver = SequenceSessionArchiver(dao(s, p)) { 1_000_000L }

        archiver.onPause(pause())
        archiver.forget(7L)
        assertTrue("映射清掉后应能新建", archiver.onPause(pause(at = 1_100_000L)))
        assertEquals(2, s.rows.size)
    }
}
