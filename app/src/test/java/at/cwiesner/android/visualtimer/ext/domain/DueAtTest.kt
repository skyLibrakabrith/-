package at.cwiesner.android.visualtimer.ext.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「到点时刻」的推导。
 *
 * 这个值用来注册 `AlarmManager` 闹钟 —— 算错了要么提前响、要么永不响。
 *
 * 三种计时器的语义完全不同：
 * - DDL：终点由**绝对时刻**决定（「今晚 8 点前交」）
 * - 倒计时：剩余时间是准的（关机期间也过去了）
 * - 正计时：**永远不到点**
 */
class DueAtTest {

    private fun rt(
        stopwatch: Boolean = false,
        remainingMillis: Long = 60_000L,
        deadlineEpochMillis: Long? = null,
    ) = TimerRuntime(
        timerId = 1L,
        label = "测试",
        totalMillis = if (stopwatch) 0L else 300_000L,
        remainingMillis = remainingMillis,
        running = true,
        deadlineEpochMillis = deadlineEpochMillis,
        stopwatch = stopwatch,
        startedAt = 1L,
    )

    @Test
    fun `DDL 用绝对截止时刻`() {
        val deadline = 1_800_000_000_000L
        assertEquals(deadline, dueAtMillisOf(rt(deadlineEpochMillis = deadline)))
    }

    @Test
    fun `倒计时用现在加剩余`() {
        val now = 1_700_000_000_000L
        val due = dueAtMillisOf(rt(remainingMillis = 90_000L), nowMillis = now)
        assertEquals(now + 90_000L, due)
    }

    @Test
    fun `正计时永远不到点`() {
        assertNull("正计时没有终点", dueAtMillisOf(rt(stopwatch = true, remainingMillis = 999_999L)))
    }

    @Test
    fun `剩余为零的倒计时不再排闹钟`() {
        // 已经到点：交给 tick 处理，不需要闹钟
        assertNull(dueAtMillisOf(rt(remainingMillis = 0L)))
    }

    @Test
    fun `DDL 优先于剩余时间`() {
        // DDL 的 remainingMillis 是算出来的，但终点以绝对时刻为准
        val deadline = 1_800_000_000_000L
        val due = dueAtMillisOf(
            rt(remainingMillis = 1L, deadlineEpochMillis = deadline),
            nowMillis = 1_700_000_000_000L,
        )
        assertEquals(deadline, due)
    }
}
