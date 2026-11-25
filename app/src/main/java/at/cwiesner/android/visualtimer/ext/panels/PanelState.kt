package at.cwiesner.android.visualtimer.ext.panels

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.data.GroupMember
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.panels.EntryKind
import at.cwiesner.android.visualtimer.ext.ui.panels.PanelEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 面板页的全部数据订阅。
 *
 * 把 12 个 `remember { dao.observe...() }` 从 900 行的 `PanelsScreen` 里搬出来，
 * 界面只负责渲染。这样做纯粹是为了让主 Composable 能读得动 ——
 * 逻辑一行没改，仍然是 Compose 状态驱动，没有引入 ViewModel。
 */
@Composable
fun rememberPanelState(): PanelState {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()
    val settings = app.settings   // 共享 + 预热，见 ExtApplication.settings 的注释

    val panels by remember { db.panelDao().observeAll() }
        .collectAsState(initial = emptyList<Panel>())

    var selectedPanel by remember { mutableIntStateOf(0) }
    val safeIndex = selectedPanel.coerceIn(0, (panels.size - 1).coerceAtLeast(0))
    val currentPanel = panels.getOrNull(safeIndex)

    val timers by remember(currentPanel?.id) {
        currentPanel?.let { db.panelDao().observePanelTimers(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<TimerItem>())

    val allTimers by remember { db.timerDao().observeAll() }
        .collectAsState(initial = emptyList<TimerItem>())
    val allRefs by remember { db.panelDao().observeAllRefs() }
        .collectAsState(initial = emptyList<PanelTimerRef>())

    val groups by remember(currentPanel?.id) {
        currentPanel?.let { db.groupDao().observeGroups(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<TimerGroup>())
    val allGroupMembers by remember { db.groupDao().observeAllMembers() }
        .collectAsState(initial = emptyList<GroupMember>())

    val alertProfiles by app.alertProfiles
        .collectAsState(initial = emptyList<AlertProfile>())

    val allSequences by remember { db.sequenceDao().observeAll() }
        .collectAsState(initial = emptyList<StepSequence>())
    val panelSequences by remember(currentPanel?.id) {
        currentPanel?.let { db.sequenceDao().observeByPanel(it.id) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList<StepSequence>())

    // 设置项
    val thresholds by settings.thresholds.collectAsState(initial = emptyList())
    val colorMode by settings.colorMode.collectAsState(initial = ColorMode.Fraction)
    val intervalMinutes by settings.intervalMinutes.collectAsState(initial = 5L)
    val fractionDivisions by settings.fractionDivisions.collectAsState(initial = 4)
    val gridColumns by settings.gridColumns.collectAsState(initial = 2)

    // 运行时
    val runtimeMap by app.panelRunner.runtimes.collectAsState()

    val groupMembersMap = remember(allGroupMembers) {
        allGroupMembers.groupBy { it.groupId }.mapValues { (_, list) ->
            list.sortedBy { it.order }.map { it.timerId }
        }
    }
    val groupedTimerIds = remember(allGroupMembers) {
        allGroupMembers.map { it.timerId }.toSet()
    }
    val timerOwnerGroup = remember(allGroupMembers) {
        allGroupMembers.associate { it.timerId to it.groupId }
    }
    val sequencePanelMap = remember(allSequences) {
        allSequences.associate { it.id to it.panelId }
    }
    // 当前面板的成员计时器 id（编辑器回填勾选状态用）
    val memberIds = remember(allRefs, currentPanel?.id) {
        if (currentPanel == null) emptySet()
        else allRefs.filter { it.panelId == currentPanel.id }.map { it.timerId }.toSet()
    }

    val thresholdMinutes = remember(thresholds) { thresholds.map { it.minutesLeft } }
    val thresholdColors = remember(thresholds) {
        thresholds.map {
            ExtColors.Palette[it.paletteIndex.coerceIn(0, ExtColors.Palette.size - 1)]
        }
    }

    var stepsBySequence by remember { mutableStateOf<Map<Long, List<Step>>>(emptyMap()) }
    LaunchedEffect(panelSequences) {
        val map = mutableMapOf<Long, List<Step>>()
        panelSequences.forEach { sq ->
            map[sq.id] = runCatching { db.sequenceDao().steps(sq.id) }.getOrDefault(emptyList())
        }
        stepsBySequence = map
    }

    // 每个面板的运行中数量，供顶部标签显示
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

    val toast: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    return PanelState(
        app = app,
        scope = scope,
        settings = settings,
        panels = panels,
        selectedPanel = selectedPanel,
        setSelectedPanel = { selectedPanel = it },
        safeIndex = safeIndex,
        currentPanel = currentPanel,
        timers = timers,
        allTimers = allTimers,
        allRefs = allRefs,
        groups = groups,
        allGroupMembers = allGroupMembers,
        alertProfiles = alertProfiles,
        allSequences = allSequences,
        panelSequences = panelSequences,
        colorMode = colorMode,
        intervalMinutes = intervalMinutes,
        fractionDivisions = fractionDivisions,
        gridColumns = gridColumns,
        runtimeMap = runtimeMap,
        groupMembersMap = groupMembersMap,
        groupedTimerIds = groupedTimerIds,
        timerOwnerGroup = timerOwnerGroup,
        sequencePanelMap = sequencePanelMap,
        memberIds = memberIds,
        thresholdMinutes = thresholdMinutes,
        thresholdColors = thresholdColors,
        stepsBySequence = stepsBySequence,
        runningCountByPanel = runningCountByPanel,
        toast = toast,
    )
}

/**
 * 面板页数据的只读快照 + 两个写入口。
 *
 * 字段都是 Compose 订阅出来的不可变值；`scope` / `toast` 给各分区做副作用。
 */
class PanelState(
    val app: ExtApplication,
    val scope: CoroutineScope,
    val settings: SettingsRepo,
    val panels: List<Panel>,
    val selectedPanel: Int,
    val setSelectedPanel: (Int) -> Unit,
    val safeIndex: Int,
    val currentPanel: Panel?,
    val timers: List<TimerItem>,
    val allTimers: List<TimerItem>,
    val allRefs: List<PanelTimerRef>,
    val groups: List<TimerGroup>,
    val allGroupMembers: List<GroupMember>,
    val alertProfiles: List<AlertProfile>,
    val allSequences: List<StepSequence>,
    val panelSequences: List<StepSequence>,
    val colorMode: ColorMode,
    val intervalMinutes: Long,
    val fractionDivisions: Int,
    val gridColumns: Int,
    val runtimeMap: Map<Long, TimerRuntime>,
    val groupMembersMap: Map<Long, List<Long>>,
    val groupedTimerIds: Set<Long>,
    val timerOwnerGroup: Map<Long, Long>,
    val sequencePanelMap: Map<Long, Long?>,
    val memberIds: Set<Long>,
    val thresholdMinutes: List<Long>,
    val thresholdColors: List<Color>,
    val stepsBySequence: Map<Long, List<Step>>,
    val runningCountByPanel: Map<Long, Int>,
    val toast: (String) -> Unit,
) {
    val db get() = app.database
    val runner get() = app.panelRunner
    val engine get() = app.sequenceEngine
    val runtimes: List<TimerRuntime> = runtimeMap.values.sortedBy { it.timerId }

    /** 跨类型统一列表：序列 / 组 / 未成组计时器一起参与排序与渲染。 */
    val gridEntries: List<PanelEntry> = buildGridEntries()

    private fun buildGridEntries(): List<PanelEntry> {
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
        return list.sortedBy { it.order }
    }

    /** 拖拽换位后按新顺序重新编号，写回各自的 order 字段。 */
    fun persistOrder(ordered: List<PanelEntry>) {
        scope.launch {
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
        }
    }
}
