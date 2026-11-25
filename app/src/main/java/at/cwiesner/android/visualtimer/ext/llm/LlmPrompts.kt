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

    /**
     * Magic ToDo + Estimator：一段话 → 按时间顺序的步骤（含估时与困难程度）。
     *
     * 借鉴 goblin.tools 的 Spiciness 思路，但在本 App 里叫**颗粒度**：
     * 用户自己说"这件事要拆多细"，颗粒度越高就拆得越细。这对 ADHD 用户很关键 —— 同样一件事，
     * 状态好的时候需要 3 步，状态差的时候需要 12 步。
     *
     * 另加一个 goblin 没有的**难度**维度：它不影响步数，只影响**估时保守程度**
     * 和每步自带的 difficulty。理由是"很难"和"很烦"不是一回事：
     * 难的任务不一定需要拆更碎，但一定需要留更多余量。
     *
     * 实测（本地 qwen3.5:4b，temperature 0.3）：
     *   颗粒度1 难度2「写实验报告」-> 3 步 / 合计 25 分
     *   颗粒度5 难度5「写实验报告」（背景：明天要交但没开始）-> 9 步 / 合计 100 分
     *   颗粒度3 难度4「学线性代数」-> 6 步 / 合计 210 分
     * 说明两个维度都真的起作用了。
     */
    val SPLIT_SYSTEM = """
        你是任务拆解助手。把用户给的一件事按时间顺序拆成可以直接开始做的小步骤。

        用户会给出两个提示：
        - 颗粒度（1-5）：越高表示需要拆得越细。1 = 最粗略（3-4 步），5 = 极细（8-12 步，每步 ≤5 分钟）。
        - 难度（1-5）：用户觉得这件事有多难。越高，每步估时要越保守（含启动摩擦、卡壳、返工）。

        规则：
        - 步骤必须按**执行顺序**排列，也就是先做什么后做什么。
        - 每步是动词开头的短句，不超过 20 个字。
        - 每步给 minutes（5 的倍数）和 difficulty（1-5，该步本身有多难）。
        - 难度高的整体任务，单步的 difficulty 也应该偏高，且 minutes 更宽容。
        - 不要写"准备开始""总结一下"这类空步骤。
        - 只输出 JSON，不要任何解释文字。

        输出格式：
        {"steps":[{"title":"打开模板文件","minutes":5,"difficulty":1}]}
    """.trimIndent()

    /** Compiler：一堆碎片念头 → 归类成若干组。
     *
     * 「每一条都必须出现」这句是实测加上的：不写的话 4B 模型会把 6 条想法
     * 硬塞进一个组，还会丢掉生活类的条目（它倾向于只回答"最重要"的部分）。
     */
    /**
     * Compiler 第一步：一堆碎片念头 → 有条目的组（**只拆，不估时**）。
     *
     * 为什么不在这一步估时：估时要看**最终顺序**。用户接下来会调整顺序、删条目，
     * 顺序一变，时长就不一样了（先做 A 再做 B 和反过来，摩擦完全不同）。
     * 所以这里刻意只要 title + difficulty，时长留给 [ESTIMATE_LIST_SYSTEM] 在排序之后统一估。
     *
     * difficulty 也**不在输入里问用户**：用户给的是一堆混乱的念头，
     * 他此刻多半说不出"这件事有多难"。让模型拆完之后自己判断更准，
     * 而且用户还能在排序界面顺手改。
     */
    val COMPILE_SYSTEM = """
        你是整理助手。用户会给你一堆零散的想法，把它们归类成若干组可执行的条目。

        **最重要：输出里的条目总数必须等于输入的条数。先数一遍再归组。**
        不能丢弃任何一条，也不能把两条合并成一条。

        规则：
        - 按主题归组，通常 2 到 3 组，最多 4 组。
        - 组名是 2 到 8 个字的名词短语，例如「学业」「生活琐事」。
        - 每条是一个动词开头的短句，不超过 20 个字。
        - 每条再给一个 difficulty（1-5）：这件事本身有多难、多需要专注。
          读一堆数字、写半页字大概是 2；开始学一个新东西、处理很陌生的流程大概是 4-5。
        - 每条再给一个 context（背景/理由，不超过 20 个字）：为什么要做它、要做到什么程度。
          根据条目本身合理推断；实在推不出就留空字符串。
        - 组内条目按**建议的执行顺序**排列（先做什么后做什么）。
        - **不要**给时长，时长会在后面单独估。
        - 只输出 JSON，不要任何解释文字。

        输出格式：
        {"groups":[{"name":"学业","items":[{"title":"复习高数第三章","difficulty":3,"context":"下周小测范围"}]}]}
    """.trimIndent()

    /**
     * Compiler 第三步：把**已经排好序**的条目一次性估时。
     *
     * 一次性而不是逐条：模型能看到整条链路，估出的总时长互相协调
     * （比如"先读文献再写提纲"，写提纲那步就不该再算查找资料的时间）。
     *
     * 输出是**纯数字数组**，按输入顺序一一对应 —— 比让模型回显标题可靠得多
     * （它回显标题时经常改字、漏字，导致对不上行）。
     */
    val ESTIMATE_LIST_SYSTEM = """
        你是时间估算助手。用户会给你一个**按顺序排列**的任务清单，请为每一条估一个现实耗时。

        规则：
        - 按给定顺序估，考虑前一步已经完成了部分准备，不要重复计算。
        - 要考虑实际摩擦（启动、找资料、被打断、卡壳），别给理想值。
        - 每条取 5 的倍数，范围 5 到 180。
        - 方括号里是该条目的**背景/要求**，它决定了做得多深：
          「随便看看」和「要能交上去」估出来差好几倍，请据此调整。
        - 输出一个与输入**等长**的分钟数数组，顺序与输入一致。
        - 只输出 JSON，不要任何解释文字。

        输出格式：
        {"minutes":[15,30,45]}
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

    /**
     * 拆解的用户消息。
     *
     * 把颗粒度/难度写成明确的一行，而不是嵌在自然语言里 —— 实测这样模型
     * 更容易"照做"（写成「请把这件事按中等颗粒度拆分」时它经常忽略）。
     *
     * 背景为空时**整行不出现**，不要留一个空的"背景："让模型胡编。
     */
    fun splitUser(
        target: String,
        granularity: Int,
        difficulty: Int,
        context: String = "",
    ): String {
        val g = granularity.coerceIn(1, 5)
        val d = difficulty.coerceIn(1, 5)
        val sb = StringBuilder()
        sb.append("要拆解的事：").append(target.trim()).append('\n')
        sb.append("颗粒度：").append(g).append('\n')
        sb.append("难度：").append(d)
        if (context.isNotBlank()) {
            sb.append('\n').append("背景/理由：").append(context.trim())
        }
        return sb.toString()
    }

    /** 整理的。
     *
     * 输入可能是多行、中英混排、带列表符号 —— 原样传，别做"聪明"的清洗，
     * 那样会丢掉用户原本的分组意图。
     *
     * 顺带把条目数写进去：模型看到"共 N 条"时更容易保证不丢，实测有效。
     */
    /**
     * Compiler 的用户消息：只放碎片原文 + 条目数提示。
     *
     * **不在这里加难度/背景**：用户给的本来就是一堆混乱念头，
     * 逼他先回答"这堆有多难"是把难度判断过早固化。
     * 难度由模型拆完之后标，用户再在排序界面改。
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

    /**
     * Compiler 第三步的用户消息：把已排序的条目喂进去估时。
     *
     * 用带序号的行，明确"顺序有意义"，并把 difficulty 与 **context** 一起给出 ——
     * 两者都是估时的关键依据：难度 4 比难度 1 要留更多余量；
     * context 决定"做多深"（「随便糊弄」和「要能交上去」差好几倍）。
     * 这正是 difficulty / context 存在的意义。
     */
    fun estimateListUser(steps: List<EstimateItem>): String {
        val sb = StringBuilder()
        sb.append("按顺序的任务清单（共 ${steps.size} 条）：\n")
        steps.forEachIndexed { i, s ->
            sb.append(i + 1).append(". ").append(s.title)
            if (s.difficulty != null) sb.append("（难度").append(s.difficulty.coerceIn(1, 5)).append("）")
            if (!s.context.isNullOrBlank()) sb.append("［").append(s.context.trim()).append("］")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun estimateUser(activity: String): String =
        "估算下面这件事需要多少分钟：\n" + activity.trim()
}
