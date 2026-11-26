package at.cwiesner.android.visualtimer.ext.ui.archive

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 归档页的日期换算。
 *
 * **为什么整页都用 [LocalDate] 而不是 `epochMillis`**：
 * 「某一天的记录」在用户眼里是**日历上的那一天**，而不是「86400000 毫秒的窗口」。
 * 有夏令时的时区里一天可能只有 23 小时或 25 小时，用 `dayStart + 86400000L`
 * 算出来的次日零点会偏一小时 —— 那条记录就同时属于两天（或哪一天都不属于），
 * 表现为「明明记了一次，归档里翻不到」。
 *
 * 所以所有窗口都用 `LocalDate.atStartOfDay(zone)` 换算，交给 java.time 处理时区规则。
 *
 * 时区**每次调用时现取**（不缓存 `ZoneId` 到常量里）：
 * 用户改了系统时区/时区自动切换之后，已经显示着的一天应该按新时区重新理解。
 */
internal object ArchiveDates {

    /** 一天之内的毫秒数。仅用于"把时刻映射到表盘角度"的**展示**换算，不用于切窗口。 */
    const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    internal fun zone(): ZoneId = ZoneId.systemDefault()

    internal fun today(): LocalDate = LocalDate.now(zone())

    /** 当天零点（左闭）。 */
    internal fun dayStart(date: LocalDate): Long =
        date.atStartOfDay(zone()).toInstant().toEpochMilli()

    /** 次日零点（右开）。 */
    internal fun dayEnd(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    internal fun localDateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    internal fun localHourOf(epochMillis: Long): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone()).hour

    /**
     * 这一天在表盘上**真实占据**的时长。
     *
     * 通常就是 24h；夏令时切换日会是 23h / 25h。
     * 表盘按它归一化成角度，否则切换日那天的弧段会明显错位
     * （比如 03:00 的位置和刻度对不上）。
     */
    internal fun daySpanMillis(date: LocalDate): Long =
        (dayEnd(date) - dayStart(date)).coerceAtLeast(1L)

    /** 把时刻钳到当天窗口内：跨午夜的记录在表盘上只能画它露在这一天的部分。 */
    internal fun clampIntoDay(epochMillis: Long, date: LocalDate): Long =
        epochMillis.coerceIn(dayStart(date), dayEnd(date))

    /** 标题用：`2026-11-27 周四`。 */
    internal fun dayTitle(date: LocalDate): String = "${date} ${weekdayCn(date)}"

    internal fun weekdayCn(date: LocalDate): String = when (date.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    /** 导出表头用：`2026-11-27`。 */
    internal fun iso(date: LocalDate): String = date.toString()

    /** 导出/详情用：`11-27`（同一年内不必重复年份）。 */
    internal fun shortDate(date: LocalDate): String =
        "%02d-%02d".format(date.monthValue, date.dayOfMonth)
}
