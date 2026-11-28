package at.cwiesner.android.visualtimer.ext.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档门槛的用户语义测试。
 *
 * 需求原话：
 * > 开始与暂停/重置/停止间小于 1/10 时长不计。
 * > 正计时开始与暂停/重置/停止间小于 30 秒不计。
 *
 * 这两条是**产品规则**，边界值必须逐条钉死：差 1 毫秒就该有不同结论。
 */
class ArchiveRulesTest {

    // ---------------- 正计时：30 秒绝对下限 ----------------

    @Test
    fun `正计时 29 秒 999 不计`() {
        assertFalse(
            ArchiveRules.shouldArchive(
                stopwatch = true,
                elapsedMillis = 29_999L,
                plannedMillis = 0L,
            ),
        )
    }

    @Test
    fun `正计时正好 30 秒计入`() {
        assertTrue(
            ArchiveRules.shouldArchive(
                stopwatch = true,
                elapsedMillis = 30_000L,
                plannedMillis = 0L,
            ),
        )
    }

    @Test
    fun `正计时的门槛与计划时长无关`() {
        // 正计时没有计划时长这个概念，传什么都应只看 30 秒
        assertTrue(
            ArchiveRules.shouldArchive(
                stopwatch = true,
                elapsedMillis = 30_000L,
                plannedMillis = 999_999_999L,
            ),
        )
    }

    // ---------------- 倒计时：计划时长的 1/10 ----------------

    @Test
    fun `25 分钟倒计时跑不满 150 秒不计`() {
        val planned = 25 * 60_000L      // 1_500_000
        assertFalse(
            ArchiveRules.shouldArchive(
                stopwatch = false,
                elapsedMillis = 149_999L,   // 差 1 毫秒到 1/10
                plannedMillis = planned,
            ),
        )
    }

    @Test
    fun `25 分钟倒计时正好 150 秒计入`() {
        val planned = 25 * 60_000L
        assertTrue(
            ArchiveRules.shouldArchive(
                stopwatch = false,
                elapsedMillis = 150_000L,
                plannedMillis = planned,
            ),
        )
    }

    @Test
    fun `1 分钟倒计时只要 6 秒就计入`() {
        assertTrue(
            ArchiveRules.shouldArchive(
                stopwatch = false,
                elapsedMillis = 6_000L,
                plannedMillis = 60_000L,
            ),
        )
    }

    @Test
    fun `0 毫秒任何情况都不计`() {
        assertFalse(ArchiveRules.shouldArchive(true, 0L, 0L))
        assertFalse(ArchiveRules.shouldArchive(false, 0L, 60_000L))
    }

    @Test
    fun `计划时长异常为 0 时退回 30 秒下限`() {
        assertFalse(ArchiveRules.shouldArchive(false, 29_999L, 0L))
        assertTrue(ArchiveRules.shouldArchive(false, 30_000L, 0L))
    }

    // ---------------- 区间并集去重（归档页合计用） ----------------

    @Test
    fun `重叠区间合并后只算一次`() {
        val merged = mergeIntervals(listOf(0L to 100L, 50L to 150L))
        assertEquals(listOf(0L to 150L), merged)
        assertEquals(150L, mergedDurationMillis(listOf(0L to 100L, 50L to 150L)))
    }

    @Test
    fun `相邻区间合并`() {
        assertEquals(listOf(0L to 200L), mergeIntervals(listOf(0L to 100L, 100L to 200L)))
    }

    @Test
    fun `完全包含的区间不重复计`() {
        assertEquals(listOf(0L to 300L), mergeIntervals(listOf(0L to 300L, 100L to 200L)))
    }

    @Test
    fun `不相交的区间各算各的`() {
        val iv = listOf(0L to 100L, 500L to 600L)
        assertEquals(iv, mergeIntervals(iv))
        assertEquals(200L, mergedDurationMillis(iv))
    }

    @Test
    fun `空与非法区间被忽略`() {
        assertEquals(emptyList<Pair<Long, Long>>(), mergeIntervals(emptyList()))
        assertEquals(emptyList<Pair<Long, Long>>(), mergeIntervals(listOf(100L to 100L, 200L to 50L)))
    }

    @Test
    fun `乱序输入也能正确合并`() {
        val iv = listOf(500L to 600L, 0L to 100L, 50L to 550L)
        assertEquals(listOf(0L to 600L), mergeIntervals(iv))
        assertEquals(600L, mergedDurationMillis(iv))
    }
}
