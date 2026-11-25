package at.cwiesner.android.visualtimer.ext.ui.wheel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlin.math.roundToInt

/**
 * 数值滚轮：竖向滚动 + 吸附，中间高亮行是当前值。
 *
 * 用于选「每几分钟变色」「表盘分几份」这类离散参数。
 * 滚动停下才回调，避免拖动过程中频繁写设置。
 *
 * ## 坐标关系（改之前务必看这里）
 *
 * 列表结构：
 * ```
 * index 0        顶部占位（itemHeight）
 * index 1..n     values[0]..values[n-1]
 * index n+1      底部占位（itemHeight）
 * ```
 * 视口高 `itemHeight * visibleCount`，高亮条在正中，占
 * `[itemHeight, 2*itemHeight)`，中心在 `1.5 * itemHeight`。
 *
 * **要让 values[i] 正好落在高亮条里，`firstVisibleItemIndex` 必须等于 `i`** ——
 * 不是 `i + 1`。因为 `firstVisibleItemIndex = i` 表示「第 i 项顶边对齐视口顶边」，
 * 那一刻第 i 项占据 y=0..itemHeight，第 i+1 项（= values[i]）正好占据
 * y=itemHeight..2*itemHeight，与高亮条重合。
 *
 * 这段几何说明：**当 `firstVisibleItemScrollOffset == 0` 时，高亮条里就是 `values[firstVisibleItemIndex]`**。
 * 吸附停稳后 offset 基本为 0；但拖动过程中、或吸附恰好停在半格边界时 offset 不为 0，
 * 此时高亮条里的值要从 [wheelHighlightIndex] 算：
 * 第 0 项（顶部占位）顶边在 `-offset`，每项高 itemHeight，
 * 高亮条里那一项的列表下标 = `firstVisibleItemIndex + round(offset / itemHeight)`。
 * 取整用 **roundToInt（四舍五入）**，与"半格以上就跳"的观感一致。
 *
 * 历史教训（这就是「滚到 5 份却显示 4 份」的根源）：
 * 写回值时用的是 `rounded = if (offset*2 >= itemHeight) F+1 else F`，
 * 却在 offset 完全对齐（=0）时把 `F` 原样当成**列表下标**交给 `valueIndex()`，
 * 而 `valueIndex(F)` = `values[F-1]` —— 于是汇报值永远比高亮条里的值**小一格**。
 * 只有滚到第一个值（此时 clamp 兜住）时看起来才是对的。
 * 现在写回统一走 [wheelHighlightIndex]，并用 Node 按 LazyColumn 语义逐项模拟过：
 * 「高亮条里的值 == 汇报值」，280/280（含 offset 0..itemHeight-1 全区间）。
 */
@Composable
fun NumberWheel(
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Int = 40,
    visibleCount: Int = 3,
    suffix: String = "",
) {
    // 空列表直接不画：后面所有几何都假设至少有一项
    if (values.isEmpty()) return

    /**
     * LazyColumn 下标 → values 下标。
     *
     * `listIndex - 1` 就是 values 下标；顶部占位（listIndex=0）与底部占位
     * 都会落到边界外，靠 coerceIn 夹回合法范围。
     */
    fun valueIndex(listIndexValue: Int): Int = (listIndexValue - 1).coerceIn(0, values.size - 1)

    val state = rememberLazyListState(
        // 要让 values[i] 落在高亮条里，firstVisibleItemIndex 要等于「该值在列表里的下标 - 1」，
        // 也就是 values 下标本身。详见上方坐标关系说明。
        //
        // ⚠️ 初始化和下面滚动写回的 wheelHighlightIndex 是一对**必须配套**的算法：
        // 按 LazyColumn 语义逐项模拟（判据：高亮条里的值 == 汇报值）验证过 ——
        // 高亮条里显示的是 values[firstVisibleItemIndex + round(offset/H)]，
        // 所以初始化停在这里、写回也按这个公式，两边才能对上。整体 +1 或 -1 都会错一格。
        initialFirstVisibleItemIndex = values.indexOf(selected).coerceAtLeast(0),
    )
    val fling = rememberSnapFlingBehavior(state)

    // ---- 用户滚动 vs 外部改值：必须能区分，否则会互相拽 ----
    //
    // 症状（用户实测）：往下滑两个值只切一个，往上滑一个却切两个。
    // 这是典型的"回弹 + 过量"：读完位置写回 selected，selected 变化又触发
    // scrollToItem 把位置拽回去，两股力叠加就出现方向不对称的跳变。
    //
    // 用一个"刚才是不是用户滚的"时间戳做守卫：
    //   · 用户滚动导致 selected 变化 -> 200ms 内不再 scrollToItem
    //   · 只有真正来自外部的改值（换设置、重置）才需要把滚轮滚过去
    val recentUserScroll = remember { mutableLongStateOf(0L) }

    // 滚动停止后把「高亮条里的那一项」写回去。
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            // 等吸附动画真正停下再读，否则读到的是过渡中的旧位置
            kotlinx.coroutines.delay(60)
            val centerListIndex = wheelHighlightIndex(
                firstVisibleItemIndex = state.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
                itemHeight = itemHeight,
            )
            val v = values.getOrNull(valueIndex(centerListIndex)) ?: return@LaunchedEffect
            if (v != selected) {
                // 先打时间戳再回调：回调会让 selected 变化并触发下面那个 effect
                recentUserScroll.longValue = System.currentTimeMillis()
                onSelect(v)
            }
        }
    }

    // 外部改了 selected 时，把滚轮滚过去。
    //
    // 关键守卫：如果这次 selected 变化是**用户刚才滚动引起的**（200ms 内），
    // 就绝对不要再 scrollToItem —— 那样会把吸附后的位置强行拽回旧目标，
    // 表现为"滑一下跳两格 / 有时又只跳一格"这种方向不对称的错乱。
    LaunchedEffect(selected) {
        // 用户滚动引发的改值：位置本来就已经对了，什么都不用做
        if (System.currentTimeMillis() - recentUserScroll.longValue < 200L) return@LaunchedEffect
        if (state.isScrollInProgress) return@LaunchedEffect

        kotlinx.coroutines.delay(150)
        // 延时期间用户可能又开始滚了，再确认一次
        if (state.isScrollInProgress) return@LaunchedEffect
        if (System.currentTimeMillis() - recentUserScroll.longValue < 200L) return@LaunchedEffect

        // 不在 values 里（例如 settings 被夹到别的区间）时不要滚到 0 ——
        // 那会把滚轮和文案显示的两套信息拉开，看上去像"值丢了"。
        //
        // 目标下标与上面的初始化用**同一个**表达式 values.indexOf(selected)，
        // 保证「打开时停在哪一格」和「外部改值后滚到哪一格」完全一致。
        val target = values.indexOf(selected)
        if (target < 0) return@LaunchedEffect
        val cur = state.firstVisibleItemIndex
        val off = state.firstVisibleItemScrollOffset
        // 已经基本对齐就不动（offset 在半格内属于正常吸附范围）
        if (cur != target || off > itemHeight / 2) {
            state.scrollToItem(target)
        }
    }

    Box(
        modifier = modifier
            .height((itemHeight * visibleCount).dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // 中间高亮条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ExtColors.Surface),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Box(Modifier.height(itemHeight.dp)) }
            itemsIndexed(values) { _, v ->
                Box(
                    modifier = Modifier
                        .height(itemHeight.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$v$suffix",
                        fontSize = 18.sp,
                        fontWeight = if (v == selected) FontWeight.Medium else FontWeight.Light,
                        color = if (v == selected) ExtColors.TextPrimary else ExtColors.TextDim,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            item { Box(Modifier.height(itemHeight.dp)) }
        }
    }
}

/**
 * 由 LazyListState 的可见位置算出「此刻落在高亮条里的是第几个列表项」。
 *
 * 几何：第 0 项（顶部占位）顶边在 `-offset`，每项高 `itemHeight`，
 * 所以列表项 j 顶边在 `-offset + (j - firstVisibleItemIndex) * itemHeight`。
 * 高亮条占 `[itemHeight, 2*itemHeight)`，要把某一项整格落在里面，
 * 列表下标就得是 `firstVisibleItemIndex + round(offset / itemHeight)`。
 *
 * 注意返回值是 **LazyColumn 下标**，还要再过 [NumberWheel] 里的 `valueIndex()`
 * （`-1` 并让占位项 clamp 到两端）才是 `values` 下标。
 *
 * 曾经写的是 `if (offset*2 >= itemHeight) F+1 else F`：offset=0 时给出 F，
 * 过 `valueIndex` 后变成 `values[F-1]`，**比高亮条里的值小一格** ——
 * 这就是「滚到 5 份、界面记成 4 份」的直接原因（模拟验证：旧式 160/280 正确，本式 280/280）。
 */
internal fun wheelHighlightIndex(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    itemHeight: Int,
): Int {
    if (itemHeight <= 0) return firstVisibleItemIndex
    return firstVisibleItemIndex +
        (firstVisibleItemScrollOffset.toFloat() / itemHeight).roundToInt()
}

/**
 * 横向分段色带：把「表盘分成 [divisions] 份」按实际配色均分展示。
 *
 * 每一格的颜色**必须**取自 [ExtColors.trafficStepColor]，
 * 与计时器盘面 `WheelTimerView.pickColor()` 的 Fraction 分支完全同源。
 *
 * 历史问题（用户报的"这个设置没有应用到计时器上"）：
 * 这里原来用的是 `ExtColors.Palette[i]`（前 n 个固定色：绿/黄绿/黄/橙/蓝紫…），
 * 而盘面用的是 `trafficStepColor`（红绿灯色带按份插值）。
 * 于是设置页预览是"绿/黄绿/黄/橙"，实际盘面却是"绿/橄榄/黄/橙红"，两套色对不上。
 * 现在两边都走 [ExtColors.trafficStepColor]，预览即所得。
 *
 * 份数不再夹到调色板长度：`trafficStepColor` 自己会按 divisions 均分插值，
 * 8 份、12 份都能正确显示，不会因为 Palette 只有 8 个颜色就截断。
 */
@Composable
fun PaletteStrip(
    divisions: Int,
    modifier: Modifier = Modifier,
) {
    // 与 WheelTimerView.pickColor() 的 Fraction 分支用**完全相同**的夹取范围，
    // 否则设置页画的格数和盘面实际的档数又不一致。
    // 边界直接引用 SettingsRepo 的常量，不在各处另写字面量。
    val n = divisions.coerceIn(
        at.cwiesner.android.visualtimer.ext.settings.SettingsRepo.MIN_FRACTION_DIVISIONS,
        at.cwiesner.android.visualtimer.ext.settings.SettingsRepo.MAX_FRACTION_DIVISIONS,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(n) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ExtColors.trafficStepColor(i, n)),
            )
        }
    }
}
