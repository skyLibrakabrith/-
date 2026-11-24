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
    val db = app.database
    val scope = rememberCoroutineScope()
    // 用 Application 持有的实例：切标签 / 回桌面都不会丢倒计时
    val runner = app.panelRunner
    val settings = remember { SettingsRepo(context) }
    val thresholds by settings.thresholds.collectAsState(initial = emptyList())
    val colorMode by settings.colorMode.collectAsState(initial = at.cwiesner.android.visualtimer.ext.settings.ColorMode.Threshold)
    val intervalMinutes by settings.intervalMinutes.collectAsState(initial = 5L)
    val fractionDivisions by settings.fractionDivisions.collectAsState(initial = 4)
    val gridColumns by settings.gridColumns.collectAsState(initial = 2)

    val panels by remember { db.panelDao().observeAll() }
        .collectAsState(initial = emptyList<Panel>())

    var selectedPanel by remember { mutableIntStateOf(0) }
    val safeIndex = selectedPanel.coerceIn(0, (panels.size - 1).coerceAtLeast(0))
    val currentPanel = panels.getOrNull(safeIndex)

    val timers by remember(currentPanel?.id) {
        currentPanel?.let { db.panelDao().observePanelTimers(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<TimerItem>())

    var showNewPanel by remember { mutableStateOf(false) }
    var showPanelEditor by remember { mutableStateOf(false) }
    var editingGroupId by remember { mutableStateOf<Long?>(null) }
    var editingSequenceId by remember { mutableStateOf<Long?>(null) }
    var focusSequenceId by remember { mutableStateOf<Long?>(null) }
    var editingTimerId by remember { mutableStateOf<Long?>(null) }
    var alertPickerTimerId by remember { mutableStateOf<Long?>(null) }
    // DDL 创建后弹出的「提前提醒」设置：timerId to 剩余毫秒
    var leadTimeTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var showOrderDialog by remember { mutableStateOf(false) }
    var showNewTimer by remember { mutableStateOf(false) }
    var showDeadline by remember { mutableStateOf(false) }

    var listMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 底边栏点「面板」时切视图（必须放在 selectedIds 声明之后）
    LaunchedEffect(toggleViewSignal) {
        if (toggleViewSignal > 0) {
            listMode = !listMode
            selectedIds = emptySet()
        }
    }
    // 把当前视图回传给底边栏，让图标与视图一致
    LaunchedEffect(listMode) { onViewChanged(listMode) }
    var showPanelManager by remember { mutableStateOf(false) }
    var focusTimerId by remember { mutableStateOf<Long?>(null) }

    // 编辑器需要「全部计时器」作为候选池
    val allTimers by remember { db.timerDao().observeAll() }
        .collectAsState(initial = emptyList<TimerItem>())
    val allRefs by remember { db.panelDao().observeAllRefs() }
        .collectAsState(initial = emptyList<PanelTimerRef>())

    // 当前面板的组与成员关系
    val groups by remember(currentPanel?.id) {
        currentPanel?.let { db.groupDao().observeGroups(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<TimerGroup>())
    val allGroupMembers by remember { db.groupDao().observeAllMembers() }
        .collectAsState(initial = emptyList<GroupMember>())
    val groupMembersMap = remember(allGroupMembers) {
        allGroupMembers.groupBy { it.groupId }.mapValues { (_, list) ->
            list.sortedBy { it.order }.map { it.timerId }
        }
    }

    // 警报配置（供计时器选择提醒方式）
    val alertProfiles by remember { db.alertProfileDao().observeAll() }
        .collectAsState(initial = emptyList<at.cwiesner.android.visualtimer.ext.data.AlertProfile>())

    // 全部序列（编辑器里分配面板时用）
    val allSequences by remember { db.sequenceDao().observeAll() }
        .collectAsState(initial = emptyList<StepSequence>())
    val sequencePanelMap = remember(allSequences) {
        allSequences.associate { it.id to it.panelId }
    }

    // 归属当前面板的序列
    val panelSequences by remember(currentPanel?.id) {
        currentPanel?.let { db.sequenceDao().observeByPanel(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<StepSequence>())

    // 已归属某个组的计时器：网格里不再单独显示
    val groupedTimerIds = remember(allGroupMembers) {
        allGroupMembers.map { it.timerId }.toSet()
    }

    // 跨类型统一列表：序列 / 组 / 未成组计时器放在一起参与排序与渲染。
    // 之前分三段渲染，导致无法在界面上跨类型拖动排序。
    val gridEntries: List<PanelEntry> = remember(
        panelSequences, groups, timers, groupedTimerIds,
    ) {
        val list = mutableListOf<PanelEntry>()
        panelSequences.forEach {
            list += PanelEntry(EntryKind.Sequence, it.id, it.name, it.sortOrder)
        }
        groups.forEach { g ->
            list += PanelEntry(EntryKind.Group, g.id, g.name, g.order)
        }
        timers.filter { it.id !in groupedTimerIds }.forEach { t ->
            list += PanelEntry(EntryKind.Timer, t.id, t.label ?: "未命名", t.position)
        }
        list.sortedBy { it.order }
    }

    // 拖动排序的本地镜像：拖动时只改这个，松手才落库
    var orderOverride by remember { mutableStateOf<List<PanelEntry>?>(null) }
    val displayEntries = orderOverride ?: gridEntries

    // 引擎状态：订阅以便序列卡片实时刷新
    val enginePhase by app.sequenceEngine.phase.collectAsState()
    val engineActiveId = app.sequenceEngine.activeSequenceId

    // 当前面板序列的步骤（用于卡片显示阶段数）
    var stepsBySequence by remember { mutableStateOf<Map<Long, List<Step>>>(emptyMap()) }
    LaunchedEffect(panelSequences) {
        val map = mutableMapOf<Long, List<Step>>()
        panelSequences.forEach { sq ->
            map[sq.id] = runCatching { db.sequenceDao().steps(sq.id) }.getOrDefault(emptyList())
        }
        stepsBySequence = map
    }

    val memberIds by remember(currentPanel?.id) {
        currentPanel?.let { p ->
            kotlinx.coroutines.flow.flow {
                emit(db.panelDao().refsOf(p.id).map { it.timerId }.toSet())
            }
        } ?: kotlinx.coroutines.flow.flowOf(emptySet())
    }.collectAsState(initial = emptySet())

    val runtimeMap by runner.runtimes.collectAsState()
    val runtimes: List<TimerRuntime> = runtimeMap.values.sortedBy { it.timerId }
    val runtimeById = runtimeMap
    // StateFlow 已驱动 UI，无需本地 tick
    val thresholdMinutes = remember(thresholds) { thresholds.map { it.minutesLeft } }
    val thresholdColors = remember(thresholds) {
        thresholds.map {
            ExtColors.Palette[it.paletteIndex.coerceIn(0, ExtColors.Palette.size - 1)]
        }
    }

    // 各面板运行中的计时器数量 —— 让「别的面板也在跑」这件事可见
    var runningCountByPanel by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
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
            onDismiss = { editingSequenceId = null },
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
                    editingSequenceId = null
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
                                onClick = { selectedPanel = index; selectedIds = emptySet() },
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
                onClick = { if (panels.isEmpty()) showNewPanel = true else showNewTimer = true },
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
                    onClose = { focusSequenceId = null },
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
                    onClose = { focusTimerId = null },
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
                            selectedIds = emptySet()
                            toast("已删除 $n 个计时器")
                        }
                    },
                    onEdit = { t -> editingTimerId = t.id },
                    onReorder = { showOrderDialog = true },
                    alertProfiles = alertProfiles,
                    onSelectAll = { selectedIds = timers.map { it.id }.toSet() },
                    onClearSelection = { selectedIds = emptySet() },
                    onChangeAlert = { t -> alertPickerTimerId = t.id },
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
                                orderOverride = list
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
                                EntryKind.Timer -> focusTimerId = entry.refId
                                EntryKind.Sequence -> focusSequenceId = entry.refId
                                EntryKind.Group -> editingGroupId = entry.refId
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
                                        onClick = { focusSequenceId = sq.id },
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
                                        onClick = { editingGroupId = g.id },
                                        onLongClick = { editingGroupId = g.id },
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
                                        onClick = { focusTimerId = t.id },
                                        onLongClick = {
                                            // DDL 已锁定时长，长按不打开编辑器
                                            if (!t.locked) editingTimerId = t.id
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
                                editingSequenceId = id
                            }
                        }
                        QuickAction("面板管理", Modifier.weight(1f)) {
                            showPanelManager = true
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
            onDismiss = { showOrderDialog = false },
            onSave = { ordered ->
                showOrderDialog = false
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
            onDismiss = { editingGroupId = null },
            onSave = { gname, ids ->
                editingGroupId = null
                scope.launch {
                    db.groupDao().upsertGroup(editingGroup.copy(name = gname))
                    db.groupDao().clearMembers(editingGroup.id)
                    db.groupDao().upsertMembers(
                        ids.mapIndexed { idx, tid ->
                            GroupMember(editingGroup.id, tid, idx)
                        },
                    )
                    toast("组「" + gname + "」已保存，" + ids.size + " 个成员")
                }
            },
            onDelete = {
                editingGroupId = null
                scope.launch {
                    db.groupDao().clearMembers(editingGroup.id)
                    db.groupDao().deleteGroup(editingGroup.id)
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
            onDismiss = { showPanelManager = false },
            onOpen = { opened ->
                val idx = panels.indexOfFirst { it.id == opened.id }
                if (idx >= 0) selectedPanel = idx
                showPanelManager = false
            },
            onEdit = { target ->
                // 从管理列表直接进该面板的编辑器
                val idx = panels.indexOfFirst { it.id == target.id }
                if (idx >= 0) selectedPanel = idx
                showPanelManager = false
                showPanelEditor = true
            },
            onCreate = { name ->
                scope.launch {
                    db.panelDao().upsert(Panel(name = name, sortOrder = panels.size))
                    selectedPanel = panels.size
                    showPanelManager = false
                    toast("面板「" + name + "」已创建")
                }
            },
            onRename = { target, name ->
                scope.launch {
                    db.panelDao().upsert(target.copy(name = name))
                    toast("已改名为「" + name + "」")
                }
            },
            onDelete = { target ->
                scope.launch {
                    db.panelDao().deleteById(target.id)
                    selectedPanel = 0
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
                    selectedPanel = panels.size
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
            onDismiss = { showPanelEditor = false },
            onSave = { name, ids ->
                showPanelEditor = false
                scope.launch {
                    db.panelDao().upsert(currentPanel.copy(name = name))
                    // 重建成员关系：先清空再按新集合写入
                    val old = db.panelDao().refsOf(currentPanel.id)
                    old.forEach { db.panelDao().removeRef(currentPanel.id, it.timerId) }
                    db.panelDao().upsertRefs(
                        ids.mapIndexed { idx, tid ->
                            PanelTimerRef(panelId = currentPanel.id, timerId = tid, order = idx)
                        },
                    )
                    toast("已保存：${ids.size} 个计时器")
                }
            },
            onDelete = {
                showPanelEditor = false
                scope.launch {
                    db.panelDao().deleteById(currentPanel.id)
                    selectedPanel = 0
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
            onDismiss = { leadTimeTarget = null },
            onConfirm = { leads ->
                leadTimeTarget = null
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
            onDismiss = { alertPickerTimerId = null },
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
            onDismiss = { editingTimerId = null },
            onConfirm = { label, duration, deadlineEpoch, alertProfileId ->
                editingTimerId = null
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
            onDismiss = { showNewTimer = false },
            onConfirm = { label, duration, deadlineEpoch, alertProfileId ->
                showNewTimer = false
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
            onDismiss = { showDeadline = false },
            onConfirm = { label, deadlineEpoch ->
                showDeadline = false
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
private fun alertNameOf(
    profileId: Long?,
    profiles: List<at.cwiesner.android.visualtimer.ext.data.AlertProfile>,
): String {
    val p = if (profileId != null) {
        profiles.firstOrNull { it.id == profileId }
    } else {
        profiles.firstOrNull()
    }
    return p?.name ?: "默认"
}

/** 时刻显示 HH:mm。 */
private fun clockOf(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

/** 网格单元：一个大圆盘 + 名称 + 结束时刻。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimerCell(
    timer: TimerItem,
    runtime: TimerRuntime?,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    colorMode: at.cwiesner.android.visualtimer.ext.settings.ColorMode,
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
                // 状态行：左「结束时刻 / 超时时长」，右「重置」按钮
                // 两者用 Row 并排，之前重置按钮绝对定位在底部会压住这行文字
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
                    // 只有「暂停 / 超时」才需要重置，运行时占位保持宽度稳定
                    if (runtime != null && (!running || runtime.isOverdue)) {
                        Box(
                            modifier = Modifier
                                .height(20.dp)
                                // 给固定最小宽度，避免「重置」两字被压成两行或偏左
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
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
        }
    }
}

/** 列表模式：多选 + 批量操作。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimerList(
    timers: List<TimerItem>,
    runtimeById: Map<Long, TimerRuntime>,
    selectedIds: Set<Long>,
    colorMode: at.cwiesner.android.visualtimer.ext.settings.ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    onToggle: (Long) -> Unit,
    onBatchDelete: () -> Unit,
    onBatchStart: () -> Unit,
    onEdit: (TimerItem) -> Unit,
    /** 长按列表行：调出排序面板（替代原来的右上角菜单项）。 */
    onReorder: () -> Unit,
    alertProfiles: List<at.cwiesner.android.visualtimer.ext.data.AlertProfile>,
    onSelectAll: () -> Unit,
    onChangeAlert: (TimerItem) -> Unit,
    onClearSelection: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 操作条常驻：全选 / 取消全选 + 选中后的批量动作
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val allSelected = timers.isNotEmpty() && selectedIds.size == timers.size
            Text(
                if (selectedIds.isEmpty()) {
                    "共 ${timers.size} 个"
                } else {
                    "已选 ${selectedIds.size}/${timers.size}"
                },
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
                    // 小圆盘：和网格里的大表盘同样的视觉，便于一眼分辨各计时器状态
                    PieTimerView(
                        totalMillis = rt?.totalMillis ?: timer.durationMillis,
                        remainingMillis = rt?.remainingMillis ?: timer.durationMillis,
                        running = running,
                        onDurationChange = null,
                        modifier = Modifier.width(52.dp),
                        maxMinutes = 60,
                        centerFontSize = 14f,
                        innerDial = false,
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
                    // 直接显示提醒名称，点一下换配置 —— 不用记住铃铛图标代表什么
                    Text(
                        text = alertNameOf(timer.alertProfileId, alertProfiles),
                        fontSize = 10.sp,
                        color = ExtColors.Amber,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ExtColors.Surface)
                            .clickable { onChangeAlert(timer) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** 网格上方的快捷操作按钮。 */
@Composable
private fun QuickAction(
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
