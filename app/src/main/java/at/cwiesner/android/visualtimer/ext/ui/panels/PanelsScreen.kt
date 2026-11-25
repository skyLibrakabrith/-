package at.cwiesner.android.visualtimer.ext.ui.panels

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.data.GroupMember
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.AiCompileDialog
import at.cwiesner.android.visualtimer.ext.ui.sequences.SequenceEditorScreen
import at.cwiesner.android.visualtimer.ext.ui.sequences.SequenceFocusScreen
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
    /**
     * 卡片/列表视图开关，由导航层持有。
     *
     * 放在外面是刻意的：PanelsScreen 切标签时会被销毁重建，视图状态若留在
     * 组件内部就跟着一起丢，表现为「从别处进来变成列表视图」。
     */
    listModeState: MutableState<Boolean> = remember { mutableStateOf(false) },
    /**
     * 底边栏「面板」按钮长按后，在按钮上方弹出的菜单项被点中时递增。
     *
     * 菜单本身画在 MainActivity 的底边栏上（锚定到按钮，而不是屏幕中央），
     * 但它要触发的动作（新建序列 / 面板管理 / 整理念头）都住在 PanelsScreen 内部 ——
     * 所以用一个「命令」信号把点击传进来，PanelsScreen 收到后执行对应的动作。
     *
     * 用自增的 Id 而不是 Boolean：连点两次同一项也要都能触发，
     * Boolean 会被去重。
     */
    actionsSignal: Int = 0,
    /** 具体是哪个动作；与 [actionsSignal] 配合使用（信号变了才生效）。 */
    pendingAction: PanelAction? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()
    // Application 持有：切标签 / 回桌面都不会丢倒计时
    val runner = app.panelRunner
    val settings = remember { SettingsRepo(context) }

    val thresholds by settings.thresholds.collectAsState(initial = emptyList())
    val colorMode by settings.colorMode.collectAsState(
        initial = at.cwiesner.android.visualtimer.ext.settings.ColorMode.Fraction,
    )
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
    var leadTimeTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var showNewTimer by remember { mutableStateOf(false) }
    var showDeadline by remember { mutableStateOf(false) }
    var showPanelManager by remember { mutableStateOf(false) }

    // Compiler：碎片念头 → 若干计时器
    var showCompileDialog by remember { mutableStateOf(false) }
    var focusTimerId by remember { mutableStateOf<Long?>(null) }

    // 以前这里有一对 longPressEntry / showPanelActions，负责「长按卡片弹出面板操作框」。
    // 已经删掉：**格子上的长按被拖拽排序完全占用**，再挂一个 onLongClick 会让弹框
    // 抢在拖拽前面把事件吃掉，表现就是"长按弹出个框，根本拖不动"。
    //
    // 那几个动作改由底边栏「面板」按钮的长按菜单提供（锚定在按钮上的 DropdownMenu），
    // 与拖拽彻底分开，互不干扰。
    //
    // 底边栏长按菜单的点击 → 执行对应动作
    LaunchedEffect(actionsSignal) {
        when (pendingAction) {
            PanelAction.NewSequence -> {
                scope.launch {
                    val pid = currentPanel?.id ?: return@launch
                    val id = db.sequenceDao().upsert(
                        StepSequence(name = "新序列", panelId = pid),
                    )
                    editingSequenceId = id
                }
            }
            PanelAction.Manage -> {
                showPanelManager = true
            }
            PanelAction.Compile -> {
                showCompileDialog = true
            }
            null -> Unit
        }
    }

    // 视图模式由 MainScaffold 持有（受控组件）。
    //
    // 之前是本地 rememberSaveable：切标签时 PanelsScreen 整个被销毁重建，
    // 恢复出来的「上次值」不一定等于用户期望的默认值，
    // 于是从其他界面点回面板时会莫名落到列表视图。
    // 现在状态在导航层，默认卡片视图，只有用户主动再点一次底边栏才切换。
    var listMode by listModeState
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(toggleViewSignal) {
        // toggleViewSignal 只在用户**再次点击**「面板」标签时自增，
        // 首次进入不会触发，所以不会把默认视图切走
        if (toggleViewSignal > 0) {
            listMode = !listMode
            selectedIds = emptySet()
        }
    }

    val allTimers by remember { db.timerDao().observeAll() }
        .collectAsState(initial = emptyList<TimerItem>())
    val allRefs by remember { db.panelDao().observeAllRefs() }
        .collectAsState(initial = emptyList<PanelTimerRef>())

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

    val alertProfiles by remember { db.alertProfileDao().observeAll() }
        .collectAsState(initial = emptyList<AlertProfile>())

    val allSequences by remember { db.sequenceDao().observeAll() }
        .collectAsState(initial = emptyList<StepSequence>())
    val sequencePanelMap = remember(allSequences) {
        allSequences.associate { it.id to it.panelId }
    }
    val timerOwnerGroup = remember(allGroupMembers) {
        allGroupMembers.associate { it.timerId to it.groupId }
    }

    val panelSequences by remember(currentPanel?.id) {
        currentPanel?.let { db.sequenceDao().observeByPanel(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<StepSequence>())

    // 当前面板的成员计时器 id（编辑器回填勾选状态用）
    val memberIds = remember(allRefs, currentPanel?.id) {
        if (currentPanel == null) emptySet()
        else allRefs.filter { it.panelId == currentPanel.id }.map { it.timerId }.toSet()
    }

    val groupedTimerIds = remember(allGroupMembers) {
        allGroupMembers.map { it.timerId }.toSet()
    }

    // 跨类型统一列表：序列 / 组 / 未成组计时器一起参与排序与渲染
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

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    var orderOverride by remember { mutableStateOf<List<PanelEntry>?>(null) }
    val displayEntries = orderOverride ?: gridEntries

    /**
     * 把拖拽后的新顺序落库。
     *
     * 三张表各有自己的 order 字段，统一按拍平后的下标重编号，
     * 这样序列 / 组 / 计时器可以互相穿插排序。
     */
    fun persistOrder(ordered: List<PanelEntry>) {
        scope.launch {
            ordered.forEachIndexed { idx, entry ->
                when (entry.kind) {
                    EntryKind.Sequence ->
                        allSequences.firstOrNull { it.id == entry.refId }?.let { sq ->
                            db.sequenceDao().upsert(sq.copy(sortOrder = idx))
                        }
                    EntryKind.Group ->
                        groups.firstOrNull { it.id == entry.refId }?.let { g ->
                            db.groupDao().upsertGroup(g.copy(order = idx))
                        }
                    EntryKind.Timer ->
                        timers.firstOrNull { it.id == entry.refId }?.let { t ->
                            db.timerDao().upsert(t.copy(position = idx))
                        }
                }
            }
            toast("顺序已保存")
        }
    }

    val enginePhase by app.sequenceEngine.phase.collectAsState()
    val engineActiveId = app.sequenceEngine.activeSequenceId

    var stepsBySequence by remember { mutableStateOf<Map<Long, List<Step>>>(emptyMap()) }
    LaunchedEffect(panelSequences) {
        val map = mutableMapOf<Long, List<Step>>()
        panelSequences.forEach { sq ->
            map[sq.id] = runCatching { db.sequenceDao().steps(sq.id) }.getOrDefault(emptyList())
        }
        stepsBySequence = map
    }

    val runtimeMap by runner.runtimes.collectAsState()
    val runtimes: List<TimerRuntime> = runtimeMap.values.sortedBy { it.timerId }
    val runtimeById = runtimeMap

    val thresholdMinutes = remember(thresholds) { thresholds.map { it.minutesLeft } }
    val thresholdColors = remember(thresholds) {
        thresholds.map {
            ExtColors.Palette[it.paletteIndex.coerceIn(0, ExtColors.Palette.size - 1)]
        }
    }

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
                showDeadline || editingTimerId != null ||
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
                    // DDL 没有编辑入口（不是「被拦住」，而是这类计时器本来就没有可编辑项）
                    onEdit = { t -> if (!t.locked) editingTimerId = t.id },
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
                        cellAspect = 0.92f,
                        onMove = { from, to ->
                            // 拖动过程中只更新本地镜像，避免每帧写库
                            val list = displayEntries.toMutableList()
                            if (from in list.indices && to in list.indices) {
                                val item = list.removeAt(from)
                                list.add(to, item)
                                orderOverride = list
                            }
                        },
                        onDragEnd = {
                            // 松手才落库：把新顺序按类型写回各自 order 字段
                            orderOverride?.let { ordered ->
                                persistOrder(ordered)
                                orderOverride = null
                            }
                        },
                        onClick = { entry ->
                            // 单击：暂停 / 继续。
                            // DDL 完全跳过 —— 它没有暂停/继续的语义，点了不该有反应。
                            if (entry.kind != EntryKind.Timer) return@DraggableGrid
                            val t = timers.firstOrNull { it.id == entry.refId }
                                ?: return@DraggableGrid
                            if (t.locked) return@DraggableGrid
                            scope.launch {
                                val cur = runner.snapshot().firstOrNull { it.timerId == t.id }
                                when {
                                    cur == null -> runner.start(t)
                                    cur.running -> runner.stop(t.id)
                                    else -> runner.resume(t.id)
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
                        onLongClick = null,
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
                                        locked = t.locked,
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

            // 原先这里有一条常驻快捷栏（新建序列 / 面板管理）。
            // 它占掉一整行高度，且与「长按面板名」的入口重复，已整体移入抽屉。
            }


        }
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

    if (showCompileDialog) {
        // Compiler：把一堆零散想法归类成若干计时器，逐个放进当前面板。
        //
        // 只建计时器、不建序列 —— 序列有"每步等确认"的语义，
        // 而整理念头产出的通常是互不相关的小事，做成独立计时器更符合直觉。
        AiCompileDialog(
            onDismiss = { showCompileDialog = false },
            onApply = { groups ->
                showCompileDialog = false
                scope.launch {
                    val pid = currentPanel?.id ?: run {
                        toast("先选一个面板")
                        return@launch
                    }
                    val existingRefs = runCatching { db.panelDao().refsOf(pid) }.getOrDefault(emptyList())
                    var order = (existingRefs.maxOfOrNull { it.order } ?: -1) + 1
                    val newRefs = mutableListOf<PanelTimerRef>()
                    var added = 0
                    groups.forEach { g ->
                        g.items.forEach { item ->
                            // 标题带上组名，网格里一眼能看出归属
                            val label = "${g.name}·${item.title}"
                            val timerId = db.timerDao().upsert(
                                TimerItem(
                                    label = label,
                                    durationMillis = item.minutes * 60_000L,
                                ),
                            )
                            newRefs += PanelTimerRef(panelId = pid, timerId = timerId, order = order)
                            order++
                            added++
                        }
                    }
                    if (newRefs.isNotEmpty()) {
                        db.panelDao().upsertRefs(newRefs)
                    }
                    toast("已加入 $added 个计时器")
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
                    db.panelDao().refsOf(currentPanel.id).forEach {
                        db.panelDao().removeRef(currentPanel.id, it.timerId)
                    }
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
            initialAlertProfileId = editingTimer.alertProfileId,
            title = "编辑计时器",
            onDismiss = { editingTimerId = null },
            onConfirm = { label, duration, deadlineEpoch, alertProfileId ->
                editingTimerId = null
                scope.launch {
                    // DDL 计时器的类型与截止时刻必须原样保留。
                    // 之前这里无条件写 kind = Countdown：一旦从列表里编辑一个
                    // locked 迁移后仍为 false 的旧 DDL，它就静默退化成了普通倒计时。
                    val keepDeadline = editingTimer.kind == TimerKind.Deadline
                    db.timerDao().upsert(
                        editingTimer.copy(
                            label = label.ifBlank { null },
                            durationMillis = duration,
                            alertProfileId = alertProfileId,
                            kind = if (keepDeadline) TimerKind.Deadline else TimerKind.Countdown,
                            deadlineEpochMillis = if (keepDeadline) {
                                editingTimer.deadlineEpochMillis
                            } else {
                                deadlineEpoch
                            },
                            locked = keepDeadline && editingTimer.locked,
                        ),
                    )
                    // 时长变了：直接清掉运行时状态。
                    // 用 reset 不行 —— 它把 remaining 设成**旧的** totalMillis，
                    // 卡片会继续显示改之前时长。
                    runner.clear(editingTimer.id)
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
