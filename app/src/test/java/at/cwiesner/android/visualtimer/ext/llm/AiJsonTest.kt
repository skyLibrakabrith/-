package at.cwiesner.android.visualtimer.ext.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AiJson] 的解析测试。
 *
 * 这些用例不是凭空想的 —— 每一条都对应真实模型（本地 qwen3.5:4b）实际吐出来的
 * 某种形状，或者是我们必须兜住的坏输入。AI 功能最容易崩的地方就是解析，
 * 所以这里覆盖得比较密。
 */
class AiJsonTest {

    // ---------------- extractJsonObject ----------------

    @Test
    fun `裸 JSON 原样取出`() {
        val raw = """{"steps":[{"title":"a","minutes":5}]}"""
        assertEquals(raw, AiJson.extractJsonObject(raw))
    }

    /** 模型很爱包 markdown 围栏，即使要求了 format=json。 */
    @Test
    fun `剥掉 json 代码围栏`() {
        val raw = "```json\n{\"steps\":[]}\n```"
        assertEquals("""{"steps":[]}""", AiJson.extractJsonObject(raw))
    }

    @Test
    fun `剥掉无语言标记的代码围栏`() {
        val raw = "```\n{\"a\":1}\n```"
        assertEquals("""{"a":1}""", AiJson.extractJsonObject(raw))
    }

    /** 前后带解释文字：取第一个 { 到最后一个 }。 */
    @Test
    fun `忽略前后的解释文字`() {
        val raw = "好的，结果如下：\n{\"steps\":[]}\n希望有帮助！"
        assertEquals("""{"steps":[]}""", AiJson.extractJsonObject(raw))
    }

    @Test
    fun `完全没有 JSON 时返回 null`() {
        assertNull(AiJson.extractJsonObject("抱歉，我无法完成这个请求。"))
    }

    // ---------------- parseSteps ----------------

    @Test
    fun `解析标准 steps`() {
        val raw = """{"steps":[{"title":"确定主题","minutes":10},{"title":"收集资料","minutes":20}]}"""
        val steps = AiJson.parseSteps(raw)
        assertNotNull(steps)
        assertEquals(2, steps!!.size)
        assertEquals("确定主题", steps[0].title)
        assertEquals(10, steps[0].minutes)
    }

    /** 4B 模型经常自己换字段名，必须容忍。 */
    @Test
    fun `容忍 title 的别名`() {
        val raw = """{"steps":[{"task":"写标题"},{"label":"列提纲"},{"text":"写正文"}]}"""
        val steps = AiJson.parseSteps(raw)
        assertEquals(3, steps!!.size)
        assertEquals("写标题", steps[0].title)
        assertEquals("列提纲", steps[1].title)
        assertEquals("写正文", steps[2].title)
    }

    @Test
    fun `容忍 minutes 的别名与字符串形式`() {
        val raw = """{"steps":[
            {"title":"a","min":10},
            {"title":"b","duration":20},
            {"title":"c","minutes":"30"},
            {"title":"d","estimate":"约 40 分钟"}
        ]}"""
        val steps = AiJson.parseSteps(raw)!!
        assertEquals(10, steps[0].minutes)
        assertEquals(20, steps[1].minutes)
        assertEquals(30, steps[2].minutes)
        assertEquals("应从字符串里抠出数字", 40, steps[3].minutes)
    }

    @Test
    fun `缺 minutes 时给默认 15 分钟而不是丢弃该步`() {
        val raw = """{"steps":[{"title":"只有标题"}]}"""
        val steps = AiJson.parseSteps(raw)!!
        assertEquals(1, steps.size)
        assertEquals(15, steps[0].minutes)
    }

    /** 离谱时长要夹到 5–180。 */
    @Test
    fun `时长越界会被夹取`() {
        val raw = """{"steps":[{"title":"a","minutes":0},{"title":"b","minutes":9999},{"title":"c","minutes":-30}]}"""
        val steps = AiJson.parseSteps(raw)!!
        assertEquals(5, steps[0].minutes)
        assertEquals(180, steps[1].minutes)
        assertEquals(5, steps[2].minutes)
    }

    @Test
    fun `空标题的条目被丢掉`() {
        val raw = """{"steps":[{"title":"","minutes":10},{"title":"  ","minutes":10},{"title":"有用","minutes":10}]}"""
        val steps = AiJson.parseSteps(raw)!!
        assertEquals(1, steps.size)
        assertEquals("有用", steps[0].title)
    }

    /** 模型换个 key 名（比如直接给数组）也要能救回来。 */
    @Test
    fun `steps 字段名不对时退化为取第一个数组`() {
        val raw = """{"todo":[{"title":"a","minutes":10}]}"""
        val steps = AiJson.parseSteps(raw)
        assertNotNull(steps)
        assertEquals("a", steps!![0].title)
    }

    /** 解析不出任何步骤必须返回 null（调用方要转成"失败"），不能返回空列表。 */
    @Test
    fun `全空返回 null`() {
        assertNull(AiJson.parseSteps("""{"steps":[]}"""))
        assertNull(AiJson.parseSteps("""{"steps":[{"title":""}]}"""))
        assertNull(AiJson.parseSteps("不是 JSON"))
    }

    // ---------------- parseGroups ----------------

    @Test
    fun `解析标准 groups`() {
        val raw = """{"groups":[
            {"name":"学业","items":[{"title":"复习高数","minutes":30},{"title":"背单词","minutes":20}]},
            {"name":"生活","items":[{"title":"带饭","minutes":10}]}
        ]}"""
        val g = AiJson.parseGroups(raw)!!
        assertEquals(2, g.size)
        assertEquals("学业", g[0].name)
        assertEquals(2, g[0].items.size)
        assertEquals("复习高数", g[0].items[0].title)
        assertEquals("生活", g[1].name)
    }

    @Test
    fun `组名为空的组被丢弃`() {
        val raw = """{"groups":[{"name":"","items":[{"title":"a"}]},{"name":"有效","items":[{"title":"b"}]}]}"""
        val g = AiJson.parseGroups(raw)!!
        assertEquals(1, g.size)
        assertEquals("有效", g[0].name)
    }

    @Test
    fun `没有条目的组被丢弃`() {
        val raw = """{"groups":[{"name":"空组","items":[]},{"name":"有内容","items":[{"title":"b"}]}]}"""
        val g = AiJson.parseGroups(raw)!!
        assertEquals(1, g.size)
        assertEquals("有内容", g[0].name)
    }

    @Test
    fun `items 字段名不对时也尝试 tasks 和 steps`() {
        val raw = """{"groups":[{"name":"组","tasks":[{"title":"a","minutes":5}]}]}"""
        val g = AiJson.parseGroups(raw)!!
        assertEquals(1, g[0].items.size)
    }

    @Test
    fun `分组解析失败返回 null`() {
        assertNull(AiJson.parseGroups("""{"groups":[]}"""))
        assertNull(AiJson.parseGroups("完全不是 JSON"))
    }

    // ---------------- parseMinutes ----------------

    @Test
    fun `解析单个估时`() {
        assertEquals(30, AiJson.parseMinutes("""{"minutes":30}"""))
    }

    @Test
    fun `估时容忍别名`() {
        assertEquals(45, AiJson.parseMinutes("""{"estimate":45}"""))
    }

    /** 有些模型只回一个数字。 */
    @Test
    fun `估时容忍纯数字`() {
        assertEquals(25, AiJson.parseMinutes("25"))
    }

    @Test
    fun `估时越界被夹取`() {
        assertEquals(180, AiJson.parseMinutes("""{"minutes":9999}"""))
        assertEquals(5, AiJson.parseMinutes("""{"minutes":1}"""))
    }

    @Test
    fun `估时解析失败返回 null`() {
        assertNull(AiJson.parseMinutes("我不知道"))
    }
}
