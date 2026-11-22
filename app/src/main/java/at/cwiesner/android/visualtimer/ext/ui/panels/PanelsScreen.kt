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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
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
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.PanelRunner
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
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
fun PanelsScreen(modifier: Modifier = Modifier) {
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
    var showNewTimer by remember { mutableStateOf(false) }
    var showDeadline by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    var listMode by remember { mutableStateOf(false) }
    var overviewMode by remember { mutableStateOf(false) }
    var showPanelManager by remember { mutableStateOf(false) }
    var focusTimerId by remember { mutableStateOf<Long?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 编辑器需要「全部计时器」作为候选池
    val allTimers by remember { db.timerDao().observeAll() }
        .collectAsState(initial = emptyList<TimerItem>())
    val allRefs by remember { db.panelDao().observeAllRefs() }
        .collectAsState(initial = emptyList<PanelTimerRef>())
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
                    actions = {
                        if (currentPanel != null) {
                            IconButton(onClick = {
                                listMode = !listMode
                                selectedIds = emptySet()
                            }) {
                                Icon(
                                    if (listMode) Icons.Filled.GridView else Icons.Filled.Checklist,
                                    contentDescription = if (listMode) "网格" else "批量管理",
                                )
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("面板管理") },
                                        onClick = { menuOpen = false; showPanelManager = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (overviewMode) "退出总览" else "按组总览") },
                                        onClick = { menuOpen = false; overviewMode = !overviewMode },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("编辑当前面板") },
                                        onClick = { menuOpen = false; showPanelEditor = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("倒计时到某个时刻") },
                                        onClick = { menuOpen = false; showDeadline = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("开始全部") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                timers.forEach { t ->
                                                    runner.clear(t.id)
                                                    runner.start(t)
                                                }
                                                toast("已启动 ${timers.size} 个计时器")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("清空计时状态") },
                                        onClick = { menuOpen = false; runner.clearAll() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除面板") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                db.panelDao().deleteById(currentPanel.id)
                                                selectedPanel = 0
                                                toast("面板已删除")
                                            }
                                        },
                                    )
                                }
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
            ExtendedFloatingActionButton(
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
            if (overviewMode) {
                // 按组总览：所有面板作为「文件夹」平铺，像手机桌面
                val byPanel: Map<Long, List<TimerItem>> = remember(allRefs, allTimers) {
                    val timerById = allTimers.associateBy { it.id }
                    allRefs.groupBy { it.panelId }.mapValues { (_, refs) ->
                        refs.sortedBy { it.order }.mapNotNull { timerById[it.timerId] }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(panels, key = { it.id }) { p ->
                        PanelGroupTile(
                            panel = p,
                            timers = byPanel[p.id].orEmpty(),
                            runtimeById = runtimeById,
                            colorMode = colorMode,
                            intervalMinutes = intervalMinutes,
                            fractionDivisions = fractionDivisions,
                            thresholdMinutes = thresholdMinutes,
                            thresholdColors = thresholdColors,
                            onClick = {
                                val idx = panels.indexOfFirst { it.id == p.id }
                                if (idx >= 0) selectedPanel = idx
                                overviewMode = false
                            },
                        )
                    }
                }
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
                    onDurationCommit = { d ->
                        scope.launch { db.timerDao().upsert(focused.copy(durationMillis = d)) }
                    },
                    onStart = {
                        scope.launch {
                            runner.clear(focused.id)
                            runner.start(focused)
                            toast("开始计时")
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
                    onBatchStart = {
                        scope.launch {
                            val chosen = timers.filter { it.id in selectedIds }
                            chosen.forEach { t -> runner.clear(t.id); runner.start(t) }
                            toast("已启动 ${chosen.size} 个计时器")
                        }
                    },
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(timers, key = { it.id }) { timer ->
                        TimerCell(
                            timer = timer,
                            runtime = runtimeById[timer.id],
                            thresholdMinutes = thresholdMinutes,
                            thresholdColors = thresholdColors,
                            colorMode = colorMode,
                            intervalMinutes = intervalMinutes,
                            fractionDivisions = fractionDivisions,
                            onClick = {
                                scope.launch {
                                    val cur = runner.snapshot().firstOrNull { it.timerId == timer.id }
                                    val name = timer.label ?: "未命名"
                                    when {
                                        // 未启动
                                        cur == null -> {
                                            runner.start(timer)
                                            toast("开始：$name")
                                        }
                                        // 已超时：点一下直接回到原始时长（优先级高于暂停，
                                        // 否则用户以为点了没反应，实际只是暂停了超时计数）
                                        cur.expired -> {
                                            runner.reset(timer.id)
                                            toast("已重置：$name")
                                        }
                                        // 运行中且未超时 → 暂停
                                        cur.running -> {
                                            runner.stop(timer.id)
                                            toast("暂停：$name")
                                        }
                                        // 已暂停 → 继续
                                        else -> {
                                            runner.start(timer)
                                            toast("继续：$name")
                                        }
                                    }
                                }
                            },
                            onLongClick = { focusTimerId = timer.id },
                            onDelete = {
                                scope.launch {
                                    runner.clear(timer.id)
                                    currentPanel?.let { db.panelDao().removeRef(it.id, timer.id) }
                                    toast("已删除")
                                }
                            },
                        )
                    }
                }
            }
            }   // end else (非总览)
        }
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
            panelName = currentPanel.name,
            allTimers = allTimers,
            memberIds = memberIds,
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
        )
    }

    if (showNewTimer && currentPanel != null) {
        TimerEditDialog(
            onDismiss = { showNewTimer = false },
            onConfirm = { label, duration, deadlineEpoch ->
                showNewTimer = false
                scope.launch {
                    val now = System.currentTimeMillis()
                    val timerId = db.timerDao().upsert(
                        TimerItem(
                            label = label.ifBlank { null },
                            durationMillis = duration,
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
                        if (deadlineEpoch != null) runner.startUntil(created, deadlineEpoch, now)
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
                    db.timerDao().get(timerId)?.let { runner.startUntil(it, deadlineEpoch, now) }
                    toast("已创建 DDL：" + (label.ifBlank { "到点倒计时" }))
                }
            },
        )
    }
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
                // 运行中显示结束时刻；超时后改显示超时时长
                Text(
                    text = if ((runtime?.overdueMillis ?: 0L) > 0L) {
                        "超时 " + DeadlineCalculator.formatRemaining(runtime!!.overdueMillis)
                    } else {
                        clockOf(System.currentTimeMillis() + remain) + " 结束"
                    },
                    fontSize = 10.sp,
                    color = if ((runtime?.overdueMillis ?: 0L) > 0L) ExtColors.Red
                    else ExtColors.TextDim,
                    maxLines = 1,
                )
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

/** 列表模式：多选 + 批量操作。 */
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选 ${selectedIds.size}",
                    fontSize = 13.sp,
                    color = ExtColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
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
                        .clickable { onToggle(timer.id) }
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
