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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
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
 * 列表结构（[LazyColumn] 无 `contentPadding`、无 `verticalArrangement.spacedBy`，
 * 每一项高度严格等于 `itemHeight`）：
 * ```
 * 列表下标 0        顶部占位（itemHeight）
 * 列表下标 1..N     values[0]..values[N-1]
 * 列表下标 N+1      底部占位（itemHeight）
 * ```
 * 视口高 `itemHeight * visibleCount`，高亮条在正中，占
 * `[itemHeight, 2*itemHeight)`，中心在 `1.5 * itemHeight`。
 *
 * 项 j 的顶边 y = `-offset + (j - firstVisibleItemIndex) * itemHeight`。
 * `values[k]` 在列表里的下标是 `k + 1`，让它占满 `[itemHeight, 2*itemHeight)`
 * 需要 `firstVisibleItemIndex == k`（此时第 k 项占 `[0, itemHeight)`，
 * 第 k+1 项 = `values[k]` 占 `[itemHeight, 2*itemHeight)`）。
 * **所以 `firstVisibleItemIndex` 与 values 下标同号同值（停在整格上时）**，
 * 而「高亮条里那一项的列表下标」等于 `firstVisibleItemIndex + 1`。
 *
 * ## n-1 的真正根源（两轮才修对，务必读完再动）
 *
 * 这个组件里同时存在**两套下标口径**：
 *   · `firstVisibleItemIndex` / `scrollToItem` / 列表项 → **LazyColumn 下标**
 *     （`values[k]` 是列表里的第 k+1 项）
 *   · `values[...]` → **values 下标**（`values[k]` 就是 k）
 *
 * 两者差 1，这 1 来自顶部占位。历史上反复出错，都是因为把一套口径的结果
 * 直接喂给了另一套：
 *
 * - **第一轮**：`initialFirstVisibleItemIndex = parseIndex + 1`
 *   —— 把 values 下标当列表下标，高亮条显示的是**下一个**值。
 * - **第二轮（c3f3af2）**：写回公式换成 `wheelHighlightIndex = F + round(offset/H)`，
 *   但**漏了顶部占位造成的 `+1`**，紧接着又过了一遍
 *   `valueIndex(li) = (li - 1).coerceIn(...)`。于是在吸附停稳（offset == 0，
 *   用户真正停住的位置）：
 *     F = values.indexOf(5) = 3  →  li = 3  →  valueIndex(3) = 2  →  values[2] = 4
 *   屏上写着 5，写回 4。而高亮条里真正的列表项是 `F + 1 = 4`（= `values[3]` = 5），
 *   所以正确写法是 `F + 1 + round(offset/H) = 4` —— `valueIndex(4) = 3`，取回 5。
 *   上一轮改的是「用哪个函数」，没改这个函数**少了一项 +1**，
 *   而且新式在 off == 0 处与旧式 `if (offset*2 >= H) F+1 else F` 输出**完全相同**，
 *   因此在用户实际会停住的位置上，那一轮改动等于没改。
 *
 * 现在只有两个换算函数，一个方向一个，且都在下面写清了返回值口径：
 *   `wheelHighlightListIndex(F, o, H)`  —— 几何量，返回 **LazyColumn 下标**（含顶部占位的 `+1`）
 *   `listIndexToValueIndex(li, size)`   —— LazyColumn 下标 → **values 下标**（`li - 1`）
 * 二者串联一次正好抵掉那个 1：`F + 1 → (F+1) - 1 = F`，而 `F == values 下标`
 * （见上方「让它占满 `[itemHeight, 2*itemHeight)` 需要 `firstVisibleItemIndex == k`」）。
 * 初始化 / `scrollToItem` 直接用 `values.indexOf(selected)`，**不要 +1**。
 * **不要在调用处手写 `-1` / `+1`**，这正是前两轮错误的成因。
 *
 * 另：首次组合时静止回调也会跑一次，拿当前位置反算值。两条路径互逆之后，
 * 算出的值恒等于 `selected`，`if (v != selected)` 不成立，不会产生任何写回 ——
 * 于是也不会再出现「打开一趟设置页，5 就自己变成 4」。
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

    // 停位：让 values[k] 占满高亮条，需要 firstVisibleItemIndex == k（= values 下标）。
    // 不要再 +1 —— 见文件头「两套口径」，这里的 F 与 values 下标同值。
    val state = rememberLazyListState(
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
            val centerListIndex = wheelHighlightListIndex(
                firstVisibleItemIndex = state.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
                itemHeight = itemHeight,
            )
            val v = values.getOrNull(listIndexToValueIndex(centerListIndex, values.size))
                ?: return@LaunchedEffect
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
            // 顶部占位：让第一个真实值能停在正中。**这一项的存在就是 listIndex 与
            // valueIndex 相差 1 的唯一原因**，删掉它必须同时改两个换算函数。
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
                        // 只做视觉强调，不参与「当前值」的判定 ——
                        // 判定一律以高亮条几何为准（见上面的写回 effect），
                        // 否则强化样式和实际选中值会来自两个来源、又能错开。
                        fontSize = 18.sp,
                        fontWeight = if (v == selected) FontWeight.Medium else FontWeight.Light,
                        color = if (v == selected) ExtColors.TextPrimary else ExtColors.TextDim,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            // 底部占位：让最后一个真实值也能停在正中
            item { Box(Modifier.height(itemHeight.dp)) }
        }
    }
}

/**
 * LazyColumn 下标 -> values 下标。
 *
 * 顶部占位占掉列表下标 0，所以 `values[k]` 在列表里是 `k + 1`，反过来减 1。
 * 两端用 coerceIn 夹住：底部占位（`size + 1`）以及拖动越界时落回合法范围。
 *
 * 这是**唯一**允许出现 `-1` 的地方。调用方不要自己再减。
 */
internal fun listIndexToValueIndex(listIndex: Int, size: Int): Int {
    if (size <= 0) return 0
    return (listIndex - 1).coerceIn(0, size - 1)
}

/**
 * 由 LazyListState 的可见位置算出「此刻落在高亮条里的是第几个**列表项**」。
 *
 * 几何（`firstVisibleItemIndex` 记作 F、offset 记作 o、`itemHeight` 记作 H）：
 * 列表项 j 的顶边 `y_j = -o + (j - F) * H`。
 * 列表项 F 顶边在 `-o`，F+1 在 `H - o`，F+2 在 `2H - o`……
 * 高亮条固定占 `[H, 2H)`。
 *
 * o == 0 时 `y_{F+1} = H`，正好是高亮条顶边 —— **高亮条里是 `F + 1`**。
 * 那个 `+1` 就是「顶部占位项」造成的位移：占位项把 values 整体往下推了一格。
 * 随着 o 增大，`F+1` 逐渐上移、`F+2` 逐渐顶上来，`o = H/2` 时两者各占一半，
 * `o > H/2` 之后 `F+2` 覆盖更多。所以覆盖最多的是 `F + 1 + round(o/H)`。
 *
 * 返回值是 **LazyColumn 下标**，调用方必须再过 [listIndexToValueIndex] 才是
 * values 下标。**不要在这里顺手减 1** —— c3f3af2 那一版正是这里和下游各扣一次，
 * 合起来少了这个 `+1`，于是屏上写着 5、写回 4。
 */
internal fun wheelHighlightListIndex(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    itemHeight: Int,
): Int {
    if (itemHeight <= 0) return firstVisibleItemIndex + 1
    return firstVisibleItemIndex + 1 +
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
 * 份数夹取与 [SettingsRepo.MIN_FRACTION_DIVISIONS]..[SettingsRepo.MAX_FRACTION_DIVISIONS]
 * 完全一致，格数恒等于传进来的 [divisions]（在合法区间内）。
 */
@Composable
fun PaletteStrip(
    divisions: Int,
    modifier: Modifier = Modifier,
) {
    // 与 WheelTimerView.pickColor() 的 Fraction 分支用**完全相同**的夹取范围，
    // 否则设置页画的格数和盘面实际的档数又不一致。
    val n = divisions.coerceIn(
        SettingsRepo.MIN_FRACTION_DIVISIONS,
        SettingsRepo.MAX_FRACTION_DIVISIONS,
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
