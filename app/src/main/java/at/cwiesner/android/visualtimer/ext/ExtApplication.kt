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
import at.cwiesner.android.visualtimer.ext.data.VibrationPresets
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        )
    }

    /** 悬浮窗点「下一阶段」时调用。 */
    fun advanceSequence() {
        sequenceEngine.confirm()
    }

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
        observeAlerts()
        observeFlashSetting()
    }

    private fun observeFlashSetting() {
        appScope.launch {
            settings.flashIntervalMillis.collect { flashIntervalMillis = it }
        }
    }

    // ---------------- 到点提醒 ----------------

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
        appScope.launch {
            panelRunner.leadAlerts.collect { (rt, lead) ->
                logCatching("notify-lead") { postLeadNotification(rt, lead) }
                logCatching("vibrate-lead") { vibration.play(longArrayOf(0L, 300L, 150L, 300L), repeatIndex = -1) }
                logCatching("sound-play-lead") { headphoneSound.playIfHeadphone() }
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
        appScope.launch {
            panelRunner.runtimes.collect { map ->
                val running = map.values.count { it.running }
                val enabled = logCatchingOr("overlay-enabled", false) { settings.overlayEnabled.first() }
                if (enabled && running > 0) {
                    logCatching("overlay-start") { TimerOverlayService.start(this@ExtApplication) }
                } else if (running == 0) {
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
    }
}
