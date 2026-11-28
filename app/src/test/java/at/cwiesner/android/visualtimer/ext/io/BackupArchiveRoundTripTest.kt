package at.cwiesner.android.visualtimer.ext.io

import at.cwiesner.android.visualtimer.ext.data.TimerSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档记录在备份里的往返测试。
 *
 * 背景：v11 给 `timer_sessions` 加了 `ongoing`，并新增了 `timer_pauses` 表。
 * 如果导出/导入不带这两样，用户「导出备份 → 换机 → 导入」之后：
 * - 进行中的记录会伪装成已完成
 * - 圆盘上的暂停空隙全部消失
 *
 * 这类漏字段编译不会报错，只能靠测试把往返钉死。
 */
class BackupArchiveRoundTripTest {

    private fun session(ongoing: Boolean = false) = TimerSession(
        id = 42L,
        label = "写报告",
        startedAt = 1_700_000_000_000L,
        endedAt = 1_700_000_600_000L,
        plannedMillis = 1_500_000L,
        kind = "Countdown",
        completed = false,
        ongoing = ongoing,
    )

    @Test
    fun `ongoing 会写进备份 JSON`() {
        val dto = session(ongoing = true).toDto()
        val json = BackupJson.encodeToString(TimerSessionDto.serializer(), dto)
        assertTrue("备份里应出现 ongoing", json.contains("\"ongoing\""))
        assertTrue(json.contains("true"))
    }

    @Test
    fun `用 DTO 直接构造也能编译`() {
        // 仅确保数据类字段齐全（防止以后加字段忘了同步）
        val dto = TimerSessionDto(
            label = "x",
            startedAt = 1L,
            endedAt = 2L,
            plannedMillis = 1L,
            kind = "Countdown",
            completed = true,
            ongoing = false,
            pauses = listOf(PauseRangeDto(10L, 20L)),
        )
        assertEquals(1, dto.pauses.size)
    }

    @Test
    fun `暂停区间会写进备份 JSON`() {
        val dto = session().toDto(
            pauses = listOf(PauseRangeDto(1_700_000_100_000L, 1_700_000_200_000L)),
        )
        val json = BackupJson.encodeToString(TimerSessionDto.serializer(), dto)
        assertTrue(json.contains("\"pauses\""))
        assertTrue(json.contains("1700000100000"))
    }

    @Test
    fun `旧备份没有 ongoing 键也能读出来`() {
        // 模拟 v3 备份：没有任何新字段
        val legacy = """
            {
              "label": "老记录",
              "startedAt": 100,
              "endedAt": 200,
              "plannedMillis": 100,
              "kind": "Countdown",
              "completed": true
            }
        """.trimIndent()
        val dto = BackupJson.decodeFromString(TimerSessionDto.serializer(), legacy)
        assertFalse("旧备份默认不是进行中", dto.ongoing)
        assertEquals("旧备份没有暂停区间", 0, dto.pauses.size)
    }

    @Test
    fun `导入时进行中的记录落成已完成`() {
        // 进行中的记录跨设备导入没有意义：它对应的运行时早就不在了，
        // 留着只会永远挂在归档页上。导入时一律落成已完成。
        val entity = session(ongoing = true).toDto().toEntity()
        assertFalse("导入后不应还是进行中", entity.ongoing)
        assertTrue("应转为已完成", entity.completed)
    }

    @Test
    fun `导入已完成的记录保持已完成`() {
        val s = session(ongoing = false).copy(completed = true)
        val entity = s.toDto().toEntity()
        assertFalse(entity.ongoing)
        assertTrue(entity.completed)
    }

    @Test
    fun `未完成且非 ongoing 的记录原样保留 completed`() {
        // 被用户提前结束的记录：completed = false 且有实际时长
        val s = session(ongoing = false).copy(completed = false)
        val entity = s.toDto().toEntity()
        assertFalse("提前结束的记录导入后仍是 completed=false", entity.completed)
    }

    @Test
    fun `完整往返不丢字段`() {
        val original = session(ongoing = true)
        val dto = original.toDto(pauses = listOf(PauseRangeDto(5L, 7L)))
        val json = BackupJson.encodeToString(TimerSessionDto.serializer(), dto)
        val back = BackupJson.decodeFromString(TimerSessionDto.serializer(), json)

        assertEquals(original.label, back.label)
        assertEquals(original.startedAt, back.startedAt)
        assertEquals(original.endedAt, back.endedAt)
        assertEquals(original.plannedMillis, back.plannedMillis)
        assertEquals(original.kind, back.kind)
        assertTrue(back.ongoing)
        assertEquals(1, back.pauses.size)
        assertEquals(5L, back.pauses[0].fromMillis)
        assertEquals(7L, back.pauses[0].toMillis)
    }
}
