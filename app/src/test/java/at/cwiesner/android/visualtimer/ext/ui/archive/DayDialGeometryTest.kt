package at.cwiesner.android.visualtimer.ext.ui.archive

import at.cwiesner.android.visualtimer.ext.data.TimerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表盘几何与导出文本的单元测试。
 *
 * 为什么这些值得测：角度/半径算错了界面**不会崩**，只会悄悄地歪 ——
 * "9 点方向的弧段点不中"这种 bug 只能靠真机一个个试，成本极高。
 * 把纯函数的手算结果钉死，真机只需验证"看起来对不对"。
 *
 * 这一天统一用 UTC 做基准：`2026-11-27T00:00Z` 的 epoch 是 1795737600000。
 * 测试不碰 `ZoneId.systemDefault()`（CI 机器的时区不可控）。
 */
class DayDialGeometryTest {

    /** 2026-11-27 00:00:00 UTC。 */
    private val midnight = 1795737600000L
    private val hour = 3_600_000L
    private val dayEnd = midnight + 24 * hour

    private fun session(
        id: Long,
        startHour: Double,
        durHours: Double,
        label: String? = "t$id",
    ) = TimerSession(
        id = id,
        label = label,
        startedAt = midnight + (startHour * hour).toLong(),
        endedAt = midnight + ((startHour + durHours) * hour).toLong(),
        plannedMillis = (durHours * hour).toLong(),
        kind = "Countdown",
        completed = true,
    )

    // ---------------- 角度基准 ----------------

    @Test
    fun `0点在上方 -90度`() {
        assertEquals(-90f, DayDialGeometry.angleOf(0L, 24 * hour), 0.001f)
    }

    @Test
    fun `6点在3点方向 0度`() {
        assertEquals(0f, DayDialGeometry.angleOf(6 * hour, 24 * hour), 0.001f)
    }

    @Test
    fun `12点在下方 90度`() {
        assertEquals(90f, DayDialGeometry.angleOf(12 * hour, 24 * hour), 0.001f)
    }

    @Test
    fun `18点在9点方向 180度`() {
        assertEquals(180f, DayDialGeometry.angleOf(18 * hour, 24 * hour), 0.001f)
    }

    @Test
    fun `角度转比例可逆`() {
        for (h in listOf(0, 3, 9, 13, 21)) {
            val frac = h / 24f
            val angle = -90f + frac * 360f
            assertEquals(frac, DayDialGeometry.fractionOfAngle(angle), 0.0001f)
        }
    }

    // ---------------- 弧段切分 ----------------

    @Test
    fun `单条记录 - 9点到10点占 1小时`() {
        val arcs = buildDialArcs(listOf(session(1, 9.0, 1.0)), midnight, dayEnd)
        assertEquals(1, arcs.size)
        val a = arcs[0]
        // 9 点 => -90 + 9/24*360 = 45 度
        assertEquals(45f, a.startAngle, 0.01f)
        // 1h / 24h * 360 = 15 度
        assertEquals(15f, a.sweepAngle, 0.01f)
        assertEquals(0, a.ring)
        assertEquals(1f, a.alpha, 0.001f)
    }

    @Test
    fun `跨午夜记录只保留落在当天的部分 - 23点到次日01点`() {
        // 开始 23:00，结束在次日 01:00
        val s = TimerSession(
            id = 7,
            label = "熬夜",
            startedAt = midnight + 23 * hour,
            endedAt = midnight + 25 * hour,
            plannedMillis = 2 * hour,
            kind = "Stopwatch",
            completed = true,
        )
        val arcs = buildDialArcs(listOf(s), midnight, dayEnd)
        assertEquals(1, arcs.size)
        // 只画 23:00 -> 24:00 这一段：起点 -90 + 23/24*360 = 255 度，扫 15 度
        assertEquals(255f, arcs[0].startAngle, 0.01f)
        assertEquals(15f, arcs[0].sweepAngle, 0.01f)
    }

    @Test
    fun `完全不在当天的记录不产生弧段`() {
        val s = TimerSession(
            id = 9,
            label = null,
            startedAt = midnight - 5 * hour,
            endedAt = midnight - 4 * hour,
            plannedMillis = hour,
            kind = "Countdown",
            completed = true,
        )
        assertTrue(buildDialArcs(listOf(s), midnight, dayEnd).isEmpty())
    }

    @Test
    fun `零长度记录不产生弧段`() {
        val s = TimerSession(
            id = 3,
            label = null,
            startedAt = midnight + hour,
            endedAt = midnight + hour,
            plannedMillis = 0L,
            kind = "Countdown",
            completed = true,
        )
        assertTrue(buildDialArcs(listOf(s), midnight, dayEnd).isEmpty())
    }

    @Test
    fun `弧段按开始时刻排序 与输入顺序无关`() {
        val a = session(1, 15.0, 1.0, "下午")
        val b = session(2, 8.0, 1.0, "早上")
        val c = session(3, 12.0, 1.0, "中午")
        val arcs = buildDialArcs(listOf(a, b, c), midnight, dayEnd)
        assertEquals(listOf("早上", "中午", "下午"), arcs.map { it.session.label })
        assertEquals(listOf(0, 1, 2), arcs.map { it.orderIdx })
    }

    // ---------------- 同心环分配 ----------------

    @Test
    fun `5条以内各占一层 0在最外`() {
        val list = (0 until 5).map { session(it.toLong() + 1, it * 2.0, 1.0) }
        val arcs = buildDialArcs(list, midnight, dayEnd)
        assertEquals(listOf(0, 1, 2, 3, 4), arcs.map { it.ring })
    }

    @Test
    fun `超过5条时复用 ring1`() {
        val list = (0 until 8).map { session(it.toLong() + 1, it * 2.0, 1.0) }
        val arcs = buildDialArcs(list, midnight, dayEnd)
        assertEquals(listOf(0, 1, 2, 3, 4, 1, 1, 1), arcs.map { it.ring })
        // 前 5 条不透明，复用的降低 alpha
        assertEquals(1f, arcs[0].alpha, 0.001f)
        assertEquals(1f, arcs[4].alpha, 0.001f)
        assertTrue(arcs[5].alpha < 1f)
        assertTrue(arcs[6].alpha < arcs[5].alpha)
    }

    @Test
    fun `环半径逐层收窄 最内不侵入中心文字区`() {
        val r0 = DayDialGeometry.ringBounds(0)
        val r1 = DayDialGeometry.ringBounds(1)
        val r4 = DayDialGeometry.ringBounds(4)
        assertEquals(0.8f, r0.outer, 0.0001f)
        assertTrue(r1.outer < r0.outer)
        // 最内层的内边界必须 >= 中心文字区（0.25R），否则弧段会压在文字上
        assertEquals(DayDialGeometry.INNER_FRACTION, r4.inner, 0.0001f)
        assertTrue(r4.inner >= 0.25f - 0.0001f)
        // 相邻层严丝合缝（内 = 下一层外）
        assertEquals(r0.inner, r1.outer, 0.0001f)
    }

    @Test
    fun `每层宽度为 step 且环带总量不超界`() {
        val b = DayDialGeometry.ringBounds(2)
        assertEquals(DayDialGeometry.ringStepFraction, b.outer - b.inner, 0.0001f)
        // 5 层铺满 [0.25, 0.80]，step = 0.55/5 = 0.11
        assertEquals(0.11f, DayDialGeometry.ringStepFraction, 0.0001f)
        val innermost = DayDialGeometry.ringBounds(4)
        assertTrue(innermost.outer <= DayDialGeometry.OUTER_FRACTION + 0.0001f)
    }

    @Test
    fun `环号超出范围会被钳住`() {
        // 负数钳到最外层，超大的钳到最内层（都不许算出负半径）
        assertEquals(DayDialGeometry.ringBounds(0), DayDialGeometry.ringBounds(-3))
        assertEquals(DayDialGeometry.ringBounds(4), DayDialGeometry.ringBounds(99))
        assertTrue(DayDialGeometry.ringBounds(99).inner > 0f)
    }

    @Test
    fun `抖动是确定性的`() {
        val a = DayDialGeometry.jitteredRadius(42L, 0.05f)
        val b = DayDialGeometry.jitteredRadius(42L, 0.05f)
        assertEquals(a, b, 0f)
        // 幅度不能超界
        assertTrue(a >= -0.05f && a <= 0.05f)
        // 相邻 id 不应总是同一个值
        val set = (1L..20L).map { DayDialGeometry.jitteredRadius(it, 1f) }.toSet()
        assertTrue(set.size > 5)
    }

    @Test
    fun `抖动幅度为0时返回0`() {
        assertEquals(0f, DayDialGeometry.jitteredRadius(123L, 0f), 0f)
    }

    // ---------------- 命中测试 ----------------

    /** 画布 1000x1000，圆心 (500,500)，满半径 500。 */
    private fun hit(arcs: List<DialArc>, x: Float, y: Float): DialHit? =
        hitTestDial(arcs, x, y, 500f, 500f, 500f)

    @Test
    fun `点9点方向的弧段能命中`() {
        // 09:00-10:00 的弧段，取中点 09:30。
        //
        // 角度换算（与 DayDialGeometry.angleOf 一致）：
        //   drawArc 角度 = -90 + (时刻/24)*360   （-90 是因为 0:00 在正上方，
        //   而 drawArc 的 0 度是 3 点钟方向）
        //   09:30 -> -90 + 142.5 = 52.5 度
        val arcs = buildDialArcs(listOf(session(1, 9.0, 1.0)), midnight, dayEnd)
        // 半径取最外环中间 (0.8+0.69)/2 = 0.745*500 ≈ 373
        val deg = Math.toRadians(52.5)
        val x = 500f + (373f * kotlin.math.cos(deg)).toFloat()
        val y = 500f + (373f * kotlin.math.sin(deg)).toFloat()
        val h = hit(arcs, x, y)
        assertNotNull(h)
        assertEquals(1L, h!!.arc.session.id)
    }

    @Test
    fun `9点方向(正左) 对应18点时刻 未命中9点的记录`() {
        val arcs = buildDialArcs(listOf(session(1, 9.0, 1.0)), midnight, dayEnd)
        // 正左 = 18:00 方向，不应该命中 09:00-10:00
        assertNull(hit(arcs, 140f, 500f))
    }

    @Test
    fun `点圆心不命中任何弧段`() {
        val arcs = buildDialArcs(listOf(session(1, 9.0, 1.0)), midnight, dayEnd)
        assertNull(hit(arcs, 500f, 500f))
    }

    @Test
    fun `点在盘外不命中`() {
        val arcs = buildDialArcs(listOf(session(1, 0.0, 24.0)), midnight, dayEnd)
        assertNull(hit(arcs, 1200f, 500f))
    }

    @Test
    fun `整圈记录任意角度都可命中`() {
        val s = TimerSession(
            id = 5,
            label = "一整天",
            startedAt = midnight,
            endedAt = dayEnd,
            plannedMillis = 24 * hour,
            kind = "Stopwatch",
            completed = true,
        )
        val arcs = buildDialArcs(listOf(s), midnight, dayEnd)
        // ring 0 的中间半径约 (0.80+0.69)/2 = 0.745 * 500 ≈ 373
        for (degDeg in listOf(0.0, 45.0, 135.0, 225.0, 315.0, 359.0)) {
            val deg = Math.toRadians(degDeg)
            val x = 500f + (373f * kotlin.math.cos(deg)).toFloat()
            val y = 500f + (373f * kotlin.math.sin(deg)).toFloat()
            assertNotNull("角度 $degDeg 应命中", hit(arcs, x, y))
        }
    }

    @Test
    fun `两层同时有记录时优先命中更外层`() {
        val a = session(1, 0.0, 12.0) // ring 0
        val b = session(2, 0.0, 12.0) // ring 1，角度完全重叠
        val arcs = buildDialArcs(listOf(a, b), midnight, dayEnd)
        // ring 0 的中间半径约 (0.80 + 0.69)/2 = 0.745
        val deg = Math.toRadians(0.0) // 6 点方向…用 0 度 = 正右 = 06:00
        val x = 500f + (373f * kotlin.math.cos(deg)).toFloat()
        val y = 500f + (373f * kotlin.math.sin(deg)).toFloat()
        val h = hit(arcs, x, y)
        assertNotNull(h)
        assertEquals(0, h!!.arc.ring)
        assertEquals(1L, h.arc.session.id)
    }

    @Test
    fun `命中半径落在盘边缘也能算出来`() {
        val arcs = buildDialArcs(listOf(session(1, 6.0, 3.0)), midnight, dayEnd)
        // session 是 06:00-09:00，取中点 07:30。
        // 角度 = -90 + (7.5/24)*360 = 22.5 度
        val deg = Math.toRadians(22.5)
        val x = 500f + (400f * kotlin.math.cos(deg)).toFloat()
        val y = 500f + (400f * kotlin.math.sin(deg)).toFloat()
        // 400/500 = 0.8，正好在最外环外缘
        assertNotNull(hit(arcs, x, y))
    }
}
