package at.cwiesner.android.visualtimer.ext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 序列运行的前台服务。
 *
 * 存在的意义：逐步间隔可能在屏幕锁定时跑，普通 Activity 会被冻结，
 * 前台服务能保证倒计时与到点警报不被系统挂起。
 *
 * 注意：本服务只负责保活与通知，正在运行的序列状态仍由
 * [at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine] 持有。
 */
class SequenceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "序列运行中"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "点击返回以确认下一阶段"
        startForeground(NOTIFICATION_ID, buildNotification(title, text))
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "序列计时",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "逐步间隔序列运行时的常驻通知"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val flags = if (open != null) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        } else 0
        val pi = open?.let {
            android.app.PendingIntent.getActivity(this, 0, it, flags)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sequence_running"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun start(context: Context, title: String, text: String) {
            val i = Intent(context, SequenceService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SequenceService::class.java))
        }
    }
}
