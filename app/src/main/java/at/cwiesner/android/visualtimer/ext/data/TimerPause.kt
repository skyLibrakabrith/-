package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次计时里的「暂停区间」。
 *
 * ## 为什么需要单独一张表
 *
 * 用户的要求是：一次计时在归档里**只有一条**记录，但圆盘上要把暂停的那段
 * 空出来（暂停期间时间确实没有在走）。
 *
 * 而 [TimerSession] 只有 `startedAt` / `endedAt`，存不下中间的暂停：
 * 把一个 25 分钟计时拆成两条记录会让归档变成"两次计时"，语义就错了。
 * 所以暂停区间独立成行，挂在某条 session 下面。
 *
 * ## 区间语义
 *
 * - `[fromMillis, toMillis)`，单位 epoch millis。
 * - `toMillis = 0` 表示**还在暂停中**（没有封口）。此时它一直算到会话结束。
 * - 同一会话的多个区间保证互不重叠（由写入方保证，见 `ExtApplication`）。
 *
 * ## 为什么不建外键
 *
 * 与本项目一贯做法一致：归档是历史，会话行被删掉时这里跟着清理即可，
 * 不依赖数据库级联（也避免迁移时的 rebuild 麻烦）。索引只是查询加速。
 */
@Entity(
    tableName = "timer_pauses",
    indices = [Index("sessionId")],
)
data class TimerPause(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属的 `timer_sessions.id`。 */
    val sessionId: Long,
    /** 暂停开始时刻。 */
    val fromMillis: Long,
    /**
     * 暂停结束（恢复）时刻；**0 = 仍在暂停中**。
     *
     * 用 0 而不是 null：SQLite 的 `NOT NULL` 加默认值处理起来更省事，
     * 而且 0 在 epoch millis 里本来就是个不可能出现的真实时刻。
     */
    val toMillis: Long = 0L,
)
