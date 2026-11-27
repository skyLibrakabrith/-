package at.cwiesner.android.visualtimer.ext.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保存序列时「哪个 id 才可信」（[resolveSavedSequenceId]）。
 *
 * 对应一个真机上确认过的数据损坏：
 *
 * ```
 * sequences: id=1, name=新序列
 * steps:     id=1..3, sequenceId=-1      ← 全是孤儿
 * ```
 *
 * 根因是 Room 的 `@Upsert` 在**更新已有行**的分支返回 **-1**（既定行为，不是错误），
 * 而保存代码把返回值直接当成新 id 去写 `steps.sequenceId`。
 *
 * 后果是静默的：界面上只表现为「序列里没有阶段」「阶段不见了」，
 * 库里却留下一批谁都找不到的行，还会被导出进备份漂到别的设备。
 */
class ResolveSavedSequenceIdTest {

    @Test
    fun `新建时用 upsert 返回的自增 id`() {
        // id = 0 表示还没落库，upsert 走插入分支，返回值可信
        assertEquals(42L, resolveSavedSequenceId(entityId = 0L, returnedId = 42L))
    }

    @Test
    fun `更新已有行时用实体自己的 id 不要用 -1`() {
        // @Upsert 更新分支返回 -1。这是最重要的一条：
        // 直接用它会让所有步骤挂到 sequenceId = -1 上。
        assertEquals(7L, resolveSavedSequenceId(entityId = 7L, returnedId = -1L))
    }

    @Test
    fun `更新时即使 upsert 返回了正常 id 也用实体自己的`() {
        // 防御性：不同 Room 版本的返回值语义可能不同，
        // 已有实体就永远用它的 id，行为不随库版本漂移。
        assertEquals(7L, resolveSavedSequenceId(entityId = 7L, returnedId = 99L))
    }

    @Test
    fun `新建时 upsert 返回 -1 也会被传出去`() {
        // 这种组合在真实路径上不该出现（插入失败会抛异常）。
        // 这里只固化"不额外编造 id"的语义：builder 用返回什么就返回什么，
        // 由调用方决定怎么处理，不在这个纯函数里静默改成 0 或别的值。
        assertEquals(-1L, resolveSavedSequenceId(entityId = 0L, returnedId = -1L))
    }
}
