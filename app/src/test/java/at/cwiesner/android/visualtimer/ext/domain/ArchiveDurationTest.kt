package at.cwiesner.android.visualtimer.ext.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 归档记录的实际时长：[elapsedMillisForArchive]。
 *
 * 一个 5 分钟的倒计时，到点后没被立刻处理、又跑了 3 分钟才停 ——
 * 那次计时实际持续了 8 分钟。归档是"当时发生了什么"的事实记录，
 * 只记计划时长会让用户看到"5 分钟"，而他明明看着它超时了 3 分钟。
 *
 * 这个函数被两条写入路径共用（`ExtApplication` 的到点归档、
 * `PanelsScreen` 的「结束」按钮），所以直接测生产函数本身，
 * 不在测试里复制一份算式（复制品测不出漂移）。
 */
class ArchiveDurationTest {

    private val minute = 60_000L

    private fun elapsed(
        totalMillis: Long = 0L,
        remainingMillis: Long = 0L,
        overdueMillis: Long = 0L,
        stopwatch: Boolean = false,
        stopwatchSegmentMillis: Long = 0L,
    ): Long = elapsedMillisForArchive(
        totalMillis = totalMillis,
        remainingMillis = remainingMillis,
        overdueMillis = overdueMillis,
        stopwatch = stopwatch,
        stopwatchSegmentMillis = stopwatchSegmentMillis,
    )

    @Test
    fun `正常到点 没有超时 等于计划时长`() {
        assertEquals(5 * minute, elapsed(totalMillis = 5 * minute))
    }

    @Test
    fun `超时三分钟 实际时长要多算这三分钟`() {
        // 本组测试的核心：5 分钟计划 + 3 分钟超时 = 8 分钟
        assertEquals(
            8 * minute,
            elapsed(totalMillis = 5 * minute, overdueMillis = 3 * minute),
        )
    }

    @Test
    fun `中途暂停结束 只算已经走过的那部分`() {
        // 5 分钟计划，走了 2 分钟就结束（剩余 3 分钟，无超时）
        assertEquals(
            2 * minute,
            elapsed(totalMillis = 5 * minute, remainingMillis = 3 * minute),
        )
    }

    @Test
    fun `暂停在超时中 超时量照算`() {
        assertEquals(
            9 * minute,
            elapsed(totalMillis = 5 * minute, overdueMillis = 4 * minute),
        )
    }

    @Test
    fun `正计时用本段时长 与超时无关`() {
        // 正计时永远不会"超时"，它的时长就是这一段累计
        assertEquals(
            7 * minute,
            elapsed(stopwatch = true, stopwatchSegmentMillis = 7 * minute),
        )
    }

    @Test
    fun `剩余大于计划时不会算出负数`() {
        // 数据不自洽（比如改了时长但运行时还没同步）时不能返回负值
        assertEquals(0L, elapsed(totalMillis = 0L, remainingMillis = 5 * minute))
    }

    @Test
    fun `正计时负数输入也被钳到零`() {
        assertEquals(0L, elapsed(stopwatch = true, stopwatchSegmentMillis = -1L))
    }
}
