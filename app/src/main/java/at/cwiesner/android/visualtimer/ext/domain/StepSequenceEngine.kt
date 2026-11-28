package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 逐步间隔的运行时状态机。
 *
 * 状态流转：
 * ```
 * Idle ──start──▶ Running(step 0) ──pause/resume──▶ Running(step 0, paused=true)
 *                    │  倒计时走完
 *                    ▼
 *                  AwaitingConfirm（超时倒计时 → 到点后转正计时）
 *                    │  用户点击
 *                    ▼
 *                  Running(step 1) → ... → Finished
 * ```
 *
 * **关键行为（按需求）**：
 * 1. 每一步结束进入 [Phase.AwaitingConfirm]，界面显示**超时倒计时**。
 * 2. 到了超时时间**不会自动跳步**，必须用户点击才前进。
 * 3. 超时之后转入**正计时**：显示"已经超时多久"（[Phase.AwaitingConfirm.overtimeMillis]
 *    持续递增），提醒用户这一段已经被拖了多长时间。
 * 4. [pause] / [resume] 只对倒计时阶段有意义：暂停时冻结剩余时间，继续时从冻结点接着走。
 */
class StepSequenceEngine(
    private val scope: CoroutineScope,
    private val onStepFinished: suspend (Step) -> Unit,
    /**
     * 序列被停止或正常跑完时回调，载荷是**真正跑过的阶段**及其实际用时。
     *
     * 用户要求：「序列被停止或跑完时，每个跑过的阶段记一条归档」。
     *
     * 为什么由引擎提供：只有它知道每一步实际走了多久（含暂停、含超时）。
     * 界面层拿到的只是快照，自己再算一遍必然和引擎不一致。
     */
    private val onSequenceEnded: suspend (List<StepRun>) -> Unit = {},
) {
    sealed interface Phase {
        data object Idle : Phase

        /** 正在执行某一步。 */
        data class Running(
            val stepIndex: Int,
            val step: Step,
            val remainingMillis: Long,
            val totalMillis: Long,
            /**
             * 是否处于暂停态。
             *
             * 刻意做成 [Running] 的字段而不是新的 Phase 子类型：界面里到处是
             * `is Phase.Running` 分支（进度环、剩余时间、步骤序号），
             * 加一个 RunningPaused 子类型会让这些分支全部漏掉新类型（且编译期
             * 不一定报错），风险远大于收益。暂停只是 Running 的一个修饰状态。
             */
            val paused: Boolean = false,
        ) : Phase {
            val progress: Float
                get() = if (totalMillis <= 0) 0f
                else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
        }

        /**
         * 等待用户确认进入下一步。
         *
         * @property elapsedMillis **阶段结束后已经过去多久**，从结束那一刻起
         *   **立刻**开始正计时（不等超时）。这是「本阶段耗时/已拖延」的直观读数。
         * @property timeoutRemainingMillis 距离超时还有多久；到 0 之后保持 0
         * @property overtimeMillis 超时后又过了多久（= elapsed − timeout，供强调用）
         * @property overdue 是否已超时（界面切换为强调态）
         */
        data class AwaitingConfirm(
            val finishedStepIndex: Int,
            val finishedStep: Step,
            val nextStep: Step?,
            val elapsedMillis: Long,
            val timeoutRemainingMillis: Long,
            val overtimeMillis: Long,
            val overdue: Boolean,
        ) : Phase

        data object Finished : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /**
     * 暂停标志。
     *
     * 倒计时循环 await 的第一个对象 —— 用它而不是 `while (paused) delay(...)` 轮询，
     * 好处有两个：暂停期间根本不产生定时任务（真机不进 doze 唤醒、runTest 里也不会有
     * 一个永远跑不完的 delay 链）；resume() 改这个值就能立刻唤醒循环。
     * 它是 StateFlow，并发写也安全（pause/resume 可能来自 UI 线程，循环跑在 scope 里）。
     */
    private val _paused = MutableStateFlow(false)

    private var tickJob: Job? = null
    /** 当前正在跑的序列 id；null 表示空闲。界面据此判断哪张序列卡片处于活动状态。 */
    var activeSequenceId: Long? = null
        private set

    private var sequence: StepSequence? = null
    private var steps: List<Step> = emptyList()

    /**
     * 本次运行中每个阶段的实际所用时长（毫秒），按阶段顺序。
     *
     * 在 [runStep] 进入时开始计时，在阶段结束时结算；`stop()` / `finish()`
     * 会把还没结算的那一步也结算进去 —— 用户中途停掉序列，那一步跑过的时间
     * 同样发生过，不能丢。
     */
    private val stepRuns = mutableListOf<StepRun>()
    /** 当前阶段开始计时的时刻（`System.nanoTime()` 基准，避免墙钟跳变）。 */
    private var currentStepStartedNanos = 0L

    /** 启动一个序列。若已有运行中的序列，先停止。 */
    fun start(seq: StepSequence, steps: List<Step>) {
        stop()
        if (steps.isEmpty()) {
            _phase.value = Phase.Finished
            return
        }
        this.sequence = seq
        this.steps = steps
        this.activeSequenceId = seq.id
        stepRuns.clear()
        runStep(0)
    }

    /**
     * 暂停当前步的倒计时。
     *
     * 只有 [Phase.Running] 且未暂停时有效，其余情况（Idle / Finished / AwaitingConfirm /
     * 已经暂停）直接 return —— [Phase.AwaitingConfirm] 本身就在等用户点击，
     * 对它做"暂停"没有语义，乱允许只会让界面出现"暂停中还在正计时"的矛盾状态。
     *
     * 只翻标志、立刻改阶段，不碰 tickJob：stepIndex 和 remainingMillis 原地保留，
     * 循环自己会在下一个 tick 处看到标志并停下来。
     */
    fun pause() {
        val current = _phase.value
        if (current !is Phase.Running) return
        if (current.paused) return
        _paused.value = true
        // 不等循环下一拍才生效：UI 点击后要立刻看到「已暂停」，避免这 100ms 里继续跳秒
        _phase.value = current.copy(paused = true)
    }

    /**
     * 从暂停处继续倒计时。
     *
     * 只清标志；阶段上的 paused=false 交给倒计时循环去做 —— 循环被唤醒后第一件事就是
     * 重新发出 `Running(..., paused = false)`。这样"是否存在活着的 tick 协程"和
     * "paused 字段"永远不会说法不一致：如果恢复逻辑自己改阶段但协程其实已经没了
     * （比如 pause 期间有人调了 stop），界面就会卡在 Running 上永远不动。
     */
    fun resume() {
        val current = _phase.value
        if (current !is Phase.Running) return
        if (!current.paused) return
        _paused.value = false
    }

    /** 用户点击「下一阶段」。仅在 [Phase.AwaitingConfirm] 状态下有效。 */
    fun confirm() {
        val current = _phase.value
        if (current !is Phase.AwaitingConfirm) return
        val next = current.finishedStepIndex + 1
        if (next >= steps.size) {
            finish()
        } else {
            runStep(next)
        }
    }

    /**
     * 正常走完整个序列。
     *
     * 必须和 [skip] 走「最后一步」的路径保持一致：**都要清掉 [activeSequenceId]**，
     * 否则序列明明已经 Finished，界面仍会认为它"正在跑"（序列卡片显示为活动态，
     * 而且下次点别的序列时行为会很怪）。
     *
     * 之前 confirm() 只 `_phase.value = Finished` + `tickJob?.cancel()`，漏了这一句 ——
     * 是补单元测试时发现的：最后一步 confirm 后 activeSequenceId 仍是 1。
     */
    private fun finish() {
        tickJob?.cancel()
        tickJob = null
        activeSequenceId = null
        _phase.value = Phase.Finished
        emitEnded()
    }

    /**
     * 结算当前阶段并回调 [onSequenceEnded]，然后清空记录。
     *
     * `stop()` 与 `finish()` 是两个独立终点（正常跑完 / 用户停掉），
     * 两者都要把"跑过的阶段"交出去，否则中途停掉的那次序列在归档里不留痕迹。
     */
    private fun emitEnded() {
        settleCurrentStep()
        if (stepRuns.isEmpty()) return
        val payload = stepRuns.toList()
        stepRuns.clear()
        scope.launch { runCatching { onSequenceEnded(payload) } }
    }

    /** 把「当前正在跑的那一步」按已过时间结算进 [stepRuns]。 */
    private fun settleCurrentStep() {
        if (currentStepStartedNanos == 0L) return
        val elapsed = (System.nanoTime() - currentStepStartedNanos) / 1_000_000L
        currentStepStartedNanos = 0L
        val phase = _phase.value
        val (idx, step) = when (phase) {
            is Phase.Running -> phase.stepIndex to phase.step
            is Phase.AwaitingConfirm -> phase.finishedStepIndex to phase.finishedStep
            else -> return
        }
        // 已经结算过这一步就不再重复（finish/stop 可能前后脚都被调用）
        if (stepRuns.any { it.stepIndex == idx }) return
        stepRuns += StepRun(
            stepIndex = idx,
            step = step,
            actualMillis = elapsed.coerceAtLeast(0L),
        )
    }

    /** 跳过当前步，直接进入下一步（不触发警报）。 */
    fun skip() {
        val current = _phase.value
        val index = when (current) {
            is Phase.Running -> current.stepIndex
            is Phase.AwaitingConfirm -> current.finishedStepIndex
            else -> return
        }
        val next = index + 1
        if (next >= steps.size) {
            // 与 confirm() 的终点共用 finish()，保证两条路径行为一致
            finish()
        } else {
            runStep(next)
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        // 清掉暂停标志：否则暂停中停机、下次 start 第一步会一出生就是"暂停"，
        // 表现为序列不动，像死机一样（停了就该回到干净状态）
        _paused.value = false
        activeSequenceId = null
        _phase.value = Phase.Idle
        // 中途停掉：已经跑过的时间同样要记进归档（用户要求"每个跑过的阶段记一条"）
        emitEnded()
    }

    private fun runStep(index: Int) {
        tickJob?.cancel()
        val step = steps[index]
        val seq = sequence ?: return
        val total = step.durationMillis

        // 上一阶段若还没结算（比如 skip 直接跳过来），先补上
        settleCurrentStep()
        currentStepStartedNanos = System.nanoTime()

        // 新的一步从"未暂停"开始：同一趟序列里暂停第 2 步，不应让第 3 步一出生就冻住。
        // 放在 launch 外面而不是协程内部，是为了让它同步生效 —— 新协程真正开跑之前
        // 也不会有短暂的"旧值"窗口。
        _paused.value = false

        tickJob = scope.launch {
            // ---- 阶段 1：倒计时 ----
            var remaining = total
            _phase.value = Phase.Running(index, step, remaining, total)
            // 暂停语义只做在倒计时这一段：AwaitingConfirm 是"在等用户点击"的正计时，
            // 没有可冻结的剩余时间，硬暂停只会让 elapsed 语义变乱。
            //
            // ## 暂停怎么实现（改之前务必读完，这里踩过一次致命坑）
            //
            // 要求：**未暂停时每 100ms 精确扣 100ms**，暂停时一分不扣。
            // 现有单测对 100ms 粒度是硬断言（advanceTimeBy(300) 后 remaining 必须少 300），
            // 所以计时节奏不能有任何偏差。
            //
            // 曾经写成
            //     val ticked = withTimeoutOrNull(TICK_MILLIS) { _paused.first { !it } }
            //     if (!ticked) remaining -= TICK_MILLIS
            // 这是**错的**：`_paused` 初始为 false，`first { !it }` 会**立即返回**，
            // 于是 withTimeoutOrNull 立刻返回 true（没超时）-> 不扣时间 ->
            // 循环疯狂空转、remaining 永不减少，整个序列卡死。
            // 而且空转的协程会把 runTest 的虚拟时钟拖住。
            //
            // 现在的写法回到"先等 100ms，再看要不要扣"，语义最直白：
            //   · 暂停中：每 100ms 醒一次，只刷新界面状态，不扣时间（不空转也不挂死）
            //   · 未暂停：照旧每 100ms 扣 100ms —— 与改动前**完全等价**
            while (isActive && remaining > 0) {
                delay(TICK_MILLIS)
                if (!isActive) return@launch
                if (_paused.value) {
                    // 暂停：本 tick 的时间不算数，只让界面看到 paused=true
                    _phase.value = Phase.Running(index, step, remaining, total, paused = true)
                    continue
                }
                remaining = (remaining - TICK_MILLIS).coerceAtLeast(0)
                _phase.value = Phase.Running(index, step, remaining, total)
            }
            if (!isActive) return@launch

            // 该步结束，触发警报
            runCatching { onStepFinished(step) }

            val nextStep = steps.getOrNull(index + 1)
            var timeoutLeft = seq.timeoutMillis
            var elapsed = 0L          // 从阶段结束起立刻累加
            var overtime = 0L

            _phase.value = Phase.AwaitingConfirm(
                finishedStepIndex = index,
                finishedStep = step,
                nextStep = nextStep,
                elapsedMillis = 0L,
                timeoutRemainingMillis = timeoutLeft,
                overtimeMillis = 0L,
                overdue = false,
            )

            // ---- 阶段 2：正计时立刻开始，同时超时倒计时并行 ----
            // 注意：全程不自动前进，必须用户调用 confirm()
            while (isActive) {
                delay(TICK_MILLIS)
                elapsed += TICK_MILLIS            // 立刻正计时，不等超时
                if (timeoutLeft > 0) {
                    timeoutLeft = (timeoutLeft - TICK_MILLIS).coerceAtLeast(0)
                } else {
                    overtime = elapsed - seq.timeoutMillis
                }
                _phase.value = Phase.AwaitingConfirm(
                    finishedStepIndex = index,
                    finishedStep = step,
                    nextStep = nextStep,
                    elapsedMillis = elapsed,
                    timeoutRemainingMillis = timeoutLeft,
                    overtimeMillis = if (overtime > 0) overtime else 0L,
                    // timeout=0 时表示「不设超时」，永远不进入强调态
                    overdue = seq.timeoutMillis > 0L && timeoutLeft <= 0,
                )
            }
        }
    }

    companion object {
        /** 计时刷新粒度。 */
        const val TICK_MILLIS: Long = 100L
    }
}

/**
 * 一个阶段在**某一次序列运行**中的实际执行情况。
 *
 * 为什么不直接给 `Step`：`Step.durationMillis` 是**计划**时长，
 * 归档要的是"实际跑了多久"（可能因为暂停更久、也可能被用户提前跳过）。
 *
 * @property stepIndex 在序列里的序号（0 起），用于排序和定位
 * @property actualMillis 实际经过的时间（含暂停、含超时）
 */
data class StepRun(
    val stepIndex: Int,
    val step: Step,
    val actualMillis: Long,
)
