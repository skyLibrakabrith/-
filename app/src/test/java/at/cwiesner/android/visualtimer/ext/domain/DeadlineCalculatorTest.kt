package at.cwiesner.android.visualtimer.ext.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [DeadlineCalculator] 的单元测试。
 *
 * 这些都是纯函数，**不碰 Android**，所以能在 CI 的 JVM 上直接跑 ——
 * 这台开发机没有 JDK，单元测试是唯一能"真验证"而不是靠 CI 编译碰运气的手段。
 *
 * 重点覆盖 24 小时那条边界：超过它就不交给精确闹钟，改用 setAlarmClock。
 */
class DeadlineCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-11-25T04:00:00Z")  // 12:00 +08:00

    // ---------------- remainingUntil ----------------

    @Test
    fun `目标在未来时返回剩余毫秒且不需要 alarm clock`() {
        val target = now.plusSeconds(3600)  // 1 小时
        val r = DeadlineCalculator.remainingUntil(target, now)
        assertTrue(r is DeadlineCalculator.Result.Ok)
        r as DeadlineCalculator.Result.Ok
        assertEquals(3_600_000L, r.remainingMillis)
        assertEquals(false, r.requiresAlarmClock)
    }

    /**
     * 24h 边界：恰好等于阈值**不算**超长，要严格大于才切 alarmClock。
     * 这条很容易写成 >=，写错会导致整点 24 小时的 DDL 走错分支。
     */
    @Test
    fun `恰好等于 24 小时不需要 alarm clock`() {
        val target = now.plusMillis(DeadlineCalculator.MAX_RELIABLE_MILLIS)
        val r = DeadlineCalculator.remainingUntil(target, now)
        r as DeadlineCalculator.Result.Ok
        assertEquals(DeadlineCalculator.MAX_RELIABLE_MILLIS, r.remainingMillis)
        assertEquals(false, r.requiresAlarmClock)
    }

    @Test
    fun `超过 24 小时一秒就需要 alarm clock`() {
        val target = now.plusMillis(DeadlineCalculator.MAX_RELIABLE_MILLIS + 1000L)
        val r = DeadlineCalculator.remainingUntil(target, now)
        r as DeadlineCalculator.Result.Ok
        assertEquals(true, r.requiresAlarmClock)
    }

    /** 目标等于此刻 → 已经过去（不是 Ok(0)）。 */
    @Test
    fun `目标等于当前时刻视为已过期`() {
        assertEquals(
            DeadlineCalculator.Result.Past,
            DeadlineCalculator.remainingUntil(now, now),
        )
    }

    @Test
    fun `目标在过去视为已过期`() {
        assertEquals(
            DeadlineCalculator.Result.Past,
            DeadlineCalculator.remainingUntil(now.minusSeconds(1), now),
        )
    }

    // ---------------- toInstant ----------------

    @Test
    fun `日期与时刻按指定时区合成 Instant`() {
        val i = DeadlineCalculator.toInstant(
            LocalDate.of(2026, 11, 25),
            LocalTime.of(17, 0),
            zone,
        )
        // 2026-11-25 17:00 +08:00 == 09:00 UTC
        assertEquals(Instant.parse("2026-11-25T09:00:00Z"), i)
    }

    // ---------------- describe ----------------

    @Test
    fun `今天的目标描述为今天加时刻`() {
        val target = DeadlineCalculator.toInstant(LocalDate.of(2026, 11, 25), LocalTime.of(17, 0), zone)
        assertEquals("今天 17:00", DeadlineCalculator.describe(target, now, zone))
    }

    @Test
    fun `明天的目标描述为明天加时刻`() {
        val target = DeadlineCalculator.toInstant(LocalDate.of(2026, 11, 26), LocalTime.of(9, 30), zone)
        assertEquals("明天 09:30", DeadlineCalculator.describe(target, now, zone))
    }

    @Test
    fun `更远的目标描述为月日加时刻`() {
        val target = DeadlineCalculator.toInstant(LocalDate.of(2026, 12, 1), LocalTime.of(14, 0), zone)
        assertEquals("12-01 14:00", DeadlineCalculator.describe(target, now, zone))
    }

    /** 边界：昨天不该被描述成「今天/明天」。 */
    @Test
    fun `过去的目标描述为月日`() {
        val target = DeadlineCalculator.toInstant(LocalDate.of(2026, 11, 24), LocalTime.of(8, 5), zone)
        assertEquals("11-24 08:05", DeadlineCalculator.describe(target, now, zone))
    }

    // ---------------- formatRemaining ----------------

    @Test
    fun `零与负数格式化为 0s`() {
        assertEquals("0s", DeadlineCalculator.formatRemaining(0L))
        assertEquals("0s", DeadlineCalculator.formatRemaining(-5000L))
    }

    @Test
    fun `不足一分钟只显示秒`() {
        assertEquals("12s", DeadlineCalculator.formatRemaining(12_000L))
        // 999ms 向下取整成 0 秒
        assertEquals("0s", DeadlineCalculator.formatRemaining(999L))
    }

    @Test
    fun `分钟级补零秒`() {
        assertEquals("4m05s", DeadlineCalculator.formatRemaining(4 * 60_000L + 5_000L))
    }

    @Test
    fun `小时级补零分钟`() {
        assertEquals("1h23m", DeadlineCalculator.formatRemaining(83 * 60_000L))
    }

    @Test
    fun `整整一小时`() {
        assertEquals("1h00m", DeadlineCalculator.formatRemaining(3_600_000L))
    }
}
