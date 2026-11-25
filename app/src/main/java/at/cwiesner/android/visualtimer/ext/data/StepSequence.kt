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
    /**
     * 这一步的困难程度，1（很容易）～ 5（很难）。null 表示没标注。
     *
     * 由 AI 拆解时给出，**主要用途是给估时当参考**：同样一句话，
     * 「很难」的步骤模型会估得更长（含启动摩擦、卡壳、返工）。
     * 用户在改时长时也能靠它判断这一步是不是不该这么短。
     *
     * 存进库而不是只在预览里显示，是因为序列建好之后用户还会反复看它 ——
     * 每步多难是判断"今天能不能做完"的重要信息，不该只在生成那一刻可见。
     */
    val difficulty: Int? = null,
    /**
     * 这一步的背景/理由：为什么要做它、要做到什么程度。
     *
     * 拆解时由模型先猜一条（比如「收集文献」-> 「老师要求至少 5 篇近三年文献」），
     * 用户在排序界面可以逐条改。**参与后面一步的估时** ——
     * 一句「随便糊弄」和一句「要能交上去」估出来差好几倍。
     *
     * null = 没写。空字符串和 null 在这里等价，都表示"没有背景"。
     */
    val context: String? = null,
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
