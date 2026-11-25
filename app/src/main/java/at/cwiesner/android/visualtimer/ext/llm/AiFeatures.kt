package at.cwiesner.android.visualtimer.ext.llm

import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo

/**
 * 三个 AI 入口的门面。
 *
 * 把「读配置 → 拼提示词 → 发请求 → 解析 JSON → 转成结果」这条流水线收在这里，
 * 界面层只需要 `AiFeatures(app).split("写实验报告")` 这一句。
 *
 * 每个方法返回 [AiResult]，**从不抛异常** —— 界面上这些都是用户点的按钮，
 * 失败必须变成一句能 toast 的话。
 */
class AiFeatures(
    private val settings: SettingsRepo,
    private val router: LlmRouter = LlmRouter(),
) {

    private suspend fun config(): LlmConfig = LlmRouter.loadConfig(settings)

    /**
     * Magic ToDo：一段话 → 按时间顺序的步骤（含估时与困难程度）。
     *
     * 对应 goblin.tools 的 Magic ToDo + Estimator：拆和估一次做完，
     * 因为分两次问模型会给出互相矛盾的结果（拆出的步骤和估时的活动对不上）。
     *
     * @param granularity 颗粒度 1–5，越高拆得越细（goblin 的 Spiciness）
     * @param difficulty 难度 1–5，越高估时越保守
     * @param context 可选的背景/理由。写进去能显著改变拆解结果 ——
     *   「明天要交但我完全没开始」会让模型优先安排能立刻交付的步骤。
     */
    suspend fun split(
        target: String,
        granularity: Int = 3,
        difficulty: Int = 3,
        context: String = "",
    ): AiResult<List<TodoStep>> {
        if (target.isBlank()) return AiResult.Failed("先输入要拆解的内容")
        val cfg = config()
        val raw = router.completeJson(
            cfg,
            LlmPrompts.SPLIT_SYSTEM,
            LlmPrompts.splitUser(target, granularity, difficulty, context),
        )
        return raw.flatMapSteps()
    }

    /**
     * Compiler 第一步：一堆碎片念头 → 归类成若干组（**只拆，不估时**）。
     *
     * 时长留给 [estimateList]：它要看**最终顺序**才准，
     * 而用户马上就要在界面上调整顺序。
     *
     * **条目保底**：本地 4B 模型经常把「下周要交的作业」这类上级概念
     * 并进更具体的条目里，导致丢条目。提示词拦不住（实测），
     * 所以这里用 [BrainDump.backfillMissing] 把缺失的原文补回来 ——
     * 宁可补一条没打磨的原文，也不能让用户以为已经处理完了。
     */
    suspend fun compile(brainDump: String): AiResult<List<TodoGroup>> {
        if (brainDump.isBlank()) return AiResult.Failed("先输入要整理的内容")
        val cfg = config()
        val raw = router.completeJson(
            cfg,
            LlmPrompts.COMPILE_SYSTEM,
            LlmPrompts.compileUser(brainDump),
        )
        return when (raw) {
            is AiResult.Failed -> raw
            is AiResult.Ok -> AiJson.parseGroups(raw.value)
                ?.let { AiResult.Ok(BrainDump.backfillMissing(it, brainDump)) }
                ?: AiResult.Failed("模型返回的格式看不懂（可能不是 JSON），请重试")
        }
    }

    /**
     * Compiler 第三步：给**已排好序**的条目一次性估时。
     *
     * @param steps 顺序即最终执行顺序；difficulty / context 都会作为估时依据喂给模型
     * @return 与 [steps] 等长的分钟数数组
     */
    suspend fun estimateList(steps: List<EstimateItem>): AiResult<List<Int>> {
        if (steps.isEmpty()) return AiResult.Failed("列表是空的")
        val cfg = config()
        val raw = router.completeJson(
            cfg,
            LlmPrompts.ESTIMATE_LIST_SYSTEM,
            LlmPrompts.estimateListUser(steps),
        )
        return when (raw) {
            is AiResult.Failed -> raw
            is AiResult.Ok -> AiJson.parseMinutesList(raw.value, steps.size)
                ?.let { AiResult.Ok(it) }
                ?: AiResult.Failed("估时结果条数对不上，请重试")
        }
    }

    /** Estimator：单个活动 → 分钟数。 */
    suspend fun estimate(activity: String): AiResult<Int> {
        if (activity.isBlank()) return AiResult.Failed("先输入要估算的活动")
        val cfg = config()
        return when (val raw = router.completeJson(cfg, LlmPrompts.ESTIMATE_SYSTEM, LlmPrompts.estimateUser(activity))) {
            is AiResult.Ok -> AiJson.parseMinutes(raw.value)
                ?.let { AiResult.Ok(it) }
                ?: AiResult.Failed("模型返回的格式看不懂，请重试")
            is AiResult.Failed -> raw
        }
    }

    // ---------------- 结果转换 ----------------
    //
    // 注意类型：AiResult.Failed 是 AiResult<Nothing>，协变所以能当任何 AiResult<T> 用，
    // 但 `when` 分支里直接返回 `this` 时，编译器需要显式把它标成目标类型。
    // 这里用 `raw.mapSteps()` 的写法而不是内联 when，避免踩这个坑。

    private fun AiResult<String>.flatMapSteps(): AiResult<List<TodoStep>> = when (this) {
        is AiResult.Failed -> AiResult.Failed(this.message, this.cause)
        is AiResult.Ok -> AiJson.parseSteps(value)
            ?.let { AiResult.Ok(it) }
            ?: AiResult.Failed("模型返回的格式看不懂（可能不是 JSON），请重试")
    }

    private fun AiResult<String>.flatMapGroups(): AiResult<List<TodoGroup>> = when (this) {
        is AiResult.Failed -> AiResult.Failed(this.message, this.cause)
        is AiResult.Ok -> AiJson.parseGroups(value)
            ?.let { AiResult.Ok(it) }
            ?: AiResult.Failed("模型返回的格式看不懂（可能不是 JSON），请重试")
    }
}
