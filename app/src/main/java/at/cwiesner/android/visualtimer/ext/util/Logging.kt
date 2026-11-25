package at.cwiesner.android.visualtimer.ext.util

import android.util.Log

/**
 * 会记日志的 `runCatching`。
 *
 * **为什么需要它**：全工程有 50 处 `runCatching{}` 静默吞异常、却只有 1 处 `Log`。
 * 结果是线上出问题时只能靠用户口述现象，logcat 里什么都没有。
 * 但直接把 50 处全换成 try/catch 又会把代码搞得很脏 —— 所以留一个
 * 和 `runCatching` 用起来一模一样的替代品，只多一个 tag。
 *
 * 用法：
 * ```
 * logCatching("PanelRunner") { db.timerDao().upsert(timer) }
 * ```
 *
 * 设计取舍：
 * - **返回 `T?` 而不是 `Result<T>`**。绝大多数调用点只关心"成功拿到值没有"
 *   （`?: return`、`?.let`），用可空类型配 `?.`/`?:` 最顺手，也最不容易漏判。
 * - **不做节流**。同一处高频失败会刷屏 logcat，但那通常是真正的 bug，
 *   被刷屏总比看不见好。真嫌吵再在调用点加条件。
 * - 异常本身**一定**打在日志里。不打印异常栈的日志等于没记。
 */
inline fun <T> logCatching(tag: String, block: () -> T): T? =
    try {
        block()
    } catch (t: Throwable) {
        Log.w(tag, "被吞掉的异常（调用点：" + tag + "）", t)
        null
    }

/**
 * 同上，但对返回值做一次兜底。
 *
 * 适合"失败了也要有个能用的值"的地方，比如读设置失败就用默认值。
 */
inline fun <T> logCatchingOr(tag: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        Log.w(tag, "被吞掉的异常，回退到默认值（调用点：" + tag + "）", t)
        fallback
    }
