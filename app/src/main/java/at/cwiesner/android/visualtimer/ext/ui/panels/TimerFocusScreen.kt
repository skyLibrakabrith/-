package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView

/** 时刻显示 HH:mm。 */
private fun clockOf(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

/**
 * 单计时器整页视图。
 *
 * 交互分区：
 * - **外圈**：拖动调整时长（仅未运行时，30 秒步进）
 * - **内圈**：点一下开始 / 暂停 / 超时后重置
 *
 * 中央控件与 `PieTimerView` 的内盘**同尺寸同心**（半径 = 饼半径 × 0.46），
 * 这样视觉上就是「内盘本身可点」，而不是叠了两个大小不一的圆。
 *
 * 拖动期间中央控件与内盘一起隐藏 —— 否则每帧重绘会让它闪烁。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimerFocusScreen(
    timer: TimerItem,
    runtime: TimerRuntime?,
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    /** DDL 锁定时长：不显示拖拽改时长的入口。 */
    locked: Boolean = false,
    onDurationCommit: (Long) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    /** 长按顶部标题重命名。DDL 也允许改名 —— `locked` 锁的是时长，不是名字。 */
    onRename: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = runtime?.running == true
    // 用 isOverdue 而不是 overdueMillis > 0：暂停后不应再显示成「超时中」，
    // 否则中央按钮永远提示「点击重置」
    val overdue = runtime?.isOverdue == true
    var draft by remember(timer.id) { mutableLongStateOf(timer.durationMillis) }
    var dragging by remember { mutableStateOf(false) }
    // 长按标题重命名的临时状态
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember(timer.id) { mutableStateOf(timer.label ?: "") }

    // 暂停时同样要停在暂停位置（之前一暂停就回落到 draft = 原始时长，
    // 视觉上和重置没区别）。只有「从未开始」或「正在拖动改时长」才用 draft。
    val remain = when {
        dragging -> draft
        runtime != null -> runtime.remainingMillis
        else -> draft
    }
    val total = when {
        dragging -> draft
        runtime != null -> runtime.totalMillis
        else -> draft
    }
    val landscape = at.cwiesner.android.visualtimer.ext.ui.isLandscape()

    // 中央控件只在「运行中 / 超时」时出现；静止状态由外圈拖动设定时长
    val isStopwatchTimer = timer.kind == at.cwiesner.android.visualtimer.ext.data.TimerKind.Stopwatch
    val showInner = running || overdue || isStopwatchTimer

    Column(
        modifier = modifier
            .fillMaxSize()
            // 点空白处关闭全屏（返回卡片视图）。
            // 放在 Column 最外层，内部按钮/轮盘会先行消费点击，
            // 只有真正落在空白区域的点击才会传到这里。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 长按标题重命名。
            //
            // 为什么放在这里而不是卡片上：卡片上的长按已经被拖拽排序独占
            // （两者抢同一个手势，这一点踩过坑）。全屏视图里没有拖拽，
            // 长按是空闲的，正好给改名用。
            if (renaming) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                    modifier = Modifier.weight(1f),
                    label = { Text("名称", fontSize = 11.sp) },
                )
                IconButton(
                    onClick = {
                        // 允许留空 —— 空名会回退成「未命名」，比强行塞一个占位名好
                        onRename(renameText)
                        renaming = false
                    },
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "确定", tint = ExtColors.Primary)
                }
                IconButton(onClick = { renaming = false }) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", tint = ExtColors.TextSecondary)
                }
            } else {
                Text(
                    text = timer.label ?: "未命名",
                    fontSize = 16.sp,
                    color = ExtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                renameText = timer.label ?: ""
                                renaming = true
                            },
                        ),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = ExtColors.TextSecondary,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(if (landscape) 0.62f else 1f)
                .aspectRatio(1f)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isStopwatch = timer.kind == at.cwiesner.android.visualtimer.ext.data.TimerKind.Stopwatch
            PieTimerView(
                totalMillis = if (isStopwatch) 0L else total,
                remainingMillis = remain,
                running = running,
                onDurationChange = if (running || locked) null else { d -> draft = d },
                onDurationCommit = if (running || locked) null else { d ->
                    draft = d
                    onDurationCommit(d)
                },
                modifier = Modifier.fillMaxSize(),
                maxMinutes = 60,
                stepMinutes = 0.5f,
                centerFontSize = 56f,
                showCenterText = false,
                innerDial = !showInner,
                innerDialVisible = !dragging,
                onDragStateChange = { dragging = it },
                colorMode = colorMode,
                intervalMinutes = intervalMinutes,
                fractionDivisions = fractionDivisions,
                thresholdMinutes = thresholdMinutes,
                thresholdColors = thresholdColors,
                overdueMillis = runtime?.overdueMillis ?: 0L,
                fillParent = true,
            )

            // 内圈：与 PieTimerView 的内盘同尺寸（半径 = 饼半径 × 0.46）。
            // DDL 不显示 —— 它不能暂停也不能重置，中央不该有可点的东西。
            if (showInner && !dragging && !locked) {
                InnerDialControl(
                    overdue = overdue,
                    remainText = if (overdue) {
                        "+" + DeadlineCalculator.formatRemaining(runtime?.overdueMillis ?: 0L)
                    } else {
                        DeadlineCalculator.formatRemaining(remain)
                    },
                    onClick = {
                        when {
                            overdue -> onReset()
                            running -> onPause()
                            else -> onStart()
                        }
                    },
                )
            }
        }

        Text(
            text = when {
                timer.kind == at.cwiesner.android.visualtimer.ext.data.TimerKind.Stopwatch ->
                    "已计 " + DeadlineCalculator.formatRemaining(remain)
                overdue -> "已超时 " + DeadlineCalculator.formatRemaining(runtime?.overdueMillis ?: 0L)
                running -> "剩余 " + DeadlineCalculator.formatRemaining(remain) +
                    " · " + clockOf(System.currentTimeMillis() + remain) + " 结束"
                else -> "时长 " + DeadlineCalculator.formatRemaining(draft) +
                    " · " + clockOf(System.currentTimeMillis() + draft) + " 结束"
            },
            fontSize = 13.sp,
            color = if (overdue) ExtColors.Red else ExtColors.TextDim,
        )

        // DDL 没有重置/暂停，整排按钮都不出现
        if (!locked) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PillButton(
                    text = "重置",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onReset,
                )
                PillButton(
                    text = if (running) "暂停" else "开始",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { if (running) onPause() else onStart() },
                )
            }
        }
    }
}

/**
 * 内圈控件。
 *
 * 尺寸与 `PieTimerView` 的内盘一致：饼半径 42% 的直径，内盘半径是饼半径的 0.46，
 * 即整块盘面直径的 `0.42 * 0.46 * 2 ≈ 38.6%`。用 `fillMaxWidth(0.386f)` 对齐，
 * 避免出现「点了没用」或「盖住外圈」的问题。
 */
@Composable
private fun InnerDialControl(
    overdue: Boolean,
    remainText: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.386f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(
                if (overdue) ExtColors.Red.copy(alpha = 0.22f)
                else ExtColors.PureBlack.copy(alpha = 0.9f),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = remainText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                color = if (overdue) ExtColors.Red else ExtColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) ExtColors.Primary else ExtColors.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) Color(0xFF001639) else ExtColors.TextPrimary,
        )
    }
}
