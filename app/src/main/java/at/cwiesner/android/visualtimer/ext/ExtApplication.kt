package at.cwiesner.android.visualtimer.ext

import android.app.Application
import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.VibrationPresets
import at.cwiesner.android.visualtimer.ext.domain.PanelRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。持有一个应用级 [CoroutineScope] 与数据库单例，
 * 并在首次启动时植入内置振动预设与默认警报配置。
 */
class ExtApplication : Application() {

    /** 应用级作用域，用于序列引擎、警报等长生命周期任务。 */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    /**
     * 计时器运行时。挂在这里而不是界面上，
     * 这样切走底部标签、甚至按 Home 键，倒计时都不会被清掉。
     */
    val panelRunner: PanelRunner by lazy { PanelRunner(appScope) }

    override fun onCreate() {
        super.onCreate()
        seedDefaultsIfEmpty()
        // 立刻开始走表，不依赖任何界面存在
        panelRunner.startTicking()
    }

    private fun seedDefaultsIfEmpty() {
        appScope.launch(Dispatchers.IO) {
            val dao = database.alertProfileDao()
            if (dao.count() > 0) return@launch
            // 内置波形全部落库，方便用户直接选用
            VibrationPresets.all.forEach { (name, pattern) ->
                dao.upsert(
                    at.cwiesner.android.visualtimer.ext.data.AlertProfile(
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
}
