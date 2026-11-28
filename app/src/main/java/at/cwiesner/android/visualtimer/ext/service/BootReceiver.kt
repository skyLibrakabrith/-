package at.cwiesner.android.visualtimer.ext.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import at.cwiesner.android.visualtimer.ext.ExtApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机广播：重建到点闹钟。
 *
 * ## 为什么必须做
 *
 * `AlarmManager` 的闹钟**在重启后会全部丢失**。用户设了个 2 小时的计时器、
 * 手机重启了 —— 如果不重建，那次计时永远不会提醒。
 *
 * ## 实现
 *
 * `BroadcastReceiver.onReceive` 里不能做长任务（约 10 秒上限），
 * 所以这里只起一个协程去查库 + 注册，立即返回。
 * 查库用应用的 `appScope`（进程这时已被拉起）。
 *
 * ## 只重建「还在跑」的
 *
 * 从 `runtime_snapshots` 读 `running = 1` 的记录，按**剩余时间**重新算到点时刻
 * （不是原来的绝对时刻 —— 关机期间时间也过去了，`remainingMillis` 才是准的）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "开机广播：开始重建到点闹钟")

        val app = context.applicationContext as? ExtApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshots = app.database.runtimeSnapshotDao().allOnce()
                val now = System.currentTimeMillis()
                var scheduled = 0
                snapshots.filter { it.running }.forEach { snap ->
                    if (snap.remainingMillis <= 0L) return@forEach
                    val label = runCatching { app.database.timerDao().get(snap.timerId)?.label }
                        .getOrNull()
                    runCatching {
                        TimerAlarmScheduler.schedule(
                            context = app,
                            timerId = snap.timerId,
                            label = label,
                            triggerAtMillis = now + snap.remainingMillis,
                        )
                        scheduled++
                    }
                }
                Log.i(TAG, "开机重建完成：$scheduled 个闹钟")
            } catch (e: Exception) {
                Log.w(TAG, "开机重建闹钟失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
