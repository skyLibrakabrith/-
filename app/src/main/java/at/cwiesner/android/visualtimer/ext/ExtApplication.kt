package at.cwiesner.android.visualtimer.ext

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
        runCatching { headphoneSound.stop() }
    }

    private val vibration: VibrationAlerter by lazy { VibrationAlerter(this) }

    /** 耳机限定声音：没有耳机时完全不响，避免外放打扰。 */
    private val headphoneSound: at.cwiesner.android.visualtimer.ext.alarm.HeadphoneSoundAlerter
        by lazy { at.cwiesner.android.visualtimer.ext.alarm.HeadphoneSoundAlerter(this) }
    private val settings: SettingsRepo by lazy { SettingsRepo(this) }

    override fun onCreate() {
        super.onCreate()
        seedDefaultsIfEmpty()
        createAlertChannel()
        // 立刻开始走表，不依赖任何界面存在
        panelRunner.startTicking()
        observeAlerts()
    }

    // ---------------- 到点提醒 ----------------

    private fun observeAlerts() {
        // 1) 新到点：发通知 + 拉起全屏闪烁悬浮窗
        appScope.launch {
            panelRunner.alerts.collect { rt ->
                runCatching { postNotification(rt) }
                // 只在耳机下出声；没耳机时这个方法自身会直接返回
                runCatching { headphoneSound.playIfHeadphone() }
                runCatching {
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
                            runCatching { postNotification(rt) }
                        }
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        // 1.8) 提前提醒：到点前的预警，走通知 + 震动 + 声音（不进入持续提醒态）
        appScope.launch {
            panelRunner.leadAlerts.collect { (rt, lead) ->
                runCatching { postLeadNotification(rt, lead) }
                runCatching { vibration.play(longArrayOf(0L, 300L, 150L, 300L), repeatIndex = -1) }
                runCatching { headphoneSound.playIfHeadphone() }
            }
        }

        // 2) 持续震动：只要还有未处理的到点计时器就一直震
        appScope.launch {
            panelRunner.alerting.collect { ids ->
                if (ids.isEmpty()) {
                    runCatching { vibration.cancel() }
                } else {
                    runCatching { startLoopingVibration(ids) }
                }
            }
        }

        // 3) 迷你悬浮窗随运行中的计时器自动起停
        appScope.launch {
            panelRunner.runtimes.collect { map ->
                val running = map.values.count { it.running }
                val enabled = runCatching { settings.overlayEnabled.first() }.getOrDefault(false)
                if (enabled && running > 0) {
                    runCatching { TimerOverlayService.start(this@ExtApplication) }
                } else if (running == 0) {
                    runCatching { TimerOverlayService.stop(this@ExtApplication) }
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
        val enabled = runCatching { settings.vibrationEnabled.first() }.getOrDefault(true)
        if (!enabled) return

        val first = ids.firstNotNullOfOrNull { panelRunner.get(it) } ?: return
        val profile: AlertProfile? = first.alertProfileId?.let { id ->
            runCatching { database.alertProfileDao().get(id) }.getOrNull()
        } ?: runCatching {
            database.alertProfileDao().observeAllOnce().firstOrNull()
        }.getOrNull()

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
        runCatching { mgr.notify((rt.timerId + leadMinutes).toInt(), n) }
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
        runCatching { mgr.notify(rt.timerId.toInt(), n) }
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
            val dao = database.alertProfileDao()
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
        private const val CHANNEL_ALERTS = "timer_alerts"
    }
}
