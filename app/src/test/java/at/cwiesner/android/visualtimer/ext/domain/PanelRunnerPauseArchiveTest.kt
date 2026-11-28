package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正计时**暂停**时发出归档事件（[PanelRunner.pauseEvents]）。
 *
 * 对应需求：「正计时暂停自动记录到归档」。
 *
 * 背景：归档原本只接在「到点」事件上，而正计时永远不到点 ——
 * 于是归档里一条正计时都没有。暂停是正计时唯一的"结束"动作，
 * 所以暂停那一刻要落一条历史。
 *
 * 这一组测试盯住三个最容易写错的点：
 * 1. **只有从"在跑"变成"停"才发事件**（重复点暂停不能刷记录）
 * 2. **倒计时暂停不发事件**（它还能继续走到点，半途暂停不该算一次完整计时）
 * 3. **继续后再暂停，第二段的时长不含前一段**（否则每段越来越长）
 */
class PanelRunnerPauseArchiveTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun runner() = PanelRunner(scope)

    private fun stopwatch(id: Long = 1, label: String = "正计时") = TimerItem(
        id = id,
        label = label,
        durationMillis = 0L,
        alertProfileId = null,
        position = 0,
        kind = TimerKind.Stopwatch,
    )

    private fun countdown(id: Long = 2, duration: Long = 60_000L) = TimerItem(
        id = id,
        label = "倒计时",
        durationMillis = duration,
        alertProfileId = null,
        position = 0,
        kind = TimerKind.Countdown,
    )

    @Test
    fun `正计时暂停会发事件 且载荷是已停止的快照`() {
        val runner = runner()
        val events = mutableListOf<TimerRuntime>()
        // onSubscription 之前 tryEmit 会被丢弃（无 replay），所以直接收集：
        // MutableSharedFlow 订阅前 emit 会丢，这正好也验证了"有订阅者"的前提。
        val job = scope.launch { runner.pauseEvents.collect { events += it } }
        // Unconfined 下 collect 立即开始，可以安全 tryEmit
        runner.startStopwatch(stopwatch())
        runner.tick(5_000L, System.currentTimeMillis())
        runner.stop(1L)

        assertEquals("正计时暂停应发出一条事件", 1, events.size)
        assertTrue("载荷应是正计时", events[0].stopwatch)
        assertFalse("载荷应是已暂停状态", events[0].running)
        assertTrue("startedAt 应已填上", events[0].startedAt > 0L)
        job.cancel()
    }

    @Test
    fun `重复暂停不会重复发事件`() {
        val runner = runner()
        val events = mutableListOf<TimerRuntime>()
        val job = scope.launch { runner.pauseEvents.collect { events += it } }
        runner.startStopwatch(stopwatch())
        runner.stop(1L)
        runner.stop(1L)   // 已经是暂停态
        runner.stop(1L)
        assertEquals("重复点暂停不该刷出多条归档", 1, events.size)
        job.cancel()
    }

    /**
     * 这条规则**在 2026-11-28 被用户明确改掉了**，旧断言是「倒计时暂停不发事件」。
     *
     * 原主意：倒计时暂停后还能继续走完，半途暂停不该算一次完整计时。
     * 用户新要求：「暂停也自动归档，但一整条 + 圆盘上空出暂停段」。
     *
     * 实现方式让两者不再冲突：暂停时写一条 `ongoing = 1` 的**占位**行，
     * 结束时原地更新它，归档里始终只有一条；暂停区间单独记在 timer_pauses 里。
     * 所以引擎这边必须发事件 —— 否则占位行和暂停区间都无从产生。
     */
    @Test
    fun `倒计时暂停也发事件`() {
        val runner = runner()
        val events = mutableListOf<TimerRuntime>()
        val job = scope.launch { runner.pauseEvents.collect { events += it } }
        runner.start(countdown())
        runner.tick(10_000L, System.currentTimeMillis())
        runner.stop(2L)

        assertEquals("倒计时暂停现在也要归档（占位行 + 暂停区间）", 1, events.size)
        assertEquals("载荷应是已停止的快照", false, events[0].running)
        job.cancel()
    }

    @Test
    fun `恢复会发 resume 事件`() {
        val runner = runner()
        val resumes = mutableListOf<Long>()
        val job = scope.launch { runner.resumeEvents.collect { resumes += it } }
        runner.start(countdown())
        runner.stop(2L)
        runner.resume(2L)

        assertEquals("恢复时要封口暂停区间", listOf(2L), resumes)
        job.cancel()
    }

    @Test
    fun `继续后再暂停 第二段的时长不含前一段`() {
        val runner = runner()
        val events = mutableListOf<TimerRuntime>()
        val job = scope.launch { runner.pauseEvents.collect { events += it } }

        runner.startStopwatch(stopwatch())
        runner.tick(10_000L, System.currentTimeMillis())   // 第一段 10 秒
        runner.stop(1L)

        runner.resume(1L)                                   // 继续
        runner.tick(3_000L, System.currentTimeMillis())     // 第二段 3 秒
        runner.stop(1L)

        assertEquals(2, events.size)
        assertEquals("第一段应为 10 秒", 10_000L, events[0].stopwatchSegmentMillis)
        assertEquals("第二段应为 3 秒（不含第一段）", 3_000L, events[1].stopwatchSegmentMillis)
        job.cancel()
    }

    @Test
    fun `继续时 startedAt 会挪到当下`() {
        val runner = runner()
        runner.startStopwatch(stopwatch())
        val firstStart = runner.get(1L)!!.startedAt

        Thread.sleep(15)   // 保证时钟前进
        runner.stop(1L)
        runner.resume(1L)
        val secondStart = runner.get(1L)!!.startedAt

        assertTrue("继续后 startedAt 必须往后挪，否则两段归档区间会重叠", secondStart > firstStart)
    }

    @Test
    fun `倒计时的 stopwatchSegmentMillis 恒为 0`() {
        val runner = runner()
        runner.start(countdown(duration = 60_000L))
        runner.tick(10_000L, System.currentTimeMillis())
        assertEquals(0L, runner.get(2L)!!.stopwatchSegmentMillis)
    }

    @Test
    fun `正计时累计值仍然正确 分段的只是归档`() {
        val runner = runner()
        runner.startStopwatch(stopwatch())
        runner.tick(10_000L, System.currentTimeMillis())
        runner.resume(1L)   // 已经在跑时 resume 也不该清零累计
        runner.tick(5_000L, System.currentTimeMillis())

        val rt = runner.get(1L)!!
        assertEquals("盘面显示的累计时长不能被分段逻辑破坏", 15_000L, rt.remainingMillis)
        assertEquals("但这一段只有 resume 之后的 5 秒", 5_000L, rt.stopwatchSegmentMillis)
    }
}
