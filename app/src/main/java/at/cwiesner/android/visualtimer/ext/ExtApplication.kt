package at.cwiesner.android.visualtimer.ext

import android.app.Application
import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.VibrationPresets
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

    override fun onCreate() {
        super.onCreate()
        seedDefaultsIfEmpty()
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
