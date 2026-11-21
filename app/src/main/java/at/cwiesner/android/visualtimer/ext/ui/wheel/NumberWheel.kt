package at.cwiesner.android.visualtimer.ext.ui.wheel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (values.indexOf(selected) - 1).coerceAtLeast(0),
    )
    val fling = rememberSnapFlingBehavior(state)

    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            val center = state.firstVisibleItemIndex +
                if (state.firstVisibleItemScrollOffset > itemHeight / 2) 1 else 0
            val v = values.getOrNull(center)
            if (v != null && v != selected) onSelect(v)
        }
    }
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress) {
            val target = (values.indexOf(selected) - 1).coerceAtLeast(0)
            if (state.firstVisibleItemIndex != target) state.scrollToItem(target)
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
