package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.DeadlineReminder
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeadlineRestore] 的测试。
 *
 * 为什么值得单独测：这是「提前提醒冷启动失效」这条 P0 的**判定核心**。
 * 本机没有 JDK / 模拟器，UI 与 Room 都跑不起来，所以把「哪些档位该恢复、哪些行该标掉」
 * 抽成纯函数后，用单测真跑它 —— 这是唯一能验证这段逻辑的手段。
 */
class DeadlineRestoreTest {

    private val now = 1_800_000_000_000L

    private fun ddl(deadlineOffsetMillis: Long, id: Long = 1L) = TimerItem(
        id = id,
        label = "交作业",
        durationMillis = deadlineOffsetMillis,
        kind = TimerKind.Deadline,
        deadlineEpochMillis = now + deadlineOffsetMillis,
        locked = true,
    )

    private fun reminder(id: Long, leadMinutes: Long, fired: Boolean = false) =
        DeadlineReminder(id = id, timerId = 1L, leadMinutes = leadMinutes, fired = fired)

    @Test
    fun `还剩 2 小时的 DDL 恢复 30 分钟与 5 分钟两档`() {
        val plan = DeadlineRestore.plan(
            timer = ddl(2 * 60 * 60_000L),
            reminders = listOf(reminder(1, 30), reminder(2, 5)),
            now = now,
        )
        assertEquals(listOf(30L, 5L), plan.liveLeads)
        assertTrue(plan.staleRowIds.isEmpty())
    }

    @Test
    fun `已经过去的档位不挂载而是标成已触发`() {
        // 还剩 20 分钟：提前 30 分钟的档位已经过去了
        val plan = DeadlineRestore.plan(
            timer = ddl(20 * 60_000L),
            reminders = listOf(reminder(1, 30), reminder(2, 15), reminder(3, 5)),
            now = now,
        )
        // 只挂还来得及的两档
        assertEquals(listOf(15L, 5L), plan.liveLeads)
        // 30 分钟那一行直接标掉，避免第一次 tick 一次性把所有档位全响
        assertEquals(listOf(1L), plan.staleRowIds)
    }

    @Test
    fun `已经到点的 DDL 全部档位都标成已触发`() {
        val plan = DeadlineRestore.plan(
            timer = ddl(-60_000L),
            reminders = listOf(reminder(1, 30), reminder(2, 5)),
            now = now,
        )
        assertTrue(plan.liveLeads.isEmpty())
        assertEquals(listOf(1L, 2L), plan.staleRowIds)
    }

    @Test
    fun `已经响过的档位不再恢复`() {
        val plan = DeadlineRestore.plan(
            timer = ddl(2 * 60 * 60_000L),
            reminders = listOf(reminder(1, 5, fired = true), reminder(2, 30)),
            now = now,
        )
        // 5 分钟那一档已经响过（fired=true），重启后不该再挂上去响第二遍；
        // 30 分钟那一档还没响，照常恢复
        assertEquals(listOf(30L), plan.liveLeads)
        // fired 的行既不该挂、也不该再被标一次（它已经是 1 了）
        assertTrue(plan.staleRowIds.isEmpty())
    }

    @Test
    fun `普通倒计时上的提醒行一律标成已触发`() {
        val countdown = TimerItem(id = 9L, durationMillis = 60_000L, kind = TimerKind.Countdown)
        val plan = DeadlineRestore.plan(
            timer = countdown,
            reminders = listOf(reminder(1, 30)),
            now = now,
        )
        assertTrue(plan.liveLeads.isEmpty())
        assertEquals(listOf(1L), plan.staleRowIds)
    }

    @Test
    fun `没有截止时刻的 Deadline 不恢复档位但把提醒行标掉`() {
        // kind 是 Deadline 却没有截止时刻 = 数据已经不自洽（老数据 / 手工改过的备份）。
        // 这种计时器恢复不出运行时，档位自然也挂不上去；
        // 但它的提醒行不能一直停在 fired = 0，否则会被反复处理、并随备份漂来漂去。
        val broken = TimerItem(id = 3L, durationMillis = 60_000L, kind = TimerKind.Deadline)
        val plan = DeadlineRestore.plan(broken, listOf(reminder(1, 30)), now)
        assertTrue(plan.liveLeads.isEmpty())
        assertEquals(listOf(1L), plan.staleRowIds)
    }

    @Test
    fun `重复档位去重后只挂一次`() {
        val plan = DeadlineRestore.plan(
            timer = ddl(60 * 60_000L),
            reminders = listOf(reminder(1, 30), reminder(2, 30)),
            now = now,
        )
        assertEquals(listOf(30L), plan.liveLeads)
    }

    @Test
    fun `行 id 按档位分组`() {
        val map = DeadlineRestore.rowIdsOf(
            listOf(reminder(11, 30), reminder(12, 30), reminder(13, 5)),
        )
        assertEquals(listOf(11L, 12L), map[30L])
        assertEquals(listOf(13L), map[5L])
    }
}
