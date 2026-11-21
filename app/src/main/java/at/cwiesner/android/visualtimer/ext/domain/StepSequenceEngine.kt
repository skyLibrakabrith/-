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
 * Idle ──start──▶ Running(step 0)
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
 */
class StepSequenceEngine(
    private val scope: CoroutineScope,
    private val onStepFinished: suspend (Step) -> Unit,
) {
    sealed interface Phase {
        data object Idle : Phase

        /** 正在执行某一步。 */
        data class Running(
            val stepIndex: Int,
            val step: Step,
            val remainingMillis: Long,
            val totalMillis: Long,
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

    private var tickJob: Job? = null
    private var sequence: StepSequence? = null
    private var steps: List<Step> = emptyList()

    /** 启动一个序列。若已有运行中的序列，先停止。 */
    fun start(seq: StepSequence, steps: List<Step>) {
        stop()
        if (steps.isEmpty()) {
            _phase.value = Phase.Finished
            return
        }
        this.sequence = seq
        this.steps = steps
        runStep(0)
    }

    /** 用户点击「下一阶段」。仅在 [Phase.AwaitingConfirm] 状态下有效。 */
    fun confirm() {
        val current = _phase.value
        if (current !is Phase.AwaitingConfirm) return
        val next = current.finishedStepIndex + 1
        if (next >= steps.size) {
            _phase.value = Phase.Finished
            tickJob?.cancel()
        } else {
            runStep(next)
        }
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
            stop()
            _phase.value = Phase.Finished
        } else {
            runStep(next)
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        _phase.value = Phase.Idle
    }

    private fun runStep(index: Int) {
        tickJob?.cancel()
        val step = steps[index]
        val seq = sequence ?: return
        val total = step.durationMillis

        tickJob = scope.launch {
            // ---- 阶段 1：倒计时 ----
            var remaining = total
            _phase.value = Phase.Running(index, step, remaining, total)
            while (isActive && remaining > 0) {
                delay(TICK_MILLIS)
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
                    overdue = timeoutLeft <= 0,
                )
            }
        }
    }

    companion object {
        /** 计时刷新粒度。 */
        const val TICK_MILLIS: Long = 100L
    }
}
