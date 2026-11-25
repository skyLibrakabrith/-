package at.cwiesner.android.visualtimer.ext.ui.sequences

import at.cwiesner.android.visualtimer.ext.data.Step

/**
 * 序列「预计时间」的格式化工具。
 *
 * 为什么单独一个文件：
 * - 序列列表（[SequencesScreen]）和序列编辑器（[SequenceEditorScreen]）都要用，
 *   编辑器里的数据是草稿 [StepDraft] 而列表里是数据库实体 [Step]，
 *   所以两个版本都要（注意用不同的函数名，见下面的说明）；写成 private 就得到处复制一份。
 * - 它是本包内部的展示细节，**故意不暴露给面板网格**：
 *   `ui/panels/SequenceCell.kt` 的圆形卡片中间只能放一个短文本，
 *   那里已经直接用 `DeadlineCalculator.formatRemaining(总秒)` 显示总时长了，
 *   再套一层「合计 xx」会撑破卡片。改那个文件不属于本工具的范围。
 *
 * 和 `DeadlineCalculator.formatRemaining` 的关系：
 * 后者面向「倒计时」（"1h23m" / "45m00s" / "12s"），好看但不会四舍五入，
 * 多个阶段加总后常出现「2h09m54s」这种带零碎秒的数，作为"预计时间"读起来很吵。
 * 这里先取整到分钟（不足 1 分钟才显示秒），再拼上「合计」前缀。
 */

/** 各阶段时长之和（毫秒）。负数按 0 计，防止脏数据把总和拉低。 */
fun totalDurationMillis(steps: List<Step>): Long =
    steps.sumOf { if (it.durationMillis > 0L) it.durationMillis else 0L }

/**
 * 编辑器的草稿版本的合计时长。
 *
 * ⚠️ **不能**和上面那个同名重载。原来写成 `fun totalDurationMillis(drafts: List<StepDraft>)`
 * 与 `fun totalDurationMillis(steps: List<Step>)` 并存，CI 直接报：
 *     Platform declaration clash: the same JVM signature (totalDurationMillis(Ljava/util/List;)J)
 * 因为 JVM 会擦除泛型参数，两个 `List<...>` 在字节码里都是 `List`，签名完全一样。
 * Kotlin 层面能分辨，JVM 层面不能 —— 所以必须用不同函数名（或 @JvmName）。
 *
 * 单独写而不是复用 [totalDurationMillis]：草稿还没落库，
 * 不能为了算个数字去造一堆 `Step` 对象（它们还带 sequenceId 等无关字段）。
 */
fun totalDraftDurationMillis(drafts: List<StepDraft>): Long =
    drafts.sumOf { if (it.durationMillis > 0L) it.durationMillis else 0L }

/**
 * 把合计毫秒格式化成「1h30m」/「45m」/「90s」。
 *
 * 规则（刻意和 `formatRemaining` 不同，它是给倒计时用的精确读数）：
 * - 有小时：只显示到分钟，且**秒四舍五入进位**，例如 2h09m54s → 「2h10m」；
 * - 不足 1 小时：只显示分钟，秒四舍五入，例如 45m00s → 「45m」、1m40s → 「2m」；
 * - 不足 1 分钟：才显示秒，例如 40s → 「40s」。
 * 四舍五入后会得到 60 分钟的情况统一进位成「1h00m」，不会出现「60m」。
 */
fun formatTotalDuration(millis: Long): String {
    if (millis <= 0L) return "0s"
    val totalSeconds = millis / 1000
    if (totalSeconds < 60L) return "${totalSeconds}s"
    // 秒 ≥ 30 就进一分钟
    val totalMinutes = (totalSeconds + 30L) / 60L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h%02dm".format(minutes) else "${minutes}m"
}

/**
 * 序列列表 / 运行界面的描述：阶段数 + 合计时长，例如「6 段 · 合计 2h10m」。
 *
 * 0 个阶段单独处理成「还没有阶段」：空序列的「合计 0s」既没信息量，
 * 又容易让人以为"这是个 0 秒的序列"，而实际上它根本跑不起来。
 */
fun describeSequenceDuration(steps: List<Step>): String = when {
    steps.isEmpty() -> "还没有阶段"
    else -> "${steps.size} 段 · 合计 " + formatTotalDuration(totalDurationMillis(steps))
}

/**
 * 编辑器阶段列表上方那一行的合计文本，例如「合计 2h10m」。
 *
 * 编辑时用户是在增删阶段，阶段数（「阶段（N 个）」）已经写死在旁边，
 * 这里只补时长；空草稿返回「合计 —」，用破折号表示"暂时算不出来"，
 * 避免出现「合计 0s」。
 */
fun describeDraftTotal(drafts: List<StepDraft>): String =
    if (drafts.isEmpty()) "合计 —" else "合计 " + formatTotalDuration(totalDraftDurationMillis(drafts))
