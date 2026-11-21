package at.cwiesner.android.visualtimer.ext.ui.wheel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * 精确时分秒选择器：三个可滚动的滚轮。
 *
 * 用 [rememberSnapFlingBehavior] 让每次滑动停在整数格上，
 * 中间高亮行表示当前选中值 —— 这是移动端输入时长的标准做法。
 *
 * 小时可配范围（0..[maxHours]），分/秒 0..59。
 */
@Composable
fun HmsWheelPicker(
    totalMillis: Long,
    onTotalChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    maxHours: Int = 23,
) {
    val totalSec = (totalMillis / 1000).toInt().coerceAtLeast(0)
    val h = (totalSec / 3600).coerceIn(0, maxHours)
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WheelColumn(
            values = (0..maxHours).toList(),
            selected = h,
            label = "时",
            modifier = Modifier.width(88.dp),
        ) { newH ->
            onTotalChange((newH.toLong() * 3600 + m.toLong() * 60 + s) * 1000)
        }
        WheelColumn(
            values = (0..59).toList(),
            selected = m,
            label = "分",
            modifier = Modifier.width(88.dp),
        ) { newM ->
            onTotalChange((h.toLong() * 3600 + newM.toLong() * 60 + s) * 1000)
        }
        WheelColumn(
            values = (0..59).toList(),
            selected = s,
            label = "秒",
            modifier = Modifier.width(88.dp),
        ) { newS ->
            onTotalChange((h.toLong() * 3600 + m.toLong() * 60 + newS) * 1000)
        }
    }
}

@Composable
private fun WheelColumn(
    values: List<Int>,
    selected: Int,
    label: String,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val itemHeight = 44.dp
    val visibleCount = 3
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 1).coerceAtLeast(0))
    val fling = rememberSnapFlingBehavior(state)

    // 滚动停下后计算居中项
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            val center = state.firstVisibleItemIndex +
                if (state.firstVisibleItemScrollOffset > itemHeight.value / 2) 1 else 0
            val v = values.getOrNull(center)
            if (v != null && v != selected) onSelect(v)
        }
    }
    // 外部值变化时同步滚动位置
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress) {
            val target = (values.indexOf(selected) - 1).coerceAtLeast(0)
            if (state.firstVisibleItemIndex != target) {
                state.scrollToItem(target)
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .height(itemHeight * visibleCount)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // 中间高亮条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ExtColors.Surface),
            )
            LazyColumn(
                state = state,
                flingBehavior = fling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 顶部/底部留白，让首尾项也能居中
                item { Box(Modifier.height(itemHeight)) }
                itemsIndexed(values) { _, v ->
                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "%02d".format(v),
                            fontSize = 24.sp,
                            fontWeight = if (v == selected) FontWeight.Medium else FontWeight.Light,
                            color = if (v == selected) ExtColors.TextPrimary else ExtColors.TextDim,
                        )
                    }
                }
                item { Box(Modifier.height(itemHeight)) }
            }
        }
        Text(
            text = label,
            fontSize = 12.sp,
            color = ExtColors.TextDim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 大号时间输入：三个方框，可点击后逐位调整。
 *
 * 比滚轮更省空间的替代方案，用于对话框里空间有限时。
 */
@Composable
fun CompactHmsInput(
    totalMillis: Long,
    onTotalChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalSec = (totalMillis / 1000).toInt().coerceAtLeast(0)
    var h by remember(totalSec) { mutableIntStateOf(totalSec / 3600) }
    var m by remember(totalSec) { mutableIntStateOf((totalSec % 3600) / 60) }
    var s by remember(totalSec) { mutableIntStateOf(totalSec % 60) }

    fun emit() = onTotalChange((h.toLong() * 3600 + m.toLong() * 60 + s) * 1000)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberStepper(value = h, range = 0..23, label = "时") { h = it; emit() }
        Text(":", fontSize = 22.sp, color = ExtColors.TextDim)
        NumberStepper(value = m, range = 0..59, label = "分") { m = it; emit() }
        Text(":", fontSize = 22.sp, color = ExtColors.TextDim)
        NumberStepper(value = s, range = 0..59, label = "秒") { s = it; emit() }
    }
}

@Composable
private fun NumberStepper(
    value: Int,
    range: IntRange,
    label: String,
    onChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBtn("−") { onChange((value - 1).coerceIn(range.first, range.last)) }
            Text(
                text = "%02d".format(value),
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                color = ExtColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            StepBtn("+") { onChange((value + 1).coerceIn(range.first, range.last)) }
        }
        Text(label, fontSize = 11.sp, color = ExtColors.TextDim)
    }
}

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ExtColors.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 18.sp, color = ExtColors.Primary)
    }
}

/** 把毫秒转成「时:分:秒」显示。 */
internal fun hmsText(millis: Long): String {
    val t = (millis / 1000).roundToInt().coerceAtLeast(0)
    return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
}

