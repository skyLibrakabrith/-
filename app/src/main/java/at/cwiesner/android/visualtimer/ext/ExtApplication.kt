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

    private val vibration: VibrationAlerter by lazy { VibrationAlerter(this) }
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
        appScope.launch {
            panelRunner.alerts.collect { rt ->
                runCatching { fireVibration(rt) }
                runCatching { postNotification(rt) }
                // 有悬浮窗权限时，用全屏闪烁悬浮窗提醒（应用不在前台也能看到）
                runCatching {
                    AlertOverlayService.start(
                        this@ExtApplication,
                        rt.label ?: "计时结束",
                        rt.timerId,
                    )
                }
            }
        }
    }

    private suspend fun fireVibration(rt: TimerRuntime) {
        val enabled = runCatching { settings.vibrationEnabled.first() }.getOrDefault(true)
        if (!enabled) return

        val profile: AlertProfile? = rt.alertProfileId?.let { id ->
            runCatching { database.alertProfileDao().get(id) }.getOrNull()
        } ?: runCatching {
            database.alertProfileDao().observeAllOnce().firstOrNull()
        }.getOrNull()

        val pattern = (profile?.vibrationPattern?.takeIf { it.isNotEmpty() }
            ?: listOf(0L, 600L, 200L, 600L)).toLongArray()

        // 到点只震一轮；循环震动交给前台界面处理
        vibration.play(pattern, repeatIndex = -1)
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
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(rt.label ?: "计时结束")
            .setContentText(
                "时间到，" + DeadlineCalculator.formatRemaining(rt.totalMillis) + " 已走完",
            )
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
