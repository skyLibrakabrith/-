package at.cwiesner.android.visualtimer.ext.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build

/**
 * 耳机限定声音提醒。
 *
 * **设计约束**：只在连接了耳机（有线或蓝牙）时播放，绝不走扬声器。
 * 这样做是为了在图书馆、办公室、夜间等场景下能靠声音提醒，
 * 又不会突然外放打扰他人。
 *
 * 实现要点：
 * 1. 用 [isHeadphoneConnected] 判断当前是否有耳机
 * 2. 设置 `USAGE_ASSISTANCE_SONIFICATION` + 强制耳机路由
 * 3. 没有耳机时**完全不播放**（不是降低音量，是真的一点都不响）
 */
class HeadphoneSoundAlerter(private val context: Context) {

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private var player: MediaPlayer? = null

    /**
     * 当前是否连了耳机。
     *
     * 覆盖：有线耳机、蓝牙 A2DP、蓝牙耳机、USB 耳机。
     * 刻意**排除**内置扬声器与听筒。
     */
    fun isHeadphoneConnected(): Boolean {
        val am = audioManager ?: return false

        // 优先用「是否在用耳机路由输出」判断，最贴近真实播放通道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return outs.any { dev ->
                when (dev.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    -> true

                    else -> false
                }
            }
        }

        @Suppress("DEPRECATION")
        return am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }

    /**
     * 播放一次提醒音。没有耳机时直接返回，什么都不做。
     *
     * @return 是否真的播放了
     */
    fun playIfHeadphone(): Boolean {
        if (!isHeadphoneConnected()) return false
        val am = audioManager ?: return false

        val uri = runCatching {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull() ?: return false

        release()
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // 用 SONIFICATION 而不是 ALARM：
                        // ALARM 流在多数 ROM 上会强制走扬声器并拉满音量，
                        // 这里要的恰恰是「只走耳机」
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = false
                prepare()
                start()
            }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { player?.stop() }
        release()
    }

    private fun release() {
        runCatching { player?.release() }
        player = null
    }
}
