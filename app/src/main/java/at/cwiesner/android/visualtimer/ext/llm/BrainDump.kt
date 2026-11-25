package at.cwiesner.android.visualtimer.ext.llm

/**
 * Compiler 的"输入保底"逻辑。
 *
 * ## 为什么需要这个文件
 *
 * 实测本地 qwen3.5:4b：给 6 条碎片，它经常只输出 5 条 ——
 * 会把「下周要交的作业」这种**上级概念**并进「复习高数」「背单词」里，
 * 认为已经用更具体的条目覆盖了它。对人类这叫归纳，但在这个场景里
 * 它就是**丢了用户写的东西**，而且用户看不出来。
 *
 * 提示词里写死「条目总数必须等于输入条数」也拦不住（实测仍会丢）。
 * 所以把这件事交给程序做：拆完校验条数，不够就把缺失的原文补回来。
 *
 * 原则：**宁可让用户看到一条没被打磨的原文，也不能让他以为已经处理完了。**
 */
object BrainDump {

    /**
     * 把用户输入切成一条条"念头"。
     *
     * 按行切，并去掉这些常见的前缀标记：
     * - 列表符：`- ` / `* ` / `· ` / `1. ` / `1、` / `① `
     * - 复选框：`[ ]` / `[]` / `[x]`
     *
     * 不做分词、不做合并 —— 用户怎么分行就怎么算一条。
     * 一行里写两件事是他自己的问题，模型会处理；但绝不能替他删。
     */
    fun splitLines(raw: String): List<String> =
        raw.lineSequence()
            .map { stripBullet(it.trim()) }
            .filter { it.isNotEmpty() }
            .toList()

    private fun stripBullet(s: String): String {
        var t = s
        // 复选框
        t = t.replace(Regex("""^\[[ xX]?]\s*"""), "")
        // 数字 / 中文序号
        t = t.replace(Regex("""^\d+\s*[.、)）]\s*"""), "")
        t = t.replace(Regex("""^[①-⑳]\s*"""), "")
        // 项目符号
        t = t.replace(Regex("""^[-*·•]\s+"""), "")
        return t.trim()
    }

    /**
     * 把模型漏掉的条目补回结果里。
     *
     * 判定"漏掉"用的是**原文包含关系**：模型会改写措辞
     * （「还有英语单词要背」-> 「背诵英语单词」），所以不能用字符串相等。
     * 这里用"原文里的每条，是否能在输出标题里找到足够多的共同字"来近似判断。
     *
     * 补回位置：追加到**条目最少的那个组**，避免全堆到最后一行。
     * 补回的条目 difficulty 用 3（中性值）、context 留空 ——
     * 我们不知道它的真实属性，不该编。
     *
     * @return 修正后的分组；没有缺失时原样返回
     */
    fun backfillMissing(groups: List<TodoGroup>, rawInput: String): List<TodoGroup> {
        val inputs = splitLines(rawInput)
        if (inputs.isEmpty() || groups.isEmpty()) return groups

        val existingTitles = groups.flatMap { g -> g.items.map { it.title } }
        val missing = inputs.filter { input ->
            existingTitles.none { title -> covers(title, input) }
        }.map { it.trim() }
            // 太短的原文（如"其他"）不补，否则会长出一条无意义的条目
            .filter { it.length >= 2 }

        if (missing.isEmpty()) return groups

        val extra = missing.map { TodoStep(title = it.take(20), minutes = 0, difficulty = 3) }

        // 平均分到各组末尾
        val result = groups.toMutableList()
        extra.forEachIndexed { i, step ->
            val idx = i % result.size
            result[idx] = result[idx].copy(items = result[idx].items + step)
        }
        return result
    }

    /**
     * 判断 [title] 是否"覆盖"了输入 [input]。
     *
     * 用字符集合的重合度：输入里超过一半的字出现在标题里就算覆盖。
     * 中文按字算比按词算稳（没有分词器），而且这类改写通常保留了核心名词。
     */
    internal fun covers(title: String, input: String): Boolean {
        val ti = normalize(title)
        val ii = normalize(input)
        if (ti.isEmpty() || ii.isEmpty()) return false
        // 直接包含最常见
        if (ti.contains(ii) || ii.contains(ti)) return true

        val titleChars = ti.toSet()
        val hit = ii.count { it in titleChars }
        return hit * 2 >= ii.length
    }

    /** 去掉标点、空白，只留文字与数字，避免标点影响重合度。 */
    private fun normalize(s: String): String =
        s.filter { it.isLetterOrDigit() }
}
