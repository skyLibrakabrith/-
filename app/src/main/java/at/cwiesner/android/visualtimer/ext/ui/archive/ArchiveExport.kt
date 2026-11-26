package at.cwiesner.android.visualtimer.ext.ui.archive

import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.ui.common.clockOf
import java.time.LocalDate

/**
 * 导出用的一次性快照。
 *
 * **为什么不直接传 `List<TimerSession>`**：
 * 导出的窗口（近 7 天 / 自定义…）和归档页当前显示的那一天**不是一回事**，
 * 而且导出必须是一个**冻结的快照** —— 用户点了「导出」之后又删了一条记录，
 * 不该让已经生成好的文本变样。所以这里把"已经算好的文本"和"用来显示摘要的计数"
 * 一起交给 Composable，Composable 只负责把文本塞进 `Intent`。
 */
internal data class ArchiveExport(
    /** 窗口起点（含）。 */
    val fromMillis: Long,
    /** 窗口终点（不含）。 */
    val untilMillis: Long,
    /** 记录条数（含跨午夜被裁到窗口里的那些）。 */
    val sessionCount: Int,
    /** 各条落在窗口内的毫秒数之和。 */
    val totalMillisInRange: Long,
    /** 完整纯文本，直接作为 `Intent.EXTRA_TEXT`。 */
    val text: String,
    /** 分享面板标题：`计时记录 11-20 ~ 11-27`。 */
    val subject: String,
) {
    val isEmpty: Boolean get() = sessionCount == 0
}

/** 导出窗口的快捷档位。 */
internal enum class ExportRange(val label: String) {
    Last7("近 7 天"),
    Last30("近 30 天"),
    ThisMonth("本月"),
    Custom("自定义"),
}

/**
 * 由档位算出 `[from, until)` 两个时刻。
 *
 * 统一用**本地零点**做边界，理由同 [ArchiveDates]：一天是日历上的一天，不是 86400000 毫秒。
 *
 * @param today 以哪一天为"今天"（传参而不是内部取 `LocalDate.now()`，单测才能可控）
 */
internal fun exportRangeBounds(range: ExportRange, today: LocalDate): Pair<Long, Long> {
    val endDateExclusive = today.plusDays(1)
    val startDate = when (range) {
        // 「近 7 天」含今天，所以往前推 6 天
        ExportRange.Last7 -> today.minusDays(6)
        ExportRange.Last30 -> today.minusDays(29)
        // 本月：当月 1 号到今天结束（不是整月，否则会把"未来的几天"也算进窗口，
        // 表头日期范围就和实际情况不符）
        ExportRange.ThisMonth -> today.withDayOfMonth(1)
        // 自定义的边界由调用方另外给，这里兜底成"只看今天"
        ExportRange.Custom -> today
    }
    return ArchiveDates.dayStart(startDate) to ArchiveDates.dayEnd(endDateExclusive.minusDays(1))
}

/**
 * 生成纯文本导出内容。
 *
 * 格式（需求给定）：
 * ```
 * 计时记录（2026-11-20 ~ 2026-11-27）
 * 共 12 条，合计 5 小时 23 分
 *
 * 日期    开始    结束    时长    名称
 * 11-20   09:15   09:45   30分    写周报
 * ```
 *
 * **为什么用纯文本而不是文件**：不需要 FileProvider、不需要任何存储权限，
 * `Intent.ACTION_SEND + text/plain` 是最稳的一条路 —— 微信、短信、笔记、邮件
 * 全都接得住，不会出现"分享出去是个打不开的附件"。
 *
 * 几条格式上的取舍：
 * - **定宽对齐**：手机上的分享目标多半还是等宽或半等宽渲染，制表符 `\t` 在不同
 *   App 里宽度不一（有的直接吞掉），所以用**空格**手工对齐，保证在任何地方都整齐。
 * - 跨午夜的记录：开始/结束各带日期前缀 `11-20 23:40`，否则 `23:40 → 00:15` 读不通。
 * - 裁剪：只导出落在窗口内的那部分时长（和归档页表盘上看到的长度一致）。
 * - 名字为空写「未命名」，避免整行只剩空白看不出来是哪一条。
 */
internal fun buildExportText(
    sessions: List<TimerSession>,
    fromMillis: Long,
    untilMillis: Long,
): ArchiveExport {
    val fromDate = ArchiveDates.localDateOf(fromMillis)
    // until 是右开区间：显示时减 1 毫秒再取日期，避免"11-21 ~ 11-28"里出现一个空档的 11-28
    val untilDate = ArchiveDates.localDateOf((untilMillis - 1L).coerceAtLeast(fromMillis))

    // 只保留与窗口有交集的记录，并按开始时刻排序
    val inRange = sessions
        .filter { it.startedAt < untilMillis && it.endedAt > fromMillis }
        .sortedWith(compareBy({ it.startedAt }, { it.id }))

    val total = inRange.sumOf { s ->
        (s.endedAt.coerceIn(fromMillis, untilMillis) - s.startedAt.coerceIn(fromMillis, untilMillis))
            .coerceAtLeast(0L)
    }

    // 跨窗口的记录在「开始/结束」列里必须带上日期，否则看不出是前一天还是后一天
    fun stamp(millis: Long): String =
        if (millis < fromMillis || millis >= untilMillis) {
            ArchiveDates.shortDate(ArchiveDates.localDateOf(millis)) + " " + clockOf(millis)
        } else {
            clockOf(millis)
        }

    val sb = StringBuilder()
    sb.append("计时记录（").append(ArchiveDates.iso(fromDate))
        .append(" ~ ").append(ArchiveDates.iso(untilDate)).append("）\n")
    sb.append("共 ").append(inRange.size).append(" 条，合计 ")
        .append(describeTotal(total)).append('\n')
    sb.append('\n')

    if (inRange.isEmpty()) {
        sb.append("这段时间没有计时记录。\n")
    } else {
        // 表头与数据行共用同一套列宽，靠 format 补齐
        sb.append(padRight("日期", 12))
        sb.append(padRight("开始", 18))
        sb.append(padRight("结束", 18))
        sb.append(padRight("时长", 14))
        sb.append("名称")
        sb.append('\n')
        for (s in inRange) {
            val clippedFrom = s.startedAt.coerceIn(fromMillis, untilMillis)
            val clippedTo = s.endedAt.coerceIn(fromMillis, untilMillis)
            val dur = (clippedTo - clippedFrom).coerceAtLeast(0L)
            sb.append(padRight(ArchiveDates.shortDate(ArchiveDates.localDateOf(s.startedAt)), 12))
            sb.append(padRight(stamp(s.startedAt), 18))
            sb.append(padRight(stamp(s.endedAt), 18))
            sb.append(padRight(describeDuration(dur), 14))
            sb.append(displayName(s).replace('\n', ' '))
            sb.append('\n')
        }
    }

    return ArchiveExport(
        fromMillis = fromMillis,
        untilMillis = untilMillis,
        sessionCount = inRange.size,
        totalMillisInRange = total,
        text = sb.toString(),
        subject = "计时记录 " + ArchiveDates.shortDate(fromDate) + " ~ " + ArchiveDates.shortDate(untilDate),
    )
}

/**
 * 按**显示宽度**补齐到 [width]。
 *
 * 中文字符占两格，`String.length` 数的是 code unit —— 「日期」长度是 2 但占 4 格。
 * 不处理的话表头和中文名字的行会错位。ASCII 记 1，其余记 2（够用了：
 * 归档里出现的只有中文、数字、冒号、连字符）。
 */
internal fun padRight(s: String, width: Int): String {
    var w = 0
    for (ch in s) w += if (ch.code < 128) 1 else 2
    // 补足到 width 后**再追加一个空格**作为列分隔。
    //
    // 为什么必须补：相邻两列都用 padRight 时，前一列补满 width 后
    // 下一列紧贴着接上 —— 表头里「日期」和「开始」之间一个空格都没有，
    // 粘成「日期        开始」，肉眼难读、程序也没法按空白切列。
    // 已经超宽时同样要补这一个空格，否则超长的名称会直接贴到下一列上。
    val pad = if (w >= width) 1 else width - w + 1
    return s + " ".repeat(pad)
}
