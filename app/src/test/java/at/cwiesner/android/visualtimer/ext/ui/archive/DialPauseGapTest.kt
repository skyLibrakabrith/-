package at.cwiesner.android.visualtimer.ext.ui.archive

import at.cwiesner.android.visualtimer.ext.data.TimerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆盘「暂停空隙」的几何测试。
 *
 * 用户要求：「一整条，比较明显的暂停时间在圆盘上空着」。
 *
 * 实现方式：一条记录的弧按暂停区间切成多段连续子弧，中间留空。
 * 这里验证切得对不对 —— 真机上"看起来有没有空"还要靠眼睛，
 * 但"切了几段、每段多长、有没有盖住暂停"是能精确断言的。
 */
class DialPauseGapTest {

    private val midnight = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun session(id: Long = 1L, startHour: Double = 10.0, durHours: Double = 2.0) =
        TimerSession(
            id = id,
            label = "t$id",
            startedAt = midnight + (startHour * hour).toLong(),
            endedAt = midnight + ((startHour + durHours) * hour).toLong(),
            plannedMillis = (durHours * hour).toLong(),
            kind = "Countdown",
            completed = true,
        )

    private val dayStart get() = midnight
    private val dayEnd get() = midnight + 24 * hour

    @Test
    fun `没有暂停时一条记录还是一段弧`() {
        val arcs = buildDialArcs(listOf(session()), dayStart, dayEnd)
        assertEquals(1, arcs.size)
    }

    @Test
    fun `暂停一次把一条记录切成两段`() {
        val s = session()   // 10:00 - 12:00
        // 11:00 - 11:30 暂停
        val pauses = mapOf(
            s.id to listOf((midnight + 11 * hour) to (midnight + 11 * hour + hour / 2)),
        )
        val arcs = buildDialArcs(listOf(s), dayStart, dayEnd, pauses)

        assertEquals("中间空掉一段，应该切成两段", 2, arcs.size)
        // 两段总扫过量应等于 (2 小时 - 0.5 小时) / 24 小时
        val swept = arcs.sumOf { it.sweepAngle.toDouble() }
        val expected = (1.5 * hour).toDouble() / (24 * hour).toDouble() * 360.0
        assertEquals(expected, swept, 0.01)

        // 两段必须同环同序（看起来还是同一次计时）
        assertEquals(arcs[0].ring, arcs[1].ring)
        assertEquals(arcs[0].orderIdx, arcs[1].orderIdx)
    }

    @Test
    fun `暂停两段切成三段`() {
        val s = session()   // 10:00 - 12:00
        val pauses = mapOf(
            s.id to listOf(
                (midnight + 10 * hour + 15 * 60_000L) to (midnight + 10 * hour + 30 * 60_000L),
                (midnight + 11 * hour + 15 * 60_000L) to (midnight + 11 * hour + 30 * 60_000L),
            ),
        )
        val arcs = buildDialArcs(listOf(s), dayStart, dayEnd, pauses)
        assertEquals(3, arcs.size)

        val swept = arcs.sumOf { it.sweepAngle.toDouble() }
        val expected = (1.5 * hour).toDouble() / (24 * hour).toDouble() * 360.0
        assertEquals(expected, swept, 0.01)
    }

    @Test
    fun `仍在暂停中的区间算到会话结束`() {
        val s = session()   // 10:00 - 12:00
        // 11:00 开始暂停，至今未恢复（toMillis = 0）
        val pauses = mapOf(s.id to listOf((midnight + 11 * hour) to 0L))
        val arcs = buildDialArcs(listOf(s), dayStart, dayEnd, pauses)

        assertEquals("11:00 之后整段空掉，只剩一段", 1, arcs.size)
        val swept = arcs.sumOf { it.sweepAngle.toDouble() }
        val expected = 1.0 / 24.0 * 360.0   // 只剩 10:00-11:00
        assertEquals(expected, swept, 0.01)
    }

    @Test
    fun `暂停覆盖整段 则该记录不画`() {
        val s = session()
        val pauses = mapOf(s.id to listOf((midnight + 9 * hour) to (midnight + 13 * hour)))
        val arcs = buildDialArcs(listOf(s), dayStart, dayEnd, pauses)
        assertEquals(0, arcs.size)
    }

    @Test
    fun `暂停区间超出记录范围会被裁剪`() {
        val s = session()   // 10:00 - 12:00
        // 暂停覆盖 9:00-13:00，等于整段都没在计时
        val pauses = mapOf(s.id to listOf((midnight + 9 * hour) to (midnight + 13 * hour)))
        assertEquals(0, buildDialArcs(listOf(s), dayStart, dayEnd, pauses).size)

        // 只覆盖边缘一半：10:00-11:00 暂停 → 只剩 11:00-12:00
        val pauses2 = mapOf(s.id to listOf((midnight + 9 * hour) to (midnight + 11 * hour)))
        val arcs2 = buildDialArcs(listOf(s), dayStart, dayEnd, pauses2)
        assertEquals(1, arcs2.size)
        assertEquals(1.0 / 24.0 * 360.0, arcs2[0].sweepAngle.toDouble(), 0.01)
    }

    @Test
    fun `多条记录各自有自己的暂停`() {
        val a = session(id = 1L, startHour = 10.0, durHours = 1.0)
        val b = session(id = 2L, startHour = 14.0, durHours = 1.0)
        val pauses = mapOf(
            a.id to listOf((midnight + 10 * hour) to (midnight + 10 * hour + 15 * 60_000L)),
        )
        val arcs = buildDialArcs(listOf(a, b), dayStart, dayEnd, pauses)

        // a 被切成 1 段（15 分钟从开头切掉后剩一段），b 1 段
        assertEquals(2, arcs.size)
        val aArcs = arcs.filter { it.session.id == a.id }
        val bArcs = arcs.filter { it.session.id == b.id }
        assertEquals(1, aArcs.size)
        assertEquals(1, bArcs.size)
        assertTrue("a 的暂停让它的弧变短", aArcs[0].sweepAngle < bArcs[0].sweepAngle)
    }
}
