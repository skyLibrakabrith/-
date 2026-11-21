package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 一个计时器条目。多面板中的每一格、逐步序列中的每一步都以它为数据基底。
 *
 * @property label 显示名，可空（UI 层回退为时长文本）
 * @property durationMillis 总时长
 * @property alertProfileId 关联的警报配置；null 表示跟随全局默认
 */
@Entity(tableName = "timers")
@Serializable
data class TimerItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String? = null,
    val durationMillis: Long,
    val alertProfileId: Long? = null,
    val position: Int = 0,
)

/**
 * 多面板：一个面板 = 一组可同时或依次运行的计时器。
 */
@Entity(tableName = "panels")
@Serializable
data class Panel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String = "grid",
    val sortOrder: Int = 0,
)

/**
 * 面板与计时器的多对多关系，带面板内顺序。
 */
@Entity(tableName = "panel_timers", primaryKeys = ["panelId", "timerId"])
@Serializable
data class PanelTimerRef(
    val panelId: Long,
    val timerId: Long,
    val order: Int = 0,
)
