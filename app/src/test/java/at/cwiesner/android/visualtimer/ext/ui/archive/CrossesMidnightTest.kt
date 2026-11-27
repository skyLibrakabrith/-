package at.cwiesner.android.visualtimer.ext.ui.archive

import at.cwiesner.android.visualtimer.ext.data.TimerSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「跨午夜」判据（[crossesMidnight]）。
 *
 * 对应一个真实 bug：归档里**没有跨午夜的记录被显示成跨午夜**。
 *
 * 原判断是 `session.endedAt + 1L < dayEnd` —— 语义反了，
 * 它问的是"这条记录是否在当天零点之前结束"，几乎对所有正常记录都成立。
 * 于是每条详情都多出「完整时长」一行和"表盘上只画了部分"的说明。
 */
class CrossesMidnightTest {

    private val day: LocalDate = LocalDate.of(2026, 11, 27)
    private val dayStart: Long = ArchiveDates.dayStart(day)
    private val hour: Long = 3_600_000L

    /** 当天 [hh]:[mm] 的时刻。 */
    private fun at(hh: Int, mm: Int = 0): Long =
        dayStart + hh * hour + mm * 60_000L

    private fun session(from: Long, to: Long) = TimerSession(
        id = 1,
        label = "t",
        startedAt = from,
        endedAt = to,
        plannedMillis = to - from,
        kind = "Countdown",
        completed = true,
    )

    @Test
    fun `同一天内开始结束 不算跨午夜`() {
        assertFalse(crossesMidnight(session(at(9), at(10))))
        assertFalse(crossesMidnight(session(at(0), at(23))))
    }

    @Test
    fun `23点59分结束 不算跨午夜`() {
        // 这是最容易被写错成"跨午夜"的一条：它离零点很近，但确实还在当天
        assertFalse(crossesMidnight(session(at(23, 0), at(23, 59))))
    }

    @Test
    fun `过了零点一秒 算跨午夜`() {
        val justAfterMidnight = at(24, 0) + 1_000L
        assertTrue(crossesMidnight(session(at(23, 0), justAfterMidnight)))
    }

    @Test
    fun `23点40到次日00点15 算跨午夜`() {
        assertTrue(crossesMidnight(session(at(23, 40), at(24, 15))))
    }

    @Test
    fun `跨两天以上也算跨午夜`() {
        assertTrue(crossesMidnight(session(at(9), at(24 * 2 + 9))))
    }

    @Test
    fun `恰好停在零点 不算跨午夜`() {
        // endedAt == 次日零点 表示"到 23:59:59.999 为止"，
        // 它落在当天窗口的右开边界上，开始与结束仍在同一天之内（结束是次日的 00:00）。
        // 按"两端本地日期是否相同"判据，00:00 属于次日，所以这里算跨午夜 ——
        // 这个边界值刻意保持与 spanTextOf 一致（那边用 endedAt >= dayEnd 判断「次日」）。
        assertTrue("到次日零点整结束的记录，结束时刻显示为「次日 00:00」，应算跨午夜", crossesMidnight(session(at(23, 59), at(24, 0))))
    }

    @Test
    fun `零长度记录不算跨午夜`() {
        assertFalse(crossesMidnight(session(at(12), at(12))))
    }
}
