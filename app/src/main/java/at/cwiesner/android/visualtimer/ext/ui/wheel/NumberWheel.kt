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
 * 这里原本写的是 `parseIndex + 1`，导致滚轮打开时高亮显示的**永远是实际值的下一项**，
 * 用户一滑动就把那个错位的值写回设置（用 Python 按 LazyColumn 语义逐项模拟核对过）。
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
            val firstIndex = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset
            // 半格以上就算滚到下一项（四舍五入到最近的格）
            val rounded = if (offset * 2 >= itemHeight) firstIndex + 1 else firstIndex
            val v = values.getOrNull(valueIndex(rounded)) ?: return@LaunchedEffect
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

        val target = values.indexOf(selected).coerceAtLeast(0)
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
 * 横向分段色带：把调色板按当前档数均分展示，直观看到"分成几档、每档什么色"。
 */
@Composable
fun PaletteStrip(
    divisions: Int,
    modifier: Modifier = Modifier,
) {
    val n = divisions.coerceIn(1, ExtColors.Palette.size)
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
                    .background(ExtColors.Palette[i % ExtColors.Palette.size]),
            )
        }
    }
}
