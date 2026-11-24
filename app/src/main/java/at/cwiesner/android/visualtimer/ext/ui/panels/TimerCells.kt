package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.alertNameOf
import at.cwiesner.android.visualtimer.ext.ui.common.clockOf
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView

/**
 * 网格单元：一个大圆盘 + 名称 + 状态行。
 *
 * 状态行左侧显示「结束时刻」或「已计」，右侧在暂停 / 超时时出现「重置」小按钮。
 * 两者用 Row 并排，不再重叠。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimerCell(
    timer: TimerItem,
    runtime: TimerRuntime?,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = runtime?.running == true
    val remain = if (running) (runtime?.remainingMillis ?: timer.durationMillis) else timer.durationMillis
    val total = if (running) (runtime?.totalMillis ?: timer.durationMillis) else timer.durationMillis
    val frac = if (total <= 0L) 0f else (remain.toFloat() / total).coerceIn(0f, 1f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    PieTimerView(
                        totalMillis = total,
                        remainingMillis = remain,
                        running = running,
                        onDurationChange = null,
                        modifier = Modifier.fillMaxSize(),
                        maxMinutes = 60,
                        centerFontSize = 20f,
                        thresholdMinutes = thresholdMinutes,
                        thresholdColors = thresholdColors,
                        colorMode = colorMode,
                        intervalMinutes = intervalMinutes,
                        fractionDivisions = fractionDivisions,
                        overdueMillis = runtime?.overdueMillis ?: 0L,
                        fillParent = true,
                    )
                }
                Text(
                    text = timer.label ?: "未命名",
                    fontSize = 11.sp,
                    color = ExtColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            timer.kind == TimerKind.Stopwatch ->
                                "已计 " + DeadlineCalculator.formatRemaining(remain)
                            (runtime?.overdueMillis ?: 0L) > 0L ->
                                "超时 " + DeadlineCalculator.formatRemaining(
                                    runtime!!.overdueMillis,
                                )
                            else -> clockOf(System.currentTimeMillis() + remain) + " 结束"
                        },
                        fontSize = 10.sp,
                        color = if ((runtime?.overdueMillis ?: 0L) > 0L) ExtColors.Red
                        else ExtColors.TextDim,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    // 仅「暂停 / 超时」时出现；运行时占位保持宽度稳定
                    if (runtime != null && (!running || runtime.isOverdue)) {
                        Box(
                            modifier = Modifier
                                .height(20.dp)
                                .widthIn(min = 34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ExtColors.Surface)
                                .clickable(onClick = onReset)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "重置",
                                fontSize = 9.sp,
                                lineHeight = 9.sp,
                                color = ExtColors.Primary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = ExtColors.OutlineStrong,
                    modifier = Modifier.size(16.dp),
                )
            }
            // 运行状态点
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            !running -> ExtColors.TextDim
                            frac > 0.5f -> ExtColors.Primary
                            frac > 0.2f -> ExtColors.Amber
                            else -> ExtColors.Red
                        },
                    ),
            )
        }
    }
}

/** 列表模式：多选 + 批量操作。长按某行进入编辑，右侧显示提醒名称。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimerList(
    timers: List<TimerItem>,
    runtimeById: Map<Long, TimerRuntime>,
    selectedIds: Set<Long>,
    alertProfiles: List<at.cwiesner.android.visualtimer.ext.data.AlertProfile>,
    onToggle: (Long) -> Unit,
    onEdit: (TimerItem) -> Unit,
    onChangeAlert: (TimerItem) -> Unit,
    onBatchDelete: () -> Unit,
    onBatchStart: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val allSelected = timers.isNotEmpty() && selectedIds.size == timers.size
            Text(
                if (selectedIds.isEmpty()) "共 ${timers.size} 个"
                else "已选 ${selectedIds.size}/${timers.size}",
                fontSize = 13.sp,
                color = ExtColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { if (allSelected) onClearSelection() else onSelectAll() },
            ) { Text(if (allSelected) "取消全选" else "全选") }
            if (selectedIds.isNotEmpty()) {
                TextButton(onClick = onBatchStart) { Text("开始") }
                TextButton(onClick = onBatchDelete) { Text("删除") }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(timers, key = { it.id }) { timer ->
                val rt = runtimeById[timer.id]
                val running = rt?.running == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ExtColors.Surface)
                        .combinedClickable(
                            onClick = { onToggle(timer.id) },
                            onLongClick = { onEdit(timer) },
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(
                        checked = timer.id in selectedIds,
                        onCheckedChange = { onToggle(timer.id) },
                    )
                    PieTimerView(
                        totalMillis = rt?.totalMillis ?: timer.durationMillis,
                        remainingMillis = rt?.remainingMillis ?: timer.durationMillis,
                        running = running,
                        onDurationChange = null,
                        modifier = Modifier.width(52.dp),
                        maxMinutes = 60,
                        centerFontSize = 14f,
                        innerDial = false,
                        colorMode = ColorMode.Threshold,
                        intervalMinutes = 5L,
                        fractionDivisions = 4,
                        thresholdMinutes = emptyList(),
                        thresholdColors = emptyList(),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            timer.label ?: "未命名",
                            fontSize = 14.sp,
                            color = ExtColors.TextPrimary,
                        )
                        Text(
                            (if (running) "运行中 · " else "已停止 · ") +
                                DeadlineCalculator.formatRemaining(
                                    rt?.remainingMillis ?: timer.durationMillis,
                                ),
                            fontSize = 12.sp,
                            color = ExtColors.TextDim,
                        )
                    }
                    // 直接显示提醒名称，点一下换配置
                    Text(
                        text = alertNameOf(timer.alertProfileId, alertProfiles),
                        fontSize = 10.sp,
                        color = ExtColors.Amber,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ExtColors.PureBlack)
                            .clickable { onChangeAlert(timer) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 网格上方的快捷操作按钮。 */
@Composable
internal fun QuickAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ExtColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, color = ExtColors.Primary)
    }
}

/** 空状态提示。 */
@Composable
internal fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = ExtColors.TextDim,
            modifier = Modifier.padding(24.dp),
        )
    }
}
