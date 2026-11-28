package at.cwiesner.android.visualtimer.ext

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import at.cwiesner.android.visualtimer.ext.alarm.VibrationAlerter
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.data.RuntimeSnapshot
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.computeRestoredProgress
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.data.VibrationPresets
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.DeadlineRestore
import at.cwiesner.android.visualtimer.ext.domain.PanelRunner
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.service.AlertOverlayService
import at.cwiesner.android.visualtimer.ext.service.TimerOverlayService
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.util.logCatching
import at.cwiesner.android.visualtimer.ext.util.logCatchingOr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用入口。
 *
 * 除了持有数据库与计时器运行时，它还负责**到点提醒**：
 * 订阅 [PanelRunner.alerts]，触发震动与通知。
 * 这样无论用户当前在哪个界面、甚至已经切到后台，倒计时结束都能感知到。
 *
 * 屏幕闪烁需要 Activity 的 window，由前台界面侧单独处理。
 */
class ExtApplication : Application() {

    /** 应用级作用域，用于计时、警报等长生命周期任务。 */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    /**
     * 一次计时归档的唯一写入者。
     *
     * 所有归档路径（暂停 / 恢复 / 重置 / 停到点 / 用户结束）都走它，
     * 语义只有一份，避免再次出现"两条路径各算一遍、结果不一致"。
     */
    val sessionArchiver by lazy {
        at.cwiesner.android.visualtimer.ext.domain.SessionArchiver(database)
    }

    /**
     * 警报配置（振动模式）的**应用级共享订阅**。
     *
     * 以前 AlertsScreen / PanelsScreen / PanelState 各自
     * `remember { db.alertProfileDao().observeAll() }` + `collectAsState(initial = emptyList())`。
     * 每次切进页面都新建一个 Flow、重新查一次 Room，而首帧只能画空列表 ——
     * 于是振动波形区先空一下再出现（用户报的「振动波形会闪」）。
     *
     * 现在统一在这里订阅一次：应用启动就开始查，页面直接读缓存。
     * 首帧要么已经有数据，要么在极短时间内到齐，不再每次重建重查。
     *
     * 初始值是空表：冷启动时仍可能先空一帧，但切标签绝不会再闪。
     */
    val alertProfiles: StateFlow<List<at.cwiesner.android.visualtimer.ext.data.AlertProfile>> by lazy {
        database.alertProfileDao().observeAll()
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())
    }


    /**
     * 数据库打开失败的提示。
     *
     * [AppDatabase.get] 去掉了 `fallbackToDestructiveMigration()`，所以迁移缺失时
     * Room 会**抛异常而不是静默清库**。这里把那次异常变成一句看得懂的提示，
     * 数据文件原地保留，用户可以先把备份导出来。
     *
     * 取值非空时，界面层应弹一次提示并把该标志清掉。
     */
    val databaseOpenError = MutableStateFlow<String?>(null)

    /**
     * 计时器运行时。挂在这里而不是界面上，
     * 这样切走底部标签、甚至按 Home 键，倒计时都不会被清掉。
     */
    val panelRunner: PanelRunner by lazy { PanelRunner(appScope) }

    /**
     * 逐步间隔的运行时引擎。
     *
     * 挂在 Application 上，这样悬浮窗的「下一阶段」按钮
     * 可以在应用不在前台时也推进序列。
     */
    val sequenceEngine: at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine by lazy {
        at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine(
            scope = appScope,
            onStepFinished = { step ->
                val name = step.label ?: "阶段完成"
                AlertOverlayService.start(
                    this@ExtApplication,
                    name,
                    step.id,
                    sequenceId = step.sequenceId,
                )
            },
            onSequenceEnded = { runs ->
                // 序列结束（跑完或被停掉）：每个**跑过的**阶段写一条归档。
                //
                // 用户要求：「序列被停止或跑完时，每个跑过的阶段记一条」。
                // 为什么按阶段而不是按序列：序列里的阶段本身就是一次次计时
                // （各有计划时长），分开记才能在归档里看出"哪一步超时了"。
                //
                // 太短的阶段按 ArchiveRules 挡掉，避免跳步操作刷出一堆 0 秒记录。
                val dao = database.timerSessionDao()
                val now = System.currentTimeMillis()
                for (r in runs) {
                    val planned = r.step.durationMillis
                    if (!at.cwiesner.android.visualtimer.ext.domain.ArchiveRules.shouldArchive(
                            stopwatch = false,
                            elapsedMillis = r.actualMillis,
                            plannedMillis = planned,
                        )
                    ) {
                        continue
                    }
                    // 区间按"从结束时刻往回退实际用时"推，保证与盘面显示一致
                    val endedAt = now
                    val startedAt = endedAt - r.actualMillis
                    logCatching("archive-sequence-step") {
                        dao.insert(
                            TimerSession(
                                label = r.step.label ?: "阶段 ${r.stepIndex + 1}",
                                startedAt = startedAt,
                                endedAt = endedAt,
                                plannedMillis = planned,
                                kind = TimerKind.Countdown.name,
                                completed = r.actualMillis >= planned,
                            ),
                        )
                    }
                }
            },
        )
    }

    /** 悬浮窗点「下一阶段」时调用。 */
    fun advanceSequence() {
        sequenceEngine.confirm()
    }

    /**
     * 序列悬浮窗要显示的快照。
     *
     * 为什么要缓存：`SequenceOverlayService` 是**纯 View 代码、没有协程作用域**
     * （照 `TimerOverlayService` / `AlertOverlayService` 的既定做法），
     * 它没法 `collect` `sequenceEngine.phase`。所以这里由应用级作用域订阅一次，
     * 把需要显示的字段算好、放在这个不可变快照里，服务端每 300ms 直接读。
     *
     * 只在 [at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine.Phase.Running] /
     * `AwaitingConfirm` 时非空，Idle 与 Finished 一律置 null（服务端看到 null 就把卡片收起）。
     *
     * 声明成 `internal` 而不是 public：它暴露的类型 `SequenceOverlayInfo` 是 internal 的，
     * 而 public 属性不能暴露 internal 类型（编译错误：Property 'public' exposes its
     * 'internal' type）。这个字段本来也只给 service 包读，internal 更准确。
     */
    @Volatile
    internal var seqOverlayInfo: SequenceOverlayInfo? = null
        private set

    /** 用户处理了提醒：停掉可能还在播的声音。 */
    fun stopAlertSound() {
        logCatching("stopAlertSound") { headphoneSound.stop() }
    }

    private val vibration: VibrationAlerter by lazy { VibrationAlerter(this) }

    /** 耳机限定声音：没有耳机时完全不响，避免外放打扰。 */
    private val headphoneSound: at.cwiesner.android.visualtimer.ext.alarm.HeadphoneSoundAlerter
        by lazy { at.cwiesner.android.visualtimer.ext.alarm.HeadphoneSoundAlerter(this) }
    /**
     * 全应用共享的设置仓库。
     *
     * **挂在 Application 上而不是各页面各自 new**：设置需要**预热** ——
     * 传 appScope 进去，DataStore 一启动就开始读，缓存好之后
     * 页面 `collectAsState` 的首帧就是真值，切标签时才不会「先闪默认值」。
     *
     * 如果每个页面自己 `remember { SettingsRepo(context) }`，那就是一份新缓存、
     * 重新读一次盘，预热白做。
     */
    val settings: SettingsRepo by lazy { SettingsRepo(this, appScope) }

    /**
     * 屏幕闪烁的暗场间隔（毫秒）。
     *
     * 缓存一份是因为 [AlertOverlayService] 是纯 View 代码、没有协程作用域，
     * 用不了 SettingsRepo 的 Flow；启动时订一次，之后服务端直接读这个值。
     */
    @Volatile
    var flashIntervalMillis: Long = SettingsRepo.DEFAULT_FLASH_INTERVAL
        private set

    override fun onCreate() {
        super.onCreate()
        seedDefaultsIfEmpty()
        createAlertChannel()
        // 立刻开始走表，不依赖任何界面存在
        panelRunner.startTicking()
        // 订阅要先于恢复：`_alerts` / `_leadAlerts` 是没有 replay 的 SharedFlow，
        // 没有订阅者时 tryEmit 的事件直接丢弃。恢复流程若先跑完，冷启动那一刻
        // 本该响的提醒会被静默吞掉。
        observeAlerts()
        observeFlashSetting()
        observeSequenceOverlay()
        // 恢复放在订阅之后，且要等那几个 launch 真正把 collect 挂上 ——
        // 见 restoreDeadlines 里那句 yield()。
        restoreDeadlines()
        // 非 DDL 的计时器按**快照**恢复进度（退出重进不丢进度），
        // 没有快照的再补一个待命运行时。
        //
        // 顺序要紧：
        // 1. 先按快照恢复（能接上真实的剩余量 / 累计量）
        // 2. 再给"没有快照的"补待命运行时（首次升级到 v10 的那批计时器就属于这种）
        // 反过来的话 restoreInactive 会先塞一个满格的进去，
        // 而 applyRestored 遇到已存在的条目会跳过 —— 快照就白读了。
        restoreProgressAndInactive()
    }

    /**
     * 启动恢复：先按快照接进度，再给没快照的补待命运行时。
     *
     * 合成一个 launch 是为了保证这两步的**顺序**：分成两个 `appScope.launch`
     * 会并发跑，`restoreInactive` 可能抢在快照恢复之前塞进满格状态。
     */
    private fun restoreProgressAndInactive() {
        appScope.launch(Dispatchers.IO) {
            logCatching("restore-progress") { restoreProgressFromSnapshots() }
            logCatching("restore-inactive") {
                val all = database.timerDao().observeAllOnce()
                panelRunner.restoreInactive(all)
            }
            // 清掉指向已删计时器的快照
            logCatching("restore-snapshot-orphans") {
                database.runtimeSnapshotDao().deleteOrphans()
            }
        }
    }

    private fun observeFlashSetting() {
        appScope.launch {
            settings.flashIntervalMillis.collect { flashIntervalMillis = it }
        }
    }

    /**
     * 冷启动恢复：把 `deadline_reminders` 读回运行时，并重建仍在未来到期的 DDL。
     *
     * **为什么必须做**：`PanelRunner` 的运行时（包括 `leadMinutesOf`）是纯内存的。
     * 创建 DDL 时提醒写进了库、也塞给了运行时，但进程一死全丢；
     * 而读取侧（`forTimer` / `pending` / `markFired`）此前**零调用** ——
     * 于是「提前 30 分钟提醒」只在创建后当次进程里有效，重启后永远不会响。
     * 同样地，`_runtimes` 也没有任何持久化，DDL 重启后就从卡片上消失（不再到点提醒）。
     *
     * 取舍：只恢复 **DDL**，不恢复普通倒计时。
     * DDL 的语义是绝对时刻（「今晚 8 点前交」），重启后剩余时间照样算得出来；
     * 普通倒计时没有这个锚点，README 的既定取舍就是进程内有效。
     *
     * 顺序要紧：
     * 1. 先重建运行时（`startUntil` 会按「截止时刻 − 现在」重算，不依赖旧的时长）；
     * 2. 再挂提前提醒，并用 `markLeadsBaseline` 把已经过去的档位标掉 ——
     *    否则第一次 tick 会把所有已过的档位一次性全响；
     * 3. 最后把不可能再触发 / 已经响过的行写回 `fired = 1`，避免下次重复。
     *
     * 放在 IO 线程：这是启动路径，不能为了几条 Room 查询阻塞主线程（ANR）。
     */
    private fun restoreDeadlines() {
        appScope.launch(Dispatchers.IO) {
            // 让出一次，给 onCreate 里那几个 `appScope.launch`（订阅 alerts / leadAlerts）
            // 真正把 collect 挂上去。
            //
            // 为什么需要：`_leadAlerts` 是 `extraBufferCapacity = 16` 但没有 replay 的
            // MutableSharedFlow —— 连一个订阅者都没有时，`tryEmit` 返回 false 并**丢弃**事件。
            // `observeAlerts()` 里的 launch 与这里的 launch 都在主线程排队，
            // 不 yield 的话恢复可能先跑，冷启动那一刻该响的提前提醒就丢了。
            // 订阅侧是 Main.immediate，这里 yield 回主线程，它们先执行。
            kotlinx.coroutines.yield()
            val restored = logCatchingOr("restore-deadlines", 0) {
                val db = database
                val all = db.timerDao().observeAllOnce()
                val now = System.currentTimeMillis()

                // 已经被删掉却还挂着提醒的孤儿行（历史 bug 的存量数据）先清掉，
                // 否则它们会一直停在 fired = 0 并被导出进备份
                val knownIds = all.map { it.id }.toSet()
                db.deadlineReminderDao().allOnce().forEach { r ->
                    if (r.timerId !in knownIds) {
                        logCatching("restore-deadlines-orphan") {
                            db.deadlineReminderDao().clearTimer(r.timerId)
                        }
                    }
                }

                // 同样清掉孤儿步骤：`sequenceId` 指向的序列已经不存在的行。
                //
                // 存量来源：保存序列时曾把 `@Upsert` 的返回值（更新分支是 -1）
                // 当成新 id 去写步骤，于是产生一批 `sequenceId = -1` 的行。
                // 代码已修，但已经写坏的数据不清会一直留在库里、被导出进备份。
                logCatching("restore-orphan-steps") {
                    db.sequenceDao().deleteOrphanSteps()
                }

                var count = 0
                all.filter { it.kind == TimerKind.Deadline }.forEach { t ->
                    val deadline = t.deadlineEpochMillis
                    if (deadline == null) {
                        // 数据不自洽（kind=Deadline 却没有截止时刻）：恢复不出运行时，
                        // 但它的提醒行不能永远停在 fired = 0，直接标掉
                        logCatching("restore-deadlines-null") {
                            db.deadlineReminderDao().forTimer(t.id).filter { !it.fired }
                                .forEach { db.deadlineReminderDao().markFired(it.id) }
                        }
                        return@forEach
                    }
                    if (deadline <= now) {
                        // 已经过期的 DDL：不再重建运行时（它已经没意义了），
                        // 但提醒行必须标成已触发，否则会一直停在 fired = 0
                        // 并被反复处理 / 随备份漂来漂去
                        logCatching("restore-deadlines-past") {
                            db.deadlineReminderDao().forTimer(t.id).filter { !it.fired }
                                .forEach { db.deadlineReminderDao().markFired(it.id) }
                        }
                        return@forEach
                    }
                    // 已经存在运行时说明它这次进程里已经跑着了，不要重建 ——
                    // startUntil 会 acknowledge 掉正在响的提醒，等于把提醒静音
                    if (panelRunner.get(t.id) != null) return@forEach

                    panelRunner.startUntil(t, deadline, now)
                    val reminders = db.deadlineReminderDao().forTimer(t.id)
                    val plan = DeadlineRestore.plan(t, reminders, now)
                    // 只登记「还需要挂载」的档位到行 id 映射：
                    // tick 触发时靠它找到要写 fired=1 的那一行
                    val liveRowIds = DeadlineRestore.rowIdsOf(
                        reminders.filter { it.leadMinutes in plan.liveLeads && !it.fired },
                    )
                    panelRunner.attachLeadMinutes(t.id, plan.liveLeads, liveRowIds)
                    // 已经过去的档位：挂上之后立刻标成已触发，与建 DDL 时的做法一致
                    panelRunner.markLeadsBaseline(
                        t.id,
                        (deadline - now).coerceAtLeast(0L),
                    )
                    plan.staleRowIds.forEach { rowId ->
                        logCatching("restore-deadlines-stale") {
                            db.deadlineReminderDao().markFired(rowId)
                        }
                    }
                    count++
                }
                count
            }
            Log.i(TAG, "冷启动恢复 DDL 运行时：$restored 条")
        }
    }

    // ---------------- 序列悬浮窗 ----------------

    /**
     * 把 [StepSequenceEngine.phase] 同步到 [seqOverlayInfo]，并据此起停 [SequenceOverlayService]。
     *
     * 用的是 200ms 轮询而不是直接 `collect(phase)`：引擎每 100ms 就发一次新状态，
     * 但悬浮窗这里**只关心三件事** —— 有没有活动序列、处于哪个大阶段、
     * 序列名 / 阶段名 / 总时长。把它们抽出来比一比，值没变就整轮跳过，
     * 值变了才写缓存（也就不会每秒十几次地触发服务端刷新）。
     *
     * 起停规则：
     * - 设置里「悬浮窗」关着 → 停；
     * - 系统没给 SYSTEM_ALERT_WINDOW → 停（省掉一次注定失败的 startForegroundService）；
     * - 否则 Idle / Finished → 停，Running / AwaitingConfirm → 起。
     *
     * 服务的 start/stop 本身是幂等的（重复 start 只是再走一次 onStartCommand），
     * 所以这里可以放心每 200ms 判断一次。
     */
    private fun observeSequenceOverlay() {
        appScope.launch {
            var overlayEnabled = false
            launch { settings.overlayEnabled.collect { overlayEnabled = it } }

            while (true) {
                val phase = sequenceEngine.phase.value
                // 序列名要查库（异步），先按需发起，再取当前已知的名字拼快照
                val activeId = sequenceEngine.activeSequenceId
                requestSequenceName(activeId)
                val info = buildSequenceOverlayInfo(phase)
                if (info != seqOverlayInfo) {
                    seqOverlayInfo = info
                }

                val shouldShow = overlayEnabled &&
                    info != null &&
                    logCatchingOr("seq-overlay-perm", false) {
                        android.provider.Settings.canDrawOverlays(this@ExtApplication)
                    }
                if (shouldShow) {
                    logCatching("seq-overlay-start") {
                        at.cwiesner.android.visualtimer.ext.service.SequenceOverlayService
                            .start(this@ExtApplication)
                    }
                } else if (!overlayEnabled) {
                    // 设置已关：一定要停，用户点「关闭悬浮窗」要立刻生效
                    logCatching("seq-overlay-stop-setting") {
                        at.cwiesner.android.visualtimer.ext.service.SequenceOverlayService
                            .stop(this@ExtApplication)
                    }
                } else if (info == null) {
                    // 序列闲置 / 已结束
                    logCatching("seq-overlay-stop-idle") {
                        at.cwiesner.android.visualtimer.ext.service.SequenceOverlayService
                            .stop(this@ExtApplication)
                    }
                }
                // 剩下一种情况：有活动序列、设置开着，但没给系统权限 —— 什么都不做，
                // 留给设置页去引导授权（悬浮窗本身也无法显示）。

                kotlinx.coroutines.delay(SEQUENCE_OVERLAY_POLL_MILLIS)
            }
        }
    }

    /**
     * 把引擎阶段折算成悬浮窗要显示的那几行字。
     *
     * 只读取 Phase.Running 的 `stepIndex` / `step` / `remainingMillis` / `totalMillis`，
     * **刻意不读 `paused`** —— 那个字段由另一个任务在改，读了就耦合了。
     */
    private fun buildSequenceOverlayInfo(
        phase: at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine.Phase,
    ): SequenceOverlayInfo? = when (phase) {
        is at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine.Phase.Running -> {
            val step = phase.step
            SequenceOverlayInfo(
                sequenceId = sequenceEngine.activeSequenceId,
                sequenceName = currentSequenceName,
                stepCaption = "第 ${phase.stepIndex + 1} 阶段 · " +
                    (step.label ?: DeadlineCalculator.formatRemaining(step.durationMillis)),
                countdownText = DeadlineCalculator.formatRemaining(phase.remainingMillis),
                awaitingConfirm = false,
                elapsedMillis = 0L,
                overtimeMillis = 0L,
                timeoutRemainingMillis = 0L,
                overdue = false,
            )
        }

        is at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine.Phase.AwaitingConfirm -> {
            val step = phase.finishedStep
            val next = phase.nextStep?.label
            SequenceOverlayInfo(
                sequenceId = sequenceEngine.activeSequenceId,
                sequenceName = currentSequenceName,
                // 悬浮窗第二行固定显示「已完成，等你确认」，这里把「完成的是哪一步 / 下一步是什么」并进来
                stepCaption = (step.label ?: "第 ${phase.finishedStepIndex + 1} 阶段") +
                    if (next != null) " · 下一步：$next" else " · 最后一步",
                countdownText = "",
                awaitingConfirm = true,
                elapsedMillis = phase.elapsedMillis,
                overtimeMillis = phase.overtimeMillis,
                timeoutRemainingMillis = phase.timeoutRemainingMillis,
                overdue = phase.overdue,
            )
        }

        else -> null
    }

    /**
     * 当前活动序列的名字。
     *
     * 引擎只持有 [StepSequenceEngine.activeSequenceId]（id），**没有名字**，
     * 所以得查一次 Room。`sequenceDao().get()` 是 suspend 的，不能在主线程直接调，
     * 因此由协程在 id 变化时异步加载，结果放进 [currentSequenceName] 给主循环读。
     *
     * 名字晚一拍到不影响观感：卡片第一拍显示「序列」，一拍之后就是真名。
     */
    @Volatile
    private var currentSequenceName: String = "序列"

    /** 已经发起过查库的序列 id，避免同一个 id 被反复查询。 */
    @Volatile
    private var requestedSequenceNameId: Long? = null

    private fun requestSequenceName(sequenceId: Long?) {
        if (sequenceId == null) {
            currentSequenceName = "序列"
            requestedSequenceNameId = null
            return
        }
        if (sequenceId == requestedSequenceNameId) return
        requestedSequenceNameId = sequenceId
        currentSequenceName = "序列"
        appScope.launch {
            val name = logCatching("seq-name-get") {
                database.sequenceDao().get(sequenceId)?.name
            }
            // 加载期间用户可能已经换了序列，别把旧名字写回去
            if (name != null && requestedSequenceNameId == sequenceId) {
                currentSequenceName = name
            }
        }
    }

    // ---------------- 到点提醒 ----------------

    /**
     * 计时器到点时：写一条归档记录；若是**未固定**的，再把它从面板上移除。
     *
     * ## 为什么所有计时器都写归档（不只是未固定的）
     *
     * 归档页的用途是"看每天什么时候开始/结束了什么"。
     * 如果只记被自动销毁的那些，正常使用的历史就全丢了 ——
     * 用户看到的归档会莫名其妙地稀疏。
     *
     * ## 为什么未固定的要移除
     *
     * `pinned = false` 表达的是"这是一次性的计时"（比如泡面 3 分钟）。
     * 到点后它已经完成使命，留在面板上只是噪音。
     * 移除时连带清掉：面板引用、组成员、提前提醒行、计时器行本身 ——
     * 少清一样就会留下孤儿数据（`groupedTimerIds` 会让 id 既不在网格、
     * 也没有组卡片，表现为"计时器凭空消失"）。
     *
     * ## 为什么手动删除/重置不写归档
     *
     * 用户主动删掉的东西说明他不在乎，不该污染历史。只有自然到点才算"发生过"。
     */
    /**
     * 正计时被暂停时写一条归档。
     *
     * ## 为什么正计时需要单独的归档路径
     *
     * 归档原本只接在「到点」事件（`panelRunner.alerts`）上。但正计时**永远不到点** ——
     * 它只往上累加，没有终点。所以它走不到那条路径，归档里一条正计时都没有。
     *
     * 正计时唯一的"结束"方式就是用户按暂停，所以暂停才是最该落一条历史的时刻。
     *
     * ## 一条暂停 = 一条记录
     *
     * 继续之后再暂停会写**第二条**，各自对应时间轴上一段真实的计时区间。
     * 为此 [PanelRunner.resume] 会在继续时把 `startedAt` 挪到当下、
     * 并记下 `stopwatchBaseMillis`，所以这里能算出"这一段"的长度。
     *
     * ## 区间怎么算
     *
     * - `startedAt`：这一段开始的墙钟时刻（由 resume 维护）
     * - `endedAt`：**暂停这一刻**的墙钟时刻。用 `startedAt + 这一段时长` 而不是
     *   `System.currentTimeMillis()`：tick 是 200ms 一拍，暂停时算出的时长
     *   比真实经过的时间最多少 200ms，两者相加会让区间终点比实际略早 ——
     *   但这样**时长与盘面上显示的数字严格一致**，用户核对时不会觉得记错了。
     * - `plannedMillis`：正计时没有"计划时长"，存这一段实际计时的长度
     *   （归档页用它算"计划 vs 实际"的差；正计时本来就该是"实际 = 计划"）。
     * - `completed = true`：正计时没有"到点"概念，不存在"提前结束"这种说法。
     */
    private suspend fun archiveStopwatchPause(rt: TimerRuntime) {
        val timer = logCatching("archive-stopwatch-get-timer") {
            database.timerDao().get(rt.timerId)
        }
        val startedAt = rt.startedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val segment = rt.stopwatchSegmentMillis
        // 一暂停就立刻暂停（时长约等于 0）：没有意义，不写。
        // 阈值取 1 秒，和 DeadlineCreator.MIN_DURATION_MILLIS 保持一致。
        if (segment < 1000L) return

        val endedAt = startedAt + segment
        logCatching("archive-stopwatch-insert") {
            database.timerSessionDao().insert(
                TimerSession(
                    label = rt.label ?: timer?.label,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    plannedMillis = segment,
                    kind = timer?.kind?.name ?: TimerKind.Stopwatch.name,
                    completed = true,
                ),
            )
        }
    }

    /**
     * 把当前运行时表写进 `runtime_snapshots`。
     *
     * ## 为什么"整表覆盖"而不是逐条 upsert
     *
     * 快照记的是**当前状态**，不是事件流水。所以：
     * 1. 先把表里多出来的行删掉（运行时已经 clear 掉的计时器）
     * 2. 再把当前每一条 upsert 进去
     *
     * 分批做会留下"已删除计时器的陈旧快照"，下次冷启动会把一个不存在的东西恢复出来。
     *
     * @param runtimes 当前全部运行时（由 `PanelRunner.snapshotRequests` 提供）
     */
    private suspend fun persistRuntimes(runtimes: List<TimerRuntime>) {
        val dao = database.runtimeSnapshotDao()
        val now = System.currentTimeMillis()
        val alive = runtimes.map { it.timerId }.toSet()
        // 清掉不在运行时表里的陈旧快照
        dao.allOnce().forEach { old ->
            if (old.timerId !in alive) {
                logCatching("snapshot-drop") { dao.deleteById(old.timerId) }
            }
        }
        runtimes.forEach { rt ->
            logCatching("snapshot-put") {
                dao.upsert(
                    RuntimeSnapshot(
                        timerId = rt.timerId,
                        remainingMillis = rt.remainingMillis,
                        overdueMillis = rt.overdueMillis,
                        running = rt.running,
                        startedAt = rt.startedAt,
                        stopwatchBaseMillis = rt.stopwatchBaseMillis,
                        savedAt = now,
                    ),
                )
            }
        }
    }

    /**
     * 冷启动：按快照恢复每个计时器的进度。
     *
     * ## 这是「退出重进不丢进度」的核心
     *
     * 之前 `_runtimes` 纯内存，进程一死就空；DDL 能靠绝对截止时刻恢复，
     * 普通倒计时和正计时则彻底丢失（回满格 / 回 0）。
     *
     * ## 恢复规则
     *
     * 用 `computeRestoredProgress` 这个纯函数算，规则见它的注释：
     * - **暂停态**：一秒不差原样恢复（离线时间不计）
     * - **运行态 + 倒计时**：按 `savedAt` 补算离线期间流逝的时间，可能变成超时
     * - **运行态 + 正计时**：累计时长继续往上加
     *
     * 恢复出来一律 `running = false`（待命）—— 冷启动瞬间让一堆计时器自己跑起来
     * 而用户根本没按开始，比"没恢复"更糟。进度已经算进 remainingMillis，不会丢。
     *
     * ## 正计时额外动作
     *
     * 正计时"重新进入"意味着上一段计时实际结束了：先写一条归档，
     * 再按 `pinned` 决定要不要清零（未固定=一次性计时，清零重来；固定=保留累计继续）。
     */
    private suspend fun restoreProgressFromSnapshots() {
        val snapshots = logCatchingOr("restore-snapshots-read", emptyList<RuntimeSnapshot>()) {
            database.runtimeSnapshotDao().allOnce()
        }
        if (snapshots.isEmpty()) return

        val timers = logCatchingOr("restore-snapshots-timers", emptyList<TimerItem>()) {
            database.timerDao().observeAllOnce()
        }
        val byId = timers.associateBy { it.id }
        val now = System.currentTimeMillis()

        // 快照对应的计时器已经被删了：顺手清掉，别留孤儿
        snapshots.filter { it.timerId !in byId }.forEach {
            logCatching("restore-snapshots-orphan") {
                database.runtimeSnapshotDao().deleteById(it.timerId)
            }
        }

        val restored = mutableListOf<TimerRuntime>()
        snapshots.forEach { snap ->
            val timer = byId[snap.timerId] ?: return@forEach
            // DDL 由 restoreDeadlines 用绝对截止时刻精确恢复，这里不插手
            if (timer.kind == TimerKind.Deadline) return@forEach

            val stopwatch = timer.kind == TimerKind.Stopwatch
            val total = if (stopwatch) 0L else timer.durationMillis
            val offline = (now - snap.savedAt).coerceAtLeast(0L)
            val progress = computeRestoredProgress(
                remainingMillis = snap.remainingMillis,
                overdueMillis = snap.overdueMillis,
                totalMillis = total,
                stopwatch = stopwatch,
                running = snap.running,
                offlineMillis = offline,
            )

            if (stopwatch) {
                // 正计时"重新进入"：上一段算结束了，写归档
                val segment = (progress.remainingMillis - snap.stopwatchBaseMillis).coerceAtLeast(0L)
                if (segment >= 1000L && snap.startedAt > 0L) {
                    logCatching("restore-stopwatch-archive") {
                        database.timerSessionDao().insert(
                            TimerSession(
                                label = timer.label,
                                startedAt = snap.startedAt,
                                endedAt = snap.startedAt + segment,
                                plannedMillis = segment,
                                kind = TimerKind.Stopwatch.name,
                                completed = true,
                            ),
                        )
                    }
                }
                // 未固定 = 一次性计时，清零重来；固定 = 保留累计，接着走
                val keep = timer.pinned
                restored += TimerRuntime(
                    timerId = timer.id,
                    label = timer.label,
                    totalMillis = 0L,
                    remainingMillis = if (keep) progress.remainingMillis else 0L,
                    running = false,
                    deadlineEpochMillis = null,
                    alertProfileId = timer.alertProfileId,
                    stopwatch = true,
                    startedAt = now,
                    stopwatchBaseMillis = if (keep) progress.remainingMillis else 0L,
                )
            } else {
                restored += TimerRuntime(
                    timerId = timer.id,
                    label = timer.label,
                    totalMillis = total,
                    remainingMillis = progress.remainingMillis.coerceAtMost(total),
                    overdueMillis = progress.overdueMillis,
                    running = false,
                    deadlineEpochMillis = null,
                    alertProfileId = timer.alertProfileId,
                    startedAt = snap.startedAt.takeIf { it > 0L } ?: now,
                )
            }
        }

        if (restored.isNotEmpty()) {
            logCatching("restore-snapshots-apply") {
                panelRunner.applyRestored(restored)
            }
        }
    }

    private suspend fun archiveAndMaybeRemove(rt: TimerRuntime) {
        val dao = database.timerSessionDao()
        val timer = logCatching("archive-get-timer") { database.timerDao().get(rt.timerId) }

        // 结束时刻：用「开始 + 实际跑过的时长」推算，而不是 System.currentTimeMillis()。
        // 原因：tick 是 200ms 一拍，到点被检测到时会比真实终点晚最多 ~200ms；
        // 用推算值能让归档里的区间和盘面上显示的数字严格一致。
        //
        // **必须把超时算进去**：一个 5 分钟的倒计时如果到点后没有立刻被处理、
        // 又跑了 3 分钟才停，那次计时实际持续了 8 分钟。
        // 只记 5 分钟会让归档页显示"5 分钟"，而用户明明看着它超时了 3 分钟 ——
        // 归档是"当时发生了什么"的事实记录，不能只记计划。
        // plannedMillis 仍然存 rt.totalMillis（计划值），所以归档能同时给出
        // "计划 5 分 / 实际 8 分"这个对比。
        // **只在"这一轮计时真的结束了"时归档**：也就是它即将被移出面板（未固定）。
        //
        // 固定的计时器到点后并没有结束 —— 它转入超时继续走，盘面还在转，
        // 用户随时可能回来继续。如果到期就归档一条，等用户之后取消固定再点「结束」，
        // 同一次计时会出现**两条**记录（到期一条 60s + 结束一条 110s），合计重复计算。
        // 真机上确认过这个重复：同一个 startedAt 落了两行。
        //
        // `timer == null` 说明计时器行已经被删了（用户直接删掉卡片），
        // 这时也该留一条历史，否则那次计时凭空消失。
        val willBeRemoved = timer == null || !timer.pinned
        if (willBeRemoved) {
            // 交给 SessionArchiver：它会自动处理「暂停时已有占位行」的情况 ——
            // 有占位行就原地更新那一条（保持"一次计时一条记录"），没有就新插一条。
            logCatching("archive-finish") {
                sessionArchiver.finish(rt.copy(label = rt.label ?: timer?.label), completed = true)
            }
        }

        // 未固定的才移除
        if (timer != null && !timer.pinned) {
            logCatching("archive-autoremove") {
                panelRunner.clear(timer.id)

                // 面板引用：所有面板都要解除（同一个计时器可能在多个面板里）。
                // 用 `.first()` 取一次快照 —— DAO 只提供 Flow 版的全量引用查询，
                // 这里是一次性清理，不需要持续订阅。
                database.panelDao().observeAllRefs().first()
                    .filter { it.timerId == timer.id }
                    .forEach { database.panelDao().removeRef(it.panelId, it.timerId) }

                // 组成员：GroupDao 没有 removeMember，按"组"重写成员列表。
                // 必须按组操作（而不是删单条），否则会留下 order 空洞。
                val allMembers = database.groupDao().allMembersOnce()
                allMembers.filter { it.timerId == timer.id }
                    .map { it.groupId }
                    .distinct()
                    .forEach { groupId ->
                        val remaining = allMembers.filter { it.groupId == groupId && it.timerId != timer.id }
                        if (remaining.isNotEmpty()) {
                            database.groupDao().upsertMembers(remaining)
                        }
                        // 组变空了就整个删掉，否则会留一个没有成员的空组卡片
                        if (remaining.isEmpty()) {
                            database.groupDao().deleteGroup(groupId)
                        }
                    }

                // 提前提醒行
                database.deadlineReminderDao().clearTimer(timer.id)
                // 计时器行本身
                database.timerDao().deleteById(timer.id)
            }
        }
    }

    private fun observeAlerts() {
        // 1) 新到点：发通知 + 拉起全屏闪烁悬浮窗
        appScope.launch {
            panelRunner.alerts.collect { rt ->
                logCatching("notify-end") { postNotification(rt) }
                // 只在耳机下出声；没耳机时这个方法自身会直接返回
                logCatching("sound-play") { headphoneSound.playIfHeadphone() }
                logCatching("overlay-start") {
                    AlertOverlayService.start(
                        this@ExtApplication,
                        rt.label ?: "计时结束",
                        rt.timerId,
                    )
                }
                // 归档 + 未固定计时器的自动移除
                logCatching("archive-session") { archiveAndMaybeRemove(rt) }
            }
        }

        // 1.2) 暂停 / 恢复：开、封暂停区间
        //
        // 用户要求"暂停也自动归档，但一整条 + 圆盘上空出暂停段"。
        // 所以暂停不是写一条新记录，而是：写一条 ongoing 占位行（进程被杀也不丢）
        // + 开一个未封口的暂停区间；恢复时封口；真正结束时原地更新那条占位行。
        appScope.launch {
            panelRunner.pauseEvents.collect { rt ->
                logCatching("archive-pause") { sessionArchiver.onPause(rt) }
            }
        }
        appScope.launch {
            panelRunner.resumeEvents.collect { timerId ->
                logCatching("archive-resume") { sessionArchiver.onResume(timerId) }
            }
        }

        // 1.25) 运行时快照落盘
        //
        // 收「该存了」的信号，把整张运行时表写进 runtime_snapshots。
        // 冷启动时 restoreProgressFromSnapshots 再读回来 —— 这样"退出重进不丢进度"。
        appScope.launch(Dispatchers.IO) {
            panelRunner.snapshotRequests.collect { runtimes ->
                logCatching("snapshot-write") { persistRuntimes(runtimes) }
            }
        }

        // 1.5) 通知持续刷新：把「已超时 MM:SS」实时显示在通知上
        appScope.launch {
            while (true) {
                val ids = panelRunner.alerting.value
                if (ids.isNotEmpty()) {
                    ids.forEach { id ->
                        panelRunner.get(id)?.let { rt ->
                            logCatching("notify-overdue") { postNotification(rt) }
                        }
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        // 1.8) 提前提醒：到点前的预警，走通知 + 震动 + 声音（不进入持续提醒态）
        //
        // 顺手把这一档写回 `deadline_reminders.fired = 1`。
        // 为什么必须写：`fired` 列此前永远是 false（`markFired` 零调用），
        // 于是冷启动恢复（restoreDeadlines）无法区分「已经响过的档位」和「还没响的」，
        // 重启后会把同一档再响一遍。行 id 由 PanelRunner.attachLeadMinutes 登记，
        // 这里查不到（null）说明是「刚建好、还没回读过 id」的那种，直接跳过即可。
        appScope.launch {
            panelRunner.leadAlerts.collect { (rt, lead) ->
                logCatching("notify-lead") { postLeadNotification(rt, lead) }
                logCatching("vibrate-lead") { vibration.play(longArrayOf(0L, 300L, 150L, 300L), repeatIndex = -1) }
                logCatching("sound-play-lead") { headphoneSound.playIfHeadphone() }
                val rowId = panelRunner.leadRowIdOf(rt.timerId, lead)
                if (rowId != null) {
                    logCatching("lead-fired-mark") {
                        withContext(Dispatchers.IO) {
                            database.deadlineReminderDao().markFired(rowId)
                        }
                    }
                }
            }
        }

        // 2) 持续震动：只要还有未处理的到点计时器就一直震
        appScope.launch {
            panelRunner.alerting.collect { ids ->
                if (ids.isEmpty()) {
                    logCatching("vibrate-cancel") { vibration.cancel() }
                } else {
                    logCatching("vibrate-loop") { startLoopingVibration(ids) }
                }
            }
        }

        // 3) 迷你悬浮窗随运行中的计时器自动起停
        //
        // ⚠️ 这里必须 combine 两个 flow，不能只在 runtimes.collect 里读一次开关：
        //
        // 原来的写法是
        //     panelRunner.runtimes.collect { map ->
        //         val enabled = settings.overlayEnabled.first()
        //         ...
        //     }
        // `first()` 是**一次性**取值，而这个 collect 只在 runtimes 变化时才跑。
        // 于是「先启动计时器，再去设置里打开悬浮窗开关」这条最常见的顺序
        // 永远不会生效 —— runtimes 没变，块不执行，开关等于白开。
        // （用户实测："悬浮窗里没显示"。）
        //
        // combine 之后，两个来源任一变化都会重新判断：
        //   · 开关打开时，若已有计时器在跑 -> 立刻显示
        //   · 计时器全部停下 -> 隐藏
        appScope.launch {
            combine(
                panelRunner.runtimes,
                settings.overlayEnabled,
            ) { map, enabled -> map.values.count { it.running } to enabled }
                .collect { (running, enabled) ->
                    if (enabled && running > 0) {
                        logCatching("overlay-start") { TimerOverlayService.start(this@ExtApplication) }
                    } else if (running == 0 || !enabled) {
                        // 开关关掉也要立刻停 —— 用户点「关闭悬浮窗」期待马上消失
                        logCatching("overlay-stop") { TimerOverlayService.stop(this@ExtApplication) }
                    }
                }
        }
    }

    /**
     * 循环震动。
     *
     * 之前是「震一轮就停」，很容易错过。现在用 repeatIndex = 0 循环播放，
     * 直到用户重置计时器 / 关闭悬浮窗。
     */
    private suspend fun startLoopingVibration(ids: Set<Long>) {
        val enabled = logCatchingOr("vibration-enabled", true) { settings.vibrationEnabled.first() }
        if (!enabled) return

        val first = ids.firstNotNullOfOrNull { panelRunner.get(it) } ?: return
        val profile: AlertProfile? = first.alertProfileId?.let { id ->
            logCatching("alert-profile-get") { database.alertProfileDao().get(id) }
        } ?: logCatching("alert-profile-first") {
            database.alertProfileDao().observeAllOnce().firstOrNull()
        }

        val pattern = (profile?.vibrationPattern?.takeIf { it.isNotEmpty() }
            ?: listOf(0L, 600L, 200L, 600L, 600L)).toLongArray()

        vibration.play(pattern, repeatIndex = 0)
    }

    /** 提前提醒通知：标注「还有多久到期」。 */
    private fun postLeadNotification(rt: TimerRuntime, leadMinutes: Long) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(rt.label ?: "即将到期")
            .setContentText("还有 " + DeadlineCalculator.formatRemaining(leadMinutes * 60_000L) + " 到期")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        logCatching("notify-post-lead") { mgr.notify((rt.timerId + leadMinutes).toInt(), n) }
    }

    private fun postNotification(rt: TimerRuntime) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = open?.let {
            PendingIntent.getActivity(
                this,
                rt.timerId.toInt(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val overdue = rt.overdueMillis
        val title = rt.label ?: "计时结束"
        val text = if (overdue > 0L) {
            "已超时 " + DeadlineCalculator.formatRemaining(overdue) + "（点击停止提醒）"
        } else {
            "时间到（点击停止提醒）"
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        logCatching("notify-post") { mgr.notify(rt.timerId.toInt(), n) }
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL_ALERTS) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "计时到点提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    // ---------------- 默认数据 ----------------

    private fun seedDefaultsIfEmpty() {
        appScope.launch(Dispatchers.IO) {
            // 这里是数据库的**首次打开点**。AppDatabase.get() 已去掉静默清库的兜底，
            // 所以迁移缺失时会在这里抛 IllegalStateException（Room 的 missing migration）。
            // 捕获它、记日志、给界面一句人话，**不要**让整个进程崩在启动阶段，
            // 更不要吞掉异常后假装一切正常 —— 那正是原来最坏的行为。
            val dao = try {
                database.alertProfileDao()
            } catch (e: Throwable) {
                Log.e(TAG, "数据库打开失败（迁移缺失？）。数据文件保持原样，未清理。", e)
                databaseOpenError.value =
                    "数据库打不开：版本不匹配。数据没有被删除，请先在「导入/导出」里备份，再反馈此问题。"
                return@launch
            }
            if (dao.count() > 0) return@launch
            VibrationPresets.all.forEach { (name, pattern) ->
                dao.upsert(
                    AlertProfile(
                        name = name,
                        vibrationPattern = pattern.toList(),
                        vibrationRepeatIndex = -1,
                        screenFlash = true,
                        torchFlash = false,   // 手电筒默认关
                        soundEnabled = false, // 默认静音
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "ExtApplication"
        private const val CHANNEL_ALERTS = "timer_alerts"

        /**
         * [observeSequenceOverlay] 的轮询间隔。
         *
         * 引擎每 100ms 发一次状态，但悬浮窗只需要显示到「秒」，
         * 这里 200ms 一拍 + 快照去重，既跟得上又不至于每拍都重建快照。
         */
        private const val SEQUENCE_OVERLAY_POLL_MILLIS = 200L
    }
}

/**
 * 序列悬浮窗要显示的**不可变快照**。
 *
 * 为什么要有这个类：[SequenceOverlayService] 是纯 View 代码、没有协程作用域，
 * 没法自己 collect [at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine.phase]。
 * 由 [ExtApplication.observeSequenceOverlay] 把要显示的字段算好放这里，
 * 服务端每 300ms 读一次。（`internal` 是为了让 service 包能读到。）
 */
internal data class SequenceOverlayInfo(
    /** 当前活动序列 id，仅用于排查/日志。 */
    val sequenceId: Long?,
    /** 序列名（第一行）。 */
    val sequenceName: String,
    /** 当前阶段说明（第二行）：`第 2 阶段 · 收集文献`，或确认态的 `收集文献 · 下一步：写作`。 */
    val stepCaption: String,
    /** 倒计时文本（第三行，仅 Running 用）：`4m05s`。 */
    val countdownText: String,
    /** 是否处于「等用户确认」态。 */
    val awaitingConfirm: Boolean,
    /** 阶段结束后已过去多久（正计时）。 */
    val elapsedMillis: Long,
    /** 超时之后又过了多久。 */
    val overtimeMillis: Long,
    /** 距离超时还剩多久；0 表示已超时或本来就没设超时。 */
    val timeoutRemainingMillis: Long,
    /** 是否已经超时（界面切强调色）。 */
    val overdue: Boolean,
)
