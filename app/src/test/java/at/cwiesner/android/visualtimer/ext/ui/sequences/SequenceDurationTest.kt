package at.cwiesner.android.visualtimer.ext.ui.sequences

import at.cwiesner.android.visualtimer.ext.data.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 序列「合计时长」格式化的单元测试。
 *
 * 这几个函数是纯 Kotlin（只在文件顶部 import 了 `data.Step`，而 Step 是 Room 实体但不涉及
 * 任何 Android 调用），所以能在 CI 的 JVM 上真跑 —— 本机没有 JDK，这是唯一能"真验证"
 * 边界显示的手段，不然只能等编译通过后在手机上肉眼看。
 *
 * 重点覆盖三件事：0 段不出现「合计 0s」、1 段怎么显示、超过 10 小时怎么显示。
 */
class SequenceDurationTest {

    private fun step(durationMillis: Long) = Step(sequenceId = 1L, durationMillis = durationMillis)

    private val min = 60_000L
    private val hour = 3_600_000L

    // ---------------- formatTotalDuration ----------------

    @Test
    fun `不足一分钟显示秒`() {
        assertEquals("0s", formatTotalDuration(0L))
        assertEquals("12s", formatTotalDuration(12_000L))
        assertEquals("40s", formatTotalDuration(40_000L))
    }

    @Test
    fun `不足一小时只显示分钟且秒四舍五入`() {
        assertEquals("45m", formatTotalDuration(45 * min))
        assertEquals("1m", formatTotalDuration(65_000L))   // 1m05s -> 1m
        assertEquals("2m", formatTotalDuration(100_000L))  // 1m40s -> 2m
        assertEquals("1h00m", formatTotalDuration(59 * min + 40_000L)) // 59m40s 进位成 1 小时
    }

    @Test
    fun `一小时以上显示 h 和两位分钟`() {
        assertEquals("1h00m", formatTotalDuration(hour))
        assertEquals("2h10m", formatTotalDuration(2 * hour + 10 * min))
        // 2h09m54s：零碎秒被四舍五入掉，不再出现「2h09m54s」
        assertEquals("2h10m", formatTotalDuration(2 * hour + 9 * min + 54_000L))
    }

    @Test
    fun `很长的时长显示为小时`() {
        // 10 小时：formatRemaining 会输出 "10h00m"，这里保持同一量级，不会退化成分钟
        assertEquals("10h00m", formatTotalDuration(10 * hour))
        // 多阶段的 24 小时以上也一样：39h20m
        assertEquals("39h20m", formatTotalDuration(39 * hour + 20 * min))
    }

    @Test
    fun `负数按 0 处理`() {
        assertEquals("0s", formatTotalDuration(-5_000L))
    }

    // ---------------- 合计 + 描述 ----------------

    @Test
    fun `合计时长忽略负值脏数据`() {
        assertEquals(30 * min, totalDurationMillis(listOf(step(20 * min), step(10 * min))))
        assertEquals(10 * min, totalDurationMillis(listOf(step(10 * min), step(-1L))))
    }

    @Test
    fun `0 个阶段不显示合计 0s`() {
        assertEquals("还没有阶段", describeSequenceDuration(emptyList()))
        assertEquals("合计 —", describeDraftTotal(emptyList()))
    }

    @Test
    fun `1 个阶段`() {
        assertEquals("1 段 · 合计 25m", describeSequenceDuration(listOf(step(25 * min))))
        assertEquals("1 段 · 合计 40s", describeSequenceDuration(listOf(step(40_000L))))
        assertEquals(
            "合计 25m",
            describeDraftTotal(listOf(StepDraft(label = "写提纲", durationMillis = 25 * min))),
        )
    }

    @Test
    fun `多个阶段`() {
        val steps = listOf(step(30 * min), step(hour), step(40 * min), step(20_000L), step(0L))
        // 30m + 1h + 40m + 20s = 2h10m20s -> 2h10m
        assertEquals("5 段 · 合计 2h10m", describeSequenceDuration(steps))
    }

    @Test
    fun `编辑器的草稿也走同一套规则`() {
        val drafts = listOf(
            StepDraft(label = "a", durationMillis = 10 * hour),
            StepDraft(label = "b", durationMillis = 2 * hour),
        )
        assertEquals("合计 12h00m", describeDraftTotal(drafts))
    }
}
