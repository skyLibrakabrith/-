package at.cwiesner.android.visualtimer.ext.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

/**
 * 耳机限定声音提醒。
 *
 * **设计约束**：只在连接了耳机（有线或蓝牙）时播放，绝不走扬声器。
 *
 * 另外支持：
 * - 用户自定义铃声 URI（来自系统铃声选择器）
 * - 播放/停止可以反复调用而不泄漏 MediaPlayer
 */
class HeadphoneSoundAlerter(private val context: Context) {

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private var player: MediaPlayer? = null

    /** 用户选定的铃声；null 表示用系统默认闹铃。 */
    var ringtoneUri: Uri? = null

    /** 当前是否连了耳机（有线 / 蓝牙 / USB）。刻意排除扬声器与听筒。 */
    fun isHeadphoneConnected(): Boolean {
        val am = audioManager ?: return false

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
     * 播放提醒音。没有耳机时直接返回 false，**一点声音都不出**。
     */
    fun playIfHeadphone(): Boolean {
        if (!isHeadphoneConnected()) return false

        val uri = ringtoneUri
            ?: runCatching {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }.getOrNull()
            ?: return false

        release()
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // SONIFICATION 而不是 ALARM：
                        // ALARM 流在多数 ROM 上会强制走扬声器并拉满音量，
                        // 这里要的正是「只走耳机」
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

    /** 预览用：即使没耳机也允许（用户在设置页主动试听）。 */
    fun preview(uri: Uri?): Boolean {
        release()
        val target = uri ?: return false
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(context, target)
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
