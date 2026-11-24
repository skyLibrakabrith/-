package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 拖拽式调序。
 *
 * **不再弹一个独立的卡片列表**，而是直接在**真实的网格**上拖 ——
 * 用户看到的就是自己的计时器，长按任意一个进入拖拽，
 * 拖动时与经过的格子交换位置，松手即生效。
 *
 * 实现要点：
 * 1. `detectDragGesturesAfterLongPress` 区分「点击进全屏」和「拖动排序」
 * 2. 用 while 循环逐帧读取拖动偏移，跨过一个格子就交换一次
 * 3. 被拖的项用 `zIndex` 提到最上层 + 轻微放大，视觉上「浮起来」
 */
@Composable
fun DragReorderDialog(
    entries: List<PanelEntry>,
    columns: Int,
    onDismiss: () -> Unit,
    onSave: (List<PanelEntry>) -> Unit,
) {
    var list by remember(entries) { mutableStateOf(entries.sortedBy { it.order }) }
    val gridState = rememberLazyGridState()

    // 拖拽状态
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    // 格子尺寸（含间距），用于判断跨过几个位置
    val cellSizePx = with(density) { 120.dp.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("拖拽调整顺序") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "长按任意一项拖动，与其他项换位；松手生效。",
                    fontSize = 12.sp,
                    color = ExtColors.TextDim,
                )

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns.coerceIn(1, 4)),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(list) { index, entry ->
                        Box(
                            modifier = Modifier
                                .zIndex(if (index == draggingIndex) 1f else 0f)
                                .graphicsLayer {
                                    if (index == draggingIndex) {
                                        translationX = dragOffsetX
                                        translationY = dragOffsetY
                                        scaleX = 1.08f
                                        scaleY = 1.08f
                                        alpha = 0.92f
                                    }
                                }
                                .pointerInput(index, list.size, columns) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragOffsetX = 0f
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffsetX += amount.x
                                            dragOffsetY += amount.y

                                            // 判断是否跨过了一个格子：横向按列数、纵向按行
                                            val movedCols = (dragOffsetX / cellSizePx).toInt()
                                            val movedRows = (dragOffsetY / cellSizePx).toInt()
                                            val delta = movedRows * columns + movedCols
                                            if (delta != 0) {
                                                val from = draggingIndex
                                                val to = (from + delta).coerceIn(
                                                    0,
                                                    list.size - 1,
                                                )
                                                if (from != to) {
                                                    val mutable = list.toMutableList()
                                                    val item = mutable.removeAt(from)
                                                    mutable.add(to, item)
                                                    list = mutable
                                                    draggingIndex = to
                                                    // 交换后把偏移量扣掉，避免一次性跳多格
                                                    dragOffsetY -= delta / columns * cellSizePx
                                                    dragOffsetX -= (delta % columns) * cellSizePx
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            draggingIndex = -1
                                            dragOffsetX = 0f
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = -1
                                            dragOffsetX = 0f
                                            dragOffsetY = 0f
                                        },
                                    )
                                }
                                .fillMaxWidth(),
                        ) {
                            EntryTile(
                                entry = entry,
                                index = index,
                                dragging = index == draggingIndex,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list) }) { Text("保存顺序") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 拖拽时的条目外观：简化的小卡片，只显示序号与名称。 */
@Composable
private fun EntryTile(
    entry: PanelEntry,
    index: Int,
    dragging: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (dragging) {
                    shadowElevation = 12f
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (dragging) ExtColors.PrimaryDim else ExtColors.Surface)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "#${index + 1}",
                fontSize = 11.sp,
                color = ExtColors.Primary,
            )
            Text(
                text = entry.name,
                fontSize = 12.sp,
                color = ExtColors.TextPrimary,
                maxLines = 2,
            )
            Text(
                text = entry.kind.label,
                fontSize = 10.sp,
                color = ExtColors.TextDim,
            )
        }
    }
}
