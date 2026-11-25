package at.cwiesner.android.visualtimer.ext.ui.panels

import android.util.Log
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
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.AiCompileDialog
import at.cwiesner.android.visualtimer.ext.ui.common.AiSplitDialog
import at.cwiesner.android.visualtimer.ext.ui.common.CompileReviewDialog
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
    /**
     * 动作已处理，请外围把 [pendingAction] 置空。
     *
     * 必须消费掉：PanelsScreen 在切标签时会被销毁重建，
     * 如果外围不清空动作，重建后的 LaunchedEffect 会把它再执行一遍
     * （表现为「从其他界面点回面板时自己弹出窗口」）。
     */
    onActionConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()
    // Application 持有：切标签 / 回桌面都不会丢倒计时
    val runner = app.panelRunner
    val settings = app.settings   // 共享 + 预热，避免切标签时先画默认值

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
    // DDL 两步创建：第一步选截止时刻，第二步选提前提醒。
    // 两步都确认后才真正落库并开始计时，避免"先建好了才问要不要提醒"。
    var pendingDeadline by remember { mutableStateOf<PendingDeadline?>(null) }
    var showNewTimer by remember { mutableStateOf(false) }
    var showDeadline by remember { mutableStateOf(false) }
    var showPanelManager by remember { mutableStateOf(false) }

    // Compiler：碎片念头 → 序列。三步：拆 → 排 → 估。
    var showCompileDialog by remember { mutableStateOf(false) }
    // 拆完待排的结果；非 null 时打开排序界面
    var compileGroups by remember { mutableStateOf<List<at.cwiesner.android.visualtimer.ext.llm.TodoGroup>?>(null) }
    // 记住要建到哪个面板（用户可能在排序期间切了面板）
    var compilePanelId by remember { mutableStateOf<Long?>(null) }

    // 列表视图里「拆解」：非 null 表示要拆的是这个已有计时器（拆完替换它）
    var splitTargetTimer by remember { mutableStateOf<TimerItem?>(null) }
    var focusTimerId by remember { mutableStateOf<Long?>(null) }

    // 以前这里有一对 longPressEntry / showPanelActions，负责「长按卡片弹出面板操作框」。
    // 已经删掉：**格子上的长按被拖拽排序完全占用**，再挂一个 onLongClick 会让弹框
    // 抢在拖拽前面把事件吃掉，表现就是"长按弹出个框，根本拖不动"。
    //
    // 那几个动作改由底边栏「面板」按钮的长按菜单提供（锚定在按钮上的 DropdownMenu），
    // 与拖拽彻底分开，互不干扰。
    //
    // 底边栏长按菜单的点击 → 执行对应动作
    //
    // ⚠️ 这里必须做成「一次性消费」，不能只靠 LaunchedEffect(key) 判断：
    //
    // 曾经写成 `LaunchedEffect(actionsSignal) { when(pendingAction) { ... } }`，
    // 结果出现「从其他界面点回面板时自己弹出面板管理/整理念头窗口」。
    // 原因是 PanelsScreen 在切标签时会被销毁重建，而 panelActionsTick /
    // pendingPanelAction 由 MainActivity 持有、**不会**重置；新组合里
    // LaunchedEffect 一定会首次执行一次，于是把上一次的旧动作重放了一遍。
    //
    // 现在：动作消费后立刻通知外围置空（onActionConsumed），
    // 这样即使组件重建、LaunchedEffect 重新首次执行，读到的也是 null。
    // 记住「已经处理到哪一次信号」。
    //
    // 用 rememberSaveable：PanelsScreen 切标签会销毁重建，
    // 普通 remember 会跟着丢，重建后又变成"从没处理过"，
    // 于是首次执行时把旧动作重放一遍。rememberSaveable 能跨重组/跨标签切换保留。
    var lastHandledActionTick by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(actionsSignal) {
        // 关键一跳：signal 没往前走就什么都不做。
        // 这挡住了"组件重建后 LaunchedEffect 首次执行"把陈年动作重放的情况。
        if (actionsSignal <= lastHandledActionTick) return@LaunchedEffect
        val action = pendingAction ?: run {
            lastHandledActionTick = actionsSignal
            return@LaunchedEffect
        }
        lastHandledActionTick = actionsSignal

        when (action) {
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
        }
        // 消费掉，防止重建后被重放
        onActionConsumed()
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

    val alertProfiles by app.alertProfiles
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
            onSave = { name, timeout, stepDrafts ->
                scope.launch {
                    val seqId = db.sequenceDao().upsert(
                        editingSeq.copy(
                            name = name,
                            timeoutMillis = timeout,
                            panelId = editingSeq.panelId ?: currentPanel?.id,
                        ),
                    )
                    db.sequenceDao().steps(seqId).forEach { db.sequenceDao().deleteStep(it.id) }
                    stepDrafts.forEachIndexed { idx, d ->
                        db.sequenceDao().upsertStep(
                            Step(
                                sequenceId = seqId,
                                durationMillis = d.durationMillis,
                                label = d.label.ifBlank { null },
                                order = idx,
                                // AI 拆解带来的两个属性要一起落库，否则界面改完顺序就丢了
                                difficulty = d.difficulty,
                                context = d.context,
                            ),
                        )
                    }
                    editingSequenceId = null
                    toast("序列「" + name + "」已保存，" + stepDrafts.size + " 个阶段")
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
                    locked = !focused.isEditable,
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
                    // 长按标题改名：只改 label，不碰时长/类型/截止时刻。
                    // 空串统一存成 null —— 这样界面会回退显示「未命名」，
                    // 而不是显示一个看不见的空字符串。
                    onRename = { newName ->
                        scope.launch {
                            val trimmed = newName.trim()
                            db.timerDao().upsert(
                                focused.copy(label = trimmed.ifBlank { null }),
                            )
                            // 正在跑的话，运行时的 label 也要同步，
                            // 否则悬浮窗/通知上还是旧名字
                            runner.updateLabel(focused.id, trimmed.ifBlank { null })
                            toast(if (trimmed.isBlank()) "已清空名称" else "已改名：$trimmed")
                        }
                    },
                    onClose = { focusTimerId = null },
                )
            } else when {
                panels.isEmpty() -> EmptyHint("还没有面板，点右下角新建一个")
                timers.isEmpty() -> EmptyHint("这个面板还没有计时器，点右下角添加")

                listMode -> TimerList(
                    timers = timers,
                    runtimeById = runtimeById,
                    selectedIds = selectedIds,
                    // 变色配置来自设置，让列表里的小圆盘和网格大表盘保持一致
                    colorMode = colorMode,
                    intervalMinutes = intervalMinutes,
                    fractionDivisions = fractionDivisions,
                    thresholdMinutes = thresholdMinutes,
                    thresholdColors = thresholdColors,
                    onToggle = { id ->
                        // 选中是**单选**语义（与"拆解只能针对一件事"保持一致）：
                        // 点已选中的 -> 取消；点别的 -> 替换成它，而不是累加。
                        //
                        // 代价是失去了多选批量操作。但批量开始/删除本来就少用，
                        // 而"选了一个又点另一个却变成选两个"在要单选拆解时很别扭。
                        // 如果以后确实要批量，可以再加一个"多选模式"开关。
                        selectedIds = if (id in selectedIds) emptySet() else setOf(id)
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
                    onEdit = { t -> if (t.isEditable) editingTimerId = t.id },
                    alertProfiles = alertProfiles,
                    // 全选在多选被关掉之后就不成立了；保留成"没有操作"更诚实
                    onSelectAll = { /* 单选模式下不支持全选 */ },
                    onClearSelection = { selectedIds = emptySet() },
                    onChangeAlert = { t -> alertPickerTimerId = t.id },
                    onBatchStart = {
                        scope.launch {
                            val chosen = timers.filter { it.id in selectedIds }
                            chosen.forEach { t -> runner.clear(t.id); runner.start(t) }
                            toast("已启动 ${chosen.size} 个计时器")
                        }
                    },
                    onSplit = { target ->
                        if (target == null) {
                            // 未选中 -> 新建序列去拆解
                            scope.launch {
                                val pid = currentPanel?.id
                                if (pid == null) {
                                    toast("先选一个面板")
                                    return@launch
                                }
                                val id = db.sequenceDao().upsert(
                                    StepSequence(name = "新序列", panelId = pid),
                                )
                                selectedIds = emptySet()
                                editingSequenceId = id
                            }
                        } else {
                            // 选中了一个 -> 带着它的名字进拆解，拆完**替换**原计时器
                            splitTargetTimer = target
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
                            if (!t.isEditable) return@DraggableGrid
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
                                        locked = !t.isEditable,
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
        // Compiler：碎片念头 → **一个序列**（不是一堆散装计时器）。
        //
        // 流程按用户要求做成三步：
        //   1) 拆：模型只给 title + difficulty + context（不估时）
        //   2) 排：用户在 CompileReviewDialog 里调顺序、改难度/背景
        //   3) 估：对**最终顺序**一次性估时，然后建序列并直接打开编辑器
        //
        // 为什么一定要先排再估：顺序变化会让时长完全不同
        // （先查资料再写和先写再补资料，摩擦差很多）。
        AiCompileDialog(
            onDismiss = { showCompileDialog = false },
            // 草稿按面板分开存：没点「整理」就退出，下次打开原样恢复
            panelId = currentPanel?.id
            onApply = { groups ->
                showCompileDialog = false
                val pid = currentPanel?.id ?: run {
                    toast("先选一个面板")
                    return@AiCompileDialog
                }
                compileGroups = groups
                compilePanelId = pid
            },
        )
    }

    // Compiler 第 2、3 步：排序 + 估时 + 建序列
    val pendingCompile = compileGroups
    if (pendingCompile != null) {
        CompileReviewDialog(
            groups = pendingCompile,
            onDismiss = { compileGroups = null },
            onConfirm = { orderedSteps ->
                compileGroups = null
                scope.launch {
                    // 建序列（先建空的，拿到 id 再写步骤）
                    val seqId = db.sequenceDao().upsert(
                        StepSequence(
                            // StepDraft 里叫 label（和 Step.label 对齐），不是 title
                            name = orderedSteps.firstOrNull()?.label?.takeIf { it.isNotBlank() }
                                ?.let { "整理：$it" } ?: "整理结果",
                            panelId = compilePanelId ?: currentPanel?.id,
                            sortOrder = (panelSequences.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                        ),
                    )
                    orderedSteps.forEachIndexed { idx, d ->
                        db.sequenceDao().upsertStep(
                            Step(
                                sequenceId = seqId,
                                durationMillis = d.durationMillis,
                                label = d.label.ifBlank { null },
                                order = idx,
                                difficulty = d.difficulty,
                                context = d.context,
                            ),
                        )
                    }
                    toast("已生成序列，共 ${orderedSteps.size} 步，可在这里调整顺序")
                    // 直接打开序列编辑器 —— 用户仍可能想再调
                    editingSequenceId = seqId
                }
            },
        )
    }

    // ---- 列表视图的「✨ 拆解」：把已有计时器拆成一个序列 ----
    //
    // 用户在列表里选中一个计时器后点拆解，走这条路径。
    // 与序列编辑器里的「拆解」区别：那边是往现有序列里追加阶段，
    // 这边是**把计时器本身换成序列**（原计时器被移除）。
    val splitTarget = splitTargetTimer
    if (splitTarget != null) {
        AiSplitDialog(
            onDismiss = { splitTargetTimer = null },
            // 预填原计时器的名字，用户不用重打一遍
            initialInput = splitTarget.label ?: "",
            onApply = { steps ->
                splitTargetTimer = null
                scope.launch {
                    val pid = currentPanel?.id
                    if (pid == null) {
                        toast("先选一个面板")
                        return@launch
                    }
                    val seqId = db.sequenceDao().upsert(
                        StepSequence(
                            name = splitTarget.label ?: "拆解结果",
                            panelId = pid,
                            sortOrder = (panelSequences.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                        ),
                    )
                    steps.forEachIndexed { idx, s ->
                        db.sequenceDao().upsertStep(
                            Step(
                                sequenceId = seqId,
                                durationMillis = s.minutes * 60_000L,
                                label = s.title.ifBlank { null },
                                order = idx,
                                difficulty = s.difficulty,
                                context = s.context,
                            ),
                        )
                    }
                    // 原计时器被序列取代：停掉运行时并移出面板，再彻底删掉这一行。
                    // 只 removeRef 会留下孤儿行（面板里看不到但仍在库里）。
                    runner.clear(splitTarget.id)
                    db.panelDao().removeRef(pid, splitTarget.id)
                    db.timerDao().deleteById(splitTarget.id)
                    selectedIds = emptySet()
                    toast("已拆成 ${steps.size} 阶段的序列")
                    editingSequenceId = seqId
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

    // DDL 提前提醒的对话框现在住在「创建流程第二步」里（见文件末尾），
    // 这里不再有"创建完才问"的那一块。

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
                // 不在这里创建！先问「提前多久提醒」，用户确认后才真正落库。
                //
                // 原来的顺序是反的：先创建 + 自动开始倒计时 + toast，
                // 然后才弹出提醒档位选择。用户看到的观感是
                // 「DDL 已经建好了，才来问我提醒」——而且如果这时直接返回，
                // 计时器已经在跑，却没设任何提前提醒。
                //
                // 现在把参数暂存到 pendingDeadline，交给下面的提前提醒对话框，
                // 确认（或显式跳过）后再一次性 createDeadlineTimer()。
                pendingDeadline = PendingDeadline(
                    label = label,
                    deadlineEpochMillis = deadlineEpoch,
                )
            },
        )
    }

    // ---- DDL 创建第二步：选提前提醒档位 ----
    //
    // 这是 DDL 创建流程的必经一步（不是"创建完之后的可选补充"）。
    // 用户在这里取消 = 放弃创建，不会留下一个已经在跑的计时器。
    val pending = pendingDeadline
    if (pending != null) {
        val now = System.currentTimeMillis()
        val remaining = (pending.deadlineEpochMillis - now).coerceAtLeast(1000L)

        /**
         * 真正落库 + 挂到面板 + 自动开始 + 登记提前提醒。
         *
         * 参数全部显式传入（pid / 截止时刻 / 名称），不依赖捕获的 Composable 局部变量 ——
         * 这个 lambda 可能在下一次重组后执行，捕获到的 `currentPanel` 等值可能已经过期。
         */
        suspend fun commitDeadline(
            panelId: Long,
            label: String,
            deadlineAt: Long,
            leads: List<Long>,
        ) {
            val startAt = System.currentTimeMillis()
            val remain = (deadlineAt - startAt).coerceAtLeast(1000L)
            val timerId = db.timerDao().upsert(
                TimerItem(
                    label = label.ifBlank { "到点倒计时" },
                    durationMillis = remain,
                    deadlineEpochMillis = deadlineAt,
                    kind = TimerKind.Deadline,
                    // DDL 一旦创建就锁定：它的时间由截止时刻决定，不允许再改。
                    // 配合卡片上的 locked 判断，编辑入口对它不出现。
                    locked = true,
                ),
            )
            val existing = runCatching { db.panelDao().refsOf(panelId) }.getOrDefault(emptyList())
            db.panelDao().upsertRefs(
                listOf(
                    PanelTimerRef(
                        panelId = panelId,
                        timerId = timerId,
                        order = (existing.maxOfOrNull { it.order } ?: -1) + 1,
                    ),
                ),
            )
            // 自动开始：DDL 建好就该在跑，用户不需要再点一下
            val started = db.timerDao().get(timerId)
            if (started == null) {
                Log.w(TAG, "DDL upsert 后立刻 get($timerId) 返回 null，无法 startUntil")
            } else {
                runner.startUntil(started, deadlineAt, startAt)
            }
            // 打完卡立刻回读运行时状态。用户报过"建出来是停着的"，
            // 这段日志是为了让那种情况在 logcat 里一眼看出来是：
            //   · startUntil 没执行（上一条 Log.w）
            //   · 执行了但 running 仍为 false
            //   · running 为 true（那就是界面侧读错，不是运行时的问题）
            val rt = runner.get(timerId)
            Log.i(
                TAG,
                "DDL created: id=$timerId locked=true remain=${remain / 1000}s " +
                    "runtime=" + if (rt == null) "null" else "running=${rt.running} rem=${rt.remainingMillis}",
            )

            // 提前提醒落库 + 同步到运行时
            db.deadlineReminderDao().clearTimer(timerId)
            if (leads.isNotEmpty()) {
                db.deadlineReminderDao().upsertAll(
                    leads.map { m ->
                        at.cwiesner.android.visualtimer.ext.data.DeadlineReminder(
                            timerId = timerId,
                            leadMinutes = m,
                        )
                    },
                )
            }
            runner.setLeadMinutes(timerId, leads)
            // 把「已经过去的档位」标记为已触发，
            // 否则刚设完第一个 tick 会把所有档位一次性全部触发
            runner.markLeadsBaseline(timerId, remain)

            toast(
                if (leads.isEmpty()) "DDL 已创建并开始计时（未设提前提醒）"
                else "DDL 已创建并开始计时，${leads.size} 个提前提醒",
            )
        }

        // 在组合期把当前面板 id 取出来，传给上面的函数
        val panelId = currentPanel?.id

        DeadlineLeadTimeDialog(
            remainingMillis = remaining,
            onDismiss = { pendingDeadline = null },
            onConfirm = { leads ->
                pendingDeadline = null
                if (panelId == null) {
                    toast("先选一个面板")
                } else {
                    scope.launch {
                        commitDeadline(
                            panelId = panelId,
                            label = pending.label,
                            deadlineAt = pending.deadlineEpochMillis,
                            leads = leads,
                        )
                    }
                }
            },
        )
    }
}

/** 本文件的日志标签。DDL 创建流程有诊断日志，出问题时按这个 tag 过滤 logcat。 */
private const val TAG = "PanelsScreen"

/**
 * DDL 两步创建的中间状态：已经选好截止时刻，正在问提前提醒档位。
 *
 * 单独抽出来是为了让「取消第二步 = 放弃创建」成为自然结果 ——
 * 此刻数据库里还没有任何东西，不需要回滚。
 */
private data class PendingDeadline(
    val label: String,
    val deadlineEpochMillis: Long,
)
