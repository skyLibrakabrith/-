package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * 可在主界面直接拖拽排序的网格。
 *
 * **不再弹独立调序窗**：用户看到的就是面板本身的网格，
 * 长按任意卡片进入拖拽，拖动时与经过的格子交换位置，松手即保存。
 *
 * 实现要点：
 * 1. 用 `detectDragGesturesAfterLongPress`，与「单击 / 双击」手势互不干扰
 * 2. 被拖项通过 zIndex + translation 浮到最上层，其余项正常排布
 * 3. 连续拖动时按「跨过的格子数」计算目标下标并实时交换
 *
 * @param items 已按 order 排好的条目（跨类型统一列表）
 * @param key 条目稳定标识
 * @param cellHeightPx 单格高度（含间距），用于换算拖动位移
 * @param columns 当前列数
 * @param onMove 拖动导致次序变化时回调（from → to）
 * @param itemContent 单个格子的内容；第二参数表示是否正被拖动
 */
@Composable
fun <T> DraggableGrid(
    items: List<T>,
    key: (T) -> Any,
    columns: Int,
    modifier: Modifier = Modifier,
    gridState: LazyGridState,
    cellHeightDp: Int = 120,
    onMove: (from: Int, to: Int) -> Unit,
    onClick: (T) -> Unit,
    /** 双击：进全屏。 */
    onDoubleClick: ((T) -> Unit)? = null,
    onLongClick: ((T) -> Unit)? = null,
    itemContent: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val cellPx = with(density) { cellHeightDp.dp.toPx() }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns.coerceIn(1, 8)),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            val dragging = index == draggingIndex
            Box(
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = offsetX
                            translationY = offsetY
                            scaleX = 1.06f
                            scaleY = 1.06f
                            alpha = 0.92f
                            shadowElevation = 14f
                        }
                    }
                    .pointerInput(index, items.size, columns) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                offsetX = 0f
                                offsetY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offsetX += amount.x
                                offsetY += amount.y

                                // 换算成跨过的格子数：横向按列、纵向按行
                                val cols = (offsetX / cellPx).toInt()
                                val rows = (offsetY / cellPx).toInt()
                                val delta = rows * columns + cols
                                if (delta != 0) {
                                    val from = draggingIndex
                                    val to = (from + delta).coerceIn(0, items.size - 1)
                                    if (from != to && from >= 0) {
                                        onMove(from, to)
                                        draggingIndex = to
                                        // 扣掉已经消费的位移，避免一次跳很多格
                                        offsetY -= rows * cellPx
                                        offsetX -= cols * cellPx
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                offsetX = 0f
                                offsetY = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                offsetX = 0f
                                offsetY = 0f
                            },
                        )
                    }
                    .fillMaxWidth(),
            ) {
                // 内容自身响应点击 / 双击 / 长按
                DraggableItemGesture(
                    onClick = { if (draggingIndex < 0) onClick(item) },
                    onDoubleClick = { if (draggingIndex < 0) onDoubleClick?.invoke(item) },
                    onLongPress = { onLongClick?.invoke(item) },
) {
                    itemContent(item, dragging)
                }
            }
        }
    }
}

/**
 * 条目手势：单击 / 双击 / 长按。
 *
 * - **单击**：暂停 / 继续
 * - **双击**：进全屏
 * - **长按**：触发父级的拖拽排序
 *
 * 用 [androidx.compose.foundation.gestures.detectTapGestures] 一次拿到
 * onTap / onDoubleTap / onLongPress，三者天然互斥，不会互相误触。
 */
@Composable
private fun DraggableItemGesture(
    onClick: () -> Unit,
    onDoubleClick: () -> Unit = {},
    onLongPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.pointerInput(Unit) {
            androidx.compose.foundation.gestures.detectTapGestures(
                onTap = { onClick() },
                onDoubleTap = { onDoubleClick() },
                onLongPress = { onLongPress() },
            )
        },
    ) {
        content()
    }
}
