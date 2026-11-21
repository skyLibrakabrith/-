package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.PanelRunner
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.ui.wheel.WheelTimerView
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 多面板主界面。
 *
 * - 顶部 Tab 切换面板，右上角菜单可重命名 / 删除面板
 * - 网格展示面板内计时器，每格可点击启动/暂停/重置
 * - 右下角 FAB 新建计时器；格子右上角可删除
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()
    val runner = remember { PanelRunner() }

    val panels by remember { db.panelDao().observeAll() }
        .collectAsState(initial = emptyList<Panel>())

    var selectedPanel by remember { mutableIntStateOf(0) }
    val safeIndex = selectedPanel.coerceIn(0, (panels.size - 1).coerceAtLeast(0))
    val currentPanel = panels.getOrNull(safeIndex)

    val timers by remember(currentPanel?.id) {
        currentPanel?.let { db.panelDao().observePanelTimers(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<TimerItem>())

    var showNewPanel by remember { mutableStateOf(false) }
    var showRenamePanel by remember { mutableStateOf(false) }
    var showNewTimer by remember { mutableStateOf(false) }
    var showDeadline by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // 运行时状态：每 200ms 推进一次并触发重组
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = System.currentTimeMillis()
        while (isActive) {
            delay(200)
            val now = System.currentTimeMillis()
            runner.tick(now - last, now)
            last = now
            tick++
        }
    }

    val runtimes: List<TimerRuntime> = remember(tick) { runner.snapshot() }
    val runtimeById = remember(tick) { runtimes.associateBy { it.timerId } }

    // 当前聚焦的计时器：大轮盘显示它。默认第一个；点击格子切换。
    var focusId by remember { mutableLongStateOf(-1L) }
    // 轮盘拖动的本地草稿时长：只在「开始」时写库，避免每帧一次 DB 写
    var draftDuration by remember { mutableLongStateOf(60_000L) }
    LaunchedEffect(focusTimer?.id) {
        focusTimer?.let { draftDuration = runtimeById[it.id]?.totalMillis ?: it.durationMillis }
    }
    val focusTimer = timers.firstOrNull { it.id == focusId } ?: timers.firstOrNull()
    val focusRuntime = focusTimer?.let { runtimeById[it.id] }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(currentPanel?.name ?: "多面板") },
                    actions = {
                        if (currentPanel != null) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("重命名面板") },
                                        onClick = { menuOpen = false; showRenamePanel = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("倒计时到某个时刻") },
                                        onClick = { menuOpen = false; showDeadline = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("清空计时状态") },
                                        onClick = { menuOpen = false; runner.clearAll(); tick++ },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除面板") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                db.panelDao().deleteById(currentPanel.id)
                                                selectedPanel = 0
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                if (panels.isNotEmpty()) {
                    TabRow(selectedTabIndex = safeIndex) {
                        panels.forEachIndexed { index, panel ->
                            Tab(
                                selected = index == safeIndex,
                                onClick = { selectedPanel = index },
                                text = { Text(panel.name) },
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (panels.isEmpty()) showNewPanel = true else showNewTimer = true
                },
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
            when {
                panels.isEmpty() -> EmptyHint("还没有面板，点右下角新建一个")
                timers.isEmpty() -> EmptyHint("这个面板还没有计时器，点右下角添加")
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // ---- 大轮盘：Visual Timer 风格主视图 ----
                    if (focusTimer != null) {
                        val runningNow = focusRuntime?.running == true
                        val total = focusRuntime?.totalMillis ?: draftDuration
                        val remain = focusRuntime?.remainingMillis ?: draftDuration

                        WheelTimerView(
                            totalMillis = total,
                            remainingMillis = remain,
                            running = runningNow,
                            onDurationChange = { newDuration -> draftDuration = newDuration },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            maxMinutes = 60,
                            stepMinutes = 1,
                            centerLabel = focusTimer.label,
                        )

                        // 轮盘下方操作条
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (runningNow) "计时中" else "时长 " +
                                    at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
                                        .formatRemaining(draftDuration),
                                fontSize = 13.sp,
                                color = ExtColors.TextSecondary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (runningNow) {
                                    TextButton(onClick = {
                                        runner.stop(focusTimer.id); tick++
                                    }) { Text("暂停") }
                                } else {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val target = focusTimer.copy(durationMillis = draftDuration)
                                            db.timerDao().upsert(target)
                                            runner.clear(focusTimer.id)
                                            runner.start(target)
                                            tick++
                                        }
                                    }) { Text("开始") }
                                }
                                TextButton(onClick = {
                                    runner.reset(focusTimer.id)
                                    draftDuration = focusRuntime?.totalMillis ?: draftDuration
                                    tick++
                                }) { Text("重置") }
                            }
                        }
                    }
                    LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(timers, key = { it.id }) { timer ->
                        TimerCell(
                            timer = timer,
                            runtime = runtimeById[timer.id],
                            onClick = {
                                focusId = timer.id
                                scope.launch {
                                    val cur = runner.snapshot().firstOrNull { it.timerId == timer.id }
                                    when {
                                        cur == null -> runner.start(timer)
                                        cur.running -> runner.stop(timer.id)
                                        cur.expired -> runner.reset(timer.id)
                                        else -> runner.start(timer)
                                    }
                                    tick++
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    runner.clear(timer.id)
                                    currentPanel?.let { db.panelDao().removeRef(it.id, timer.id) }
                                    tick++
                                }
                            },
                        )
                    }
                }
                }
            }
        }
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
                }
            },
        )
    }

    if (showRenamePanel && currentPanel != null) {
        PanelNameDialog(
            initialName = currentPanel.name,
            title = "重命名面板",
            onDismiss = { showRenamePanel = false },
            onConfirm = { name ->
                showRenamePanel = false
                scope.launch { db.panelDao().upsert(currentPanel.copy(name = name)) }
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
                    val created = db.timerDao().get(timerId)
                    if (created != null) {
                        if (deadlineEpoch != null) {
                            runner.startUntil(created, deadlineEpoch, now)
                        }
                    }
                    tick++
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
                    tick++
                }
            },
        )
    }
}

@Composable
private fun TimerCell(
    timer: TimerItem,
    runtime: TimerRuntime?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val remaining = runtime?.remainingMillis ?: timer.durationMillis
    val total = runtime?.totalMillis ?: timer.durationMillis
    val running = runtime?.running == true

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MiniTimerRing(
                    label = timer.label,
                    remainingMillis = remaining,
                    totalMillis = total,
                    running = running,
                )
                if (runtime?.deadlineEpochMillis != null) {
                    Text(
                        "DDL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
