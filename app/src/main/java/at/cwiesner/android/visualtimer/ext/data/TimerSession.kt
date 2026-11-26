package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次计时会话的归档记录（历史表）。
 *
 * 为什么不复用 [TimerItem]：计时器行会被删除、被改名、被改时长。
 * 历史必须是「当时发生了什么」的**事实快照**，不能随行一起变、更不能跟着行一起消失。
 * 所以这里把当时用到的信息冗余存一份，不建外键 ——
 * 行删了这条历史依然可读（本项目一贯不建外键，也免得级联删除误伤历史）。
 */
@Entity(tableName = "timer_sessions")
data class TimerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 冗余存当时的名字：计时器行删掉之后，历史里仍然看得出这是哪一次计时。 */
    val label: String?,
    /** 开始时刻（epoch millis）。 */
    val startedAt: Long,
    /** 结束时刻（epoch millis）。 */
    val endedAt: Long,
    /** 计划时长，用来算「提前结束 / 超时」的差。 */
    val plannedMillis: Long,
    /**
     * 计时器类型，存 [TimerKind] 的 `name`（**String 而非枚举**）。
     *
     * 刻意不声明成枚举、也不挂 TypeConverter：
     * 归档是**历史数据**，将来枚举增删成员（甚至改名）时，
     * 已经落库的老记录不能被解析失败牵连 —— 存字符串，未知值原样保留即可。
     */
    val kind: String,
    /** true = 正常到点（含超时）；false = 被用户提前结束。 */
    val completed: Boolean,
)
