package at.cwiesner.android.visualtimer.ext.llm

import kotlinx.serialization.Serializable

/**
 * AI 调用的结果。
 *
 * 刻意用 sealed interface 而不是抛异常：
 * 这三个入口都是**用户在界面上点的按钮**，失败必须变成一句能看懂的话，
 * 而不是把异常冒到协程里导致 App 崩掉或静默无反应。
 */
sealed interface AiResult<out T> {
    data class Ok<T>(val value: T) : AiResult<T>

    /**
     * 失败。
     *
     * @property message 给用户看的一句话（中文，可直接 toast/展示）
     * @property cause 原始异常，只用于记日志
     */
    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : AiResult<Nothing>
}

/**
 * 一个可拆解的步骤：标题 + 估计分钟数 + 困难程度 + 背景。
 *
 * 对应 goblin.tools 的 Magic ToDo + Estimator：
 * 拆解与估时是同一次往返里一起产出的，因为分开问两次模型会给出互相矛盾的结果。
 *
 * @property minutes 0 表示**还没估时**。Compiler 的流程是「先拆、排序、再统一估时」，
 *   所以拆出来的中间产物是 minutes=0 + difficulty 已知；等排序后
 *   [at.cwiesner.android.visualtimer.ext.llm.AiFeatures.estimateList] 再填上。
 *   用 0 而不是可空是因为下游建 Step 时不用再处理 null。
 * @property difficulty 1（很容易）～5（很难）；null = 模型没给
 * @property context 背景/理由：为什么要做它、要做到什么程度。估时会用到。
 */
@Serializable
data class TodoStep(
    val title: String,
    val minutes: Int = 0,
    val difficulty: Int? = null,
    val context: String? = null,
)

/** 一组计时器/序列：Compiler 把碎片念头归类后的产物。 */
@Serializable
data class TodoGroup(
    val name: String,
    val items: List<TodoStep>,
)

/** 模型返回的原始 JSON 结构（拆解）。 */
@Serializable
internal data class StepsPayload(val steps: List<TodoStep> = emptyList())

/** 模型返回的原始 JSON 结构（整理念头）。 */
@Serializable
internal data class GroupsPayload(val groups: List<TodoGroup> = emptyList())

/**
 * 送去估时的单条内容。
 *
 * 只带估时真正用得上的三样：标题、难度、背景。
 * 不复用 [TodoStep] 是因为此时 minutes 还没算出来，带着它容易让调用方误以为已经有值。
 */
data class EstimateItem(
    val title: String,
    val difficulty: Int? = null,
    val context: String? = null,
)
