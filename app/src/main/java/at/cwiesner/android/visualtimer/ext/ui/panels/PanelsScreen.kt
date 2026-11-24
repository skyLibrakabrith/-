package at.cwiesner.android.visualtimer.ext.ui.panels

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.GroupMember
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.PanelRunner
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.alertNameOf
import at.cwiesner.android.visualtimer.ext.ui.common.clockOf
import at.cwiesner.android.visualtimer.ext.ui.sequences.SequenceEditorScreen
import at.cwiesner.android.visualtimer.ext.ui.sequences.SequenceFocusScreen
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 多面板主界面。
 *
 * 两种呈现：
 * - **网格**：每个计时器一个大圆盘，全部同时可见、同时运行。点圆盘开始/暂停。
 * - **列表**：紧凑列表 + 多选，批量开始 / 批量删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelsScreen(
    modifier: Modifier = Modifier,
    /** 每次自增表示底边栏「面板」被再次点击，要求切换卡片/列表视图。 */
    toggleViewSignal: Int = 0,
    /** 视图变化时回传，供底边栏图标同步。 */
    onViewChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val runner = app.panelRunner
    val scope = rememberCoroutineScope()

    // 所有数据流与业务操作收敛到 ViewModel（旋转不丢状态、逻辑可测）
    val vm: PanelsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = PanelsViewModel.factory(
            db = app.database,
            runner = runner,
            settings = remember { SettingsRepo(context) },
        ),
    )

    // ---- 设置 ----
    val thresholds by vm.thresholds.collectAsState()
    val colorMode by vm.colorMode.collectAsState()
    val intervalMinutes by vm.intervalMinutes.collectAsState()
    val fractionDivisions by vm.fractionDivisions.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()

    // ---- 面板与内容 ----
    val panels by vm.panels.collectAsState()
    val selectedPanel by vm.selectedPanelIndex.collectAsState()
    val currentPanel by vm.currentPanel.collectAsState()
    val timers by vm.currentTimers.collectAsState()
    val groups by vm.currentGroups.collectAsState()
    val panelSequences by vm.currentSequences.collectAsState()
    val allTimers by vm.allTimers.collectAsState()
    val alertProfiles by vm.alertProfiles.collectAsState()
    val allSequences by vm.allSequences.collectAsState()
    val groupMembersMap by vm.groupMembersMap.collectAsState()
    val groupedTimerIds by vm.groupedTimerIds.collectAsState()
    val timerOwnerGroup by vm.timerOwnerGroup.collectAsState()
    val sequencePanelMap by vm.sequencePanelMap.collectAsState()
    val stepsBySequence by vm.stepsBySequence.collectAsState()
    val gridEntries by vm.gridEntries.collectAsState()

    // ---- 界面交互状态 ----
    val listMode by vm.listMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val orderOverride by vm.orderOverride.collectAsState()
    val focusTimerId by vm.focusTimerId.collectAsState()
    val focusSequenceId by vm.focusSequenceId.collectAsState()
    val editingTimerId by vm.editingTimerId.collectAsState()
    val editingGroupId by vm.editingGroupId.collectAsState()
    val editingSequenceId by vm.editingSequenceId.collectAsState()
    val alertPickerTimerId by vm.alertPickerTimerId.collectAsState()
    val leadTimeTarget by vm.leadTimeTarget.collectAsState()
    val showPanelManager by vm.showPanelManager.collectAsState()
    val showPanelEditor by vm.showPanelEditor.collectAsState()
    val showNewTimer by vm.showNewTimer.collectAsState()
    val showDeadline by vm.showDeadline.collectAsState()
    val showOrderDialog by vm.showOrderDialog.collectAsState()

    // 拖动期间用本地镜像，松手才落库
    val displayEntries = orderOverride ?: gridEntries
    val safeIndex = selectedPanel.coerceIn(0, (panels.size - 1).coerceAtLeast(0))

    // ---- 运行时状态 ----
    val runtimeMap by runner.runtimes.collectAsState()
    val runtimeById = runtimeMap
    val enginePhase by app.sequenceEngine.phase.collectAsState()
    val engineActiveId = app.sequenceEngine.activeSequenceId

    // 阈值 → 分钟 + 颜色（供圆盘取色）
    val thresholdMinutes = remember(thresholds) { thresholds.map { it.minutesLeft } }
    val thresholdColors = remember(thresholds) {
        thresholds.map {
            ExtColors.Palette[it.paletteIndex.coerceIn(0, ExtColors.Palette.size - 1)]
        }
    }

    // ---- 视图切换（底边栏点「面板」触发）----
    LaunchedEffect(toggleViewSignal) {
        if (toggleViewSignal > 0) vm.toggleListMode()
    }
    LaunchedEffect(listMode) { onViewChanged(listMode) }

    // 各面板运行中数量：让「别的面板也在跑」可见
    var runningCountByPanel by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    val db = app.database
    LaunchedEffect(runtimeMap, panels) {
        val map = mutableMapOf<Long, Int>()
        for (p in panels) {
            val ids = runCatching { db.panelDao().refsOf(p.id).map { it.timerId } }
                .getOrDefault(emptyList())
            val n = ids.count { id -> runtimeMap[id]?.running == true }
            if (n > 0) map[p.id] = n
        }
        runningCountByPanel = map
    }
    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // ---- 序列编辑（长按序列块进入）：就地渲染全屏编辑器 ----
    val editingSeq = editingSequenceId?.let { sid ->
        allSequences.firstOrNull { it.id == sid }
    }
    if (editingSeq != null) {
        SequenceEditorScreen(
            sequence = editingSeq,
            steps = stepsBySequence[editingSeq.id].orEmpty(),
            onDismiss = { vm.setEditingSequence(null) },
            onSave = { name, timeout, stepPairs ->
                scope.launch {
                    val seqId = db.sequenceDao().upsert(
                        editingSeq.copy(
                            name = name,
                            timeoutMillis = timeout,
                            panelId = editingSeq.panelId ?: currentPanel?.id,
                        ),
                    )
                    db.sequenceDao().steps(seqId).forEach { db.sequenceDao().deleteStep(it.id) }
                    stepPairs.forEachIndexed { idx, pair ->
                        db.sequenceDao().upsertStep(
                            Step(
                                sequenceId = seqId,
                                durationMillis = pair.second,
                                label = pair.first.ifBlank { null },
                                order = idx,
                            ),
                        )
                    }
                    vm.setEditingSequence(null)
                    toast("序列「" + name + "」已保存，" + stepPairs.size + " 个阶段")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(currentPanel?.name ?: "多面板")
                            val runningCount = runtimes.count { it.running }
                            if (runningCount > 0) {
                                Text(
                                    "$runningCount 个运行中",
                                    fontSize = 11.sp,
                                    color = ExtColors.Primary,
                                )
                            }
                        }
                    },
                )
                if (panels.isNotEmpty()) {
                    TabRow(
                        selectedTabIndex = safeIndex,
                        containerColor = ExtColors.PureBlack,
                        contentColor = ExtColors.Primary,
                    ) {
                        panels.forEachIndexed { index, panel ->
                            Tab(
                                selected = index == safeIndex,
                                onClick = { vm.selectPanel(index); vm.clearSelection() },
                                text = {
                                    val active = runningCountByPanel[panel.id] ?: 0
                                    Text(
                                        if (active > 0) "${panel.name} ·$active" else panel.name,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            // 任何编辑弹窗打开时隐藏 FAB，避免图标浮在对话框上
            val anyDialogOpen = showPanelEditor || showPanelManager || showNewTimer ||
                showDeadline || showOrderDialog || editingTimerId != null ||
                editingGroupId != null || editingSequenceId != null ||
                focusSequenceId != null || focusTimerId != null
            if (!anyDialogOpen) ExtendedFloatingActionButton(
                onClick = { if (panels.isEmpty()) showNewPanel = true else vm.setShowNewTimer(true) },
                containerColor = ExtColors.Primary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(if (panels.isEmpty()) "新建面板" else "加计时器") },
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            run {
            val focusedSeq = focusSequenceId?.let { sid ->
                panelSequences.firstOrNull { it.id == sid }
            }
            if (focusedSeq != null) {
                val seqSteps = stepsBySequence[focusedSeq.id].orEmpty()
                val seqActive = engineActiveId == focusedSeq.id
                SequenceFocusScreen(
                    sequence = focusedSeq,
                    steps = seqSteps,
                    phase = if (seqActive) enginePhase else StepSequenceEngine.Phase.Idle,
                    isActive = seqActive,
                    onStart = {
                        if (seqSteps.isEmpty()) {
                            toast("这个序列还没有阶段，长按卡片去编辑")
                        } else {
                            app.sequenceEngine.start(focusedSeq, seqSteps)
                            toast("开始序列「" + focusedSeq.name + "」")
                        }
                    },
                    onNext = { app.sequenceEngine.confirm() },
                    onStop = {
                        app.sequenceEngine.stop()
                        toast("已停止")
                    },
                    onReset = {
                        app.sequenceEngine.stop()
                        toast("序列已重置")
                    },
                    onClose = { vm.setFocusSequence(null) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
            val focused = focusTimerId?.let { id -> timers.firstOrNull { it.id == id } }
            if (focused != null) {
                TimerFocusScreen(
                    timer = focused,
                    runtime = runtimeById[focused.id],
                    colorMode = colorMode,
                    intervalMinutes = intervalMinutes,
                    fractionDivisions = fractionDivisions,
                    thresholdMinutes = thresholdMinutes,
                    thresholdColors = thresholdColors,
                    locked = focused.locked,
                    onDurationCommit = { d ->
                        scope.launch { db.timerDao().upsert(focused.copy(durationMillis = d)) }
                    },
                    onStart = {
                        scope.launch {
                            val cur = runner.snapshot().firstOrNull { it.timerId == focused.id }
                            if (cur != null && !cur.running && !cur.expired) {
                                // 有暂停状态 → 从暂停处继续
                                runner.resume(focused.id)
                                toast("继续计时")
                            } else {
                                runner.clear(focused.id)
                                runner.start(focused)
                                toast("开始计时")
                            }
                        }
                    },
                    onPause = {
                        runner.stop(focused.id)
                        toast("已暂停")
                    },
                    onReset = { runner.reset(focused.id) },
                    onClose = { vm.setFocusTimer(null) },
                )
            } else when {
                panels.isEmpty() -> EmptyHint("还没有面板，点右下角新建一个")
                timers.isEmpty() -> EmptyHint("这个面板还没有计时器，点右下角添加")

                listMode -> TimerList(
                    timers = timers,
                    runtimeById = runtimeById,
                    selectedIds = selectedIds,
                    colorMode = colorMode,
                    intervalMinutes = intervalMinutes,
                    fractionDivisions = fractionDivisions,
                    thresholdMinutes = thresholdMinutes,
                    thresholdColors = thresholdColors,
                    onToggle = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onBatchDelete = {
                        scope.launch {
                            val n = selectedIds.size
                            selectedIds.forEach { id ->
                                runner.clear(id)
                                currentPanel?.let { db.panelDao().removeRef(it.id, id) }
                            }
                            vm.clearSelection()
                            toast("已删除 $n 个计时器")
                        }
                    },
                    onEdit = { t -> vm.setEditingTimer(t.id) },
                    onReorder = { vm.setShowOrderDialog(true) },
                    alertProfiles = alertProfiles,
                    onSelectAll = { vm.selectAll() },
                    onClearSelection = { vm.clearSelection() },
                    onChangeAlert = { t -> vm.setAlertPicker(t.id) },
                    onBatchStart = {
                        scope.launch {
                            val chosen = timers.filter { it.id in selectedIds }
                            chosen.forEach { t -> runner.clear(t.id); runner.start(t) }
                            toast("已启动 ${chosen.size} 个计时器")
                        }
                    },
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    DraggableGrid(
                        items = displayEntries,
                        key = { e -> e.kind.name + e.refId },
                        columns = if (at.cwiesner.android.visualtimer.ext.ui.isLandscape()) {
                            (gridColumns + 1).coerceAtMost(8)
                        } else {
                            gridColumns
                        },
                        gridState = rememberLazyGridState(),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        onMove = { from, to ->
                            // 拖动时只更新本地镜像，松手才写库
                            val list = displayEntries.toMutableList()
                            if (from in list.indices && to in list.indices) {
                                val item = list.removeAt(from)
                                list.add(to, item)
                                vm.setOrderOverride(list)
                            }
                        },
                        onClick = { entry ->
                            // 单击：暂停 / 继续
                            val t = timers.firstOrNull { it.id == entry.refId }
                            if (entry.kind == EntryKind.Timer && t != null) {
                                scope.launch {
                                    // DDL 锁定后不允许暂停：它的时间由截止时刻决定，
                                    // 暂停会让倒计时与实际剩余时间脱节
                                    if (t.locked) {
                                        toast("DDL 已锁定，不能暂停")
                                    } else {
                                        val cur = runner.snapshot()
                                            .firstOrNull { it.timerId == t.id }
                                        when {
                                            cur == null -> runner.start(t)
                                            cur.running -> runner.stop(t.id)
                                            else -> runner.resume(t.id)
                                        }
                                    }
                                }
                            }
                        },
                        onDoubleClick = { entry ->
                            // 双击：进全屏
                            when (entry.kind) {
                                EntryKind.Timer -> vm.setFocusTimer(entry.refId)
                                EntryKind.Sequence -> vm.setFocusSequence(entry.refId)
                                EntryKind.Group -> vm.setEditingGroup(entry.refId)
                            }
                        },
                        onLongClick = { /* 长按由拖拽接管，这里留空 */ },
                    ) { entry, dragging ->
                        when (entry.kind) {
                            EntryKind.Sequence -> {
                                val sq = panelSequences.firstOrNull { it.id == entry.refId }
                                if (sq != null) {
                                    SequenceCell(
                                        sequence = sq,
                                        steps = stepsBySequence[sq.id].orEmpty(),
                                        phase = if (engineActiveId == sq.id) enginePhase
                                        else StepSequenceEngine.Phase.Idle,
                                        isActive = engineActiveId == sq.id,
                                        onClick = { vm.setFocusSequence(sq.id) },
                                        onLongClick = { editingSequenceId = sq.id },
                                    )
                                }
                            }

                            EntryKind.Group -> {
                                val g = groups.firstOrNull { it.id == entry.refId }
                                if (g != null) {
                                    TimerGroupCell(
                                        group = g,
                                        members = groupMembersMap[g.id].orEmpty()
                                            .mapNotNull { id -> timers.firstOrNull { it.id == id } },
                                        runtimeById = runtimeById,
                                        colorMode = colorMode,
                                        intervalMinutes = intervalMinutes,
                                        fractionDivisions = fractionDivisions,
                                        thresholdMinutes = thresholdMinutes,
                                        thresholdColors = thresholdColors,
                                        onClick = { vm.setEditingGroup(g.id) },
                                        onLongClick = { vm.setEditingGroup(g.id) },
                                    )
                                }
                            }

                            EntryKind.Timer -> {
                                val t = timers.firstOrNull { it.id == entry.refId }
                                if (t != null) {
                                    TimerCell(
                                        timer = t,
                                        runtime = runtimeById[t.id],
                                        thresholdMinutes = thresholdMinutes,
                                        thresholdColors = thresholdColors,
                                        colorMode = colorMode,
                                        intervalMinutes = intervalMinutes,
                                        fractionDivisions = fractionDivisions,
                                        onClick = { vm.setFocusTimer(t.id) },
                                        onLongClick = {
                                            // DDL 已锁定时长，长按不打开编辑器
                                            if (!t.locked) vm.setEditingTimer(t.id)
                                        },
                                        onReset = {
                                            scope.launch { runner.reset(t.id) }
                                        },
                                        onDelete = {
                                            scope.launch {
                                                runner.clear(t.id)
                                                currentPanel?.let {
                                                    db.panelDao().removeRef(it.id, t.id)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
                    
                    // 快捷栏只出现在卡片视图，放在网格下方（系统栏留白带内）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickAction("新建序列", Modifier.weight(1f)) {
                            scope.launch {
                                val pid = currentPanel?.id ?: return@launch
                                val id = db.sequenceDao().upsert(
                                    StepSequence(name = "新序列", panelId = pid),
                                )
                                vm.setEditingSequence(id)
                            }
                        }
                        QuickAction("面板管理", Modifier.weight(1f)) {
                            vm.setShowPanelManager(true)
                        }
                    }
            }   // 关闭 else {

            
        }
    }

    // ---- 调整顺序 ----
    if (showOrderDialog && currentPanel != null) {
        // 把序列 / 组 / 未成组计时器拍平成统一列表
        val entries = remember(panelSequences, groups, timers, groupedTimerIds) {
            val list = mutableListOf<PanelEntry>()
            panelSequences.forEach {
                list += PanelEntry(EntryKind.Sequence, it.id, it.name, it.sortOrder)
            }
            groups.forEach {
                list += PanelEntry(EntryKind.Group, it.id, it.name, it.order)
            }
            timers.filter { it.id !in groupedTimerIds }.forEach {
                list += PanelEntry(EntryKind.Timer, it.id, it.label ?: "未命名", it.position)
            }
            list
        }
        DragReorderDialog(
            entries = entries,
            columns = gridColumns,
            onDismiss = { vm.setShowOrderDialog(false) },
            onSave = { ordered ->
                vm.setShowOrderDialog(false)
                scope.launch {
                    // 统一重新编号，再按类型写回各自的 order 字段
                    ordered.forEachIndexed { idx, entry ->
                        when (entry.kind) {
                            EntryKind.Sequence -> {
                                allSequences.firstOrNull { it.id == entry.refId }?.let { sq ->
                                    db.sequenceDao().upsert(sq.copy(sortOrder = idx))
                                }
                            }
                            EntryKind.Group -> {
                                groups.firstOrNull { it.id == entry.refId }?.let { g ->
                                    db.groupDao().upsertGroup(g.copy(order = idx))
                                }
                            }
                            EntryKind.Timer -> {
                                timers.firstOrNull { it.id == entry.refId }?.let { t ->
                                    db.timerDao().upsert(t.copy(position = idx))
                                }
                            }
                        }
                    }
                    toast("顺序已保存")
                }
            },
        )
    }

    // ---- 组编辑（长按组卡片进入）----
    val editingGroup = editingGroupId?.let { gid -> groups.firstOrNull { it.id == gid } }
    if (editingGroup != null) {
        val occupied = remember(allGroupMembers, groups) {
            val nameById = groups.associate { it.id to it.name }
            val map = mutableMapOf<Long, String>()
            allGroupMembers.forEach { m ->
                if (m.groupId != editingGroup.id) {
                    nameById[m.groupId]?.let { map[m.timerId] = it }
                }
            }
            map
        }
        GroupEditorDialog(
            group = editingGroup,
            candidateTimers = timers,
            memberIds = groupMembersMap[editingGroup.id].orEmpty().toSet(),
            occupiedBy = occupied,
            onDismiss = { vm.setEditingGroup(null) },
            onSave = { gname, ids ->
                vm.setEditingGroup(null)
                scope.launch {
                    vm.saveGroup(editingGroup, gname, ids)
                    toast("组「" + gname + "」已保存，" + ids.size + " 个成员")
                }
            },
            onDelete = {
                vm.setEditingGroup(null)
                scope.launch {
                    vm.deleteGroup(editingGroup)
                    toast("已删除组「" + editingGroup.name + "」")
                }
            },
        )
    }

    if (showPanelManager) {
        // 每个面板的 (成员数, 运行中数)
        val counts = remember(allRefs, runtimeMap) {
            allRefs.groupBy { it.panelId }.mapValues { (_, refs) ->
                refs.size to refs.count { runtimeMap[it.timerId]?.running == true }
            }
        }
        PanelManagerDialog(
            panels = panels,
            counts = counts,
            onDismiss = { vm.setShowPanelManager(false) },
            onOpen = { opened ->
                val idx = panels.indexOfFirst { it.id == opened.id }
                if (idx >= 0) vm.selectPanel(idx)
                vm.setShowPanelManager(false)
            },
            onEdit = { target ->
                // 从管理列表直接进该面板的编辑器
                val idx = panels.indexOfFirst { it.id == target.id }
                if (idx >= 0) vm.selectPanel(idx)
                vm.setShowPanelManager(false)
                vm.setShowPanelEditor(true)
            },
            onCreate = { name ->
                scope.launch {
                    vm.createPanel(name)
                    vm.selectPanel(panels.size)
                    vm.setShowPanelManager(false)
                    toast("面板「" + name + "」已创建")
                }
            },
            onRename = { target, name ->
                scope.launch {
                    vm.renamePanel(target, name)
                    toast("已改名为「" + name + "」")
                }
            },
            onDelete = { target ->
                scope.launch {
                    vm.deletePanel(target)
                    vm.selectPanel(0)
                    toast("面板「" + target.name + "」已删除")
                }
            },
        )
    }

    if (showNewPanel) {
        PanelNameDialog(
            onDismiss = { showNewPanel = false },
            onConfirm = { name ->
                showNewPanel = false
                scope.launch {
                    val panelId = db.panelDao().upsert(Panel(name = name, sortOrder = panels.size))
                    val timerId = db.timerDao().upsert(
                        TimerItem(label = "5 分钟", durationMillis = 300_000L),
                    )
                    db.panelDao().upsertRefs(
                        listOf(PanelTimerRef(panelId = panelId, timerId = timerId, order = 0)),
                    )
                    vm.selectPanel(panels.size)
                    toast("面板「$name」已创建")
                }
            },
        )
    }

    if (showPanelEditor && currentPanel != null) {
        PanelEditorDialog(
            panelId = currentPanel.id,
            panelName = currentPanel.name,
            allTimers = allTimers,
            memberIds = memberIds,
            groups = groups,
            groupMembers = groupMembersMap,
            allSequences = allSequences,
            sequencePanelMap = sequencePanelMap,
            onDismiss = { vm.setShowPanelEditor(false) },
            onSave = { name, ids ->
                vm.setShowPanelEditor(false)
                scope.launch {
                    vm.savePanelMembers(currentPanel, name, ids)
                    toast("已保存：${ids.size} 个计时器")
                }
            },
            onDelete = {
                vm.setShowPanelEditor(false)
                scope.launch {
                    vm.deletePanel(currentPanel)
                    vm.selectPanel(0)
                    toast("面板已删除")
                }
            },
            onCreateGroup = { gname, timerIds ->
                scope.launch {
                    val gid = db.groupDao().upsertGroup(
                        TimerGroup(panelId = currentPanel.id, name = gname, order = groups.size),
                    )
                    // 一个计时器只能属于一个组：先从旧组里摘掉
                    timerIds.forEach { tid ->
                        allGroupMembers.filter { it.timerId == tid }.forEach { old ->
                            db.groupDao().clearMembers(old.groupId)
                            db.groupDao().upsertMembers(
                                allGroupMembers
                                    .filter { it.groupId == old.groupId && it.timerId != tid }
                                    .map { GroupMember(it.groupId, it.timerId, it.order) },
                            )
                        }
                    }
                    db.groupDao().upsertMembers(
                        timerIds.mapIndexed { idx, tid -> GroupMember(gid, tid, idx) },
                    )
                    toast("已创建组「" + gname + "」，" + timerIds.size + " 个成员")
                }
            },
            onDeleteGroup = { target ->
                scope.launch {
                    db.groupDao().clearMembers(target.id)
                    db.groupDao().deleteGroup(target.id)
                    toast("已删除组「" + target.name + "」")
                }
            },
            onToggleSequence = { seqId, on ->
                scope.launch {
                    val target = allSequences.firstOrNull { it.id == seqId } ?: return@launch
                    db.sequenceDao().upsert(
                        target.copy(panelId = if (on) currentPanel.id else null),
                    )
                    toast(if (on) "已加入面板" else "已移出面板")
                }
            },
        )
    }

    // ---- DDL 提前提醒（创建后立即弹出）----
    val leadTarget = leadTimeTarget
    if (leadTarget != null) {
        DeadlineLeadTimeDialog(
            remainingMillis = leadTarget.second,
            onDismiss = { vm.setLeadTimeTarget(null) },
            onConfirm = { leads ->
                vm.setLeadTimeTarget(null)
                scope.launch {
                    db.deadlineReminderDao().clearTimer(leadTarget.first)
                    if (leads.isNotEmpty()) {
                        db.deadlineReminderDao().upsertAll(
                            leads.map { m ->
                                at.cwiesner.android.visualtimer.ext.data.DeadlineReminder(
                                    timerId = leadTarget.first,
                                    leadMinutes = m,
                                )
                            },
                        )
                    }
                    // 同步注册到运行时，tick 才能按时触发
                    runner.setLeadMinutes(leadTarget.first, leads)
                    // 初始化时把「已经过去的档位」标记为已触发，
                    // 否则刚设完第一个 tick 会把所有档位一次性全部触发
                    runner.markLeadsBaseline(leadTarget.first, leadTarget.second)
                    toast(
                        if (leads.isEmpty()) "已跳过提前提醒"
                        else "已设置 ${leads.size} 个提前提醒"
                    )
                }
            },
        )
    }

    // ---- 提醒类型（列表右侧按钮触发）----
    val alertPickerTimer = alertPickerTimerId?.let { id ->
        allTimers.firstOrNull { it.id == id }
    }
    if (alertPickerTimer != null) {
        AlertProfilePickerDialog(
            profiles = alertProfiles,
            selectedId = alertPickerTimer.alertProfileId,
            onDismiss = { vm.setAlertPicker(null) },
            onSelect = { pid ->
                scope.launch {
                    db.timerDao().upsert(alertPickerTimer.copy(alertProfileId = pid))
                    val name = alertProfiles.firstOrNull { it.id == pid }?.name ?: "默认"
                    toast("提醒已设为：" + name)
                }
            },
        )
    }

    // ---- 编辑已有计时器（列表模式 / 长按卡片）----
    val editingTimer = editingTimerId?.let { id -> allTimers.firstOrNull { it.id == id } }
    if (editingTimer != null) {
        TimerEditDialog(
            alertProfiles = alertProfiles,
            initialLabel = editingTimer.label ?: "",
            initialDuration = editingTimer.durationMillis,
            title = "编辑计时器",
            onDismiss = { vm.setEditingTimer(null) },
            onConfirm = { label, duration, deadlineEpoch, alertProfileId ->
                vm.setEditingTimer(null)
                scope.launch {
                    db.timerDao().upsert(
                        editingTimer.copy(
                            label = label.ifBlank { null },
                            durationMillis = duration,
                            alertProfileId = alertProfileId,
                            kind = TimerKind.Countdown,
                        ),
                    )
                    // 时长变了，运行状态重置，避免旧的倒计时残留
                    runner.reset(editingTimer.id)
                    toast("已保存：" + label.ifBlank { "未命名" })
                }
            },
        )
    }

    if (showNewTimer && currentPanel != null) {
        TimerEditDialog(
            alertProfiles = alertProfiles,
            onDismiss = { vm.setShowNewTimer(false) },
            onConfirm = { label, duration, deadlineEpoch, alertProfileId ->
                vm.setShowNewTimer(false)
                scope.launch {
                    val now = System.currentTimeMillis()
                    val timerId = db.timerDao().upsert(
                        TimerItem(
                            label = label.ifBlank { null },
                            durationMillis = duration,
                            alertProfileId = alertProfileId,
                            kind = TimerKind.Countdown,
                        ),
                    )
                    val existing = db.panelDao().refsOf(currentPanel.id)
                    db.panelDao().upsertRefs(
                        listOf(
                            PanelTimerRef(
                                panelId = currentPanel.id,
                                timerId = timerId,
                                order = existing.size,
                            ),
                        ),
                    )
                    db.timerDao().get(timerId)?.let { created ->
                        if (deadlineEpoch != null) {
                            runner.startUntil(created, deadlineEpoch, now)
                        }
                    }
                    toast("已添加：" + (label.ifBlank { "未命名" }) + " · " +
                        DeadlineCalculator.formatRemaining(duration))
                }
            },
        )
    }

    if (showDeadline && currentPanel != null) {
        DeadlineDialog(
            onDismiss = { vm.setShowDeadline(false) },
            onConfirm = { label, deadlineEpoch ->
                vm.setShowDeadline(false)
                scope.launch {
                    val now = System.currentTimeMillis()
                    val remaining = (deadlineEpoch - now).coerceAtLeast(1000L)
                    val timerId = db.timerDao().upsert(
                        TimerItem(
                            label = label.ifBlank { "到点倒计时" },
                            durationMillis = remaining,
                            deadlineEpochMillis = deadlineEpoch,
                            kind = TimerKind.Deadline,
                            // DDL 一旦创建就锁定：时间由截止时刻决定，不允许再改
                            locked = true,
                        ),
                    )
                    val existing = db.panelDao().refsOf(currentPanel.id)
                    db.panelDao().upsertRefs(
                        listOf(
                            PanelTimerRef(
                                panelId = currentPanel.id,
                                timerId = timerId,
                                order = existing.size,
                            ),
                        ),
                    )
                    // 自动开始倒计时
                    db.timerDao().get(timerId)?.let { runner.startUntil(it, deadlineEpoch, now) }
                    toast("DDL 已创建并开始倒计时")
                    // 紧接着问一句要不要提前提醒
                    // 传秒级精度，避免整毫秒取整后档位错位
                    leadTimeTarget = timerId to (remaining / 60_000L * 60_000L)
                }
            },
        )
    }
}

/** 提醒配置名；未绑定时回退到列表第一个配置名。 */
