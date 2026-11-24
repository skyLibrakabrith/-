package at.cwiesner.android.visualtimer.ext.data

import androidx.room.TypeConverter

/**
 * 计时器类型。
 *
 * - [Countdown] 普通倒计时：从设定的时长往下走
 * - [Deadline]  截止时刻：绑定一个绝对时间点，剩余量随时间流逝
 * - [Clock]     时钟：显示当前时刻，同时作为正计时/整点提醒的载体
 */
enum class TimerKind {
    Countdown,
    Deadline,
    Clock,

    /** 正计时：从 0 往上数，用于「记录已经过了多久」。 */
    Stopwatch;

    companion object {
        fun fromKey(key: String?): TimerKind =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: Countdown
    }
}

class TimerKindConverter {
    @TypeConverter
    fun toKey(kind: TimerKind?): String = (kind ?: TimerKind.Countdown).name

    @TypeConverter
    fun fromKey(key: String?): TimerKind = TimerKind.fromKey(key)
}
