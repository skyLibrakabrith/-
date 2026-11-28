package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.Step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活判据。
 *
 * 背景（真机实测）：App 退到后台后 `am kill`，pid 直接变空、`dumpsys alarm`
 * 里 0 条闹钟、等 10 秒也不重启 —— **到点不会响**。
 * 前台服务能让进程在系统回收时排得靠后。
 *
 * 这里是"该不该起前台服务"的纯判据，抽出来才测得动。
 */
class KeepAliveTest {

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
    fun `开关关着永远不保活`() {
        assertFalse(shouldKeepAlive(false, anyTimerRunning = true, sequencePhase = running))
        assertFalse(shouldKeepAlive(false, anyTimerRunning = false, sequencePhase = running))
        assertFalse(
            shouldKeepAlive(false, anyTimerRunning = false, sequencePhase = StepSequenceEngine.Phase.Idle),
        )
    }

    @Test
    fun `有计时器在跑时保活`() {
        assertTrue(
            shouldKeepAlive(
                true,
                anyTimerRunning = true,
                sequencePhase = StepSequenceEngine.Phase.Idle,
            ),
        )
    }

    @Test
    fun `序列倒计时时保活`() {
        assertTrue(shouldKeepAlive(true, anyTimerRunning = false, sequencePhase = running))
    }

    @Test
    fun `序列等确认时保活`() {
        // 阶段已结束但在等你点下一阶段，随时会回来 —— 进程不该被回收
        assertTrue(shouldKeepAlive(true, anyTimerRunning = false, sequencePhase = awaiting))
    }

    @Test
    fun `没有计时在跑时不保活`() {
        // 核心：避免"什么都不跑还常驻一条通知"
        assertFalse(
            shouldKeepAlive(true, anyTimerRunning = false, sequencePhase = StepSequenceEngine.Phase.Idle),
        )
        assertFalse(
            shouldKeepAlive(true, anyTimerRunning = false, sequencePhase = StepSequenceEngine.Phase.Finished),
        )
    }

    @Test
    fun `通知开关关闭时不发通知`() {
        assertFalse(shouldPostNotification(notificationEnabled = false))
    }

    @Test
    fun `通知开关开启时发通知`() {
        assertTrue(shouldPostNotification(notificationEnabled = true))
    }
}
