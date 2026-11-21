package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 振动波形定义。
 *
 * **命名说明**：每个模式用几何名称命名（Pulse / Cadence / Staccato ...），
 * 不用描述性中文名，UI 上以波形条直接呈现。
 *
 * @property pattern 与 [android.os.VibrationEffect.createWaveform] 相同语义的
 *   「关-开-关-开...」时间序列，单位毫秒，首元素应为 0。
 */
@Entity(tableName = "alert_profiles")
@Serializable
data class AlertProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val vibrationPattern: List<Long> = emptyList(),
    val vibrationRepeatIndex: Int = -1,
    val screenFlash: Boolean = false,
    val torchFlash: Boolean = false,
    val soundEnabled: Boolean = false,
) {
    /** 波形的总时长，用于 UI 绘制与「过长模式」判定。 */
    val totalMillis: Long get() = vibrationPattern.sum()
}
