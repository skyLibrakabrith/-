package at.cwiesner.android.visualtimer.ext.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 冷启动时「离线期间过去了多久」的补算（[computeRestoredProgress]）。
 *
 * 这是「退出重进不丢进度」的核心算法，也是唯一需要仔细推敲边界的部分。
 *
 * 规则（来自用户需求）：
 * - **暂停态**：一秒不差地恢复 —— 离线的那段时间根本没在计时
 * - **运行态 + 倒计时**：按墙钟扣掉离线时间；扣光了转入超时
 * - **运行态 + 正计时**：累计时长继续往上加（正计时没有终点）
 */
class ComputeRestoredProgressTest {

    private val minute = 60_000L

    @Test
    fun `暂停的倒计时 离线多久都不扣`() {
        // 用户按了暂停然后杀进程，第二天回来：剩余必须还是 5 分钟
        val r = computeRestoredProgress(
            remainingMillis = 5 * minute,
            overdueMillis = 0,
            totalMillis = 10 * minute,
            stopwatch = false,
            running = false,
            offlineMillis = 24 * 60 * minute,
        )
        assertEquals(5 * minute, r.remainingMillis)
        assertEquals(0L, r.overdueMillis)
    }

    @Test
    fun `运行的倒计时 扣掉离线时间`() {
        val r = computeRestoredProgress(
            remainingMillis = 5 * minute,
            overdueMillis = 0,
            totalMillis = 10 * minute,
            stopwatch = false,
            running = true,
            offlineMillis = 2 * minute,
        )
        assertEquals(3 * minute, r.remainingMillis)
        assertEquals(0L, r.overdueMillis)
    }

    @Test
    fun `运行的倒计时 离线期间到点 剩余归零转入超时`() {
        val r = computeRestoredProgress(
            remainingMillis = 5 * minute,
            overdueMillis = 0,
            totalMillis = 10 * minute,
            stopwatch = false,
            running = true,
            offlineMillis = 8 * minute,
        )
        assertEquals(0L, r.remainingMillis)
        // 5 分钟用完后还多跑了 3 分钟
        assertEquals(3 * minute, r.overdueMillis)
    }

    @Test
    fun `已经在超时的倒计时 超时量累加`() {
        val r = computeRestoredProgress(
            remainingMillis = 0L,
            overdueMillis = 2 * minute,
            totalMillis = 10 * minute,
            stopwatch = false,
            running = true,
            offlineMillis = 90_000L,
        )
        assertEquals(0L, r.remainingMillis)
        assertEquals(2 * minute + 90_000L, r.overdueMillis)
    }

    @Test
    fun `暂停的正计时 累计不变`() {
        val r = computeRestoredProgress(
            remainingMillis = 7 * minute,
            overdueMillis = 0,
            totalMillis = 0L,
            stopwatch = true,
            running = false,
            offlineMillis = 60 * minute,
        )
        assertEquals(7 * minute, r.remainingMillis)
    }

    @Test
    fun `运行的正计时 累计继续增加`() {
        val r = computeRestoredProgress(
            remainingMillis = 7 * minute,
            overdueMillis = 0,
            totalMillis = 0L,
            stopwatch = true,
            running = true,
            offlineMillis = 3 * minute,
        )
        assertEquals(10 * minute, r.remainingMillis)
        // 正计时永远不会"超时"
        assertEquals(0L, r.overdueMillis)
    }

    @Test
    fun `离线时长为零或负数时原样返回`() {
        val r = computeRestoredProgress(
            remainingMillis = 5 * minute,
            overdueMillis = 0,
            totalMillis = 10 * minute,
            stopwatch = false,
            running = true,
            offlineMillis = 0L,
        )
        assertEquals(5 * minute, r.remainingMillis)
    }

    @Test
    fun `离线时长超过总时长 超时量不会算错`() {
        // 10 分钟倒计时，跑了 25 分钟：剩余 0，超时 15 分钟
        val r = computeRestoredProgress(
            remainingMillis = 10 * minute,
            overdueMillis = 0,
            totalMillis = 10 * minute,
            stopwatch = false,
            running = true,
            offlineMillis = 25 * minute,
        )
        assertEquals(0L, r.remainingMillis)
        assertEquals(15 * minute, r.overdueMillis)
    }
}
