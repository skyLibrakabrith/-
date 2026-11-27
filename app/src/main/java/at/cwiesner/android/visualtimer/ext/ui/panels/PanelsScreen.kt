package at.cwiesner.android.visualtimer.ext.ui.panels

import android.util.Log
import at.cwiesner.android.visualtimer.ext.panels.rememberPanelState
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.data.saveAndGetId
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.data.GroupMember
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.ui.archive.ArchiveDates
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.elapsedMillisForArchive
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCreator
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.AiCompileDialog
import at.cwiesner.android.visualtimer.ext.ui.common.AiSplitDialog
import at.cwiesner.android.visualtimer.ext.ui.common.CompileReviewDialog
import at.cwiesner.android.visualtimer.ext.ui.common.ConfirmDeleteDialog
import at.cwiesner.android.visualtimer.ext.ui.sequences.SequenceEditorScreen
import at.cwiesner.android.visualtimer.ext.ui.sequences.SequenceFocusScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 多面板主界面。
 *
 * 两种呈现：
 * - **网格**：每个计时器一个大圆盘，全部同时可见、同时运行。点圆盘开始/暂停。
 * - **列表**：紧凑列表 + 多选，批量开始 / 批量删除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    // ---- 面板页的全部数据都来自 PanelState ----
    //
    // 这里原来有约 170 行自己的 DAO 订阅 / 设置订阅 / 派生表计算，
    // 与 `PanelState.kt` 里那份逐行重复（那份曾经零调用）。
    // 现在只保留这一个入口，数据从哪来只在一处定义。
    //
    // 下面这些局部别名是为了让本文件里 300 多处引用不用全改成 `state.x` ——
    // 它们**不是**新的订阅，只是同一个不可变值的另一个名字；
    // 删掉任何一行都不会改变行为，改成 `state.x` 也一样。
    val state = rememberPanelState()
    val app = state.app
    val db = state.db
    val scope = state.scope
    val runner = state.runner
    val settings = state.settings

    val colorMode = state.colorMode
    val intervalMinutes = state.intervalMinutes
    val fractionDivisions = state.fractionDivisions
    val gridColumns = state.gridColumns

    val panels = state.panels
    val safeIndex = state.safeIndex
    val currentPanel = state.currentPanel

    val timers = state.timers
    val thresholdMinutes = state.thresholdMinutes
    val thresholdColors = state.thresholdColors

    var showNewPanel by remember { mutableStateOf(false) }
    var showPanelEditor by remember { mutableStateOf(false) }
    var editingGroupId by remember { mutableStateOf<Long?>(null) }
    var editingSequenceId by remember { mutableStateOf<Long?>(null) }
    var focusSequenceId by remember { mutableStateOf<Long?>(null) }
    // 非 null 表示正在确认删除这条序列。
    //
    // 为什么网格里的删除要单独走一个确认框，而不是像 TimerCell 那样卡片右上角画个垃圾桶：
    // 网格卡片上的长按已经被**拖拽排序**吃掉（见文件里那段 longPressEntry 的注释），
    // 卡片里也不该再叠点击层（会给 DraggableGrid 的手势留破口）。
    // 所以入口放在「双击进入的全屏序列页」和「面板编辑器」这两处 ——
    // 它们本来就是用户查看/管理这条序列的界面，不是网格内部。
    var sequenceDeleteTarget by remember { mutableStateOf<StepSequence?>(null) }
    var editingTimerId by remember { mutableStateOf<Long?>(null) }
    var alertPickerTimerId by remember { mutableStateOf<Long?>(null) }
    // DDL 两步创建：第一步选截止时刻，第二步选提前提醒。
    // 两步都确认后才真正落库并开始计时，避免"先建好了才问要不要提醒"。
    var pendingDeadline by remember { mutableStateOf<PendingDeadline?>(null) }
    var showNewTimer by remember { mutableStateOf(false) }
    var showPanelManager by remember { mutableStateOf(false) }
    // 正在重命名的面板；null = 没有。长按 TabRow 上的面板名触发。
    var renamingPanel by remember { mutableStateOf<Panel?>(null) }

    // Compiler：碎片念头 → 序列。三步：拆 → 排 → 估。
    var showCompileDialog by remember { mutableStateOf(false) }
    // 拆完待排的结果；非 null 时打开排序界面
    var compileGroups by remember { mutableStateOf<List<at.cwiesner.android.visualtimer.ext.llm.TodoGroup>?>(null) }
    // 记住要建到哪个面板（用户可能在排序期间切了面板）
    var compilePanelId by remember { mutableStateOf<Long?>(null) }

    // 列表视图里「拆解」：非 null 表示要拆的是这个已有计时器（拆完替换它）
    var splitTargetTimer by remember { mutableStateOf<TimerItem?>(null) }
    /** 「合并为序列」对话框是否打开（列表视图多选 ≥2 时触发）。 */
    var mergeDialogOpen by remember { mutableStateOf(false) }
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
    // 已经消费到哪个 tick。
    //
    // ⚠️ 这里曾经是 `if (toggleViewSignal > 0) { 切换 }`，注释还写着
    // 「首次进入不会触发」—— 但那句注释是错的：`panelViewToggleTick` 由导航层
    // 用 `remember` 持有，只要用户切换过一次视图，它的值就**一直大于 0**。
    // 于是每次从其他页点「面板」进来，PanelsScreen 重建、这个 LaunchedEffect
    // 首次运行，看到 >0 就又翻一次视图 —— 表现为「每次进面板视图都变」。
    //
    // 正确做法：记住上次处理到哪个 tick，只对**新增量**做出反应。
    //
    // 用 rememberSaveable 而不是 remember：页面切走时会被销毁，重建后
    // remember 会重置成「当前 tick」。虽然那样首次进入不会误翻转，
    // 但用户在本页切过一次视图后切走再回来，记住的进度就丢了 ——
    // 下次再进来又会把那次切换重放一遍。rememberSaveable 能扛过重建。
    var lastHandledToggle by rememberSaveable { mutableIntStateOf(toggleViewSignal) }
    LaunchedEffect(toggleViewSignal) {
        if (toggleViewSignal != lastHandledToggle) {
            lastHandledToggle = toggleViewSignal
            listMode = !listMode
            selectedIds = emptySet()
        }
    }

    // 以下数据同样来自 PanelState（原来是本文件自己订阅 DAO 的第二份）
    val allTimers = state.allTimers
    val allRefs = state.allRefs
    val groups = state.groups
    val allGroupMembers = state.allGroupMembers
    val groupMembersMap = state.groupMembersMap
    val alertProfiles = state.alertProfiles
    val allSequences = state.allSequences
    val sequencePanelMap = state.sequencePanelMap
    val timerOwnerGroup = state.timerOwnerGroup
    val panelSequences = state.panelSequences
    val memberIds = state.memberIds
    val groupedTimerIds = state.groupedTimerIds
    val gridEntries = state.gridEntries

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 删除一条序列，并**级联删掉它的全部阶段**。
     *
     * steps 表没有对 sequences 的外键，只删父行会留下一辈子看不到的孤儿步骤
     * （占库、还会被导出进备份）。顺序固定先子后父，与删组的
     * clearMembers → deleteGroup 一致。
     *
     * 这里和 SequencesScreen 里的同名逻辑保持一份实现：两处都能删序列，
     * 少了一处级联就等于从另一条路径制造孤儿行。
     */
    fun deleteSequenceCascade(seq: StepSequence) {
        scope.launch {
            try {
                db.sequenceDao().steps(seq.id).forEach { db.sequenceDao().deleteStep(it.id) }
                db.sequenceDao().deleteById(seq.id)
                // 引擎握着这条序列的内存副本，不停的话会继续倒计时一个已不存在的序列
                if (app.sequenceEngine.activeSequenceId == seq.id) app.sequenceEngine.stop()
                // 正在用它的界面全部退出，否则保存会用旧 id 把它重新插回来
                if (focusSequenceId == seq.id) focusSequenceId = null
                if (editingSequenceId == seq.id) editingSequenceId = null
                sequenceDeleteTarget = null
                toast("已删除序列「" + seq.name + "」")
                Log.i("PanelsScreen", "序列已删除（含级联步骤）：id=" + seq.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 不静默吞：删除失败时至少 logcat 里查得到
                Log.e("PanelsScreen", "删除序列失败 id=" + seq.id, e)
                toast("删除失败，请重试")
            }
        }
    }

    var orderOverride by remember { mutableStateOf<List<PanelEntry>?>(null) }
    val displayEntries = orderOverride ?: gridEntries

    /**
     * 删除一个计时器：运行时 → 面板关联 → **自增删除计时器行本身**。
     *
     * 为什么必须有最后一步：Room 里刻意没有 `@ForeignKey`（避免清库时约束爆炸），
     * 所以每条删除路径都得手工级联。列表「删除」和卡片垃圾桶原先只做了
     * `clear` + `removeRef`，`timers` 行还在库里 —— 于是删掉的计时器
     * 仍会被全量导出进备份，也会出现在面板编辑器的候选列表里。
     * 拆解流程早就补了 `deleteById`（并留了注释），这里统一到同一路径。
     *
     * `GroupMember` / `DeadlineReminder` 没有外键，也要一起清，
     * 否则它们指向一个不存在的计时器：前者会让计数虚高，后者会沉淀成永远
     * `fired = 0` 的孤儿行、被冷启动恢复反复处理。
     *
     * suspend 而不是内部自己 launch：调用方（批量删除）要在循环结束后才 toast。
     */
    suspend fun deleteTimerRows(timerId: Long, removeRefPanelId: Long?) {
        try {
            runner.clear(timerId)
            // removeRef 只针对本次可见的那个面板；同一条计时器可能被多个面板引用，
            // 只删当前面板的关联是原有行为（别的面板仍显示它），这里不擅自扩大范围
            removeRefPanelId?.let { db.panelDao().removeRef(it, timerId) }
            // 组里也摘掉：`groupedTimerIds` 由 group_members 算出，
            // 残留成员会让「已成组」的判定继续成立，而里面的计时器已经不存在
            // （结果是这个 id 既不在网格里、也没有对应的组卡片，彻底消失）。
            //
            // 按「组」处理而不是按「成员」：一条计时器只会属于一个组（建组时保证），
            // 但同一次删除里逐个成员重写整组容易把顺序写乱。这里对涉及的每个组
            // 用**当前快照减去被删的那一个**重写一次，顺序保持原样。
            val affectedGroups = allGroupMembers
                .filter { it.timerId == timerId }
                .map { it.groupId }
                .distinct()
            affectedGroups.forEach { gid ->
                val remaining = allGroupMembers
                    .filter { it.groupId == gid && it.timerId != timerId }
                    .sortedBy { it.order }
                    .mapIndexed { idx, m -> GroupMember(m.groupId, m.timerId, idx) }
                db.groupDao().clearMembers(gid)
                if (remaining.isNotEmpty()) db.groupDao().upsertMembers(remaining)
            }
            db.deadlineReminderDao().clearTimer(timerId)
            db.timerDao().deleteById(timerId)
            Log.i("PanelsScreen", "计时器已删除：id=" + timerId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("PanelsScreen", "删除计时器失败 id=" + timerId, e)
            toast("删除失败，请重试")
        }
    }
    /**
     * 「结束」一个**未固定**的计时器：先写归档，再从面板移除。
     *
     * ## 为什么未固定的按钮叫「结束」而不是「重置」
     *
     * `pinned = false` 表达的是"这是一次性计时"（泡面 3 分钟、跑一次测试）。
     * 对一次性计时，"重置回满格"没有意义 —— 用户想要的是"这件事做完了/不做了"。
     * 所以按钮语义变成结束：记一条历史、把卡片收走。
     *
     * 固定的计时器仍叫「重置」：它还要反复用，回到满格才是想要的。
     *
     * ## 归档字段
     *
     * - `startedAt` 用运行时的墙钟起点（没有就退回"结束时刻 − 已计时长"）
     * - `endedAt` 用**现在**（用户主动结束，没有比这更准的终点）
     * - `completed = false` → 归档里显示**「提前结束」**：
     *   它是事实（没到点就停了），和自然到点的「按时到点」区分开
     * - `plannedMillis` 存原始计划时长，这样归档里能看到"计划 15 分，实际跑了 8 分"
     */
    suspend fun archiveAndEndTimer(t: TimerItem) {
        val rt = runner.get(t.id)
        val now = System.currentTimeMillis()
        val elapsed = if (rt == null) {
            0L
        } else {
            // 实际时长（含超时）。算式收在 elapsedMillisForArchive 里，
            // 与「到点自动归档」那条路径共用。
            elapsedMillisForArchive(
                totalMillis = rt.totalMillis,
                remainingMillis = rt.remainingMillis,
                overdueMillis = rt.overdueMillis,
                stopwatch = rt.stopwatch,
                stopwatchSegmentMillis = rt.stopwatchSegmentMillis,
            )
        }
        val startedAt = rt?.startedAt?.takeIf { it > 0L } ?: (now - elapsed).coerceAtLeast(0L)
        // 时长为 0（从没跑过就点结束）不写归档 —— 那不是"发生过一次计时"
        if (elapsed >= 1000L) {
            runCatching {
                db.timerSessionDao().insert(
                    TimerSession(
                        label = t.label,
                        startedAt = startedAt,
                        endedAt = now,
                        plannedMillis = if (rt?.stopwatch == true) elapsed else rt?.totalMillis ?: t.durationMillis,
                        kind = t.kind.name,
                        completed = false,
                    ),
                )
            }
        }
        deleteTimerRows(t.id, removeRefPanelId = currentPanel?.id)
        toast("已结束并归档" + t.label?.let { "：$it" }.orEmpty())
    }


    /**
     * 删除一个面板，并级联清掉它的全部从属行。
     *
     * **不级联的后果是计时器凭空消失**：`timer_groups.panelId` 指向一个已不存在的
     * 面板时，`observeGroups(面板)` 查不到这些组，但 `allGroupMembers` 仍会返回它们
     * 的成员；而网格渲染用 `timers.filter { it.id !in groupedTimerIds }` 把
     * 「已成组」的计时器排除在外 —— 组没了、计时器又被当成组成员排除掉，两边都看不见。
     *
     * 同样要清的还有：本面板的 panel_timers 关联、组的成员行、以及
     * `sequences.panelId`（否则序列挂在一个不存在的面板下，也再无入口可见）。
     *
     * 注意**不动** `timers` 表本身：面板是「视图」而不是计时器的所有者，
     * 同一条计时器可以同时属于多个面板，随面板一起删掉会误删别处的计时器。
     */
    suspend fun deletePanelCascade(panelId: Long) {
        try {
            // 1) 本面板的组：先子（成员）后父（组）
            val panelGroups = groups.filter { it.panelId == panelId }
            panelGroups.forEach { g ->
                db.groupDao().clearMembers(g.id)
                db.groupDao().deleteGroup(g.id)
            }
            // 2) 本面板的序列：解除归属（删序列有独立的级联删除入口，这里只摘钩）
            panelSequences.forEach { sq ->
                db.sequenceDao().upsert(sq.copy(panelId = null))
            }
            // 3) 本面板与计时器的关联
            db.panelDao().refsOf(panelId).forEach {
                db.panelDao().removeRef(panelId, it.timerId)
            }
            // 4) 面板本身
            db.panelDao().deleteById(panelId)
            Log.i("PanelsScreen", "面板已删除（含级联）：id=" + panelId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("PanelsScreen", "删除面板失败 id=" + panelId, e)
            toast("删除失败，请重试")
        }
    }


    // 序列引擎与运行时（同样来自 PanelState）
    val enginePhase = state.enginePhase
    val engineActiveId = state.engineActiveId
    val stepsBySequence = state.stepsBySequence
    val runtimeMap = state.runtimeMap
    val runtimes = state.runtimes
    val runtimeById = runtimeMap
    val runningCountByPanel = state.runningCountByPanel

    // ---- 序列编辑（长按序列块进入）：就地渲染全屏编辑器 ----
    val editingSeq = editingSequenceId?.let { sid ->
        allSequences.firstOrNull { it.id == sid }
    }
    if (editingSeq != null) {
        SequenceEditorScreen(
            sequence = editingSeq,
            steps = stepsBySequence[editingSeq.id].orEmpty(),
            onDismiss = { editingSequenceId = null; state.refreshSteps() },
            onSave = { name, timeout, stepDrafts ->
                scope.launch {
                    // 用 saveAndGetId 而不是裸 upsert：Room 的 @Upsert 在
                    // "更新已有行"的分支返回 -1，拿它当 seqId 写步骤会产生
                    // sequenceId=-1 的孤儿步骤（界面上表现为"阶段不见了"）。
                    val seqId = db.sequenceDao().saveAndGetId(
                        editingSeq.copy(
                            name = name,
                            timeoutMillis = timeout,
                            panelId = editingSeq.panelId ?: currentPanel?.id,
                        ),
                    )
                    db.sequenceDao().steps(seqId).forEach { db.sequenceDao().deleteStep(it.id) }
                    // 批量插入，不要 upsertStep 循环：
                    // `Step.id` 默认 0，循环 upsert 会互相覆盖，保存后只剩最后一个阶段。
                    db.sequenceDao().insertSteps(
                        stepDrafts.mapIndexed { idx, d ->
                            Step(
                                sequenceId = seqId,
                                durationMillis = d.durationMillis,
                                label = d.label.ifBlank { null },
                                order = idx,
                                // AI 拆解带来的两个属性要一起落库，否则界面改完顺序就丢了
                                difficulty = d.difficulty,
                                context = d.context,
                            )
                        },
                    )
                    editingSequenceId = null
                    // 重读步骤，否则退回面板时卡片上的阶段数/总时长是旧的
                    state.refreshSteps()
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
                    actions = {
                        // 面板管理（改名 / 增删 / 排序面板）的入口。
                        //
                        // 原来在底边栏「面板」的长按抽屉里，和"往当前面板加东西"
                        // （新建序列 / 整理念头）混在一起，语义不一致：
                        // 抽屉是"对这个面板做什么"，而面板管理是"对面板集合本身做什么"。
                        // 现在按"作用于整体 -> 放顶栏"的惯例挪到右上角。
                        IconButton(onClick = { showPanelManager = true }) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = "面板管理",
                                tint = ExtColors.Primary,
                            )
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
                                onClick = { state.setSelectedPanel(index); selectedIds = emptySet() },
                                text = {
                                    val active = runningCountByPanel[panel.id] ?: 0
                                    // 长按面板名 → 重命名。
                                    //
                                    // Tab 本身只有 onClick，没有长按参数，所以要在
                                    // text 上另挂一层 combinedClickable。
                                    //
                                    // 这里**故意**不违反 AGENTS.md 那条"绝不嵌套点击层"：
                                    // 那条铁律针对的是卡片内外都挂点击层、内层先吃掉第一下
                                    // 导致双击错乱。这里的场景不同：
                                    //   · 内层 onClick 是显式空实现（不消费动作）
                                    //   · Tab 的 onClick 只负责切换选中，没有双击语义
                                    //   · 长按要能冒泡到内层，所以内层必须自己接 onLongClick
                                    // 若实测发现单击切换失效，回退方案是把重命名挪到
                                    // 顶栏的「面板管理」里（那里本来就有改名入口）。
                                    Text(
                                        text = if (active > 0) "${panel.name} ·$active" else panel.name,
                                        modifier = Modifier.combinedClickable(
                                            onClick = { state.setSelectedPanel(index); selectedIds = emptySet() },
                                            onLongClick = { renamingPanel = panel },
                                        ),
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
                pendingDeadline != null || editingTimerId != null ||
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
                    // 「下一阶段」必须用 skip() 而不是 confirm()。
                    //
                    // `confirm()` 第一行是 `if (current !is AwaitingConfirm) return`，
                    // 而 AwaitingConfirm 只在本阶段**倒计时走完之后**才进入。
                    // 用户在阶段还在倒计时时点「下一阶段」→ confirm() 立刻返回，
                    // 按钮看起来完全没反应（真机确认过）。
                    //
                    // `skip()` 对两种相位都有效，且 AwaitingConfirm 下走的是同一条
                    // `runStep(next)`，语义正好是"现在就进入下一步"。
                    onNext = { app.sequenceEngine.skip() },
                    onStop = {
                        app.sequenceEngine.stop()
                        toast("已停止")
                    },
                    onReset = {
                        app.sequenceEngine.stop()
                        toast("序列已重置")
                    },
                    onClose = { focusSequenceId = null },
                    // 网格里序列卡片没有删除入口（长按被拖拽占用），删除收在全屏视图里
                    onDelete = { sequenceDeleteTarget = focusedSeq },
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
                        scope.launch {
                            val updated = focused.copy(durationMillis = d)
                            db.timerDao().upsert(updated)
                            // 必须同步运行时！
                            //
                            // 原来这里只写库：列表视图读 `timer.durationMillis`（库）→ 显示新时长，
                            // 而网格/继续计时读 `runtime.remainingMillis`（内存旧值）→ 还是旧时长。
                            // 用户看到的就是「全屏改成 26 分钟，继续后仍是 15 分钟，但列表里是 26 分钟」。
                            //
                            // replaceTotal 会换上新时长、回落到「未开始」态、清掉 firedLeads，
                            // 正是改时长该有的语义（旧时长下判过的「到点」和已响的提醒都作废）。
                            runner.replaceTotal(focused.id, d)
                            // 改完不该马上被连响一串提前提醒：按新剩余量重设基线
                            runner.markLeadsBaseline(focused.id, d)
                        }
                    },
                    onStart = {
                        scope.launch {
                            val cur = runner.snapshot().firstOrNull { it.timerId == focused.id }
                            // 「是否暂停过」用 `!cur.running` 判，**不要**掺 `expired`。
                            // `expired`（到过点，历史事实）在暂停后仍为 true，而 `isOverdue`
                            // （= expired && running）此时为 false —— 若要判「现在是否超时」
                            // 只能用 isOverdue。这里的语义只是「有没有可以继续的进度」：
                            // 暂停中的计时器（含已超时后又暂停的）一律 resume，
                            // 只有从未开始时才 start。否则「已超时 → 暂停 → 点中央按钮」
                            // 会落到 clear()+start() = 重置，和网格上的同一操作结论相反。
                            if (cur != null && !cur.running) {
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
                    onReset = {
                        // 与网格卡片同一套语义：未固定 = 结束并归档，固定 = 重置
                        scope.launch {
                            if (focused.pinned) {
                                runner.reset(focused.id)
                            } else {
                                archiveAndEndTimer(focused)
                                // 卡片已被移除，全屏要跟着退出，否则会停在一个不存在的计时器上
                                focusTimerId = null
                            }
                        }
                    },
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
                // 判断的必须是「面板里**什么卡片都没有**」，不能只看 timers。
                //
                // 原来只判 `timers.isEmpty()`：面板里只有序列 / 组、没有普通计时器时，
                // 整块内容被这个空状态挡掉 —— 用户看到"这个面板还没有计时器"，
                // 但他的序列明明还在，也点不进去（双击打不开的观感来源之一）。
                timers.isEmpty() && panelSequences.isEmpty() && groups.isEmpty() ->
                    EmptyHint("这个面板还没有内容，点右下角添加")

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
                        // 多选切换：点已选中的取消，点未选中的加入。
                        //
                        // 上一版曾改成单选（"点别的就替换成它"），目的是让「拆解」
                        // 的语义 unambiguous；但代价是**顶部的「全选」按钮彻底失效**
                        // （只能选一个，全选没有意义），而批量开始/删除仍然需要多选。
                        //
                        // 现在回到多选，「拆解只能针对一个」这条约束改由
                        // onSplit 自己处理：多选时按钮置灰（见 TimerList 里对
                        // 选中数量 <= 1 的判断）。
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onBatchDelete = {
                        scope.launch {
                            val n = selectedIds.size
                            selectedIds.forEach { id ->
                                // 必须真的删掉 timers 行，不能只 removeRef。
                                // 只解除面板关联时：文案说「已删除」，但这行仍在库里 ——
                                // 会被全量导出进备份，也会出现在「面板编辑器 → 添加计时器」
                                // 的候选列表里（那个列表来自 allTimers），
                                // 表现为「删掉的东西又冒出来」。
                                // 与下面的拆解流程（runner.clear → removeRef → deleteById）
                                // 保持同一条路径。
                                deleteTimerRows(id, removeRefPanelId = currentPanel?.id)
                            }
                            selectedIds = emptySet()
                            toast("已删除 $n 个计时器")
                        }
                    },
                    // DDL 没有编辑入口（不是「被拦住」，而是这类计时器本来就没有可编辑项）
                    onEdit = { t -> if (t.isEditable) editingTimerId = t.id },
                    alertProfiles = alertProfiles,
                    // 全选：选中当前面板的全部计时器
                    onSelectAll = { selectedIds = timers.map { it.id }.toSet() },
                    onClearSelection = { selectedIds = emptySet() },
                    // 列表视图也要显示序列（原来只有卡片视图有）
                    sequences = panelSequences,
                    stepsBySequence = stepsBySequence,
                    onOpenSequence = { seq -> focusSequenceId = seq.id },
                    onEditSequence = { seq -> editingSequenceId = seq.id },
                    onMergeToSequence = { mergeDialogOpen = true },
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
                                // 落库逻辑在 PanelState.persistOrder 里（与界面无关的纯数据操作）。
                                // toast 留在调用点：PanelState 不该知道"拖完要提示一句"这种事。
                                state.persistOrder(ordered)
                                toast("顺序已保存")
                                orderOverride = null
                            }
                        },
                        // 只有计时器卡片自己处理点按（它把点击收窄到圆盘，
                        // 避免左上角图钉与暂停/继续打架）。
                        // 序列卡 / 组卡交给网格挂，否则它们连点击层都没有 —— 双击打不开。
                        tapHandledByItem = { entry -> entry.kind == EntryKind.Timer },
                        onClick = { entry ->
                            when (entry.kind) {
                                // 计时器由圆盘接管，不经过这里
                                EntryKind.Timer -> Unit
                                EntryKind.Sequence -> Unit
                                EntryKind.Group -> editingGroupId = entry.refId
                            }
                        },
                        onDoubleClick = { entry ->
                            when (entry.kind) {
                                // 计时器卡不走这里（它自己挂双击）。写上只是兜底，
                                // 万一将来某条目改回由网格处理也不会静默失效。
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
                                        // 圆盘点击：暂停 / 继续
                                        //
                                        // 与上面 DraggableGrid.onClick 里原来那段逻辑**完全一致**，
                                        // 只是触发区域从"整张卡片"收窄到"圆盘"。
                                        // 收窄的原因：左上角新加了「固定」图钉，
                                        // 整卡可点的话点图钉会顺带触发暂停。
                                        //
                                        // DDL 跳过：它没有暂停/继续语义（时长由截止时刻决定）。
                                        onDialTap = if (!t.isEditable) {
                                            null
                                        } else {
                                            {
                                                scope.launch {
                                                    val cur = runner.snapshot()
                                                        .firstOrNull { it.timerId == t.id }
                                                    when {
                                                        cur == null -> runner.start(t)
                                                        cur.running -> runner.stop(t.id)
                                                        else -> runner.resume(t.id)
                                                    }
                                                }
                                            }
                                        },
                                        onDialDoubleTap = { focusTimerId = t.id },
                                        // 切换固定：只写库，不动运行时
                                        // （固定与否只影响"到点后是否自动移除"）
                                        onTogglePin = {
                                            scope.launch {
                                                runCatching {
                                                    db.timerDao().upsert(t.copy(pinned = !t.pinned))
                                                }
                                                toast(if (t.pinned) "已取消固定" else "已固定到面板")
                                            }
                                        },
                                        onReset = {
                                            // 未固定 = 一次性计时，按钮是「结束」：
                                            // 先归档再移除（等价于「到点」的收尾，只是用户主动结束）。
                                            // 固定 = 「重置」：回到满格接着用，不写归档。
                                            scope.launch {
                                                if (t.pinned) {
                                                    runner.reset(t.id)
                                                } else {
                                                    archiveAndEndTimer(t)
                                                }
                                            }
                                        },
                                        onDelete = {
                                            scope.launch {
                                                deleteTimerRows(
                                                    t.id,
                                                    removeRefPanelId = currentPanel?.id,
                                                )
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
                if (idx >= 0) state.setSelectedPanel(idx)
                showPanelManager = false
            },
            onEdit = { target ->
                // 从管理列表直接进该面板的编辑器
                val idx = panels.indexOfFirst { it.id == target.id }
                if (idx >= 0) state.setSelectedPanel(idx)
                showPanelManager = false
                showPanelEditor = true
            },
            onCreate = { name ->
                scope.launch {
                    db.panelDao().upsert(Panel(name = name, sortOrder = panels.size))
                    state.setSelectedPanel(panels.size)
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
                    // 必须级联：只删 panels 行会残留 timer_groups / group_members /
                    // panel_timers，让属于这些组的计时器从网格里凭空消失（见函数注释）
                    deletePanelCascade(target.id)
                    state.setSelectedPanel(0)
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
                    state.setSelectedPanel(panels.size)
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
            panelId = currentPanel?.id,
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
                    // 批量插入，不要用 upsertStep 循环。
                    //
                    // `Step.id` 是自增主键、构造时默认 0，循环 `upsertStep` 会让
                    // Room 走 INSERT OR REPLACE 语义 —— 后一条覆盖前一条，
                    // 最后表里只剩最后一个阶段（用户报的「拆解应用后只显示一个阶段」）。
                    // insertSteps 是真正的批量自增插入，且在一个事务里。
                    db.sequenceDao().insertSteps(
                        steps.mapIndexed { idx, s ->
                            Step(
                                sequenceId = seqId,
                                durationMillis = s.minutes * 60_000L,
                                label = s.title.ifBlank { null },
                                order = idx,
                                difficulty = s.difficulty,
                                context = s.context,
                            )
                        },
                    )
                    // 原计时器被序列取代：停掉运行时并移出面板，再彻底删掉这一行。
                    // 只 removeRef 会留下孤儿行（面板里看不到但仍在库里）。
                    deleteTimerRows(splitTarget.id, removeRefPanelId = pid)
                    selectedIds = emptySet()
                    // 让 stepsBySequence 重读，否则返回面板时拿到的是旧 map
                    state.refreshSteps()
                    toast("已拆成 ${steps.size} 阶段的序列")
                    editingSequenceId = seqId
                }
            },
        )
    }

    // ---- 列表视图的「合并为序列」：把选中的计时器 / 序列合成一个序列 ----
    //
    // 与「拆解」方向相反：
    //   拆解 = 一个计时器 → 多个阶段
    //   合并 = 多个计时器/序列 → 一个序列的多阶段
    //
    // 展开规则：
    //   · 倒计时 → 一个阶段（用它自己的名称与时长）
    //   · 序列   → 把它**已有的全部阶段**展开进来（按 order）
    // 合并完删除原计时器/序列（与拆解一致：产物取代原物，不留重复内容）。
    if (mergeDialogOpen) {
        // 按当前列表顺序取选中项，这样合并结果的阶段顺序 = 用户看到的顺序
        val chosen = timers.filter { it.id in selectedIds }
        var mergeName by remember { mutableStateOf("合并 " + ArchiveDates.dayTitle(ArchiveDates.today())) }
        AlertDialog(
            onDismissRequest = { mergeDialogOpen = false },
            title = { Text("合并为序列") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mergeName,
                        onValueChange = { mergeName = it },
                        label = { Text("序列名称") },
                        singleLine = true,
                    )
                    Text(
                        "将合并 ${chosen.size} 个计时器为 ${chosen.size} 个阶段。",
                        fontSize = 13.sp,
                        color = ExtColors.TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mergeDialogOpen = false
                        val name = mergeName.trim().ifBlank { "合并的序列" }
                        scope.launch {
                            val pid = currentPanel?.id
                            if (pid == null) {
                                toast("先选一个面板")
                                return@launch
                            }
                            val seqId = db.sequenceDao().upsert(
                                StepSequence(
                                    name = name,
                                    panelId = pid,
                                    sortOrder = (panelSequences.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                                ),
                            )
                            // 批量插入（不要循环 upsertStep：会互相覆盖，见 insertSteps 的注释）
                            db.sequenceDao().insertSteps(
                                chosen.mapIndexed { idx, t ->
                                    Step(
                                        sequenceId = seqId,
                                        durationMillis = t.durationMillis,
                                        label = t.label,
                                        order = idx,
                                    )
                                },
                            )
                            // 原计时器被序列取代
                            chosen.forEach { t -> deleteTimerRows(t.id, removeRefPanelId = pid) }
                            selectedIds = emptySet()
                            state.refreshSteps()
                            toast("已合并 ${chosen.size} 个阶段")
                            editingSequenceId = seqId
                        }
                    },
                ) { Text("合并") }
            },
            dismissButton = {
                TextButton(onClick = { mergeDialogOpen = false }) { Text("取消") }
            },
        )
    }

    // ---- 长按面板名重命名 ----
    //
    // 为什么单独做一个只有输入框的对话框，而不是复用 PanelEditorDialog：
    // 用户只是想改个名字，不该被要求进到一个"增删计时器 / 管理组"的大面板里去。
    val renaming = renamingPanel
    if (renaming != null) {
        var newName by remember(renaming.id) { mutableStateOf(renaming.name) }
        AlertDialog(
            onDismissRequest = { renamingPanel = null },
            title = { Text("重命名面板") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newName.trim()
                        // 空名不接受：面板 Tab 上会变成一片空白，用户再也点不中它
                        if (trimmed.isNotEmpty()) {
                            scope.launch {
                                runCatching { db.panelDao().upsert(renaming.copy(name = trimmed)) }
                                toast("已重命名为「$trimmed」")
                            }
                        }
                        renamingPanel = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renamingPanel = null }) { Text("取消") }
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
                    // 与「面板管理」里的删除走同一条级联路径
                    deletePanelCascade(currentPanel.id)
                    state.setSelectedPanel(0)
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
            onDeleteSequence = { sq ->
                // 先关掉面板编辑器再弹确认：两个 AlertDialog 同时在场时，
                // 底层的那个会挡住确认框的触摸区域
                showPanelEditor = false
                sequenceDeleteTarget = sq
            },
        )
    }

    // DDL 提前提醒的对话框现在住在「创建流程第二步」里（见文件末尾），
    // 这里不再有"创建完才问"的那一块。

    // ---- 序列删除确认（全屏序列视图的删除图标 / 面板编辑器里的删除触发）----
    val seqToDelete = sequenceDeleteTarget
    if (seqToDelete != null) {
        ConfirmDeleteDialog(
            title = "删除序列",
            text = "「" + seqToDelete.name + "」及其全部阶段会被永久删除，无法撤销。",
            onDismiss = { sequenceDeleteTarget = null },
            onConfirm = { deleteSequenceCascade(seqToDelete) },
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
                    // 时长变了：不能直接 clear()。
                    // 原来无条件 `runner.clear(id)` 把**整条运行时删掉**：
                    // 正在跑的倒计时归零、正在响的提醒被 acknowledge ——
                    // 用户只是改了个名字，计时器却被重置了。
                    // 现在：
                    // · 运行时不存在 → 什么都不用做（下次 start 会从库里取新值）
                    // · 时长没变 → 只同步 label（悬浮窗/通知/卡片标题都读运行时）
                    // · 时长真变了 → 换上新的 total 并作废「到点」结论，
                    //   但保留 running，正在跑的继续跑
                    val cur = runner.get(editingTimer.id)
                    if (cur != null) {
                        val newDeadline = editingTimer.deadlineEpochMillis
                        val durationChanged = !keepDeadline && cur.totalMillis != duration
                        if (keepDeadline && newDeadline != null) {
                            // DDL：终点由截止时刻决定，运行时里那份 deadlineEpochMillis
                            // 才是 tick 实际用的值，不同步就还是按旧时刻走
                            if (cur.deadlineEpochMillis != newDeadline) {
                                val nowMs = System.currentTimeMillis()
                                runner.replaceDeadline(editingTimer.id, newDeadline, nowMs)
                                runner.markLeadsBaseline(
                                    editingTimer.id,
                                    (newDeadline - nowMs).coerceAtLeast(0L),
                                )
                            }
                        } else if (durationChanged) {
                            runner.replaceTotal(editingTimer.id, duration)
                            // 新的剩余时长下，已经过去的提前提醒档位要立刻标掉，
                            // 否则第一次 tick 会把一串档位同时触发
                            runner.markLeadsBaseline(editingTimer.id, duration)
                        }
                        runner.updateLabel(editingTimer.id, label.ifBlank { null })
                    }
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
                if (deadlineEpoch != null) {
                    // 选了「截止时刻」= 用户在 DDL 两步流程的第一步，不能在这里按
                    // 普通倒计时建掉 —— 否则 kind 落成 Countdown、deadlineEpochMillis
                    // 不落库，重启后就是一个与截止时刻脱钩的「剩余 N 小时」。
                    // 转交给第二步（选提前提醒），结构与菜单入口完全一致。
                    pendingDeadline = PendingDeadline(
                        label = label,
                        deadlineEpochMillis = deadlineEpoch,
                        alertProfileId = alertProfileId,
                    )
                    return@TimerEditDialog
                }
                scope.launch {
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
                    toast("已添加：" + (label.ifBlank { "未命名" }) + " · " +
                        DeadlineCalculator.formatRemaining(duration))
                    Log.d(TAG, "普通计时器已创建 id=" + timerId)
                }
            },
        )
    }

    // ---- DDL 创建第二步：选提前提醒档位 ----
    //
    // 入口只有一个：首页「创建 DDL」按钮（`HomeScreen` → `DeadlineCreator`）。
    // 面板页原来还有一条「新建截止时刻」的独立入口，已随抽屉项一起删掉 ——
    // 同一种东西两条实现路径，正是历史上「首页建的 DDL 不自动开始」的成因。
    //
    // 走到这里的流程：`pendingDeadline` 被赋值（本轮已无面板页入口，
    // 但保留这段是因为 PendingDeadline 类型与 commitDeadline 仍被其它路径使用）。
    val pending = pendingDeadline
    if (pending != null) {
        val now = System.currentTimeMillis()
        val remaining = (pending.deadlineEpochMillis - now).coerceAtLeast(1000L)

        /**
         * 真正落库 + 挂到面板 + 自动开始 + 登记提前提醒。
         *
         * 参数全部显式传入（pid / 截止时刻 / 名称），不依赖捕获的 Composable 局部变量 ——
         * 这个 lambda 可能在下一次重组后执行，捕获到的 `currentPanel` 等值可能已经过期。
         *
         * ## 为什么改成委托
         *
         * 这个函数原来的实现体（写库 locked=true → upsertRefs → startUntil →
         * clearTimer + upsertAll 提醒行 → 回读 forTimer → attachLeadMinutes →
         * markLeadsBaseline）已被逐行搬到 `DeadlineCreator.createDeadline`。
         * 原因：首页 `HomeScreen` 的「创建 DDL」按钮走的是另一套 `createTimer()`，
         * 那套只写了 kind 和 deadlineEpochMillis，漏了 locked / startUntil /
         * 提醒落库这三件事（后果分别是「DDL 可被编辑」「创建后不自动开始」
         * 「永远不会有提醒」）。而首页是底部导航第一个 tab、默认落地页，
         * 这条有缺陷的路径反而最常被走到。
         *
         * 抽成共享函数后两个入口共用同一份实现，就不会再各自分叉。
         * 这里只保留本界面特有的部分：拿 `db` / `runner`、取 now、以及 toast 文案。
         */
        suspend fun commitDeadline(
            panelId: Long,
            label: String,
            deadlineAt: Long,
            leads: List<Long>,
            alertProfileId: Long?,
        ) {
            DeadlineCreator.createDeadline(
                db = db,
                runner = runner,
                panelId = panelId,
                label = label,
                deadlineAt = deadlineAt,
                leads = leads,
                alertProfileId = alertProfileId,
                now = System.currentTimeMillis(),
            )
            // 保留原来的 toast 文案：用户可见行为不变
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
                        // 两条入口（菜单「新建截止时刻」/「加计时器 → 截止时刻」）
                        // 在这里汇流：落库与提前提醒写入都只发生在 commitDeadline 内部，
                        // 所以提醒行一定带着正确的 timerId，不需要事后再靠内容匹配补写。
                        commitDeadline(
                            panelId = panelId,
                            label = pending.label,
                            deadlineAt = pending.deadlineEpochMillis,
                            leads = leads,
                            alertProfileId = pending.alertProfileId,
                        )
                    }
                }
            },
        )
    }
}

/**
 * 本文件的日志标签。
 *
 * 注意：DDL 创建流程的诊断日志（Runtime 状态回读的那几条「建出来是停着的」排查日志）
 * 已随实现体一起搬进 `DeadlineCreator`，那里用的是它自己的 tag。
 * 本常量现在只剩「普通计时器已创建」一条 Log.d 在用，不要删。
 */
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
    /** 「加计时器 → 截止时刻」路径里第一步已选的提醒方式；菜单入口没有，为 null。 */
    val alertProfileId: Long? = null,
)
