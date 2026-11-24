package at.cwiesner.android.visualtimer.ext.alarm

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 屏幕闪烁执行器（不需要权限，只要窗口可见）。
 *
 * 实现方式：在宿主 Activity 的 window 上切换屏幕亮度与状态栏可见性，
 * 产生视觉上的明暗脉冲。与真正的「闪光灯」区分开，后者见 [TorchAlerter]。
 */
class ScreenFlashAlerter(private val activity: Activity) {

    private var job: Job? = null

    /** 当前屏幕亮度（-1 表示跟随系统）。用于结束时还原。 */
    private var originalBrightness: Float? = null

    /**
     * 开始闪烁。
     *
     * @param flashMillis 亮起持续时间
     * @param gapMillis 熄灭持续时间
     * @param times 闪烁次数；0 表示一直闪到 [stop]
     */
    fun start(
        scope: CoroutineScope,
        flashMillis: Long = 400L,
        gapMillis: Long = 250L,
        times: Int = 0,
    ) {
        stop()
        val window = activity.window ?: return
        originalBrightness = window.attributes.screenBrightness

        job = scope.launch {
            var count = 0
            while (isActive && (times == 0 || count < times)) {
                setBrightness(1f)
                delay(flashMillis)
                setBrightness(0.05f)
                delay(gapMillis)
                count++
            }
            restore()
        }
    }

    /** 停止闪烁并还原亮度。 */
    fun stop() {
        job?.cancel()
        job = null
        restore()
    }

    private fun setBrightness(value: Float) {
        runCatching {
            val window = activity.window ?: return
            val attrs = window.attributes
            attrs.screenBrightness = value
            window.attributes = attrs
        }
    }

    private fun restore() {
        runCatching {
            val window = activity.window ?: return
            val attrs = window.attributes
            attrs.screenBrightness = originalBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        }
        originalBrightness = null
    }
}

/**
 * 手电筒闪烁执行器。
 *
 * **默认关闭**（用户要求）。仅在用户显式在警报配置里打开 `torchFlash` 时使用。
 * 需要 CAMERA 权限（Android M+ 对 setTorchMode 不强制，但多数 ROM 会检查）。
 */
class TorchAlerter(private val context: Context) {

    private var job: Job? = null
    private var cameraId: String? = null

    private val manager: CameraManager? by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    /** 设备是否有可用闪光灯。 */
    fun isAvailable(): Boolean {
        val m = manager ?: return false
        return runCatching {
            cameraId = m.cameraIdList.firstOrNull { id ->
                val chars = m.getCameraCharacteristics(id)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                flash == true && chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            cameraId != null
        }.getOrDefault(false)
    }

    fun start(
        scope: CoroutineScope,
        onMillis: Long = 180L,
        offMillis: Long = 220L,
        times: Int = 0,
    ) {
        if (!isAvailable()) return
        stop()
        job = scope.launch {
            val id = cameraId ?: return@launch
            var count = 0
            while (isActive && (times == 0 || count < times)) {
                setTorch(id, true)
                delay(onMillis)
                setTorch(id, false)
                delay(offMillis)
                count++
            }
            setTorch(id, false)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        cameraId?.let { setTorch(it, false) }
    }

    private fun setTorch(id: String, on: Boolean) {
        runCatching { manager?.setTorchMode(id, on) }
    }
}
