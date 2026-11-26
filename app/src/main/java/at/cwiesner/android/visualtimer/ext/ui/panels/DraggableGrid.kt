package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex


/**
 * 可在主界面直接拖拽排序的网格。
 *
 * ## 为什么改成这个结构
 *
 * 之前是手写手势状态机：`awaitEachGesture` + `withTimeoutOrNull` 自己判长按，
 * 再 `drag()` 收位移，格数按「位移 ÷ 单元格尺寸」估算。
 * 三处都不成立，所以单击、双击、长按、拖拽**全都不工作**：
 *
 * 1. `withTimeoutOrNull` 在手势域里跑循环 `awaitPointerEvent`，
 *    超时到点后协程被取消，后续 `drag()` 拿不到这次按压，
 *    数据模型和框架的指针状态对不上。
 * 2. 换位靠估算跨了几格，格宽/间距/内边距任何一项不精确就错位。
 * 3. 单击被延迟派发，和拖拽分支共享状态，互相打架。
 *
 * 现在拆成两个互不干扰的层，这是社区通行做法
 * （johanneskoehler/compose-reorderable、Calvin-LL/Reorderable 都是这个结构）：
 *
 * | 层 | 载体 | 职责 |
 * |---|---|---|
 * | 排序 | 网格层 `detectDragGesturesAfterLongPress` | 长按进入拖拽，用**几何命中**换位 |
 * | 点按 | 每个卡片 `combinedClickable` | 单击 / 双击 / 长按，与拖拽无关 |
 *
 * 关键点：排序层**不消费**按下事件，所以卡片自己收到的点按序列是完整的；
 * 长按既能让卡片触发 `onLongClick`，也能让网格层开始拖拽 —— 拖动期间
 * 卡片不会收到点击回调（位置已经变了，用户本来也不在点同一个东西）。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /**
     * 是否由**网格层**统一挂点按手势。
     *
     * - `true`（默认）：网格层给整个格子挂 `combinedClickable`。
     *   适合"整张卡片都可以点"的场景（序列卡、组卡、列表视图）。
     * - `false`：网格层**不挂**点按手势，由 `itemContent` 自己在卡片内部
     *   某块区域里挂。适合"只有卡片的一部分可点"的场景。
     *
     * ## 为什么要这个开关
     *
     * 计时器卡片要求：**只有圆盘可点**（暂停/继续、双击进全屏），
     * 而左上角的图钉另有用途（切换固定）。如果网格层给整卡挂点击，
     * 图钉就落在点击区里 —— 点图钉会同时触发暂停，两个动作打架。
     *
     * 让卡片自己挂的好处是：可以把点击区精确限定在圆盘那个 Box 上，
     * 图钉在圆盘之外、完全不重叠。而拖拽仍然由网格层全区域接管，
     * 因为 `detectDragGesturesAfterLongPress` 本来就不消费按下事件
     * （见本文件 LazyVerticalGrid 那段注释）。
     */
    gridHandlesTaps: Boolean = true,
    /** 拖拽松手时回调：新顺序要在这里落库。 */
    onDragEnd: (() -> Unit)? = null,
    itemContent: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val curOnMove by rememberUpdatedState(onMove)
    val curOnDragEnd by rememberUpdatedState(onDragEnd)
    val curOnClick by rememberUpdatedState(onClick)
    val curOnDouble by rememberUpdatedState(onDoubleClick)
    val curOnLong by rememberUpdatedState(onLongClick)

    val density = LocalDensity.current
    val cols = columns.coerceIn(1, 8)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(cols),
        modifier = modifier
            // ---- 排序层：长按后拖拽 ----
            //
            // 注意这里**没有** awaitFirstDown(requireUnconsumed = true)，
            // 也不 consume 按下事件：它只关心「长按之后的位移」，
            // 之前的点按序列完全留给卡片自己处理。
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset ->
                        // 用落点坐标反查是哪一个格子 —— 不估算、不换算，
                        // 直接问「哪个可见项包含这个点」
                        val hit = hitTestItem(gridState, startOffset)
                        if (hit == null) {
                            draggingIndex = -1
                            return@detectDragGesturesAfterLongPress
                        }
                        draggingIndex = hit
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        if (draggingIndex < 0) return@detectDragGesturesAfterLongPress
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y

                        // 被拖卡片的当前**真实**矩形（原位置 + 累计位移），
                        // 拿它和其他可见项的真实矩形做几何命中。
                        // 只要拖动中的矩形覆盖了某个格子的中心，就换位。
                        val dragged = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == draggingIndex } ?: return@detectDragGesturesAfterLongPress
                        val rect = Rect(
                            offset = Offset(
                                dragged.offset.x + offsetX,
                                dragged.offset.y + offsetY,
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                dragged.size.width.toFloat(),
                                dragged.size.height.toFloat(),
                            ),
                        )
                        val target = gridState.layoutInfo.visibleItemsInfo
                            .filter { it.index != draggingIndex }
                            .firstOrNull { info ->
                                rect.contains(
                                    Offset(
                                        info.offset.x + info.size.width / 2f,
                                        info.offset.y + info.size.height / 2f,
                                    ),
                                )
                            }
                        if (target != null) {
                            curOnMove(draggingIndex, target.index)
                            // 换位后重置累计位移：后面的位移重新从新位置算，
                            // 否则会连续触发多次、一次跳好几格
                            offsetX = 0f
                            offsetY = 0f
                        }
                    },
                    onDragEnd = {
                        draggingIndex = -1
                        offsetX = 0f
                        offsetY = 0f
                        curOnDragEnd?.invoke()
                    },
                    onDragCancel = {
                        draggingIndex = -1
                        offsetX = 0f
                        offsetY = 0f
                    },
                )
            },
        horizontalArrangement = Arrangement.spacedBy(spacingDp.dp),
        verticalArrangement = Arrangement.spacedBy(spacingDp.dp),
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            val dragging = index == draggingIndex
            Box(
                modifier = Modifier
                    // ---- 点按层：单击 / 双击 / 长按 ----
                    //
                    // 单击直接进全屏的真正原因，是卡片内部还套了一层
                    // combinedClickable（TimerCell/SequenceCell/TimerGroupCell 自己
                    // 各挂了一个）：内层先消费掉第一下，外层看到的事件序列残缺，
                    // 双击计数直接错乱。那一层已经删掉，现在全区域只有这一个。
                    //
                    // 注意本版本 Compose 的 combinedClickable **没有**
                    // hasDoubleClick / doubleClickTimeoutMillis 参数
                    // （只有 enabled/onClickLabel/role/onLongClickLabel/
                    //  onLongClick/onDoubleClick/onClick），
                    // 双击窗口用框架默认值 ~300ms，不能在这里调。
                    //
                    // gridHandlesTaps = false 时这一层不挂：
                    // 计时器卡片要把点按收窄到圆盘，由卡片自己在圆盘上挂，
                    // 这样左上角图钉（切换固定）不会和暂停/继续打架。
                    .then(
                        if (gridHandlesTaps) {
                            Modifier.combinedClickable(
                                onClick = { curOnClick(item) },
                                onDoubleClick = curOnDouble?.let { d -> { d(item) } },
                                onLongClick = curOnLong?.let { l -> { l(item) } },
                            )
                        } else {
                            Modifier
                        },
                    )
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
                    .fillMaxWidth()
                    .aspectRatio(cellAspect),
            ) {
                itemContent(item, dragging)
            }
        }
    }
}

/**
 * 用坐标反查命中哪个可见项。
 *
 * LazyGrid 的 `visibleItemsInfo` 已经带了每项的 `offset` 与 `size`，
 * 直接做矩形包含判断即可 —— 比按「位移 ÷ 边长」估算稳妥得多
 * （不依赖单元格是否正方形、间距是多少、有没有内边距）。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun hitTestItem(state: LazyGridState, position: Offset): Int? =
    state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        Rect(
            offset = Offset(info.offset.x.toFloat(), info.offset.y.toFloat()),
            size = androidx.compose.ui.geometry.Size(
                info.size.width.toFloat(),
                info.size.height.toFloat(),
            ),
        ).contains(position)
    }?.index
