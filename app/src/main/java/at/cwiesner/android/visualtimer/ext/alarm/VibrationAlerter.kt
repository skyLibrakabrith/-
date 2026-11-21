package at.cwiesner.android.visualtimer.ext.alarm

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 震动执行器。
 *
 * 用 [VibrationEffect.createWaveform] 播放 [AlertProfile.vibrationPattern]，
 * 与系统振动器 API 的时间数组语义一致。
 */
class VibrationAlerter(private val context: Context) {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    /**
     * 播放一段波形。
     *
     * @param pattern 「关-开-关-开...」毫秒数组，首元素应为 0
     * @param repeatIndex -1 表示不重复；否则从该下标开始循环
     * @param amplitude 振幅 1..255，默认最大值
     */
    fun play(
        pattern: LongArray,
        repeatIndex: Int = -1,
        amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE,
    ) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || pattern.isEmpty()) return

        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createWaveform(pattern, repeatIndex).let {
                if (amplitude != VibrationEffect.DEFAULT_AMPLITUDE &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                ) {
                    VibrationEffect.createWaveform(pattern, intArrayOf(0), repeatIndex)
                } else {
                    it
                }
            }
        } else {
            @Suppress("DEPRECATION")
            return v.vibrate(pattern, repeatIndex)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            v.vibrate(effect)
        }
    }

    /** 立即停止。 */
    fun cancel() {
        vibrator?.cancel()
    }

    /**
     * 预览一段波形（UI 里的「试听」按钮）。
     * 时长上限 [PREVIEW_MAX_MILLIS]，避免用户误触后长时间震动。
     */
    fun preview(pattern: LongArray) {
        val trimmed = trimTo(pattern, PREVIEW_MAX_MILLIS)
        play(trimmed)
    }

    private fun trimTo(pattern: LongArray, maxMillis: Long): LongArray {
        if (pattern.isEmpty()) return pattern
        val out = ArrayList<Long>(pattern.size)
        var acc = 0L
        for ((i, seg) in pattern.withIndex()) {
            if (acc >= maxMillis) break
            val take = if (acc + seg > maxMillis) maxMillis - acc else seg
            // 保证首位是 0（延时），其余交替
            out += if (i == 0) 0L else take
            acc += seg
        }
        if (out.size % 2 == 0) out += PREVIEW_TAIL_MILLIS
        return out.toLongArray()
    }

    companion object {
        const val PREVIEW_MAX_MILLIS = 4_000L
        private const val PREVIEW_TAIL_MILLIS = 200L
    }
}
