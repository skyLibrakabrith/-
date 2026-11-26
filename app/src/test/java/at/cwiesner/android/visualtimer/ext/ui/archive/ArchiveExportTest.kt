package at.cwiesner.android.visualtimer.ext.ui.archive

import at.cwiesner.android.visualtimer.ext.data.TimerSession
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出文本 / 时长格式化 / 范围换算的单元测试。
 *
 * 导出文本是**给人看、会粘到别处去**的东西，错位或多算一天没法靠界面发现，
 * 所以这里把格式和边界都钉死。
 */
class ArchiveExportTest {

    private val hour = 3_600_000L

    /** 用 UTC 基准构造时刻，避免测试依赖 CI 机器时区。 */
    private fun epochOf(date: LocalDate, hourOfDay: Int): Long {
        val midnight = ArchiveDates.dayStart(date)
        return midnight + hourOfDay * hour
    }

    private fun session(
        id: Long,
        date: LocalDate,
        startHour: Int,
        durHours: Double,
        label: String? = "任务$id",
        planned: Double = durHours,
        completed: Boolean = true,
    ) = TimerSession(
        id = id,
        label = label,
        startedAt = epochOf(date, startHour),
        endedAt = epochOf(date, startHour) + (durHours * hour).toLong(),
        plannedMillis = (planned * hour).toLong(),
        kind = "Countdown",
        completed = completed,
    )

    // ---------------- 时长格式化 ----------------

    @Test
    fun `describeDuration 各种粒度`() {
        assertEquals("0 秒", describeDuration(0L))
        assertEquals("45 秒", describeDuration(45_000L))
        assertEquals("3 分", describeDuration(3 * 60_000L))
        assertEquals("3 分 20 秒", describeDuration(3 * 60_000L + 20_000L))
        assertEquals("1 小时 整", describeDuration(hour))
        assertEquals("1 小时 23 分", describeDuration(hour + 23 * 60_000L))
        assertEquals("2 小时 5 分 7 秒", describeDuration(2 * hour + 5 * 60_000L + 7_000L))
    }

    @Test
    fun `describeTotal 四舍五入到分钟`() {
        assertEquals("0 分", describeTotal(0L))
        // 30m40s + 30m40s = 61m20s -> 61 分钟 -> 1 小时 1 分
        assertEquals("1 小时 1 分", describeTotal(2 * (30 * 60_000L + 40_000L)))
        // 29s 抹成 0 分钟
        assertEquals("0 分", describeTotal(29_000L))
        // 31s 进成 1 分钟
        assertEquals("1 分", describeTotal(31_000L))
        assertEquals("2 小时", describeTotal(2 * hour))
    }

    @Test
    fun `displayName 空名回退`() {
        val s = session(1, LocalDate.of(2026, 11, 20), 9, 0.5, label = null)
        assertEquals("未命名", displayName(s))
        assertEquals("未命名", displayName(s.copy(label = "   ")))
        assertEquals("写周报", displayName(s.copy(label = "写周报")))
    }

    @Test
    fun `kindLabel 容错未知值`() {
        assertEquals("倒计时", kindLabel("Countdown"))
        assertEquals("截止时刻", kindLabel("Deadline"))
        assertEquals("正计时", kindLabel("Stopwatch"))
        assertEquals("时钟", kindLabel("Clock"))
        // 未知值原样显示，不要伪装成"倒计时"
        assertEquals("FutureKind", kindLabel("FutureKind"))
    }

    @Test
    fun `planDeltaText 三种情况`() {
        val base = session(1, LocalDate.of(2026, 11, 20), 9, 1.0, planned = 1.0)
        assertEquals("按时", planDeltaText(base))
        assertEquals("超时 10 分", planDeltaText(base.copy(endedAt = base.startedAt + hour + 10 * 60_000L)))
        assertEquals("提前 10 分", planDeltaText(base.copy(endedAt = base.startedAt + hour - 10 * 60_000L)))
        // 1000ms 内算准时（tick 精度带来的毫秒误差不该显示成"超时"）
        assertEquals("按时", planDeltaText(base.copy(endedAt = base.startedAt + hour + 900L)))
    }

    @Test
    fun `跨午夜的 spanText 标出次日`() {
        val date = LocalDate.of(2026, 11, 20)
        val s = TimerSession(
            id = 1,
            label = "熬夜",
            startedAt = epochOf(date, 23),
            endedAt = epochOf(date, 23) + 2 * hour,
            plannedMillis = 2 * hour,
            kind = "Stopwatch",
            completed = true,
        )
        val text = spanTextOf(s, ArchiveDates.dayEnd(date))
        assertTrue(text, text.contains("次日"))
    }

    // ---------------- 范围换算 ----------------

    @Test
    fun `近7天含今天`() {
        val today = LocalDate.of(2026, 11, 27)
        val (from, until) = exportRangeBounds(ExportRange.Last7, today)
        assertEquals(ArchiveDates.dayStart(LocalDate.of(2026, 11, 21)), from)
        assertEquals(ArchiveDates.dayEnd(LocalDate.of(2026, 11, 27)), until)
        // 窗口长度正好 7 个日历日边界
        assertEquals(LocalDate.of(2026, 11, 21), ArchiveDates.localDateOf(from))
        assertEquals(LocalDate.of(2026, 11, 27), ArchiveDates.localDateOf(until - 1L))
    }

    @Test
    fun `近30天含今天`() {
        val today = LocalDate.of(2026, 11, 27)
        val (from, _) = exportRangeBounds(ExportRange.Last30, today)
        assertEquals(ArchiveDates.dayStart(LocalDate.of(2026, 10, 29)), from)
    }

    @Test
    fun `本月从1号开始`() {
        val today = LocalDate.of(2026, 11, 27)
        val (from, until) = exportRangeBounds(ExportRange.ThisMonth, today)
        assertEquals(ArchiveDates.dayStart(LocalDate.of(2026, 11, 1)), from)
        assertEquals(ArchiveDates.dayEnd(LocalDate.of(2026, 11, 27)), until)
    }

    @Test
    fun `自定义范围两端弄反会对调`() {
        val today = LocalDate.of(2026, 11, 27)
        val request = ExportRequest(
            range = ExportRange.Custom,
            customStart = LocalDate.of(2026, 11, 25),
            customEnd = LocalDate.of(2026, 11, 20),
        )
        val (fromDate, toDate) = resolveExportBounds(request, today)
        assertEquals(LocalDate.of(2026, 11, 20), fromDate)
        assertEquals(LocalDate.of(2026, 11, 25), toDate)
    }

    // ---------------- 导出文本 ----------------

    @Test
    fun `导出文本包含表头 合计和逐行`() {
        val d = LocalDate.of(2026, 11, 20)
        val rows = listOf(
            session(1, d, 9, 0.5, "写周报"),
            session(2, d, 14, 1.5, "开会"),
        )
        val from = ArchiveDates.dayStart(d)
        val until = ArchiveDates.dayEnd(d)
        val export = buildExportText(rows, from, until)

        assertEquals(2, export.sessionCount)
        assertEquals(2 * hour, export.totalMillisInRange)
        val text = export.text
        assertTrue(text, text.contains("计时记录"))
        assertTrue(text, text.contains("共 2 条"))
        assertTrue(text, text.contains("合计 2 小时"))
        assertTrue(text, text.contains("日期"))
        assertTrue(text, text.contains("写周报"))
        assertTrue(text, text.contains("开会"))
        assertTrue(text, text.contains("30 分"))
        assertTrue(text, text.contains("1 小时 30 分"))
        // 表头行的列结构：日期/开始/结束/时长/名称
        val lines = text.trimEnd('\n').split("\n")
        assertTrue(lines.size >= 5)
        assertTrue(lines[0], lines[0].startsWith("计时记录（"))
    }

    @Test
    fun `空窗口导出不崩并说明没有记录`() {
        val d = LocalDate.of(2026, 11, 20)
        val export = buildExportText(
            emptyList(),
            ArchiveDates.dayStart(d),
            ArchiveDates.dayEnd(d),
        )
        assertEquals(0, export.sessionCount)
        assertTrue(export.isEmpty)
        assertTrue(export.text, export.text.contains("没有计时记录"))
    }

    @Test
    fun `窗口外记录被过滤 窗口内的裁剪`() {
        val d = LocalDate.of(2026, 11, 20)
        val inside = session(1, d, 10, 1.0, "在内")
        val outside = session(2, d.plusDays(3), 10, 1.0, "在外")
        val from = ArchiveDates.dayStart(d)
        val until = ArchiveDates.dayEnd(d)
        val export = buildExportText(listOf(inside, outside), from, until)
        assertEquals(1, export.sessionCount)
        assertTrue(export.text, export.text.contains("在内"))
        assertFalse(export.text, export.text.contains("在外"))
    }

    @Test
    fun `跨窗口边界的记录只算窗口内时长`() {
        val d = LocalDate.of(2026, 11, 20)
        // 22:00 开始，持续 4 小时 —— 只有 2 小时落在 11-20
        val s = session(1, d, 22, 4.0, "熬夜")
        val from = ArchiveDates.dayStart(d)
        val until = ArchiveDates.dayEnd(d)
        val export = buildExportText(listOf(s), from, until)
        assertEquals(1, export.sessionCount)
        assertEquals(2 * hour, export.totalMillisInRange)
        // 结束时刻在窗口外，必须带日期前缀
        assertTrue(export.text, export.text.contains("11-21"))
    }

    @Test
    fun `按显示宽度对齐 中文列头也是两格`() {
        // "日期" 显示宽度 4 格（每个中文算 2）-> 补 8 格到 12，**再加 1 个列分隔空格**。
        // 断言"显示宽度"而不是字符数：中文字符 1 个占 2 格，两者不是一回事。
        assertEquals("日期" + " ".repeat(9), padRight("日期", 12))
        // "ab" 占 2 格 -> 补 10 格 + 1 个分隔空格
        assertEquals("ab" + " ".repeat(11), padRight("ab", 12))
        // 已超出宽度时只追加一个分隔空格，不能截断
        assertEquals("abcdefghijklm ", padRight("abcdefghijklm", 12))
    }

    @Test
    fun `导出行的列宽一致`() {
        val d = LocalDate.of(2026, 11, 20)
        val rows = listOf(
            session(1, d, 9, 0.5, "写周报"),
            session(2, d, 14, 2.0, "一个很长的名字"),
        )
        val export = buildExportText(rows, ArchiveDates.dayStart(d), ArchiveDates.dayEnd(d))
        val lines = export.text.trimEnd('\n').split("\n")
        // 行序：[0] 标题  [1] 统计  [2] 空行  [3] 表头  [4..] 数据
        val header = lines[3]
        val data1 = lines[4]
        val data2 = lines[5]

        // 验证各列**起点位置**一致。
        //
        // 不用"找连续空白的边界"那种启发式 —— 中英文混排下
        // （「30分」里"分"占两格）很容易把中文字符误判成列起点，
        // 得出"没对齐"的错误结论。这里直接按"从行首数到某个子串"的位置比。
        fun colOf(line: String, needle: String): Int {
            val idx = line.indexOf(needle)
            assertTrue("表头/数据行里找不到「$needle」", idx >= 0)
            // 把字符下标换算成显示列
            var col = 0
            for (i in 0 until idx) col += if (line[i].code < 128) 1 else 2
            return col
        }

        // 四个列头在表头行里的起点
        val hStart = colOf(header, "开始")
        val hEnd = colOf(header, "结束")
        val hDur = colOf(header, "时长")
        val hName = colOf(header, "名称")

        // 两条数据行里对应内容应与表头对齐（开始列、结束列各比 "09"/"14" 这类时刻）
        assertEquals(hStart, colOf(data1, "09:00"))
        assertEquals(hStart, colOf(data2, "14:00"))
        assertEquals(hEnd, colOf(data1, "09:30"))
        assertEquals(hEnd, colOf(data2, "16:00"))
        // 时长列不能用 "30" / "2" 去搜 —— 它们会先命中结束时刻里的子串
        // （"09:30" 里的 "30"）。
        // describeDuration 的实际格式是 "30 分"（数字与单位之间有空格），
        // 不是 "30分" —— 以实现为准，别按想当然的格式写断言。
        assertEquals(hDur, colOf(data1, "30 分"))
        assertEquals(hDur, colOf(data2, "2 小时"))
        assertEquals(hName, colOf(data1, "写周报"))
        assertEquals(hName, colOf(data2, "一个"))
    }

    @Test
    fun `subject 带日期范围`() {
        val fromDate = LocalDate.of(2026, 11, 20)
        val toDate = LocalDate.of(2026, 11, 27)
        val export = buildExportText(
            emptyList(),
            ArchiveDates.dayStart(fromDate),
            ArchiveDates.dayEnd(toDate),
        )
        assertEquals("计时记录 11-20 ~ 11-27", export.subject)
    }
}
