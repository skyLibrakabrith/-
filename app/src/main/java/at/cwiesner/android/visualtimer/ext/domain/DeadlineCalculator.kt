package at.cwiesner.android.visualtimer.ext.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * DDL（截止时刻）→ 剩余时长的换算。
 *
 * 设计要点：
 * - 所有计算都基于 [Instant]，避免夏令时/时区漂移。
 * - 目标时间已过时返回 [Result.Past]，UI 层据此禁用确认按钮。
 * - 超过 [MAX_RELIABLE_MILLIS] 的时长不交给 AlarmManager 精确闹钟，
 *   改用 setAlarmClock，避免系统在长期休眠后丢失。
 */
object DeadlineCalculator {

    /** 24 小时。超过此时长精确闹钟不可靠。 */
    const val MAX_RELIABLE_MILLIS: Long = 24L * 60 * 60 * 1000

    sealed interface Result {
        data class Ok(
            val remainingMillis: Long,
            val requiresAlarmClock: Boolean,
        ) : Result

        data object Past : Result

        data object TooLong : Result
    }

    /**
     * @param target 用户选定的截止时刻
     * @param now 当前时刻，便于测试注入
     */
    fun remainingUntil(
        target: Instant,
        now: Instant = Instant.now(),
    ): Result {
        val millis = Duration.between(now, target).toMillis()
        return when {
            millis <= 0L -> Result.Past
            else -> Result.Ok(
                remainingMillis = millis,
                requiresAlarmClock = millis > MAX_RELIABLE_MILLIS,
            )
        }
    }

    /**
     * 把「日期 + 时刻」组合成 Instant。
     *
     * 若日期是今天且时刻已过，UI 层应提示用户。
     */
    fun toInstant(
        date: LocalDate,
        time: LocalTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant = LocalDateTime.of(date, time).atZone(zone).toInstant()

    /**
     * 目标时刻的友好描述，用于卡片副标题。
     * 形如「今天 17:00」「明天 09:30」「11-25 14:00」。
     */
    fun describe(
        target: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val targetDate = target.atZone(zone).toLocalDate()
        val nowDate = now.atZone(zone).toLocalDate()
        val timeText = target.atZone(zone).toLocalTime()
            .let { "%02d:%02d".format(it.hour, it.minute) }
        return when (targetDate) {
            nowDate -> "今天 $timeText"
            nowDate.plusDays(1) -> "明天 $timeText"
            else -> "%02d-%02d %s".format(
                targetDate.monthValue,
                targetDate.dayOfMonth,
                timeText,
            )
        }
    }

    /**
     * 把剩余毫秒格式化成便于阅读的文本：`1h23m`、`4m05s`、`12s`。
     */
    fun formatRemaining(millis: Long): String {
        if (millis <= 0) return "0s"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "${h}h%02dm".format(m)
            m > 0 -> "${m}m%02ds".format(s)
            else -> "${s}s"
        }
    }
}
