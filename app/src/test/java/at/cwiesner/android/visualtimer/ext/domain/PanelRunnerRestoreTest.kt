package at.cwiesner.android.visualtimer.ext.domain

import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动恢复（[PanelRunner.restoreInactive]）。
 *
 * 这一组测试对应一个真实用户可见的 bug：
 * **「正计时退出重进以后变成了倒计时」**。
 *
 * 根因是 `_runtimes` 是纯内存的，进程死了就空；而恢复流程只处理 DDL，
 * 普通倒计时和正计时都没有运行时。卡片于是按 `durationMillis` 回退成
 * "满格倒计时" —— 正计时的 durationMillis 是 0，就显示成 0:00 的倒计时。
 */
class PanelRunnerRestoreTest {

    // PanelRunner 只在 startTicking() 里用 scope 启 tick 协程；
    // 这些测试都不启动 tick，scope 只是构造参数，用完统一取消。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun runner() = PanelRunner(scope)

    private fun timer(
        id: Long,
        kind: TimerKind,
        duration: Long = 0L,
        label: String? = null,
        deadline: Long? = null,
    ) = TimerItem(
        id = id,
        label = label,
        durationMillis = duration,
        alertProfileId = null,
        position = 0,
        kind = kind,
        deadlineEpochMillis = deadline,
        locked = kind == TimerKind.Deadline,
    )

    @Test
    fun `正计时恢复后仍然是正计时 不会变成倒计时`() {
        val runner = runner()
        runner.restoreInactive(listOf(timer(1, TimerKind.Stopwatch, duration = 0L)))
        val rt = runner.get(1)
        assertNotNull("正计时必须被恢复出运行时，否则卡片会回退成倒计时", rt)
        assertTrue("恢复出来的必须是 stopwatch 语义", rt!!.stopwatch)
        assertEquals(0L, rt.remainingMillis)
        assertEquals(0L, rt.totalMillis)
    }

    @Test
    fun `普通倒计时恢复后是满格且未运行`() {
        val runner = runner()
        runner.restoreInactive(listOf(timer(2, TimerKind.Countdown, duration = 300_000L)))
        val rt = runner.get(2)
        assertNotNull(rt)
        assertFalse(rt!!.stopwatch)
        assertEquals(300_000L, rt.remainingMillis)
        assertEquals(300_000L, rt.totalMillis)
        // 冷启动不能让计时器自己跑起来 —— 用户根本没按过开始
        assertFalse("恢复出来的应是待命状态，不是正在跑", rt.running)
    }

    @Test
    fun `DDL 不在这里恢复 交给 restoreDeadlines`() {
        val runner = runner()
        val now = System.currentTimeMillis()
        runner.restoreInactive(
            listOf(timer(3, TimerKind.Deadline, label = "ddl", deadline = now + 60_000L)),
        )
        // DDL 的剩余量由绝对截止时刻决定，必须走 restoreDeadlines 那条更精细的路径
        // （还要处理提醒行、过期判定）。这里插手会造成两处逻辑打架。
        assertNull("DDL 不该被 restoreInactive 处理", runner.get(3))
    }

    @Test
    fun `已有运行时的计时器不会被覆盖`() {
        val runner = runner()
        val t = timer(4, TimerKind.Countdown, duration = 120_000L, label = "工作中")
        runner.start(t)
        // 跑掉 30 秒
        runner.tick(30_000L, System.currentTimeMillis())
        val before = runner.get(4)!!

        runner.restoreInactive(listOf(t))

        val after = runner.get(4)!!
        assertEquals(
            "恢复不能把正在跑的计时器重置回满格",
            before.remainingMillis,
            after.remainingMillis,
        )
        assertTrue("正在跑的状态不能被改掉", after.running)
    }

    @Test
    fun `混合输入时每一类都各归各位`() {
        val runner = runner()
        val now = System.currentTimeMillis()
        runner.restoreInactive(
            listOf(
                timer(10, TimerKind.Countdown, duration = 60_000L),
                timer(11, TimerKind.Stopwatch, duration = 0L),
                timer(12, TimerKind.Deadline, deadline = now + 600_000L),
            ),
        )
        assertNotNull(runner.get(10))
        assertNotNull(runner.get(11))
        assertNull(runner.get(12))
        assertTrue(runner.get(11)!!.stopwatch)
        assertFalse(runner.get(10)!!.stopwatch)
    }

    @Test
    fun `空列表不产生任何运行时`() {
        val runner = runner()
        runner.restoreInactive(emptyList())
        assertEquals(0, runner.snapshot().size)
    }
}
