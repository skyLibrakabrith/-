package at.cwiesner.android.visualtimer.ext.domain

/**
 * 归档规则的纯逻辑：决定"这段计时该不该记一条"。
 *
 * ## 为什么单独抽出来
 *
 * 这些规则是**用户明确指定的产品语义**，散落在几处 `if` 里最容易写歪 ——
 * 之前就有过一次：倒计时暂停归档和结束归档各写一份算式，超时那段一边算一边不算。
 * 抽成纯函数后可以用单测把每条边界钉死。
 *
 * ## 用户指定的规则（原话）
 *
 * > 开始与暂停/重置/停止间小于 1/10 时长不计。
 * > 正计时开始与暂停/重置/停止间小于 30 秒不计。
 *
 * 注意两条的基准不同：
 * - **倒计时**按「计划时长的 1/10」—— 一个 25 分钟的番茄钟，跑不到 2.5 分钟
 *   就暂停/重置，说明用户只是误触或改主意，不该在归档里留一条。
 * - **正计时**没有"计划时长"，只能给一个绝对下限 30 秒。
 */
object ArchiveRules {

    /** 正计时的最短计入时长（毫秒）。用户指定 30 秒。 */
    const val MIN_STOPWATCH_MILLIS: Long = 30_000L

    /** 倒计时的计入门槛 = 计划时长 × 这个比例。用户指定 1/10。 */
    const val COUNTDOWN_FRACTION_DENOMINATOR: Long = 10L

    /**
     * 这段计时是否值得写进归档。
     *
     * @param stopwatch 是否正计时
     * @param elapsedMillis 实际走了多久（**含超时**，调用方用
     *   [elapsedMillisForArchive] 算好）
     * @param plannedMillis 计划时长；正计时时忽略
     */
    fun shouldArchive(
        stopwatch: Boolean,
        elapsedMillis: Long,
        plannedMillis: Long,
    ): Boolean {
        if (elapsedMillis <= 0L) return false
        return if (stopwatch) {
            elapsedMillis >= MIN_STOPWATCH_MILLIS
        } else {
            // plannedMillis <= 0 时没有可参照的基准（数据异常），
            // 退回用正计时那条绝对下限，至少不会把 3 秒的误触记进去。
            if (plannedMillis <= 0L) {
                elapsedMillis >= MIN_STOPWATCH_MILLIS
            } else {
                elapsedMillis >= plannedMillis / COUNTDOWN_FRACTION_DENOMINATOR
            }
        }
    }
}

/**
 * 把一组 `[start, end)` 区间合并成互不重叠、按开始排序的结果。
 *
 * 归档页的「合计」要用它去重。为什么必须去重：暂停时会先写一条进行中的记录，
 * 之后再补成完整记录；同一次计时的区间可能相邻甚至重叠（跨午夜拆分、
 * 多个计时器并行时同一条会话被多次 touching）。直接 `sumOf { end - start }`
 * 会把时间重复计入。
 */
fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
    val valid = intervals
        .filter { it.second > it.first }
        .sortedBy { it.first }
    if (valid.isEmpty()) return emptyList()
    val out = mutableListOf<Pair<Long, Long>>()
    var curStart = valid[0].first
    var curEnd = valid[0].second
    for ((s, e) in valid.drop(1)) {
        if (s <= curEnd) {
            // 相邻或重叠：并成一段
            if (e > curEnd) curEnd = e
        } else {
            out += curStart to curEnd
            curStart = s
            curEnd = e
        }
    }
    out += curStart to curEnd
    return out
}

/** 区间并集的总长度（毫秒）。 */
fun mergedDurationMillis(intervals: List<Pair<Long, Long>>): Long =
    mergeIntervals(intervals).sumOf { it.second - it.first }

/**
 * 把「串行跑过的若干阶段」铺成互不重叠、首尾相接的区间。
 *
 * ## 为什么需要这个
 *
 * 序列的每个阶段都是**依次**执行的。归档时如果每段都写成
 * `endedAt = now; startedAt = endedAt - actual`，几段就会全部叠在末尾：
 *
 * ```
 * 实际：0→600  600→900  900→1800
 * 错误：1200→1800  1500→1800  900→1800
 * ```
 *
 * 后果是归档合计（按区间并集算）把 30 分钟算成 5 分钟。
 *
 * @param endMillis 最后一个阶段的结束时刻
 * @param actualMillis 各阶段实际用时，顺序即执行顺序
 * @return 与输入等长的 `(start, end)` 列表
 */
fun layOutSequentialIntervals(
    endMillis: Long,
    actualMillis: List<Long>,
): List<Pair<Long, Long>> {
    val total = actualMillis.sumOf { it.coerceAtLeast(0L) }
    var cursor = endMillis - total
    return actualMillis.map { d ->
        val len = d.coerceAtLeast(0L)
        val start = cursor
        cursor += len
        start to cursor
    }
}
