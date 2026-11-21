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
 * @property timeoutMillis 每步结束时展示确认界面的超时时间；
 *   到了超时时间只高亮提示，**不会自动跳步**（用户要求：点击后才进入下一个阶段）。
 *   设为 0 表示不显示超时。
 * @property timeoutAutoAdvance 是否在超时后自动前进。默认 false，
 *   与用户要求一致：显示超时多久，点击后才下一个阶段。
 */
@Entity(tableName = "sequences")
@Serializable
data class StepSequence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timeoutMillis: Long = 30_000L,
    val timeoutAutoAdvance: Boolean = false,
    val sortOrder: Int = 0,
)
