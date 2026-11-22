package at.cwiesner.android.visualtimer.ext.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 逐步间隔序列中的一步。
 *
 * 与 [TimerItem] 的区别：Step 属于某个序列，且带自己的警报与过渡策略。
 */
@Entity(tableName = "steps")
@Serializable
data class Step(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceId: Long,
    val durationMillis: Long,
    val label: String? = null,
    val alertProfileId: Long? = null,
    val order: Int = 0,
)

/**
 * 逐步间隔序列：按顺序执行 [Step]，每一步结束后等待用户确认或超时。
 *
 * @property timeoutMillis 每步结束后的等待超时。**默认 0 表示不设超时**，
 *   此时只有正计时在走，不会出现「已超时」的红色强调。
 *   设为大于 0 时，正计时与超时倒计时并行：正计时从 0 立刻开始，
 *   超时到了只切强调态，**仍然不会自动跳步**，必须用户点击。
 * @property timeoutAutoAdvance 是否在超时后自动前进。默认 false，
 *   与用户要求一致：显示超时多久，点击后才下一个阶段。
 */
@Entity(tableName = "sequences")
@Serializable
data class StepSequence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timeoutMillis: Long = 0L,
    val timeoutAutoAdvance: Boolean = false,
    val sortOrder: Int = 0,
    /**
     * 所属面板；null 表示还没归属任何面板（在序列页单独管理）。
     * 归属后它会作为一张卡片出现在该面板的网格里。
     */
    val panelId: Long? = null,
)
