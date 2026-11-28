package at.cwiesner.android.visualtimer.ext.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 到点闹钟的注册 / 取消。
 *
 * ## 为什么光有前台服务不够（真机实测）
 *
 * 前台服务能让「用户切后台」这一场景不被优先回收，但以下情况仍会杀掉进程：
 * - 用户从最近任务里划掉
 * - 厂商 ROM 的激进清理
 * - 系统低内存强杀
 *
 * 那时**没有任何东西会把我们叫起来** —— 实测 `dumpsys alarm` 里一条都没有。
 *
 * 这个类补上那一环：给每个在跑的计时器注册一个到点闹钟，
 * 即使进程死了，`TimerAlarmReceiver` 也会被系统拉起来发通知。
 *
 * ## 精度说明（不做过度的承诺）
 *
 * - 有 `SCHEDULE_EXACT_ALARM` 且系统允许 → `setExactAndAllowWhileIdle`
 * - 否则降级 `setAndAllowWhileIdle`，系统可能把它推迟到打盹窗口
 * - 这两种都**不是毫秒级**精确，对计时器场景够用，但不要指望和盘面数字严格同步
 *
 * ## `PendingIntent` 的 requestCode
 *
 * 用 `timerId` 作 requestCode，保证「同一个计时器只保留一个待触发闹钟」。
 * 取消时也用同一个 requestCode + `FLAG_NO_CREATE` 语义（`cancel` 本身幂等）。
 */
object TimerAlarmScheduler {

    private const val TAG = "TimerAlarm"
    private const val ACTION_FIRE = "at.cwiesner.android.visualtimer.ext.ACTION_TIMER_ALARM"

    /** 给某个计时器注册到点闹钟。`triggerAtMillis` 已过期则忽略。 */
    fun schedule(context: Context, timerId: Long, label: String?, triggerAtMillis: Long) {
        val now = System.currentTimeMillis()
        if (triggerAtMillis <= now) {
            // 已经过了：不用再排，调用方自己的 tick 会处理到点
            Log.d(TAG, "跳过已过期的闹钟 timer=$timerId")
            return
        }
        val mgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, timerId, label)

        runCatching {
            val isExactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mgr.canScheduleExactAlarms()
            } else {
                true
            }
            if (isExactAllowed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    mgr.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
                Log.d(TAG, "精确闹钟 timer=$timerId at=$triggerAtMillis")
            } else {
                // 没有精确权限：降级。系统可能推迟，但至少进程死了也能被叫起来。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    mgr.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
                Log.w(TAG, "无精确闹钟权限，降级为不精确 timer=$timerId")
            }
        }.onFailure { Log.w(TAG, "注册闹钟失败 timer=$timerId", it) }
    }

    /** 取消某个计时器的到点闹钟（停止/重置/结束/删除时调用）。 */
    fun cancel(context: Context, timerId: Long) {
        val mgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, timerId, null)
        runCatching { mgr.cancel(pi) }.onFailure { Log.w(TAG, "取消闹钟失败 timer=$timerId", it) }
        pi.cancel()
    }

    private fun pendingIntent(context: Context, timerId: Long, label: String?): PendingIntent {
        val i = Intent(context, TimerAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(TimerAlarmReceiver.EXTRA_TIMER_ID, timerId)
            putExtra(TimerAlarmReceiver.EXTRA_LABEL, label)
        }
        return PendingIntent.getBroadcast(
            context,
            timerId.toInt(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
