package at.cwiesner.android.visualtimer.ext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 计时进行中的**保活前台服务**。
 *
 * ## 为什么需要（真机实测结论）
 *
 * 把 App 退到后台后 `am kill`：进程直接变空、`dumpsys alarm` 里 0 条闹钟、
 * 等 10 秒也不会自动重启 —— **到点不会响**。
 *
 * 前台服务不能让进程"永生"，但能让系统在回收时把我们排到后面
 * （Android 的 oom_adj 分组里，带前台通知的进程优先级远高于普通后台进程）。
 *
 * ## 它不做什么
 *
 * - **不驱动计时**：计时仍由 `PanelRunner` 的 200ms tick 负责
 * - **不显示倒计时**：要看进度用悬浮窗（`TimerOverlayService`）
 * - 这里只挂一条 `IMPORTANCE_MIN` 的通知占位，让进程不被轻易回收
 *
 * ## 启停由 `ExtApplication` 决定
 *
 * 订阅 `shouldKeepAlive(backgroundEnabled, runtimes, phase)`，值变化时才
 * start/stop。服务本身不判断业务状态，避免两处各判一份。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        startForeground(NOTIFICATION_ID, buildNotification(text))
        // 不返回 START_STICKY：系统重启服务时 intent 为 null，我们无从判断
        // 当时是否真的还有计时在跑。由 ExtApplication 的订阅者重新 start 更可靠。
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = open?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("计时进行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "计时保活",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "计时进行时的常驻通知，用来避免应用被系统提前回收"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "timer_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "at.cwiesner.android.visualtimer.ext.KEEPALIVE_STOP"
        private const val DEFAULT_TEXT = "计时进行中"

        /**
         * 启动或更新保活服务。
         *
         * @param text 通知正文，例如「3 个计时在运行」
         */
        fun start(context: Context, text: String = DEFAULT_TEXT) {
            val i = Intent(context, KeepAliveService::class.java).apply {
                putExtra(EXTRA_TEXT, text)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
