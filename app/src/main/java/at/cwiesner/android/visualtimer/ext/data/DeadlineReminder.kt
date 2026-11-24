package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * DDL 的提前提醒点。
 *
 * 一个 DDL 可以挂多个提醒（如提前 30 分钟 + 提前 5 分钟），
 * 到这些时刻会各触发一次提醒。
 */
@Entity(tableName = "deadline_reminders")
@Serializable
data class DeadlineReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联的计时器（kind = Deadline）。 */
    val timerId: Long,
    /** 提前多少分钟提醒。 */
    val leadMinutes: Long,
    /** 是否已经触发过，避免重复提醒。 */
    val fired: Boolean = false,
)

/** 可选的前置提醒档位（分钟）。 */
object LeadTimeOptions {
    val all: List<Long> = listOf(1, 3, 5, 10, 15, 30, 60, 120, 180, 360, 720, 1440)
}
