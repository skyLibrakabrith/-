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
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.StepSequence
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
    /** DDL：时间由截止时刻决定，不提供重置入口。 */
    locked: Boolean = false,
    /**
     * 圆盘上的点按。null = 由网格层接管整卡点击（默认）。
     *
     * 非 null 时（卡片视图），点按**只**在圆盘上生效：
     * - 单击 → 暂停 / 继续
     * - 双击 → 进全屏
     *
     * 为什么要把点击收窄到圆盘：左上角有个图钉按钮用来切换「固定」，
     * 如果整卡都能点，点图钉会顺带触发暂停，两个动作打架。
     * 圆盘与图钉在布局上完全不重叠，收窄后互不干扰。
     *
     * 长按**不**在这里处理：长按要留给网格层的拖拽排序。
     */
    onDialTap: (() -> Unit)? = null,
    onDialDoubleTap: (() -> Unit)? = null,
    /** 切换「固定」。null = 不显示图钉（列表视图不需要）。 */
    onTogglePin: (() -> Unit)? = null,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = runtime?.running == true
    // 只要存在运行时状态（运行中**或暂停**），就用它的剩余量。
    // 之前写的是 if (running) runtime else timer.durationMillis ——
    // 一暂停就回落到原始时长，盘面立刻显示满格，看着就像被重置了。
    val remain = runtime?.remainingMillis ?: timer.durationMillis
    val total = runtime?.totalMillis ?: timer.durationMillis
    val frac = if (total <= 0L) 0f else (remain.toFloat() / total).coerceIn(0f, 1f)

    // 卡片本身**不再**挂 combinedClickable。
    //
    // 之前这里和 DraggableGrid 的卡片外层各挂了一个点击层：
    // 内层 combinedClickable 先消费掉「单击」，外层判定成第一次点按，
    // 用户的第二下（本意是双击）就被内层当成了「又一次单击」——
    // 表现是「点一下直接进全屏」。
    // 点按统一交给网格层，卡片只负责画和少数子控件（重置/删除）。
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f),
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
                        .weight(1f)
                        // 点击区就是这个 Box 的范围。盘面本身是居中的正方形，
                        // 所以这里也约束成正方形（aspectRatio(1f) + 居中），
                        // 让"可点区域"和"看得见的圆盘"尽量重合 ——
                        // 否则会出现"点在圆盘旁边的空白处也算点击"的错觉。
                        //
                        // 用 pointerInput + detectTapGestures 而不是 combinedClickable：
                        //   ① 不需要长按（长按要留给网格层拖拽），combinedClickable
                        //      的 onLongClick 传 null 时理论上也行，但显式只用
                        //      单击/双击更清楚表达意图；
                        //   ② 这个版本 Compose 的 combinedClickable 没有
                        //      doubleClickTimeoutMillis，detectTapGestures 同样没有，
                        //      但语义边界更明确。
                        .then(
                            // 只要**单击或双击有一个**可用，就要挂手势。
                            //
                            // 之前这里只判断 `onDialTap != null`，导致 DDL 卡片
                            // （它没有暂停语义，所以 onDialTap 传的是 null）
                            // 连双击也一起被禁掉了 —— 表现为"DDL 双击进不了全屏"。
                            // 双击和单击是两件事，不能用一个条件绑死。
                            if (onDialTap != null || onDialDoubleTap != null) {
                                Modifier.pointerInput(timer.id) {
                                    detectTapGestures(
                                        onTap = onDialTap?.let { { it() } },
                                        onDoubleTap = onDialDoubleTap?.let { { it() } },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
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
                    // 出现条件分两种情况，**不能**用一个 `!locked` 一刀切：
                    //
                    //   ① 暂停后需重置 —— 只有普通计时器会有这种状态。
                    //      DDL 没有暂停语义（时长由截止时刻决定），所以对它隐藏是对的。
                    //   ② **超时后需重置 —— 所有类型都需要，DDL 也一样。**
                    //
                    // 之前写成 `if (!locked && ...)`，等于把整个重置入口对 DDL 关掉了。
                    // 结果 DDL 到点后弹着提醒、界面里却没有任何办法把它按下去 ——
                    // 用户只能等提醒自己消失，或者从悬浮窗上操作。
                    val needsReset = runtime != null &&
                        (runtime.isOverdue || (!running && !locked))
                    if (needsReset) {
                        // 未固定 = 一次性计时，按钮语义是「结束」（归档后从面板移除）；
                        // 固定 = 还要继续用，按钮语义是「重置」（回到满格重新来）。
                        //
                        // 为什么不做成两个按钮：两个小按钮挤在 20dp 高的行里会点不准，
                        // 而且同一时刻只有一种是用户真正想做的（由 pinned 唯一决定）。
                        val endMode = !timer.pinned
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
                                text = if (endMode) "结束" else "重置",
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
            // 固定开关（原来是"运行状态点"）。
            //
            // 那个小圆点原先用颜色表达"剩余紧迫度"，但这份信息盘面本来就有
            // （整个圆盘的填充色就是按剩余比例变的），等于重复。
            // 把它改成一个**有实际功能**的控件更划算：切换是否固定在面板上。
            //
            // 未固定（pinned = false）的计时器到点后会自动从面板移除，
            // 只留一条记录在归档页。适合"一次性"的计时（比如"泡面 3 分钟"）。
            //
            // 位置在圆盘之外（左上角），与圆盘的点击区完全不重叠 ——
            // 这正是把点击收窄到圆盘的目的。
            if (onTogglePin != null) {
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(32.dp),
                ) {
                    Icon(
                        imageVector = if (timer.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (timer.pinned) "取消固定" else "固定到面板",
                        tint = if (timer.pinned) ExtColors.Primary else ExtColors.TextDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 列表模式：多选 + 批量操作。右侧显示提醒名称。
 *
 * 手势分配（与卡片视图刻意不同）：
 * - **单击** → 编辑该计时器
 * - **长按** → 切换选中（配合顶部的批量开始/删除）
 *
 * 为什么这样分：卡片视图里长按已经被**拖拽排序**完全占用，编辑入口只能另找地方；
 * 列表视图没有拖拽，正好承接编辑功能。两边不重叠，用户不用记两套规则冲突。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimerList(
    timers: List<TimerItem>,
    runtimeById: Map<Long, TimerRuntime>,
    selectedIds: Set<Long>,
    alertProfiles: List<at.cwiesner.android.visualtimer.ext.data.AlertProfile>,
    /**
     * 变色配置，必须来自 settings。
     *
     * 之前这里的小圆盘写死了 `ColorMode.Fraction` + `fractionDivisions = 4`，
     * 于是在设置里改「表盘比例」的份数，列表视图里的小圆盘纹丝不动 ——
     * 用户看到的就是「设置没生效」。
     */
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<androidx.compose.ui.graphics.Color>,
    onToggle: (Long) -> Unit,
    onEdit: (TimerItem) -> Unit,
    onChangeAlert: (TimerItem) -> Unit,
    onBatchDelete: () -> Unit,
    onBatchStart: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    /**
     * 「拆解」按钮。
     *
     * 语义由调用方决定：
     * - 没有选中任何计时器 -> 新建一个序列去拆解
     * - 选中了**一个** -> 把这个计时器拆成序列（原计时器被替换）
     *
     * 为什么限制只选一个：拆解的产物是"一个序列"，
     * 同时拆两个会得到两个序列，那就该分两次点 ——
     * 一次一个让结果和意图一一对应，也避免"批量拆解"这种没人想得清楚的交互。
     */
    onSplit: (TimerItem?) -> Unit,
    /**
     * 「合并为序列」：把选中的计时器 / 序列合成一个新序列。
     *
     * 选中 ≥2 项时才出现（见工具栏那段注释）。
     */
    onMergeToSequence: () -> Unit = {},
    /**
     * 当前面板里的序列，显示在列表**末尾**。
     *
     * 为什么列表视图原来没有序列：`TimerList` 只接收 `timers`。
     * 用户明确反馈「列表视图里没有序列」—— 卡片视图有、列表视图没有，
     * 同一个面板切换视图就看到不同内容，这是 bug 不是设计。
     *
     * 序列行与计时器行的区别：
     * - 单选/多选不参与（序列的批量开始/删除语义与计时器不同，不混在一起）
     * - 点击直接进序列全屏（这是列表里最自然的"打开"动作）
     */
    sequences: List<StepSequence> = emptyList(),
    /** 每个序列的阶段数，用于在行里显示「N 阶段」。 */
    stepsBySequence: Map<Long, List<at.cwiesner.android.visualtimer.ext.data.Step>> = emptyMap(),
    /** 点序列行 → 进序列全屏。 */
    onOpenSequence: (StepSequence) -> Unit = {},
    /**
     * 长按序列行 → 打开序列编辑器。
     *
     * 为什么网格里没有这个入口：网格层长按被拖拽排序占用
     * （`detectDragGesturesAfterLongPress`）。列表视图不参与拖拽，
     * 长按是空的，正好给"编辑"用 —— 与计时器行的"长按选中"不冲突
     * （序列不参与多选，见上方注释）。
     */
    onEditSequence: (StepSequence) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // allSelected 的判据用「当前面板的 id 是否全部被选中」，
            // 而不是 size 相等 —— selectedIds 里可能残留别的面板的 id
            // （切面板时清空了，但历史遗留/并发更新仍可能不干净）。
            // 用 id 集合包含判断，切面板后不会出现"明明全选按钮写着取消全选，实际只选了一部分"。
            val allSelected = timers.isNotEmpty() && timers.all { it.id in selectedIds }
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

            // 拆解：只在选中 0 个或 1 个时可用。
            // 选了多个时不出现 —— 见 onSplit 的注释（拆解只能针对单件事）。
            if (selectedIds.size <= 1) {
                val target = if (selectedIds.size == 1) {
                    timers.firstOrNull { it.id == selectedIds.first() }
                } else {
                    null
                }
                TextButton(onClick = { onSplit(target) }) {
                    Text(if (target != null) "✨ 拆解这个" else "✨ 拆解")
                }
            }

            // 「合并为序列」：选中 ≥2 项时出现。
            //
            // 与「拆解」方向相反：
            //   拆解 = 一个计时器 → 多个阶段
            //   合并 = 多个计时器/序列 → 一个序列的多阶段
            // 两者互斥（一个要 ≤1 个选中，一个要 ≥2 个），所以同一时刻只出现一个，
            // 工具栏不会挤。
            if (selectedIds.size >= 2) {
                TextButton(onClick = onMergeToSequence) { Text("合并为序列") }
            }

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
                            // 单击编辑、长按选中（详见文件里 TimerList 的注释）
                            onClick = { onEdit(timer) },
                            onLongClick = { onToggle(timer.id) },
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
                        // 跟随设置（原来是写死的 Fraction + 4 份，导致设置改了这里不变）
                        colorMode = colorMode,
                        intervalMinutes = intervalMinutes,
                        fractionDivisions = fractionDivisions,
                        thresholdMinutes = thresholdMinutes,
                        thresholdColors = thresholdColors,
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

            // 序列显示在计时器之后。
            //
            // 用户反馈过「列表视图里没有序列」：卡片视图有、列表视图没有，
            // 同一个面板切个视图就看到不同内容 —— 那是 bug。
            // 这里不给序列加 Checkbox：批量开始/删除是计时器语义，
            // 序列的"开始"要走引擎，混进同一套批量操作会让人误解。
            // 点行 = 进序列全屏，这是列表里最自然的"打开"动作。
            items(sequences, key = { "seq-" + it.id }) { seq ->
                val steps = stepsBySequence[seq.id].orEmpty()
                val totalMillis = steps.sumOf { it.durationMillis }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ExtColors.Surface)
                        // 单击进全屏、长按进编辑器。
                        // 原来只有 `.clickable`，长按什么都没发生 —— 用户报"列表里长按序列
                        // 没有弹出序列编辑器"。
                        .combinedClickable(
                            onClick = { onOpenSequence(seq) },
                            onLongClick = { onEditSequence(seq) },
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = ExtColors.Primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            seq.name,
                            fontSize = 14.sp,
                            color = ExtColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${steps.size} 阶段 · " + DeadlineCalculator.formatRemaining(totalMillis),
                            fontSize = 12.sp,
                            color = ExtColors.TextSecondary,
                        )
                    }
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
