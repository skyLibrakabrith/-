package at.cwiesner.android.visualtimer.ext.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import at.cwiesner.android.visualtimer.ext.ExtApplication

/**
 * 到点闹钟回调。
 *
 * 由 [TimerAlarmScheduler] 注册。**即使 App 进程已经死了**，系统也会把这个
 * receiver 拉起来 —— 这是前台服务之外最后一道兜底。
 *
 * 实测证据：App 退后台后 `am kill`，pid 变空、`dumpsys alarm` 里 0 条闹钟，
 * 到点什么都没发生。有了这个 receiver，闹钟会把进程叫起来。
 *
 * 能做的事很有限（广播接收器有 ~10 秒执行上限），所以这里只：
 * 1. 发一条高优先级通知（复用 `ExtApplication.postNotification` 的逻辑在服务侧不可达，
 *    所以直接走 `AlertOverlayService`，它本来就会发前台通知 + 显示闪烁卡片）
 * 2. 拉起 `KeepAliveService` 让进程存活，好让震动循环跑起来
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L)
        if (timerId <= 0L) return
        val label = intent.getStringExtra(EXTRA_LABEL)
        Log.i(TAG, "到点闹钟触发 timer=$timerId label=$label")

        val app = context.applicationContext as? ExtApplication ?: return

        // 1) 拉起闪烁/通知服务（它内部会 startForeground 发通知）
        runCatching {
            AlertOverlayService.start(context, label ?: "计时结束", timerId)
        }.onFailure { Log.w(TAG, "启动提醒服务失败", it) }

        // 2) 让进程活着，震动循环才有机会跑
        runCatching { KeepAliveService.start(context, "计时到点") }

        // 3) 通知应用内的运行时：它被叫起来了，重新落一次快照
        runCatching { app.panelRunner.requestSnapshotNow() }
    }

    companion object {
        private const val TAG = "TimerAlarmReceiver"
        const val ACTION_FIRE = "at.cwiesner.android.visualtimer.ext.ACTION_TIMER_ALARM"
        const val EXTRA_TIMER_ID = "timerId"
        const val EXTRA_LABEL = "label"
    }
}
