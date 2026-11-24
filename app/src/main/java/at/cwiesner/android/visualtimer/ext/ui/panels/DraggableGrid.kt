package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull

/** 长按判定阈值。 */
private const val LONG_PRESS_MS = 400L

/** 双击判定窗口。 */
private const val DOUBLE_TAP_MS = 260L

/** 位移超过这个值就认为是滚动而非点按。 */
private const val DRAG_THRESHOLD_PX = 8f

/**
 * 可在主界面直接拖拽排序的网格。
 *
 * ## 手势设计（重要）
 *
 * 之前是**两层 `pointerInput`**：外层 `detectDragGesturesAfterLongPress`、
 * 内层 `detectTapGestures`。两层同时监听同一次按下，事件被子层消费，
 * 外层收不到，导致长按拖动时好时坏；子层又因为父层在拖拽中被不断重组，
 * 双击识别也不稳定。
 *
 * 现在改成**单层手势状态机**，一次按下只走一条判定路径：
 *
 * | 操作 | 判定 |
 * |---|---|
 * | 单击 | 400ms 内抬起，且位移 < 8px |
 * | 双击 | 与上一次单击间隔 < 260ms |
 * | 长按拖拽 | 按住 ≥400ms 后继续拖动，跨格即换位 |
 * | 长按松手 | 按住 ≥400ms 但没位移 → 触发 [onLongClick] |
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
    onDoubleClick: ((T) -> Unit)? = null,
    onLongClick: ((T) -> Unit)? = null,
    itemContent: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 双击识别：记录上一次单击的条目与时间
    var lastTapIndex by remember { mutableIntStateOf(-1) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

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
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalMove = 0f

                            // ---- 阶段一：等长按成立，或判断为滚动 / 提前抬起 ----
                            // withTimeoutOrNull 超时返回 null 表示「按住够久了」
                            val stayPressed = withTimeoutOrNull(LONG_PRESS_MS) {
                                var moved = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    val delta = change.positionChange()
                                    moved += delta.getDistance()
                                    if (moved > DRAG_THRESHOLD_PX) break   // 滚动，放手
                                    if (!change.pressed) break             // 提前抬起
                                }
                                true
                            }

                            // stayPressed == null 表示 withTimeoutOrNull 超时，
                            // 也就是「按住够久了」→ 长按成立，进入拖拽分支。
                            if (stayPressed == null) {
                                // 长按成立，直接进入下面的拖拽逻辑
                            } else {
                                // 这段时间内抬起或移动了 → 处理单击 / 双击
                                val now = System.currentTimeMillis()
                                val isDouble = lastTapIndex == index &&
                                    (now - lastTapAt) <= DOUBLE_TAP_MS
                                if (isDouble) {
                                    lastTapIndex = -1
                                    lastTapAt = 0L
                                    onDoubleClick?.invoke(item)
                                } else {
                                    lastTapIndex = index
                                    lastTapAt = now
                                    onClick(item)
                                }
                                return@awaitEachGesture
                            }

                            // ---- 阶段二：长按成立，进入拖拽 ----
                            draggingIndex = index
                            offsetX = 0f
                            offsetY = 0f

                            drag(down.id) { change ->
                                change.consume()
                                val delta = change.positionChange()
                                offsetX += delta.x
                                offsetY += delta.y
                                totalMove += delta.getDistance()

                                val cols = (offsetX / cellPx).toInt()
                                val rows = (offsetY / cellPx).toInt()
                                val d = rows * columns + cols
                                if (d != 0) {
                                    val from = draggingIndex
                                    val to = (from + d).coerceIn(0, items.size - 1)
                                    if (from != to && from >= 0) {
                                        onMove(from, to)
                                        draggingIndex = to
                                        // 扣掉已消费的位移，避免一次跳多格
                                        offsetY -= rows * cellPx
                                        offsetX -= cols * cellPx
                                    }
                                }
                            }

                            draggingIndex = -1
                            offsetX = 0f
                            offsetY = 0f

                            // 长按但没怎么动 → 当作长按操作
                            if (totalMove <= DRAG_THRESHOLD_PX) {
                                onLongClick?.invoke(item)
                            }
                        }
                    }
                    .fillMaxWidth(),
            ) {
                itemContent(item, dragging)
            }
        }
    }
}
