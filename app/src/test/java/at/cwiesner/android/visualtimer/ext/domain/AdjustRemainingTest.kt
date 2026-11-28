package at.cwiesner.android.visualtimer.ext.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ±5 按钮的语义：**按剩余时间调整，保留已跑进度**。
 *
 * 用户报的问题：
 * > 我计时十五分钟还剩五分钟的时候我暂停加五然后计时变成了二十分钟是啥意思
 *
 * 原来 `+5` 走 `onDurationCommit` → `replaceTotal`，那语义是"改总时长 = 重新开始"：
 * 15 分钟跑剩 5 分钟 → 加 5 变成总时长 20、**剩余也重置成 20**，
 * 之前跑的 10 分钟白跑，反而要多等 15 分钟。
 *
 * 正确语义：剩余 5 → 10，总时长 15 → 20，已跑的 10 分钟不动。
 */
class AdjustRemainingTest {

    private fun runtime(
        totalMillis: Long = 15 * 60_000L,
        remainingMillis: Long = 5 * 60_000L,
        overdueMillis: Long = 0L,
    ) = TimerRuntime(
        timerId = 1L,
        label = "测试",
        totalMillis = totalMillis,
        remainingMillis = remainingMillis,
        running = false,
        deadlineEpochMillis = null,
        overdueMillis = overdueMillis,
        startedAt = 1L,
    )

    @Test
    fun `剩余五分钟加五 变成剩余十分钟 总时长二十分钟`() = runTest {
        val runner = PanelRunner(backgroundScope)
        runner.applyRestored(listOf(runtime()))

        runner.adjustRemaining(1L, 10 * 60_000L)

        val r = runner.get(1L)!!
        assertEquals("剩余应变成 10 分钟", 10 * 60_000L, r.remainingMillis)
        assertEquals("总时长应变成 20 分钟（15 + 5）", 20 * 60_000L, r.totalMillis)
        // 已跑 = 15 − 5 = 10 分钟，加完剩余 10 → 总 20，已跑那段没变
        assertEquals(
            "已跑的 10 分钟必须保持不变",
            10 * 60_000L,
            r.totalMillis - r.remainingMillis,
        )
    }

    @Test
    fun `剩余五分钟减五 被夹到一分钟`() = runTest {
        val runner = PanelRunner(backgroundScope)
        runner.applyRestored(listOf(runtime()))

        // 界面层会把结果 clamp 到至少 1 分钟，这里直接传 1 分钟验证
        runner.adjustRemaining(1L, 60_000L)

        val r = runner.get(1L)!!
        assertEquals(60_000L, r.remainingMillis)
        assertEquals("总时长 = 已跑 10 分钟 + 1 分钟", 11 * 60_000L, r.totalMillis)
    }

    @Test
    fun `不再是重新开始 进度得以保留`() = runTest {
        val runner = PanelRunner(backgroundScope)
        // 跑了一大半：总 60 分、剩 3 分（已跑 57 分）
        runner.applyRestored(listOf(runtime(totalMillis = 60 * 60_000L, remainingMillis = 3 * 60_000L)))

        runner.adjustRemaining(1L, 8 * 60_000L)

        val r = runner.get(1L)!!
        assertEquals(8 * 60_000L, r.remainingMillis)
        assertEquals("总时长 = 已跑 57 分 + 8 分 = 65 分", 65 * 60_000L, r.totalMillis)
        // 关键：remaining 不能被重置成 total（那是 replaceTotal 的行为）
        assertTrue("剩余必须远小于总时长，说明进度没被清", r.remainingMillis < r.totalMillis)
    }

    @Test
    fun `超时状态下加到有剩余 会清掉超时`() = runTest {
        val runner = PanelRunner(backgroundScope)
        // 已超时：剩余 0、超时 2 分钟
        runner.applyRestored(
            listOf(runtime(totalMillis = 15 * 60_000L, remainingMillis = 0L, overdueMillis = 120_000L)),
        )

        runner.adjustRemaining(1L, 5 * 60_000L)

        val r = runner.get(1L)!!
        assertEquals(5 * 60_000L, r.remainingMillis)
        assertEquals("有剩余了就不该还算超时", 0L, r.overdueMillis)
    }

    @Test
    fun `运行时不存在时安全返回`() = runTest {
        val runner = PanelRunner(backgroundScope)
        // 不该抛异常
        runner.adjustRemaining(999L, 60_000L)
        assertEquals(null, runner.get(999L))
    }
}
