package at.cwiesner.android.visualtimer.ext.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机广播。
 *
 * 用途：AlarmManager 的闹钟在重启后会丢失，这里重建未来 24 小时内的定时任务。
 * 当前版本只做最小实现（占位 + 日志），因为序列状态尚未持久化到磁盘。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // TODO: 从数据库恢复未完成的序列与 DDL 计时，重新向 AlarmManager 注册
    }
}
