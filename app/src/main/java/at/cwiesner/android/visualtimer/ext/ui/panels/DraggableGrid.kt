package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
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
private const val DRAG_THRESHOLD_PX = 12f

/**
 * 可在主界面直接拖拽排序的网格。
 *
 * ## 手势：单层状态机
 *
 * 一次按下只走一条判定路径，不再叠两层 `pointerInput`
 * （之前外层 `detectDragGesturesAfterLongPress` 与内层 `detectTapGestures`
 * 抢同一次事件，长按拖动时好时坏、双击识别不稳）。
 *
 * | 操作 | 判定 |
 * |---|---|
 * | 长按拖拽 | 按住 ≥400ms 后移动，跨格即换位，松手落库 |
 * | 长按松手 | 按住 ≥400ms 未移动 → [onLongClick] |
 * | 单击 | 260ms 内无第二下 → [onClick] |
 * | 双击 | 260ms 内有第二下 → [onDoubleClick]（单击不再触发） |
 *
 * ## 单元格尺寸
 *
 * 拖拽换位靠「位移 ÷ 单元格尺寸」算跨了几格。之前把**固定 120dp** 当单元格
 * 高度，而真实卡片是 `宽度 ÷ 0.92` 的竖长形，列数一变就对不上 ——
 * 拖动要么不动、要么一次跳好几格。现在用 `BoxWithConstraints` 取真实宽度
 * 再换算高度。
 */
@Composable
fun <T> DraggableGrid(
    items: List<T>,
    key: (T) -> Any,
    columns: Int,
    modifier: Modifier = Modifier,
    gridState: LazyGridState,
    /** 卡片宽高比（宽/高）。计分卡是 0.92。 */
    cellAspect: Float = 0.92f,
    spacingDp: Int = 12,
    onMove: (from: Int, to: Int) -> Unit,
    onClick: (T) -> Unit,
    onDoubleClick: ((T) -> Unit)? = null,
    onLongClick: ((T) -> Unit)? = null,
    /** 拖拽松手时回调：新顺序要在这里落库。 */
    onDragEnd: (() -> Unit)? = null,
    itemContent: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var dragged by remember { mutableStateOf(false) }

    // 双击识别：记录上一次单击的条目与时间
    var lastTapIndex by remember { mutableIntStateOf(-1) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    // 回调捕获在 pointerInput 里，必须用 rememberUpdatedState 拿最新闭包，
    // 否则手势里用的永远是第一次组合时那份过期 lambda
    val curOnClick by rememberUpdatedState(onClick)
    val curOnDouble by rememberUpdatedState(onDoubleClick)
    val curOnLong by rememberUpdatedState(onLongClick)
    val curOnMove by rememberUpdatedState(onMove)
    val curOnDragEnd by rememberUpdatedState(onDragEnd)
    val curItemCount by rememberUpdatedState(items.size)

    val density = LocalDensity.current
    val cols = columns.coerceIn(1, 8)

    BoxWithConstraints {
        val spacingPx = with(density) { spacingDp.dp.toPx() }
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val cellWidthPx = ((totalWidthPx - spacingPx * (cols - 1)) / cols).coerceAtLeast(1f)
        val cellHeightPx = (cellWidthPx / cellAspect).coerceAtLeast(1f)

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(cols),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(spacingDp.dp),
            verticalArrangement = Arrangement.spacedBy(spacingDp.dp),
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
                                scaleX = 1.04f
                                scaleY = 1.04f
                                alpha = 0.92f
                                shadowElevation = 14f
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var pressMoved = 0f
                                var releasedEarly = false

                                // 阶段一：等长按成立（超时返回 null），
                                // 或者在这之前就被滚动/抬手打断
                                val hitLongPress = withTimeoutOrNull(LONG_PRESS_MS) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        pressMoved += change.positionChange().getDistance()
                                        if (pressMoved > DRAG_THRESHOLD_PX) {
                                            releasedEarly = true
                                            break
                                        }
                                        if (!change.pressed) {
                                            releasedEarly = true
                                            break
                                        }
                                        if (change.isConsumed) {
                                            releasedEarly = true
                                            break
                                        }
                                    }
                                    true
                                }

                                if (hitLongPress == null) {
                                    // ---- 长按成立 → 拖拽 ----
                                    draggingIndex = index
                                    offsetX = 0f
                                    offsetY = 0f
                                    dragged = false

                                    drag(down.id) { change: PointerInputChange ->
                                        change.consume()
                                        val d = change.positionChange()
                                        offsetX += d.x
                                        offsetY += d.y
                                        val squared = offsetX * offsetX + offsetY * offsetY
                                        if (squared > DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) {
                                            dragged = true
                                        }

                                        val colDelta = (offsetX / cellWidthPx).toInt()
                                        val rowDelta = (offsetY / cellHeightPx).toInt()
                                        val step = rowDelta * cols + colDelta
                                        if (step != 0 && draggingIndex >= 0) {
                                            val from = draggingIndex
                                            val to = (from + step)
                                                .coerceIn(0, curItemCount - 1)
                                            if (from != to) {
                                                curOnMove(from, to)
                                                draggingIndex = to
                                                // 扣掉已消费的位移，避免一次跳多格
                                                offsetX -= colDelta * cellWidthPx
                                                offsetY -= rowDelta * cellHeightPx
                                            }
                                        }
                                    }

                                    val moved = dragged
                                    draggingIndex = -1
                                    offsetX = 0f
                                    offsetY = 0f
                                    dragged = false

                                    if (moved) {
                                        // 拖拽松手 → 新顺序落库
                                        curOnDragEnd?.invoke()
                                    } else {
                                        // 长按但没拖动 → 长按操作（进编辑）
                                        curOnLong?.invoke(item)
                                    }
                                    return@awaitEachGesture
                                }

                                // ---- 阶段二：短按 ----
                                if (!releasedEarly) return@awaitEachGesture

                                val now = System.currentTimeMillis()
                                val isDouble = lastTapIndex == index &&
                                    (now - lastTapAt) <= DOUBLE_TAP_MS
                                if (isDouble) {
                                    lastTapIndex = -1
                                    lastTapAt = 0L
                                    curOnDouble?.invoke(item)
                                } else {
                                    lastTapIndex = index
                                    lastTapAt = now
                                    // 等到双击窗口过去仍没有第二下，才派发单击。
                                    // 之前先派发单击再判双击 —— 双击的第一下已经
                                    // 把计时器暂停了，看起来像「双击没反应」。
                                    // 这里也顺便让系统有机会把这次按下判成滚动。
                                    if (curOnDouble == null) {
                                        lastTapIndex = -1
                                        lastTapAt = 0L
                                        curOnClick(item)
                                    } else {
                                        kotlinx.coroutines.delay(DOUBLE_TAP_MS)
                                        if (lastTapIndex == index) {
                                            lastTapIndex = -1
                                            lastTapAt = 0L
                                            curOnClick(item)
                                        }
                                    }
                                }
                            }
                        }
                        .fillMaxWidth()
                        .aspectRatio(cellAspect),
                ) {
                    itemContent(item, dragging)
                }
            }
        }
    }
}
