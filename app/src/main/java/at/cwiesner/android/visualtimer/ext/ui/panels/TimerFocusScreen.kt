package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

        // 底部有 ±5 按钮行时，主盘缩一点。
        //
        // **正计时没有"时长"这个概念**（它从 0 往上跑，没有终点），
        // 所以既不该出现 ±5 按钮行，也不该允许拖圆盘改时长 ——
        // 之前这两样对正计时都还开着，用户拖一下就把「已计 12:30」改成了别的数字。
        val miniWheelVisible = !locked && !running && !isStopwatchTimer
        Box(
            modifier = Modifier
                .fillMaxWidth(
                    when {
                        landscape -> 0.62f
                        // 0.62 是试出来的：0.80 / 0.72 时 ±5 按钮行下面的「重置/开始」
                        // 会被底栏裁掉（真机逐档试过）。竖屏本来也放不下
                        // "满宽大盘 + 按钮行 + 操作行"三件套。
                        miniWheelVisible -> 0.62f
                        // 没有按钮行时主盘可以占满宽度 —— 但**满宽会让 aspectRatio(1f)
                        // 给出 1080px 的高度**，把后面的「已计」和操作行挤到只剩 26dp，
                        // 结果「重置 / 开始」的标签被压成 0 高度、看起来像没有字
                        // （正计时页真机上确认过）。所以留一档 0.78。
                        else -> 0.78f
                    },
                )
                .aspectRatio(1f)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isStopwatch = timer.kind == at.cwiesner.android.visualtimer.ext.data.TimerKind.Stopwatch
            PieTimerView(
                totalMillis = if (isStopwatch) 0L else total,
                remainingMillis = remain,
                running = running,
                // 正计时不给拖拽调时长（见上面 miniWheelVisible 的注释）
                onDurationChange = if (running || locked || isStopwatchTimer) null else { d -> draft = d },
                onDurationCommit = if (running || locked || isStopwatchTimer) null else { d ->
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

        // ---- 小轮盘：全屏页的精细调时长入口 ----
        //
        // 为什么大轮盘拖起来不够：全屏那个盘占满宽度，拖动 1 分钟只移动几个像素，
        // 想从 15 分调到 26 分得拖很长的弧；反过来想微调 30 秒又容易拖过头。
        // 小轮盘直径小、同样的角度对应同样的分钟数但物理距离短，适合精调；
        // 两侧的 ±按钮则用来一次跳一档，不用拖。
        //
        // 仅在**未锁定且未运行**时可用，与主盘保持一致：
        // 运行中改时长语义不清（是改本轮还是下一轮？），DDL 的时长由截止时刻决定。
        // ---- ±5 微调 ----
        //
        // 只有两个按钮，**没有小表盘**：全屏大表盘本身就能拖拽调时长，
        // 再放一个缩小版圆盘是重复入口，只占地方还容易让人以为它俩作用不同。
        // （之前确实加过一个小轮盘，已按用户意见去掉。）
        //
        // ±5 保留是因为「按档跳」和大表盘拖拽是两种不同的操作习惯：
        // 15 分 → 45 分，拖半个圆很难精准，点 6 次 +5 更省事。
        //
        // 仅在**未锁定且未运行**时可用，与主盘保持一致：
        // 运行中改时长语义不清（是改本轮还是下一轮？），DDL 的时长由截止时刻决定。
        if (miniWheelVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniAdjustButton("−5") {
                    val next = (draft - 5 * 60_000L).coerceAtLeast(60_000L)
                    draft = next
                    onDurationCommit(next)
                }
                Spacer(modifier = Modifier.size(12.dp))
                MiniAdjustButton("+5") {
                    val next = (draft + 5 * 60_000L).coerceAtMost(60 * 60_000L)
                    draft = next
                    onDurationCommit(next)
                }
            }
        }

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

/**
 * 小轮盘两侧的 ±按钮。窄、竖排居中，不抢主盘视觉。
 *
 * 单独一个组件而不是复用 [PillButton]：PillButton 是"整行一半宽"的主操作按钮
 * （重置 / 暂停），这里只要一个 48dp 的小方块，用 PillButton 会把行撑开。
 */
@Composable
private fun RowScope.MiniAdjustButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ExtColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = ExtColors.Primary)
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
