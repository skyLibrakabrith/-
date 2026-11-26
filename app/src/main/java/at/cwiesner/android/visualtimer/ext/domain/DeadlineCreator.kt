package at.cwiesner.android.visualtimer.ext.domain

import android.util.Log
import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.DeadlineReminder
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind

/**
 * 创建 DDL 计时器：落库 → 挂面板 → **自动开始** → 登记提前提醒。
 *
 * ## 为什么要有这个文件
 *
 * 这个流程原本只存在于 [at.cwiesner.android.visualtimer.ext.ui.panels.PanelsScreen]
 * 的 `commitDeadline` 里（一个 Composable 内部的局部 suspend fun）。
 * 而首页 `HomeScreen` 的「创建 DDL」按钮走的却是另一套 `createTimer()`，
 * 那套只写了 `kind` 和 `deadlineEpochMillis`，四件事漏了三件：
 *
 * | 该做 | 首页那套 | 后果 |
 * |---|---|---|
 * | `locked = true` | 没写 | DDL 可被编辑 |
 * | `startUntil()` | 没调 | **创建后不自动开始** |
 * | 写 `deadline_reminders` | 没写 | **永远不会有提醒** |
 *
 * 首页是底部导航第一个 tab（默认落地页），所以这条有缺陷的路径反而是最常走的。
 * 抽成顶层函数后两处共用同一份实现，就不会再分叉。
 *
 * ## 为什么纯函数部分单独拆出来
 *
 * 本机没有 JDK / 模拟器，`app/src/test` 只能跑纯 Kotlin（没有 Robolectric）。
 * 所以把「算时长 / 算提醒 / 判断哪些档位已经过去」抽成 [planDeadline]，
 * 让它在 JVM 单测里能真跑；真正的 DAO 写入留在 [createDeadline] 里，
 * 由真机 adb 验证覆盖（那比单测更可信）。
 */
object DeadlineCreator {

    /**
     * 创建一条 DDL 前的纯计算部分。
     *
     * @property durationMillis 初始时长（毫秒）。**至少 1000** ——
     *   截止时刻若已经过去，`deadlineAt - now` 会是 0 或负数，
     *   直接当 duration 用会建出一个 0 秒计时器，界面上等于瞬间到点。
     * @property reminders 要去重后写进 `deadline_reminders` 的档位（分钟），升序。
     * @property alreadyPastLeads 这些档位「已经错过了」（剩余分钟已经少于该档位），
     *   必须交给 [PanelRunner.markLeadsBaseline] 标成已触发。
     *
     *   为什么必须标：[PanelRunner.tick] 的触发判据是 `lead <= 剩余分钟`。
     *   举例：距截止还有 5 分钟，用户设了「提前 10 分钟」和「提前 2 分钟」两档。
     *   10 分钟那档**已经过了**（剩 5 分已经小于 10 分），如果不标，
     *   第一次 tick 就会立刻弹出「距离截止还有 10 分钟」——纯属打扰。
     */
    data class Plan(
        val durationMillis: Long,
        val reminders: List<Long>,
        val alreadyPastLeads: Set<Long>,
    )

    /**
     * 计算创建一条 DDL 需要的一切。
     *
     * @param now 当前时刻（epoch millis），显式传入以便单测固定时间。
     * @param deadlineAt 目标截止时刻（epoch millis）。
     * @param leads 用户选的提前提醒档位（分钟），可能为空、可能含重复。
     */
    fun planDeadline(now: Long, deadlineAt: Long, leads: List<Long>): Plan {
        val durationMillis = (deadlineAt - now).coerceAtLeast(MIN_DURATION_MILLIS)
        val reminders = leads.filter { it > 0L }.distinct().sorted()
        // 剩余分钟用整数除法，与 PanelRunner.markLeadsBaseline 的算法保持一致
        // （那边是 `lead <= remainingMillis / 60_000L`）。
        // 两处口径不同会导致「创建时判为未过、第一次 tick 却立刻触发」。
        val remainMin = durationMillis / 60_000L
        val alreadyPast = reminders.filter { it <= remainMin }.toSet()
        return Plan(
            durationMillis = durationMillis,
            reminders = reminders,
            alreadyPastLeads = alreadyPast,
        )
    }

    /**
     * 真正创建一条 DDL。
     *
     * 参数全部显式传入（面板 id / 截止时刻 / 名称），不依赖 Composable 局部变量 ——
     * 调用方可能在重组之后才执行，那时捕获到的 `currentPanel` 等值可能已经过期。
     *
     * @return 新建计时器的 id。
     */
    suspend fun createDeadline(
        db: AppDatabase,
        runner: PanelRunner,
        panelId: Long,
        label: String,
        deadlineAt: Long,
        leads: List<Long>,
        alertProfileId: Long?,
        now: Long,
    ): Long {
        val plan = planDeadline(now, deadlineAt, leads)

        val timerId = db.timerDao().upsert(
            TimerItem(
                label = label.ifBlank { "到点倒计时" },
                durationMillis = plan.durationMillis,
                deadlineEpochMillis = deadlineAt,
                kind = TimerKind.Deadline,
                // DDL 一旦创建就锁定：它的时间由截止时刻决定，不允许再改。
                // 配合 TimerItem.isEditable，编辑入口对它不出现。
                locked = true,
                alertProfileId = alertProfileId,
            ),
        )

        // 挂到面板末尾
        val existing = runCatching { db.panelDao().refsOf(panelId) }.getOrDefault(emptyList())
        db.panelDao().upsertRefs(
            listOf(
                PanelTimerRef(
                    panelId = panelId,
                    timerId = timerId,
                    order = (existing.maxOfOrNull { it.order } ?: -1) + 1,
                ),
            ),
        )

        // ---- 自动开始 ----
        // DDL 建好就该在跑，用户不需要再点一下。
        // 这一句是「不自动开始」那个 bug 的核心：漏了它，卡片会一直显示一个
        // 静止的数字（看起来像在跑，其实永远不会变）。
        val started = db.timerDao().get(timerId)
        if (started == null) {
            // get 立刻返回 null 说明 upsert 与查询之间出了预期外的问题
            // （事务边界、id 类型不符等）。不静默吞掉 —— 这类问题在真机上
            // 表现就是"建出来了但不动"，没有日志的话只能靠猜。
            Log.w(TAG, "DDL upsert 后立刻 get($timerId) 返回 null，无法 startUntil")
        } else {
            runner.startUntil(started, deadlineAt, now)
        }
        // 打完卡立刻回读运行时状态，让 logcat 一眼看出属于哪种情况：
        //   · startUntil 没执行（上一条 Log.w）
        //   · 执行了但 running 仍为 false
        //   · running 为 true（那就是界面侧读错，不是运行时的问题）
        val rt = runner.get(timerId)
        Log.i(
            TAG,
            "DDL created: id=$timerId locked=true remain=${plan.durationMillis / 1000}s " +
                "leads=$leads runtime=" +
                if (rt == null) "null" else "running=${rt.running} rem=${rt.remainingMillis}",
        )

        // ---- 提前提醒落库 ----
        // 先清后写：同一个 timerId 理论上不会有旧行（id 是新的），
        // 但清一次是幂等的，能防住任何 id 复用的情况。
        db.deadlineReminderDao().clearTimer(timerId)
        if (plan.reminders.isNotEmpty()) {
            db.deadlineReminderDao().upsertAll(
                plan.reminders.map { m ->
                    DeadlineReminder(timerId = timerId, leadMinutes = m)
                },
            )
        }

        // 回读一次建立「档位 → 行 id」映射。
        // 为什么非要回读：`upsertAll(List)` 不返回自增 id，而提前提醒触发后要把
        // 那一行标成 fired = 1（否则冷启动恢复分不清响过的和没响过的，会重复响）。
        val savedRows = if (plan.reminders.isEmpty()) {
            emptyList()
        } else {
            runCatching { db.deadlineReminderDao().forTimer(timerId) }
                .getOrDefault(emptyList())
        }
        runner.attachLeadMinutes(timerId, plan.reminders, DeadlineRestore.rowIdsOf(savedRows))
        // 把「已经过去的档位」标为已触发，否则第一次 tick 会把它们一次性全响
        runner.markLeadsBaseline(timerId, plan.durationMillis)

        return timerId
    }

    /** 初始时长的下限。见 [Plan.durationMillis] 的说明。 */
    const val MIN_DURATION_MILLIS: Long = 1000L

    /**
     * 日志标签。
     *
     * 注意：`android.util.Log` 在纯 JVM 单测里会抛 "not mocked" 异常，
     * 所以 [createDeadline]（会打日志的那部分）**不进单测** ——
     * 单测只测纯净的 [planDeadline]，它不碰任何 Android 类。
     * 这里 import android.util.Log 是安全的，因为单测根本不会加载
     * createDeadline 那个函数体（JVM 只在调用时才解析）。
     */
    private const val TAG = "DeadlineCreator"
}
