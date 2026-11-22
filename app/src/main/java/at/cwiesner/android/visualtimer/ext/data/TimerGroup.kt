package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 面板内的「组」。
 *
 * **组不是面板**：它只存在于某个面板内部，由若干计时器复合而成，
 * 在网格里占的位置和单个计时器**一样大** —— 只是一个格子，
 * 格子里并排显示成员的小盘。
 *
 * 这样用户可以在一个面板里把「同一件事的几个阶段」绑在一起，
 * 又不用牺牲网格的整体密度。
 */
@Entity(tableName = "timer_groups")
@Serializable
data class TimerGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val panelId: Long,
    val name: String,
    val order: Int = 0,
)

/** 组与计时器的组成关系。 */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "timerId"])
@Serializable
data class GroupMember(
    val groupId: Long,
    val timerId: Long,
    val order: Int = 0,
)
