package at.cwiesner.android.visualtimer.ext.llm

/**
 * 所有提示词集中在这里。
 *
 * 为什么单独一个文件：提示词是**要反复调**的东西，散在各个功能类里
 * 以后根本没法对照着改。而且本地 4B 模型和云端大模型的提示词效果差别很大，
 * 放一起才看得出该为谁让步。
 *
 * 写提示词的三条经验（都是实测出来的）：
 * 1. **必须给 JSON 形状的实例**，只说"输出 JSON"不够，4B 模型会自己发明字段名。
 * 2. **明确写死数量范围**，否则会给 12 步（太碎）或 2 步（没用）。
 * 3. **不要在提示词里要求解释**。结构化输出时任何多余文字都是解析失败的来源。
 */
object LlmPrompts {

    /** Magic ToDo + Estimator：一段话 → 有序步骤 + 每步分钟数。 */
    val SPLIT_SYSTEM = """
        你是任务拆解助手。把用户给的一件事拆成可以直接开始做的小步骤。

        规则：
        - 步骤必须按执行顺序排列，3 到 7 步。
        - 每步是一个动词开头的短句，不超过 20 个字。
        - 每步给出预计耗时 minutes，取 5 的倍数，范围 5 到 90。
        - 不要写"准备开始""总结"这类空步骤。
        - 只输出 JSON，不要任何解释文字。

        输出格式：
        {"steps":[{"title":"打开文档模板","minutes":5}]}
    """.trimIndent()

    /** Compiler：一堆碎片念头 → 归类成若干组。
     *
     * 「每一条都必须出现」这句是实测加上的：不写的话 4B 模型会把 6 条想法
     * 硬塞进一个组，还会丢掉生活类的条目（它倾向于只回答"最重要"的部分）。
     */
    val COMPILE_SYSTEM = """
        你是整理助手。用户会给你一堆零散的想法，把它们归类成若干组可执行的条目。

        规则：
        - 每一条输入都必须出现在某个组里，不能丢弃、不能合并成一条。
        - 按主题归组，通常 2 到 3 组，最多 4 组。
        - 组名是 2 到 8 个字的名词短语，例如「学业」「生活琐事」。
        - 每条是一个动词开头的短句，不超过 20 个字，并给出 minutes（5 的倍数，5 到 90）。
        - 只输出 JSON，不要任何解释文字。

        输出格式：
        {"groups":[{"name":"学业","items":[{"title":"复习高数第三章","minutes":30}]}]}
    """.trimIndent()

    /** Estimator：单个活动 → 分钟数。 */
    val ESTIMATE_SYSTEM = """
        你是时间估算助手。给用户给的一件事一个现实的总耗时。

        规则：
        - 要考虑实际会发生的摩擦（找资料、启动、中断），别给理想值。
        - 取 5 的倍数，范围 5 到 180。
        - 只输出 JSON，不要任何解释文字。

        输出格式：
        {"minutes":30}
    """.trimIndent()

    /** 拆解的用户消息。把目标塞进去，顺带把"别多问"写清楚。 */
    fun splitUser(target: String): String =
        "把下面这件事拆解成可以立刻开始的步骤：\n" + target.trim()

    /** 整理的。
     *
     * 输入可能是多行、中英混排、带列表符号 —— 原样传，别做"聪明"的清洗，
     * 那样会丢掉用户原本的分组意图。
     *
     * 顺带把条目数写进去：模型看到"共 N 条"时更容易保证不丢，实测有效。
     */
    fun compileUser(brainDump: String): String {
        // 注意用 count() 不是 size：lineSequence() 返回 Sequence，Sequence 没有 size 属性
        // （这里犯过一次，CI 报 "Unresolved reference 'size'"）。
        val items = brainDump.lineSequence()
            .map { it.trim() }
            .count { it.isNotEmpty() }
        val hint = if (items > 0) "（共 $items 条，请全部归入组内）" else ""
        return "把下面这些零散想法归类整理$hint：\n" + brainDump.trim()
    }

    fun estimateUser(activity: String): String =
        "估算下面这件事需要多少分钟：\n" + activity.trim()
}
