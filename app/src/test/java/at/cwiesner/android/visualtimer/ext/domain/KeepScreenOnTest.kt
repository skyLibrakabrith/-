package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.Step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕常亮判据。
 *
 * 用户要求：「App 内**计时时**保持屏幕常亮」，默认开。
 *
 * 原来的实现是"开关一开就一直亮"——翻设置页、看归档也让屏幕永不熄灭。
 * 这里把判据钉死：开关关着永远不亮；开着但在跑计时才亮。
 */
class KeepScreenOnTest {

    private val running = StepSequenceEngine.Phase.Running(
        stepIndex = 0,
        step = Step(id = 1L, sequenceId = 1L, durationMillis = 60_000L, order = 0),
        remainingMillis = 30_000L,
        totalMillis = 60_000L,
    )

    private val awaiting = StepSequenceEngine.Phase.AwaitingConfirm(
        finishedStepIndex = 0,
        finishedStep = Step(id = 1L, sequenceId = 1L, durationMillis = 60_000L, order = 0),
        nextStep = null,
        elapsedMillis = 1_000L,
        timeoutRemainingMillis = 0L,
        overtimeMillis = 0L,
        overdue = false,
    )

    @Test
    fun `开关关着永远不常亮`() {
        assertFalse(shouldKeepScreenOn(false, anyTimerRunning = true, sequencePhase = running))
        assertFalse(shouldKeepScreenOn(false, anyTimerRunning = false, sequencePhase = running))
        assertFalse(shouldKeepScreenOn(false, anyTimerRunning = false,
            sequencePhase = StepSequenceEngine.Phase.Idle))
    }

    @Test
    fun `有计时器在跑时常亮`() {
        assertTrue(shouldKeepScreenOn(true, anyTimerRunning = true,
            sequencePhase = StepSequenceEngine.Phase.Idle))
    }

    @Test
    fun `序列倒计时时常亮`() {
        assertTrue(shouldKeepScreenOn(true, anyTimerRunning = false, sequencePhase = running))
    }

    @Test
    fun `序列等用户确认下一阶段时常亮`() {
        // 阶段已结束但在等你点「下一阶段」，随时会回来操作，不该熄屏
        assertTrue(shouldKeepScreenOn(true, anyTimerRunning = false, sequencePhase = awaiting))
    }

    @Test
    fun `序列暂停中仍常亮`() {
        // 暂停只是先停一下，过几秒就回来
        val paused = running.copy(paused = true)
        assertTrue(shouldKeepScreenOn(true, anyTimerRunning = false, sequencePhase = paused))
    }

    @Test
    fun `没有计时在跑时不常亮`() {
        // 这是本次改动的核心：开关开着，但翻设置页/看归档时不该让屏幕永不熄灭
        assertFalse(shouldKeepScreenOn(true, anyTimerRunning = false,
            sequencePhase = StepSequenceEngine.Phase.Idle))
    }

    @Test
    fun `序列已结束不常亮`() {
        assertFalse(shouldKeepScreenOn(true, anyTimerRunning = false,
            sequencePhase = StepSequenceEngine.Phase.Finished))
        assertFalse(shouldKeepScreenOn(true, anyTimerRunning = false,
            sequencePhase = StepSequenceEngine.Phase.Idle))
    }
}
