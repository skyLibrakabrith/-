package at.cwiesner.android.visualtimer.ext.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeadlineCreator.planDeadline] 的测试。
 *
 * ## 为什么测这一层而不是整个 createDeadline
 *
 * `createDeadline` 调 Room DAO，没有 Robolectric 就跑不起来；而本机没有 JDK/模拟器，
 * 单测是唯一能真跑的验证手段。所以把「算时长 / 去重提醒 / 判断哪些档位已过去」
 * 抽成纯函数 [DeadlineCreator.planDeadline] 单独测。
 *
 * DAO 写入与 `startUntil` 那部分由真机 adb 验证覆盖 —— 那比单测更可信，
 * 因为它能证明"卡片上的数字确实在变"。
 *
 * ## 这里每一条都对应一个真实症状
 *
 * - `durationMillis 至少 1000` ← 防止选出「已过去的时刻」时建出 0 秒计时器
 * - `alreadyPastLeads` ← 防止创建后第一次 tick 把所有已过去的档位一次性全响
 * - 去重排序 ← 防止备份里出现重复的提醒行、或通知顺序错乱
 */
class DeadlineCreatorTest {

    private val min = 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `正常创建 - 时长与提醒都正确`() {
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 60 * min,
            leads = listOf(5L, 30L),
        )
        assertEquals(60 * min, plan.durationMillis)
        assertEquals(listOf(5L, 30L), plan.reminders)
    }

    @Test
    fun `提醒档位去重并升序`() {
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 120 * min,
            leads = listOf(30L, 5L, 30L, 5L),
        )
        assertEquals(listOf(5L, 30L), plan.reminders)
    }

    @Test
    fun `非正的档位被丢弃`() {
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 60 * min,
            leads = listOf(0L, -5L, 15L),
        )
        assertEquals(listOf(15L), plan.reminders)
    }

    @Test
    fun `没有提醒时 reminders 为空`() {
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 60 * min,
            leads = emptyList(),
        )
        assertTrue(plan.reminders.isEmpty())
        assertTrue(plan.alreadyPastLeads.isEmpty())
    }

    @Test
    fun `截止时刻已过去 - 时长兜底到 1000 毫秒`() {
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now - 10 * min,
            leads = listOf(5L, 30L),
        )
        assertEquals(DeadlineCreator.MIN_DURATION_MILLIS, plan.durationMillis)
        // 只剩 0 分钟，两档都已经过去了
        assertEquals(setOf(5L, 30L), plan.alreadyPastLeads)
    }

    @Test
    fun `截止时刻正好等于现在 - 同样兜底`() {
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now,
            leads = listOf(10L),
        )
        assertEquals(DeadlineCreator.MIN_DURATION_MILLIS, plan.durationMillis)
        assertEquals(setOf(10L), plan.alreadyPastLeads)
    }

    @Test
    fun `已经错过的档位要被标出来 - 否则第一次 tick 全响`() {
        // 距截止 5 分钟，用户设了「提前 10 分钟」和「提前 2 分钟」
        // 10 分钟那档已经错过（剩 5 分 < 10 分），必须标掉
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 5 * min,
            leads = listOf(10L, 2L),
        )
        assertEquals(listOf(2L, 10L), plan.reminders)
        assertEquals(setOf(10L), plan.alreadyPastLeads)
        assertTrue("提前 2 分钟还没到，不该被标", 2L !in plan.alreadyPastLeads)
    }

    @Test
    fun `档位等于剩余分钟时视为已过去 - 与 markLeadsBaseline 口径一致`() {
        // PanelRunner.markLeadsBaseline 的判据是 `lead <= remainingMillis / 60_000`，
        // 取等号时算「已经过去」。这里必须完全一致，否则会出现
        // 「创建时判为未过、第一次 tick 却立刻触发」的错位。
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 10 * min,
            leads = listOf(10L),
        )
        assertEquals(setOf(10L), plan.alreadyPastLeads)
    }

    @Test
    fun `还剩很多时没有任何档位算过去`() {
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 24 * 60 * min,
            leads = listOf(5L, 60L, 720L),
        )
        assertTrue(plan.alreadyPastLeads.isEmpty())
    }

    @Test
    fun `秒级零头不影响档位判断`() {
        // 剩 10 分 30 秒 -> remainMin = 10（整数除法）
        val plan = DeadlineCreator.planDeadline(
            now = now,
            deadlineAt = now + 10 * min + 30_000L,
            leads = listOf(10L, 11L),
        )
        assertEquals(10 * min + 30_000L, plan.durationMillis)
        // 按 `lead >= remainMin` 判据：10 >= 10 已过，11 >= 10 也已过
        // （要求提前 11 分钟，可只剩 10 分 30 秒 —— 这档同样已经错过了）
        assertEquals(setOf(10L, 11L), plan.alreadyPastLeads)
    }
}
