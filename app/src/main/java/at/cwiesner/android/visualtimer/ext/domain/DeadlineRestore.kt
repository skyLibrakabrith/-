package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.DeadlineReminder
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind

/**
 * 冷启动后要从 `deadline_reminders` 表恢复哪些提前提醒。
 *
 * **为什么需要这一步**：`deadline_reminders` 表一直有写入（建 DDL 时 `upsertAll`），
 * 但它的三个读取方法（`forTimer` / `pending` / `markFired`）此前**零调用** ——
 * `PanelRunner` 的 `leadMinutesOf` 是纯内存的，进程一死就空，
 * 于是「提前 30 分钟提醒」在重启后永远不会响。写入侧一直是好的，缺的是读取侧。
 *
 * 这里把「哪些还能响」做成纯函数，好让它在单测里真跑（本机没有 JDK，单测是唯一能跑的验证）。
 * 真正的接线在 [at.cwiesner.android.visualtimer.ext.ExtApplication.restoreDeadlines]：
 * 它负责查库，把结果交给 [PanelRunner.attachLeadMinutes]。
 */
object DeadlineRestore {

    /**
     * 一条 DDL 的提前提醒在冷启动时该恢复成什么。
     *
     * @property liveLeads 还需要挂到运行时上的档位（分钟）。
     * @property staleRowIds 已经不可能再触发的提醒行 id，应当直接标成已触发。
     */
    data class Plan(
        val liveLeads: List<Long>,
        val staleRowIds: List<Long>,
    )

    /**
     * 计算一条 DDL 的恢复计划。
     *
     * 规则（每条都对应一个具体的错误行为）：
     * 1. `kind != Deadline` → 什么也不做。提前提醒只对 DDL 有意义，
     *    普通倒计时的 `restore` 不重建运行时，给它挂档位也没人 tick。
     * 2. `deadlineEpochMillis == null` → 同上。没有绝对截止时刻就不是 DDL。
     * 3. 截止时刻已经过去 → 把它的提醒全部标成已触发。
     *    否则这些行会永远停在 `fired = 0`，被导出进备份、并在下次冷启动时被反复处理。
     * 4. 仍在运行中：**已经过去的档位直接标已触发，不挂到运行时**。
     *    这一条是必须的 —— [PanelRunner.tick] 的判据是 `lead <= 剩余分钟`，
     *    如果挂了但没标，重新打开 App 的第一次 tick 会把所有已过去的档位
     *    **一次性全部触发**（同一个人同时收到「提前 2 小时」「提前 1 小时」…一串通知）。
     *    这与 [PanelRunner.markLeadsBaseline] 在建 DDL 时做的事完全一致。
     * 5. 库里已经 `fired = true` 的行不再挂 —— 用户已经收到过这次提醒，
     *    重启后再响一遍就是重复打扰。
     *
     * @param now 当前时刻（epoch millis）。显式传入是为了能在单测里固定时间。
     */
    fun plan(
        timer: TimerItem,
        reminders: List<DeadlineReminder>,
        now: Long,
    ): Plan {
        // 非 Deadline 的计时器不该有提前提醒行（历史 bug / 手工改的备份会造出这种垃圾行），
        // 它们永远不会被恢复，留着只会被反复处理并导出进备份，所以直接标成已触发
        if (timer.kind != TimerKind.Deadline) {
            return Plan(emptyList(), reminders.filter { !it.fired }.map { it.id })
        }
        val deadline = timer.deadlineEpochMillis
            ?: return Plan(emptyList(), reminders.filter { !it.fired }.map { it.id })

        val unfired = reminders.filter { !it.fired }
        val remainingMinutes = (deadline - now) / 60_000L
        if (remainingMinutes <= 0L) {
            // 已经到点或过期：这些提醒不可能再触发了
            return Plan(emptyList(), unfired.map { it.id })
        }

        val live = unfired.filter { it.leadMinutes <= remainingMinutes }
        val stale = unfired.filter { it.leadMinutes > remainingMinutes }
        return Plan(
            liveLeads = live.map { it.leadMinutes }.distinct().sortedDescending(),
            staleRowIds = stale.map { it.id },
        )
    }

    /**
     * 建档时写库的提醒行与档位对齐。
     *
     * `commitDeadline` 先 `upsertAll` 再 `setLeadMinutes`，
     * 但 upsert 拿不到行 id（`List` 版的 `@Upsert` 返回 Unit），
     * 所以要回读一次 `forTimer` 才能建立「档位 → 行 id」映射。
     * 这里只做「把回读结果折成 map」，同样是为了能在单测里真跑。
     */
    fun rowIdsOf(reminders: List<DeadlineReminder>): Map<Long, List<Long>> =
        reminders.groupBy { it.leadMinutes }.mapValues { (_, rows) -> rows.map { it.id } }
}
