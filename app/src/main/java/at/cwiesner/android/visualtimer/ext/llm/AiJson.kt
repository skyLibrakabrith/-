package at.cwiesner.android.visualtimer.ext.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把模型输出解析成结构化结果。
 *
 * 这一层是**整个 AI 功能里最容易出错的地方**，所以做了几件防御：
 *
 * 1. **先剥 ```json 围栏**。即使要求了 `format=json`，模型（尤其是云端那些）
 *    偶尔还是会包一层 markdown 代码块。不剥就是 100% 解析失败。
 * 2. **容忍字段别名**。`title` / `label` / `text` / `name` / `task` 都当标题认；
 *    `minutes` / `min` / `duration` / `estimate` 都当时长认。
 *    4B 模型非常喜欢自己换字段名。
 * 3. **时长做区间夹取**，`0` 或 `9999` 这种离谱值会被夹到 5–180。
 * 4. **空结果当失败**，不当成功返回空列表 —— 空列表写进数据库等于"点了按钮什么也没发生"，
 *    用户完全不知道发生了什么。
 */
object AiJson {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 时长合理区间（分钟）。 */
    private const val MIN_MINUTES = 5
    private const val MAX_MINUTES = 180

    /** 拆解/整理时，某步没给时长就用这个值（而不是丢掉这一步）。 */
    private const val DEFAULT_MINUTES = 15

    private val TITLE_KEYS = listOf("title", "label", "text", "name", "task", "step", "content")
    private val MINUTES_KEYS = listOf("minutes", "min", "mins", "duration", "estimate", "time")
    private val DIFFICULTY_KEYS = listOf("difficulty", "hard", "level", "hardness")
    private val CONTEXT_KEYS = listOf("context", "note", "why", "reason", "detail", "description")

    /**
     * 剥掉 markdown 代码围栏和前后噪声，取出第一段像 JSON 对象的文本。
     *
     * 处理这些情况：
     * - ```json\n{...}\n```
     * - 前面有 "好的，结果如下：" 之类的话
     * - 后面有 "希望有帮助"
     */
    fun extractJsonObject(raw: String): String? {
        var s = raw.trim()
        // 去掉 ```lang ... ``` 围栏
        val fence = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
        fence.find(s)?.let { s = it.groupValues[1].trim() }

        val start = s.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = s[start]
        val close = if (open == '{') '}' else ']'

        // 从后往前找最后一个闭合符，能覆盖"后面跟着解释文字"的情况
        val end = s.lastIndexOf(close)
        if (end <= start) return null
        return s.substring(start, end + 1)
    }

    /**
     * 解析拆解结果。
     *
     * @return 步骤列表；无法解析或为空时返回 null（由调用方转成 Failed）
     */
    fun parseSteps(raw: String): List<TodoStep>? {
        val body = extractJsonObject(raw) ?: return null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        // 优先 steps 字段；有些模型会直接给数组或换个名字
        val arr = obj["steps"]?.jsonArray
            ?: obj["items"]?.jsonArray
            ?: obj["tasks"]?.jsonArray
            ?: obj.values.firstOrNull()?.let { v ->
                runCatching { v.jsonArray }.getOrNull()
            }
            ?: return null

        val steps = arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = pickString(o, TITLE_KEYS)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val minutes = pickMinutes(o)
            TodoStep(title = title, minutes = minutes, difficulty = findDifficulty(o))
        }.filter { it.title.isNotEmpty() }

        return steps.ifEmpty { null }
    }

    /** 解析整理结果。 */
    fun parseGroups(raw: String): List<TodoGroup>? {
        val body = extractJsonObject(raw) ?: return null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        val arr = obj["groups"]?.jsonArray
            ?: obj["categories"]?.jsonArray
            ?: obj.values.firstOrNull()?.let { v ->
                runCatching { v.jsonArray }.getOrNull()
            }
            ?: return null

        val groups = arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = pickString(o, listOf("name", "title", "group", "label"))?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val itemsArr = o["items"]?.jsonArray
                ?: o["tasks"]?.jsonArray
                ?: o["steps"]?.jsonArray
                ?: return@mapNotNull null
            val items = itemsArr.mapNotNull { itEl ->
                val io = runCatching { itEl.jsonObject }.getOrNull() ?: return@mapNotNull null
                val title = pickString(io, TITLE_KEYS)?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                // 分组结果里的 minutes 用 findMinutes（找不到就是 0 = 还没估），
                // **不用** pickMinutes 的默认 15：Compiler 第一步本来就不估时，
                // 给个 15 会让界面显示一堆假的"15 分钟"。
                TodoStep(
                    title = title,
                    minutes = findMinutes(io) ?: 0,
                    difficulty = findDifficulty(io),
                    context = findContext(io),
                )
            }
            if (items.isEmpty()) null else TodoGroup(name = name, items = items)
        }

        return groups.ifEmpty { null }
    }

    /** 解析单个估时。
     *
     * 注意这里**不能**在 extractJsonObject 返回 null 时立刻 return null：
     * 模型有时只回一个裸数字（"25"），根本没有花括号。
     * 之前写成 `?: return null` 就导致这类回复被当成解析失败（CI #79 抓到）。
     */
    fun parseMinutes(raw: String): Int? {
        val body = extractJsonObject(raw)
        if (body != null) {
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (obj != null) {
                // 是 JSON 对象就以它为准：用 findMinutes（找不到返回 null）而不是
                // pickMinutes（有默认值 15）。单独估时没有"这条没给时间"这种语义，
                // 拿不到就是失败，不能编一个 15 分钟出来。
                //
                // 也**不能**在这里退回去扫原文的裸数字：对 {"foo":1} 扫原文会抠出 1，
                // 再把 1 夹成 5 分钟，等于凭空编了个结果。
                return findMinutes(obj)
            }
        }
        // 走到这里说明模型压根没给 JSON，那才退回到"只回了一个数字"的兜底
        Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return clampMinutes(it) }
        return null
    }

    /**
     * 解析**一组**估时（Compiler 第三步用）。
     *
     * 期望 `{"minutes":[15,30,45]}`，与输入顺序一一对应。
     *
     * @param expected 期望的条数。返回的数组**必须等长**，否则返回 null ——
     *   长度对不上就说明模型漏了/多给了条目，这时宁可整批失败让用户重试，
     *   也不能硬凑：错位的时长会让整条序列的节奏全错。
     */
    fun parseMinutesList(raw: String, expected: Int): List<Int>? {
        if (expected <= 0) return null
        val body = extractJsonObject(raw) ?: return null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

        // 优先 minutes 字段；兼容模型直接给数组或换个名字的情况
        val arr = obj?.get("minutes")?.jsonArray
            ?: obj?.get("estimates")?.jsonArray
            ?: obj?.values?.firstOrNull { runCatching { it.jsonArray }.isSuccess }?.jsonArray
            ?: runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: return null

        val values = arr.mapNotNull { el ->
            val n = runCatching { el.jsonPrimitive }.getOrNull() ?: return@mapNotNull null
            n.intOrNull?.let { return@mapNotNull clampMinutes(it) }
            Regex("""(\d+)""").find(n.contentOrNull.orEmpty())?.groupValues?.get(1)
                ?.toIntOrNull()?.let { clampMinutes(it) }
        }
        return if (values.size == expected) values else null
    }

    private fun pickString(o: JsonObject, keys: List<String>): String? {
        for (k in keys) {
            val v = o[k] ?: continue
            val s = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    /**
     * 找时长；**找不到返回 null**。
     *
     * 和 [pickMinutes] 的区别很重要：
     * - 拆解/整理里，某条步骤没给时长 → 用默认值 15，别把这条丢掉（[pickMinutes]）
     * - 单独估时里，拿不到时长 → 必须返回 null 让调用方报失败（[findMinutes]）
     * 把两者混成一个函数会让"估时失败"变成"估出 15 分钟"这种假结果。
     */
    private fun findMinutes(o: JsonObject): Int? {
        for (k in MINUTES_KEYS) {
            val v = o[k] ?: continue
            val n = runCatching { v.jsonPrimitive }.getOrNull() ?: continue
            n.intOrNull?.let { return clampMinutes(it) }
            // "30" / "30分钟" / "1.5h" 这类
            val text = n.contentOrNull.orEmpty()
            Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { return clampMinutes(it) }
        }
        return null
    }

    /** 没给时长就用默认值，而不是丢弃这个步骤。 */
    private fun pickMinutes(o: JsonObject): Int = findMinutes(o) ?: DEFAULT_MINUTES

    /**
     * 找困难程度，夹到 1–5；没有或解析不了就返回 null（= 未标注）。
     *
     * 和 minutes 不同，这里**不做默认值填充**：
     * "没标难度" 和 "难度 3" 是两件事，编一个默认值会让界面显示假的难度标签。
     */
    private fun findDifficulty(o: JsonObject): Int? {
        for (k in DIFFICULTY_KEYS) {
            val v = o[k] ?: continue
            val n = runCatching { v.jsonPrimitive }.getOrNull() ?: continue
            n.intOrNull?.let { return it.coerceIn(1, 5) }
            // "3/5"、"hard" 这类：抓出数字；纯文字就用关键词兜底
            val text = n.contentOrNull.orEmpty()
            Regex("""(\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { return it.coerceIn(1, 5) }
            when (text.trim().lowercase()) {
                "easy", "simple", "容易", "简单" -> return 1
                "medium", "normal", "中等" -> return 3
                "hard", "difficult", "困难", "难" -> return 5
            }
        }
        return null
    }

    /**
     * 找背景/理由，空字符串归一成 null。
     *
     * 提示词里允许模型"推不出就留空字符串"，所以这里必须把 "" 也当"没有"，
     * 否则界面会显示一行的空引号。
     */
    private fun findContext(o: JsonObject): String? {
        for (k in CONTEXT_KEYS) {
            val v = o[k] ?: continue
            val s = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()
            if (!s.isNullOrBlank()) return s.trim().take(60)
        }
        return null
    }

    private fun clampMinutes(m: Int): Int = m.coerceIn(MIN_MINUTES, MAX_MINUTES)
}
