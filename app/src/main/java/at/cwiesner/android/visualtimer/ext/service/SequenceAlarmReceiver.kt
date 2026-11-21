package at.cwiesner.android.visualtimer.ext.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 精确闹钟回调。
 *
 * 用于「序列在某一步结束时」以及「DDL 倒推计时到点」的场景：
 * 即使进程被回收，AlarmManager 仍会把我们叫起来触发警报。
 */
class SequenceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "计时结束"
        // 触发持久通知；具体警报由前台服务或 Activity 接管
        SequenceService.start(context, "计时结束", label)
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val ACTION_ALARM = "at.cwiesner.android.visualtimer.ext.ACTION_ALARM"
    }
}
