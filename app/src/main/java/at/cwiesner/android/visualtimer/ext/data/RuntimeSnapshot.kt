package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 计时器运行时的一份**持久化快照**。
 *
 * ## 为什么需要它
 *
 * `PanelRunner.runtimes` 是纯内存的 `StateFlow`，进程一死就空了。
 * 之前只有 DDL 能从 `deadlineEpochMillis` 精确恢复，普通倒计时和正计时
 * **丢得干干净净**：退出重进后回到满格 / 回到 0 —— 用户看到的就是
 * 「所有卡片退出重进都丢失了进度」。
 *
 * ## 为什么单独一张表，而不是往 timers 上加列
 *
 * `timers` 存的是**配置**（这个计时器本来多长），快照存的是**状态**
 * （它现在剩多少）。两者的生命周期也不同：
 * - 配置改了不该顺手把状态改动（改时长有专门的同步逻辑）
 * - 状态每 2 秒都在变，写到 timers 表会让"配置"这个概念被高频写入污染
 * - 快照可以随时删掉而不影响配置（删掉=下次从配置重建）
 *
 * ## 字段语义
 *
 * | 字段 | 说明 |
 * |---|---|
 * | `remainingMillis` | 倒计时=剩余；正计时=**累计已计时长** |
 * | `overdueMillis` | 超时累计（倒计时走完后继续累加的那个值） |
 * | `running` | 存盘时是否在跑。恢复时**统一置为暂停**，见下 |
 * | `startedAt` | 这一轮的墙钟起点（归档用） |
 * | `stopwatchBaseMillis` | 正计时分段基准（见 `TimerRuntime.stopwatchSegmentMillis`） |
 * | `savedAt` | 存盘时刻，用来补算"离线期间过去了多久" |
 *
 * ## 恢复后为什么统一是"暂停"
 *
 * 冷启动瞬间让一堆计时器自己跑起来，而用户根本没按过开始，比"没恢复"更糟。
 * 所以恢复出来一律是待命态；运行态的进度已经按 `savedAt` 补算进
 * `remainingMillis`，用户点一下继续即可，**进度不会丢**。
 */
@Entity(tableName = "runtime_snapshots")
data class RuntimeSnapshot(
    /** 与 `timers.id` 一一对应；不建外键（本项目一贯做法）。 */
    @PrimaryKey val timerId: Long,
    val remainingMillis: Long,
    val overdueMillis: Long = 0L,
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val stopwatchBaseMillis: Long = 0L,
    val savedAt: Long,
)

/**
 * 把快照的**离线流逝**算进剩余时长。
 *
 * ## 为什么要这个纯函数
 *
 * 恢复时不能照抄 `remainingMillis`：计时器在进程死掉的那段时间里，
 * 如果当时是**在跑**的，墙钟一直在走，剩余量应该继续减少（正计时则继续增加）。
 * 如果当时是**暂停**的，那段时间不算数 —— 一秒都不该扣。
 *
 * 这正是用户要的行为：
 * - 「暂停了，退出重进，进度应该一秒不差」
 * - 「在跑的时候退出，重进后应该发现时间已经过去了」
 *
 * 抽成纯函数是为了能脱离 Room / Android 直接单测边界
 * （跨午夜、超过总时长、正计时三种情况）。
 *
 * @param remainingMillis 存盘时的剩余（正计时为累计）
 * @param totalMillis 计划总时长（正计时为 0，表示没有终点）
 * @param stopwatch 是否正计时
 * @param running 存盘时是否在跑
 * @param offlineMillis 从存盘到现在经过的毫秒数
 */
data class RestoredProgress(
    val remainingMillis: Long,
    val overdueMillis: Long,
)

fun computeRestoredProgress(
    remainingMillis: Long,
    overdueMillis: Long,
    totalMillis: Long,
    stopwatch: Boolean,
    running: Boolean,
    offlineMillis: Long,
): RestoredProgress {
    // 暂停状态：离线期间不算数，原样保留。
    // 这是「暂停的进度不丢失」的核心 —— 不做任何时间推算。
    if (!running || offlineMillis <= 0L) {
        return RestoredProgress(remainingMillis, overdueMillis)
    }

    // 正计时：没有终点，离线期间继续往上累加
    if (stopwatch || totalMillis <= 0L) {
        return RestoredProgress(remainingMillis + offlineMillis, 0L)
    }

    // 倒计时：先从剩余里扣；扣完了的部分转入"超时"
    val afterRun = remainingMillis - offlineMillis
    return if (afterRun > 0L) {
        RestoredProgress(afterRun, 0L)
    } else {
        // 已经把剩余耗尽：说明离线期间到点了。
        // 超时量 = 已经在超时的部分 + 这次新超出的部分
        RestoredProgress(0L, overdueMillis + (-afterRun))
    }
}
