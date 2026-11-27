package at.cwiesner.android.visualtimer.ext.ui.archive

import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.ui.common.clockOf

/**
 * 归档页的文本格式化。
 *
 * **为什么不用 `DeadlineCalculator.formatRemaining`**：
 * 那个函数是给"倒计时剩余量"用的，习惯是 `1h23m` / `4m05s` —— 秒级精度。
 * 归档里的时长是**已经发生的事**，一条三分钟的计时显示成 `3m00s` 很吵，
 * 而一整天的合计更需要 `5 小时 23 分` 这种读法。两种语义不该共用一个函数，
 * 所以这里另写 `describeDuration`。
 */

/** 一条记录的时长文本：`1 小时 23 分` / `23 分` / `45 秒`。 */
internal fun describeDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600L
    val m = (totalSeconds % 3600L) / 60L
    val s = totalSeconds % 60L
    return when {
        // 有小时且秒不为 0 时也带上秒：`1 小时 23 分` 丢了 20 秒会让人以为记错了
        h > 0L && s > 0L -> "$h 小时 $m 分 $s 秒"
        h > 0L -> "$h 小时 " + (if (m > 0L) "$m 分" else "整")
        m > 0L && s > 0L -> "$m 分 $s 秒"
        m > 0L -> "$m 分"
        else -> "$s 秒"
    }
}

/**
 * 合计时长，**做小时/分钟取整**（秒不显示）。
 *
 * 一天的总计动辄几小时，秒数没有意义；但也不能简单截断 ——
 * 30 分 40 秒 + 30 分 40 秒 应该是「1 小时 2 分」而不是「1 小时」。
 * 所以先四舍五入到分钟再拆。
 */
internal fun describeTotal(millis: Long): String {
    if (millis <= 0L) return "0 分"
    val totalMinutes = Math.round(millis / 60_000.0)
    val h = totalMinutes / 60L
    val m = totalMinutes % 60L
    return when {
        h > 0L && m > 0L -> "$h 小时 $m 分"
        h > 0L -> "$h 小时"
        else -> "$m 分"
    }
}

/** 记录在界面上的名字。名字可以是空的（计时器行被删、创建时没起名）。 */
internal fun displayName(s: TimerSession): String {
    val l = s.label?.trim()
    return if (l.isNullOrEmpty()) "未命名" else l
}

/**
 * 记录类型的中文名。
 *
 * [TimerSession.kind] 存的是字符串（见实体注释：历史数据不能因为枚举增删而解析失败），
 * 所以这里用 [TimerKind.fromKey] 容错转换，未知值原样显示 ——
 * 宁可看到一个陌生的英文词，也不要显示成误导性的"倒计时"。
 */
internal fun kindLabel(kind: String): String {
    val known = TimerKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
    return when (known) {
        TimerKind.Countdown -> "倒计时"
        TimerKind.Deadline -> "截止时刻"
        TimerKind.Clock -> "时钟"
        TimerKind.Stopwatch -> "正计时"
        null -> kind
    }
}

/** 是否按时结束：`completed` 为 true 表示正常到点（含超时）。 */
internal fun completionLabel(s: TimerSession): String =
    if (s.completed) "按时到点" else "提前结束"

/**
 * 这条记录是否**跨越了午夜**：开始与结束不在同一个本地日期。
 *
 * ## 为什么单独抽出来
 *
 * 之前详情弹窗里写的是 `session.endedAt + 1L < dayEnd`，语义完全反了 ——
 * 它问的是"这条记录是否在当天零点之前结束"，也就是**几乎所有正常记录**都成立。
 * 结果每条详情都多出一行「完整时长」和一句
 * "表盘上只画了落在这一天里的部分"（归档里没跨午夜的记录被记成跨午夜）。
 *
 * 抽成纯函数是为了能直接单测边界：23:59:59 结束不算跨午夜，
 * 00:00:01 结束才算。
 */
internal fun crossesMidnight(s: TimerSession): Boolean =
    ArchiveDates.localDateOf(s.startedAt) != ArchiveDates.localDateOf(s.endedAt)

/**
 * 开始/结束时刻。
 *
 * 跨午夜的那一头（结束落在次日）要标出「次日」，否则同一条记录会显示成
 * `23:40 → 00:15`，读起来像"倒着走"的 32 小时。
 */
internal fun spanTextOf(s: TimerSession, dayEnd: Long): String {
    val start = clockOf(s.startedAt)
    val end = if (s.endedAt >= dayEnd) "次日 " + clockOf(s.endedAt) else clockOf(s.endedAt)
    return "$start – $end"
}

/**
 * 实际时长（**裁剪到当天窗口内**）。
 *
 * 表盘上画的是裁剪后的那一段，详情里必须和盘面上看到的长度**一致**，
 * 否则用户会怀疑自己点错了记录。整段时长在 [actualFullMillis] 里另给。
 */
internal fun actualMillisInDay(s: TimerSession, dayStart: Long, dayEnd: Long): Long =
    (s.endedAt.coerceIn(dayStart, dayEnd) - s.startedAt.coerceIn(dayStart, dayEnd))
        .coerceAtLeast(0L)

/** 记录的真实总时长（不裁剪）。 */
internal fun actualFullMillis(s: TimerSession): Long =
    (s.endedAt - s.startedAt).coerceAtLeast(0L)

/**
 * 与计划时长的差：`按时` / `超时 5 分` / `提前 5 分`。
 *
 * 为什么单独给一行而不是只说 completed：`completed = true` 只说明"到过点"，
 * 超时 20 分钟也是 true。用户看归档想知道的是"这次有没有拖"。
 */
internal fun planDeltaText(s: TimerSession): String {
    val planned = s.plannedMillis
    if (planned <= 0L) return "未设定计划时长"
    val diff = actualFullMillis(s) - planned
    val absDiff = kotlin.math.abs(diff)
    return when {
        // 1 秒以内当作"正好"，避免浮点/毫秒误差把准时显示成"超时 0 分"
        absDiff <= 1000L -> "按时"
        diff > 0L -> "超时 " + describeDuration(absDiff)
        else -> "提前 " + describeDuration(absDiff)
    }
}
