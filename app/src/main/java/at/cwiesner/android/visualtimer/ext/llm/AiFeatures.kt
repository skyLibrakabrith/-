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
     * Magic ToDo：一段话 → 有序步骤（含估时）。
     *
     * 对应 goblin.tools 的 Magic ToDo + Estimator：拆和估一次做完，
     * 因为分两次问模型会给出互相矛盾的结果（拆出的步骤和估时的活动对不上）。
     */
    suspend fun split(target: String): AiResult<List<TodoStep>> {
        if (target.isBlank()) return AiResult.Failed("先输入要拆解的内容")
        val cfg = config()
        val raw = router.completeJson(cfg, LlmPrompts.SPLIT_SYSTEM, LlmPrompts.splitUser(target))
        return raw.flatMapSteps()
    }

    /**
     * Compiler：一堆碎片念头 → 归类成若干组。
     *
     * 空行分隔的行会被当作独立念头；不空行也可以，模型自己能分词。
     */
    suspend fun compile(brainDump: String): AiResult<List<TodoGroup>> {
        if (brainDump.isBlank()) return AiResult.Failed("先输入要整理的内容")
        val cfg = config()
        val raw = router.completeJson(cfg, LlmPrompts.COMPILE_SYSTEM, LlmPrompts.compileUser(brainDump))
        return raw.flatMapGroups()
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
