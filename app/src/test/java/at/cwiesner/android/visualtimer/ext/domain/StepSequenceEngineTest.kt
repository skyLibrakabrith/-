package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepSequenceEngine] 的状态机测试。
 *
 * 用 `runTest` 的虚拟时钟推进，所以不会真的等 —— 每一步的 100ms tick 是瞬间跳过去的。
 *
 * 重点验证文档里写死的三条行为：
 * 1. 每步结束进入 AwaitingConfirm；
 * 2. 超时后**不自动跳步**，必须 confirm()；
 * 3. 结束时 elapsed 立刻开始正计时，超时后 overtime 递增。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepSequenceEngineTest {

    private fun seq(
        timeoutMillis: Long = 0L,
        autoAdvance: Boolean = false,
        name: String = "测试序列",
    ) = StepSequence(
        id = 1L,
        name = name,
        timeoutMillis = timeoutMillis,
        timeoutAutoAdvance = autoAdvance,
    )

    private fun steps(vararg durations: Long): List<Step> =
        durations.mapIndexed { i, d ->
            Step(id = (i + 1).toLong(), sequenceId = 1L, durationMillis = d, label = "第${i + 1}步", order = i)
        }

    /**
     * 把引擎建在 [TestScope.backgroundScope] 上。
     *
     * 这一点是必须的，不是风格问题：`StepSequenceEngine.runStep` 的第二阶段是
     * `while (isActive) { delay(...) }` **无限循环**，只有 `stop()` 或取消 scope 才停。
     * 如果建在普通 `runTest` scope 上，测试体结束后 `runTest` 会等待该协程完成 ——
     * 而它永远不会完成，于是整个测试挂死（CI 上表现为 unit test 步骤永远 in_progress，
     * 而不是失败报错，非常难查）。
     *
     * `backgroundScope` 在测试体结束时会被自动取消，正好对应"引擎还挂着但我们不管了"。
     */
    private fun TestScope.newEngine(onFinished: suspend (Step) -> Unit = {}) =
        StepSequenceEngine(scope = backgroundScope, onStepFinished = onFinished)

    // ---------------- 基本流转 ----------------

    @Test
    fun `初始状态是 Idle`() = runTest {
        val engine = newEngine()
        assertEquals(StepSequenceEngine.Phase.Idle, engine.phase.value)
        assertNull(engine.activeSequenceId)
    }

    @Test
    fun `空序列直接 Finished`() = runTest {
        val engine = newEngine()
        engine.start(seq(), emptyList())
        assertEquals(StepSequenceEngine.Phase.Finished, engine.phase.value)
    }

    @Test
    fun `启动后进入第一步 Running 并开始倒计时`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L))
        runCurrent()

        val p = engine.phase.value
        assertTrue(p is StepSequenceEngine.Phase.Running)
        p as StepSequenceEngine.Phase.Running
        assertEquals(0, p.stepIndex)
        assertEquals(1000L, p.totalMillis)
        assertEquals(1L, engine.activeSequenceId)

        // 推进 300ms，剩余应该减少（TICK_MILLIS=100）
        advanceTimeBy(300L)
        runCurrent()
        val p2 = engine.phase.value as StepSequenceEngine.Phase.Running
        assertEquals(700L, p2.remainingMillis)
    }

    /** 倒计时走完后进入 AwaitingConfirm，并且**不会**自动前进。 */
    @Test
    fun `一步走完进入 AwaitingConfirm 且不自动跳步`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(500L, 500L))

        advanceTimeBy(600L)
        runCurrent()

        val p = engine.phase.value
        assertTrue("应为 AwaitingConfirm，实际是 $p", p is StepSequenceEngine.Phase.AwaitingConfirm)
        p as StepSequenceEngine.Phase.AwaitingConfirm
        assertEquals(0, p.finishedStepIndex)
        assertEquals("第2步", p.nextStep?.label)

        // 再等很久，仍然停在这一步 —— 必须用户点击
        advanceTimeBy(10_000L)
        runCurrent()
        val still = engine.phase.value as StepSequenceEngine.Phase.AwaitingConfirm
        assertEquals(0, still.finishedStepIndex)
    }

    /**
     * 没有超时设置（timeout=0）时永远不进入强调态，但 elapsed 照样正计时。
     *
     * 注意 `overtimeMillis` 的语义：实现里 `timeoutLeft` 恒为 0，所以每次 tick 都走
     * `overtime = elapsed - timeoutMillis` 这个 else 分支，即 **overtime 恒等于 elapsed**。
     * 界面只看 `overdue` 决定是否切强调态（它是 false，行为正确），
     * 但不要把 overtime 断言成 0 —— 那是对实现语义的误解，不是 bug。
     */
    @Test
    fun `超时为 0 时 elapsed 正计时且永不 overdue`() = runTest {
        val engine = newEngine()
        engine.start(seq(timeoutMillis = 0L), steps(100L))

        advanceTimeBy(100L + 500L)   // 走完 + 再等 500ms
        runCurrent()

        val p = engine.phase.value as StepSequenceEngine.Phase.AwaitingConfirm
        assertTrue("elapsed 应立即开始正计时，实际 ${p.elapsedMillis}", p.elapsedMillis > 0L)
        assertEquals("timeout=0 时永远不该进入强调态", false, p.overdue)
        assertEquals(0L, p.timeoutRemainingMillis)
        // timeout=0 时 overtime 跟随 elapsed（见上方说明），而不是恒为 0
        assertEquals(p.elapsedMillis, p.overtimeMillis)
    }

    /** 有超时设置时，超时到点后 overdue=true 并开始累计 overtime。 */
    @Test
    fun `超过 timeout 后进入 overdue 并累计 overtime`() = runTest {
        val engine = newEngine()
        // 第 1 步 100ms，超时 1000ms
        engine.start(seq(timeoutMillis = 1000L), steps(100L))

        // 走完 + 还没到超时
        advanceTimeBy(100L + 300L)
        runCurrent()
        val mid = engine.phase.value as StepSequenceEngine.Phase.AwaitingConfirm
        assertEquals(false, mid.overdue)
        assertTrue("超时倒计时应 > 0，实际 ${mid.timeoutRemainingMillis}", mid.timeoutRemainingMillis > 0L)

        // 再推进超过超时
        advanceTimeBy(1200L)
        runCurrent()
        val late = engine.phase.value as StepSequenceEngine.Phase.AwaitingConfirm
        assertEquals(true, late.overdue)
        assertEquals(0L, late.timeoutRemainingMillis)
        assertTrue("overtime 应累计，实际 ${late.overtimeMillis}", late.overtimeMillis > 0L)
    }

    // ---------------- confirm ----------------

    @Test
    fun `confirm 推进到下一步`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(100L, 200L))

        advanceTimeBy(150L)
        runCurrent()
        engine.confirm()
        runCurrent()

        val p = engine.phase.value as StepSequenceEngine.Phase.Running
        assertEquals(1, p.stepIndex)
        assertEquals(200L, p.totalMillis)
    }

    /**
     * 回归测试：序列**正常走完**时也必须清空 [StepSequenceEngine.activeSequenceId]。
     *
     * 这条是补测试时抓到的真实 bug：confirm() 走到最后一步只设了 Finished + cancel tick，
     * 漏清 activeSequenceId。而 PanelsScreen 用
     * `val seqActive = engineActiveId == focusedSeq.id` 判断是否活动态，
     * 于是序列明明已完成，焦点屏仍当它「正在跑」。
     *
     * skip() 走终点原本就清（它内部调 stop()），两条路径行为不一致 —— 现在共用 finish()。
     */
    @Test
    fun `最后一步 confirm 后进入 Finished 并清空 activeSequenceId`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(100L))

        advanceTimeBy(150L)
        runCurrent()
        engine.confirm()
        runCurrent()

        assertEquals(StepSequenceEngine.Phase.Finished, engine.phase.value)
        assertNull("序列已完成就必须清空 activeSequenceId，否则界面会一直显示进行中", engine.activeSequenceId)
    }

    /** confirm 只在 AwaitingConfirm 时有效 —— 在 Running 期间调用必须被忽略。 */
    @Test
    fun `Running 状态下 confirm 无效`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L, 1000L))
        runCurrent()

        engine.confirm()
        runCurrent()

        val p = engine.phase.value as StepSequenceEngine.Phase.Running
        assertEquals("不应跳步", 0, p.stepIndex)
    }

    // ---------------- skip ----------------

    @Test
    fun `skip 在 Running 时跳到下一步`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L, 1000L))
        runCurrent()

        engine.skip()
        runCurrent()

        val p = engine.phase.value as StepSequenceEngine.Phase.Running
        assertEquals(1, p.stepIndex)
    }

    @Test
    fun `skip 在 AwaitingConfirm 时跳到下一步`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(100L, 500L))
        advanceTimeBy(150L)
        runCurrent()

        engine.skip()
        runCurrent()

        val p = engine.phase.value as StepSequenceEngine.Phase.Running
        assertEquals(1, p.stepIndex)
    }

    @Test
    fun `最后一步 skip 后 Finished`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L))
        runCurrent()

        engine.skip()
        runCurrent()

        assertEquals(StepSequenceEngine.Phase.Finished, engine.phase.value)
    }

    // ---------------- stop / 重启 ----------------

    @Test
    fun `stop 回到 Idle 并取消 tick`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L))
        runCurrent()

        engine.stop()
        runCurrent()

        assertEquals(StepSequenceEngine.Phase.Idle, engine.phase.value)
        assertNull(engine.activeSequenceId)

        // 停止后再推进时间，状态不应再变
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(StepSequenceEngine.Phase.Idle, engine.phase.value)
    }

    @Test
    fun `重复 start 会先停掉旧的而不会并行跑两条`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L))
        runCurrent()

        engine.start(seq(), steps(500L, 500L))
        runCurrent()

        val p = engine.phase.value as StepSequenceEngine.Phase.Running
        assertEquals(0, p.stepIndex)
        assertEquals(500L, p.totalMillis)
    }

    /** 每一步结束都要回调 onStepFinished —— 告警链路依赖它。 */
    @Test
    fun `每步结束回调 onStepFinished`() = runTest {
        val fired = mutableListOf<String?>()
        val engine = newEngine { step -> fired.add(step.label) }
        engine.start(seq(), steps(100L, 100L))

        advanceTimeBy(150L)
        runCurrent()
        engine.confirm()

        advanceTimeBy(150L)
        runCurrent()

        assertEquals(listOf("第1步", "第2步"), fired)
    }

    // ---------------- 「下一阶段」按钮该用哪个方法 ----------------

    /**
     * 真机上确认过的问题：序列全屏的「下一阶段」原来接的是 `confirm()`，
     * 而 `confirm()` 第一行就是 `if (current !is AwaitingConfirm) return` ——
     * `AwaitingConfirm` 只在本阶段**倒计时走完**后才进入。
     * 所以用户在阶段还在倒计时时点「下一阶段」，`confirm()` 立刻返回，按钮毫无反应。
     *
     * 这两条测试把「两个方法的适用相位」钉死，防止以后又接错。
     */
    @Test
    fun `confirm 在 Running 状态下无效`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L, 1000L))

        // 还在第一阶段倒计时中
        advanceTimeBy(200L)
        runCurrent()
        assertTrue(engine.phase.value is StepSequenceEngine.Phase.Running)

        engine.confirm()

        // 没有任何变化 —— 这就是"点了没用"
        val phase = engine.phase.value
        assertTrue(phase is StepSequenceEngine.Phase.Running)
        assertEquals(0, (phase as StepSequenceEngine.Phase.Running).stepIndex)
    }

    @Test
    fun `skip 在 Running 状态下能直接进入下一阶段`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L, 1000L))

        advanceTimeBy(200L)
        runCurrent()
        engine.skip()
        // runStep 里对 phase 的写入发生在协程里，要让调度器跑一拍才看得到
        runCurrent()

        val phase = engine.phase.value
        assertTrue("skip 后应进入第二阶段", phase is StepSequenceEngine.Phase.Running)
        assertEquals(1, (phase as StepSequenceEngine.Phase.Running).stepIndex)
    }

    @Test
    fun `skip 在最后一步会结束序列`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1000L))

        advanceTimeBy(200L)
        runCurrent()
        engine.skip()
        runCurrent()

        assertEquals(StepSequenceEngine.Phase.Finished, engine.phase.value)
        assertNull("结束时必须清掉 activeSequenceId", engine.activeSequenceId)
    }

    // ---------------- 暂停 / 继续（原来零覆盖）----------------

    @Test
    fun `pause 冻结倒计时`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1_000L, 1_000L))
        advanceTimeBy(200L); runCurrent()

        engine.pause()
        val frozen = (engine.phase.value as StepSequenceEngine.Phase.Running).remainingMillis

        // 再推进 500ms，剩余时间不能变
        advanceTimeBy(500L); runCurrent()
        assertEquals(
            "暂停后倒计时必须冻结",
            frozen,
            (engine.phase.value as StepSequenceEngine.Phase.Running).remainingMillis,
        )
    }

    @Test
    fun `resume 从原处继续而不是重置`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1_000L))
        advanceTimeBy(400L); runCurrent()
        engine.pause()
        val frozen = (engine.phase.value as StepSequenceEngine.Phase.Running).remainingMillis

        engine.resume()
        runCurrent()
        assertEquals("恢复后仍从冻结处开始", frozen,
            (engine.phase.value as StepSequenceEngine.Phase.Running).remainingMillis)

        advanceTimeBy(100L); runCurrent()
        assertEquals("恢复后继续扣时间", frozen - 100L,
            (engine.phase.value as StepSequenceEngine.Phase.Running).remainingMillis)
    }

    @Test
    fun `pause 保留 activeSequenceId 而 stop 清空`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1_000L))
        advanceTimeBy(100L); runCurrent()

        engine.pause()
        assertEquals("暂停不结束序列", 1L, engine.activeSequenceId)

        engine.stop()
        assertNull("stop 才清空", engine.activeSequenceId)
        assertEquals(StepSequenceEngine.Phase.Idle, engine.phase.value)
    }

    @Test
    fun `pause 不触发 onSequenceEnded 而 stop 会`() = runTest {
        val endedA = mutableListOf<List<StepRun>>()
        val engineA = StepSequenceEngine(
            scope = backgroundScope,
            onStepFinished = {},
            onSequenceEnded = { runs, _ -> endedA += runs },
        )
        engineA.start(seq(), steps(1_000L))
        advanceTimeBy(400L); runCurrent()
        engineA.pause()
        runCurrent()
        assertEquals("暂停不等于结束，不该归档", 0, endedA.size)

        engineA.stop()
        runCurrent()
        assertEquals("stop 才归档", 1, endedA.size)
    }

    @Test
    fun `AwaitingConfirm 下 pause 无效`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(100L))
        advanceTimeBy(200L); runCurrent()
        assertTrue(engine.phase.value is StepSequenceEngine.Phase.AwaitingConfirm)

        engine.pause()
        assertTrue("该相位本来就在等用户，没有可冻结的",
            engine.phase.value is StepSequenceEngine.Phase.AwaitingConfirm)
    }

    // ---------------- updateSteps：编辑序列时同步引擎 ----------------

    @Test
    fun `updateSteps 插入新阶段后当前步下标随之改变`() = runTest {
        val engine = newEngine()
        val original = steps(1_000L, 1_000L)      // id 1,2
        engine.start(seq(), original)
        advanceTimeBy(400L); runCurrent()
        assertEquals(0, (engine.phase.value as StepSequenceEngine.Phase.Running).stepIndex)

        // 在第一步前面插一个 id=99 的「1.5 阶段」
        val inserted = listOf(
            Step(id = 99L, sequenceId = 1L, durationMillis = 500L, label = "1.5", order = 0),
            original[0].copy(order = 1),
            original[1].copy(order = 2),
        )
        assertTrue(engine.updateSteps(inserted))

        val phase = engine.phase.value as StepSequenceEngine.Phase.Running
        assertEquals("当前步原来是下标 0，插到前面后应变 1", 1, phase.stepIndex)
        assertEquals("阶段总数应变成 3", 3, inserted.size)
    }

    @Test
    fun `updateSteps 不重置倒计时进度`() = runTest {
        val engine = newEngine()
        val original = steps(1_000L, 1_000L)
        engine.start(seq(), original)
        advanceTimeBy(400L); runCurrent()
        val before = (engine.phase.value as StepSequenceEngine.Phase.Running).remainingMillis

        engine.updateSteps(
            listOf(
                Step(id = 99L, sequenceId = 1L, durationMillis = 500L, label = "1.5", order = 0),
                original[0], original[1],
            ),
        )
        assertEquals("加阶段不能让当前倒计时重来",
            before, (engine.phase.value as StepSequenceEngine.Phase.Running).remainingMillis)
    }

    @Test
    fun `updateSteps 当前步被删掉时不崩`() = runTest {
        val engine = newEngine()
        val original = steps(1_000L, 1_000L)
        engine.start(seq(), original)
        advanceTimeBy(400L); runCurrent()

        // 把正在跑的 id=1 删掉，只留 id=2
        assertTrue(engine.updateSteps(listOf(original[1])))
        val phase = engine.phase.value
        assertTrue("必须仍处于 Running，不能崩或卡住",
            phase is StepSequenceEngine.Phase.Running)
        assertEquals(0, (phase as StepSequenceEngine.Phase.Running).stepIndex)
    }

    @Test
    fun `updateSteps 在 Idle 时不生效`() = runTest {
        val engine = newEngine()
        assertFalse("没有活动序列就不该应用", engine.updateSteps(steps(1_000L)))
        assertEquals(StepSequenceEngine.Phase.Idle, engine.phase.value)
    }

    @Test
    fun `updateSteps 空列表不生效`() = runTest {
        val engine = newEngine()
        engine.start(seq(), steps(1_000L))
        advanceTimeBy(100L); runCurrent()
        assertFalse(engine.updateSteps(emptyList()))
        assertTrue(engine.phase.value is StepSequenceEngine.Phase.Running)
    }

    // ---------------- 暂停 / 继续事件（本轮新增）----------------

    /** 建一个带暂停回调的引擎。 */
    private fun TestScope.engineWithPauseEvents(
        onPaused: suspend (SequencePause) -> Unit = {},
        onResumed: suspend (SequencePause) -> Unit = {},
    ) = StepSequenceEngine(
        scope = backgroundScope,
        onStepFinished = {},
        onSequenceEnded = { _, _ -> },
        onPaused = onPaused,
        onResumed = onResumed,
    )

    @Test
    fun `pause 发出 onPaused 且载荷含阶段与已跑时长`() = runTest {
        val events = mutableListOf<SequencePause>()
        val engine = engineWithPauseEvents(onPaused = { events += it })
        engine.start(seq(name = "写周报"), steps(1_000L, 2_000L))
        advanceTimeBy(300L); runCurrent()

        engine.pause()
        runCurrent()

        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("写周报", e.sequenceName)
        assertEquals(0, e.stepIndex)
        assertEquals("第1步", e.step.label)
        // 已跑时长：至少 300ms（虚拟时钟推进了 300）
        assertEquals(300L, e.stepElapsedMillis)
    }

    @Test
    fun `resume 发出 onResumed`() = runTest {
        val paused = mutableListOf<SequencePause>()
        val resumed = mutableListOf<SequencePause>()
        val engine = engineWithPauseEvents(
            onPaused = { paused += it },
            onResumed = { resumed += it },
        )
        engine.start(seq(), steps(1_000L))
        advanceTimeBy(200L); runCurrent()
        engine.pause(); runCurrent()
        engine.resume(); runCurrent()

        assertEquals(1, paused.size)
        assertEquals(1, resumed.size)
        assertEquals("继续事件的阶段应与暂停一致", paused[0].stepIndex, resumed[0].stepIndex)
    }

    @Test
    fun `已暂停时再 pause 不重复发事件`() = runTest {
        val events = mutableListOf<SequencePause>()
        val engine = engineWithPauseEvents(onPaused = { events += it })
        engine.start(seq(), steps(1_000L))
        advanceTimeBy(100L); runCurrent()

        engine.pause(); runCurrent()
        engine.pause(); runCurrent()

        assertEquals("重复暂停只应发一次", 1, events.size)
    }

    @Test
    fun `Idle 状态 pause 不发事件`() = runTest {
        val events = mutableListOf<SequencePause>()
        val engine = engineWithPauseEvents(onPaused = { events += it })

        engine.pause(); runCurrent()

        assertEquals(0, events.size)
    }

    @Test
    fun `stop 不发暂停事件`() = runTest {
        val events = mutableListOf<SequencePause>()
        val engine = engineWithPauseEvents(onPaused = { events += it })
        engine.start(seq(), steps(1_000L))
        advanceTimeBy(100L); runCurrent()

        engine.stop(); runCurrent()

        assertEquals("stop 不是暂停，不该发暂停事件", 0, events.size)
    }
}
