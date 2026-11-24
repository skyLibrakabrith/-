package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.GroupMember
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.domain.PanelRunner
import at.cwiesner.android.visualtimer.ext.settings.ColorThreshold
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 面板页的 ViewModel。
 *
 * 把原先散在 `PanelsScreen` 里的 **40 多个 `remember` 状态**收敛到这里：
 * - 所有 Room 数据流（面板、计时器、组、序列、警报配置）集中订阅
 * - 所有写操作（增删改、排序、成组、分配序列）集中成方法
 * - 界面只负责渲染 + 把用户操作转发过来
 *
 * 好处：屏幕旋转不丢状态、逻辑可单测、PanelsScreen 只关心布局。
 */
class PanelsViewModel(
    private val db: AppDatabase,
    private val runner: PanelRunner,
    private val settings: SettingsRepo,
) : ViewModel() {

    // ---------------- 设置 ----------------

    val thresholds: StateFlow<List<ColorThreshold>> = settings.thresholds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val colorMode: StateFlow<ColorMode> = settings.colorMode
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ColorMode.Threshold,
        )

    val intervalMinutes: StateFlow<Long> = settings.intervalMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 5L)

    val fractionDivisions: StateFlow<Int> = settings.fractionDivisions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 4)

    val gridColumns: StateFlow<Int> = settings.gridColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    // ---------------- 基础数据 ----------------

    val panels: StateFlow<List<Panel>> = db.panelDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTimers: StateFlow<List<TimerItem>> = db.timerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val alertProfiles: StateFlow<List<AlertProfile>> = db.alertProfileDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSequences: StateFlow<List<StepSequence>> = db.sequenceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allGroupMembers: StateFlow<List<GroupMember>> = db.groupDao().observeAllMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------- 选中面板 ----------------

    private val _selectedPanelIndex = MutableStateFlow(0)
    val selectedPanelIndex: StateFlow<Int> = _selectedPanelIndex.asStateFlow()

    /** 当前面板（越界时自动收敛到最后一个）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPanel: StateFlow<Panel?> = combine(
        panels,
        _selectedPanelIndex,
    ) { list, index ->
        list.getOrNull(index.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前面板内的计时器。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentTimers: StateFlow<List<TimerItem>> = currentPanel
        .flatMapLatest { p ->
            if (p == null) flowOf(emptyList())
            else db.panelDao().observePanelTimers(p.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前面板内的组。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentGroups: StateFlow<List<TimerGroup>> = currentPanel
        .flatMapLatest { p ->
            if (p == null) flowOf(emptyList())
            else db.groupDao().observeGroups(p.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 归属当前面板的序列。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSequences: StateFlow<List<StepSequence>> = currentPanel
        .flatMapLatest { p ->
            if (p == null) flowOf(emptyList())
            else db.sequenceDao().observeByPanel(p.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 组 id → 成员计时器 id（按 order 排序）。 */
    val groupMembersMap: StateFlow<Map<Long, List<Long>>> = allGroupMembers
        .let { src ->
            combine(src) { list ->
                list.groupBy { it.groupId }.mapValues { (_, items) ->
                    items.sortedBy { it.order }.map { it.timerId }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 已编组的计时器 id 集合。 */
    val groupedTimerIds: StateFlow<Set<Long>> = allGroupMembers
        .let { src ->
            combine(src) { list -> list.map { it.timerId }.toSet() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 计时器 id → 归属组 id。 */
    val timerOwnerGroup: StateFlow<Map<Long, Long>> = allGroupMembers
        .let { src ->
            combine(src) { list -> list.associate { it.timerId to it.groupId } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 序号映射：序列 id → panelId。 */
    val sequencePanelMap: StateFlow<Map<Long, Long?>> = allSequences
        .let { src ->
            combine(src) { list -> list.associate { it.id to it.panelId } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 序列 id → 步骤列表（用于卡片显示阶段数）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stepsBySequence: StateFlow<Map<Long, List<Step>>> = currentSequences
        .flatMapLatest { list ->
            kotlinx.coroutines.flow.flow {
                val map = mutableMapOf<Long, List<Step>>()
                list.forEach { sq ->
                    map[sq.id] = runCatching { db.sequenceDao().steps(sq.id) }
                        .getOrDefault(emptyList())
                }
                emit(map)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ---------------- 跨类型统一列表（供拖拽网格） ----------------

    // combine 有 4 路专用重载，比数组 + 强转更安全
    val gridEntries: StateFlow<List<PanelEntry>> = combine(
        currentSequences,
        currentGroups,
        currentTimers,
        groupedTimerIds,
    ) { seqs, groups, timers, grouped ->
        val list = mutableListOf<PanelEntry>()
        seqs.forEach { list += PanelEntry(EntryKind.Sequence, it.id, it.name, it.sortOrder) }
        groups.forEach { g -> list += PanelEntry(EntryKind.Group, g.id, g.name, g.order) }
        timers.filter { it.id !in grouped }.forEach { t ->
            list += PanelEntry(EntryKind.Timer, t.id, t.label ?: "未命名", t.position)
        }
        list.sortedBy { it.order }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------- 界面交互状态 ----------------

    private val _listMode = MutableStateFlow(false)
    val listMode: StateFlow<Boolean> = _listMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** 拖动排序期间只在本地镜像上交换，松手才落库。 */
    private val _orderOverride = MutableStateFlow<List<PanelEntry>?>(null)
    val orderOverride: StateFlow<List<PanelEntry>?> = _orderOverride.asStateFlow()

    private val _focusTimerId = MutableStateFlow<Long?>(null)
    val focusTimerId: StateFlow<Long?> = _focusTimerId.asStateFlow()

    private val _focusSequenceId = MutableStateFlow<Long?>(null)
    val focusSequenceId: StateFlow<Long?> = _focusSequenceId.asStateFlow()

    private val _editingTimerId = MutableStateFlow<Long?>(null)
    val editingTimerId: StateFlow<Long?> = _editingTimerId.asStateFlow()

    private val _editingGroupId = MutableStateFlow<Long?>(null)
    val editingGroupId: StateFlow<Long?> = _editingGroupId.asStateFlow()

    private val _editingSequenceId = MutableStateFlow<Long?>(null)
    val editingSequenceId: StateFlow<Long?> = _editingSequenceId.asStateFlow()

    private val _alertPickerTimerId = MutableStateFlow<Long?>(null)
    val alertPickerTimerId: StateFlow<Long?> = _alertPickerTimerId.asStateFlow()

    /** DDL 创建后要问的提前提醒：timerId to 剩余毫秒。 */
    private val _leadTimeTarget = MutableStateFlow<Pair<Long, Long>?>(null)
    val leadTimeTarget: StateFlow<Pair<Long, Long>?> = _leadTimeTarget.asStateFlow()

    private val _showPanelManager = MutableStateFlow(false)
    val showPanelManager: StateFlow<Boolean> = _showPanelManager.asStateFlow()

    private val _showPanelEditor = MutableStateFlow(false)
    val showPanelEditor: StateFlow<Boolean> = _showPanelEditor.asStateFlow()

    private val _showNewTimer = MutableStateFlow(false)
    val showNewTimer: StateFlow<Boolean> = _showNewTimer.asStateFlow()

    private val _showDeadline = MutableStateFlow(false)
    val showDeadline: StateFlow<Boolean> = _showDeadline.asStateFlow()

    private val _showOrderDialog = MutableStateFlow(false)
    val showOrderDialog: StateFlow<Boolean> = _showOrderDialog.asStateFlow()

    // ---- setter ----

    fun selectPanel(index: Int) { _selectedPanelIndex.value = index }
    fun toggleListMode() {
        _listMode.value = !_listMode.value
        _selectedIds.value = emptySet()
    }
    fun toggleSelection(id: Long) {
        val cur = _selectedIds.value
        _selectedIds.value = if (id in cur) cur - id else cur + id
    }
    fun selectAll() { _selectedIds.value = currentTimers.value.map { it.id }.toSet() }
    fun clearSelection() { _selectedIds.value = emptySet() }
    fun setOrderOverride(v: List<PanelEntry>?) { _orderOverride.value = v }
    fun setFocusTimer(id: Long?) { _focusTimerId.value = id }
    fun setFocusSequence(id: Long?) { _focusSequenceId.value = id }
    fun setEditingTimer(id: Long?) { _editingTimerId.value = id }
    fun setEditingGroup(id: Long?) { _editingGroupId.value = id }
    fun setEditingSequence(id: Long?) { _editingSequenceId.value = id }
    fun setAlertPicker(id: Long?) { _alertPickerTimerId.value = id }
    fun setLeadTimeTarget(v: Pair<Long, Long>?) { _leadTimeTarget.value = v }
    fun setShowPanelManager(v: Boolean) { _showPanelManager.value = v }
    fun setShowPanelEditor(v: Boolean) { _showPanelEditor.value = v }
    fun setShowNewTimer(v: Boolean) { _showNewTimer.value = v }
    fun setShowDeadline(v: Boolean) { _showDeadline.value = v }
    fun setShowOrderDialog(v: Boolean) { _showOrderDialog.value = v }

    // ---------------- 写操作 ----------------

    /** 新建计时器并挂到当前面板。 */
    fun createTimer(
        label: String?,
        durationMillis: Long,
        alertProfileId: Long?,
        deadlineEpochMillis: Long? = null,
    ) {
        val panel = currentPanel.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val isDeadline = deadlineEpochMillis != null
            val timerId = db.timerDao().upsert(
                TimerItem(
                    label = label,
                    durationMillis = durationMillis,
                    alertProfileId = alertProfileId,
                    kind = if (isDeadline) TimerKind.Deadline else TimerKind.Countdown,
                    deadlineEpochMillis = deadlineEpochMillis,
                    // DDL 锁定时长：时间由截止时刻唯一决定
                    locked = isDeadline,
                ),
            )
            val existing = db.panelDao().refsOf(panel.id)
            db.panelDao().upsertRefs(
                listOf(PanelTimerRef(panel.id, timerId, existing.size)),
            )
            val created = db.timerDao().get(timerId)
            if (created != null) {
                if (isDeadline) {
                    runner.startUntil(created, deadlineEpochMillis!!, now)
                    _leadTimeTarget.value = timerId to (durationMillis / 60_000L * 60_000L)
                }
            }
        }
    }

    /** 更新计时器。时长变化后重置运行态，避免旧倒计时残留。 */
    fun updateTimer(timer: TimerItem, label: String?, durationMillis: Long, alertProfileId: Long?) {
        viewModelScope.launch {
            db.timerDao().upsert(
                timer.copy(
                    label = label,
                    durationMillis = durationMillis,
                    alertProfileId = alertProfileId,
                ),
            )
            runner.reset(timer.id)
        }
    }

    /** 删除计时器（同时摘掉面板关联与运行态）。 */
    fun deleteTimer(timer: TimerItem) {
        val panel = currentPanel.value ?: return
        viewModelScope.launch {
            runner.clear(timer.id)
            db.panelDao().removeRef(panel.id, timer.id)
        }
    }

    /** 保存提前提醒。 */
    fun saveLeadTime(timerId: Long, remainingMillis: Long, leads: List<Long>) {
        _leadTimeTarget.value = null
        viewModelScope.launch {
            db.deadlineReminderDao().clearTimer(timerId)
            if (leads.isNotEmpty()) {
                db.deadlineReminderDao().upsertAll(
                    leads.map {
                        at.cwiesner.android.visualtimer.ext.data.DeadlineReminder(
                            timerId = timerId,
                            leadMinutes = it,
                        )
                    },
                )
            }
            runner.setLeadMinutes(timerId, leads)
            // 把已经过去的档位标为已触发，避免第一个 tick 全量触发
            runner.markLeadsBaseline(timerId, remainingMillis)
        }
    }

    /** 提交拖拽排序结果。 */
    fun commitOrder(entries: List<PanelEntry>) {
        _orderOverride.value = null
        viewModelScope.launch {
            entries.forEachIndexed { idx, entry ->
                when (entry.kind) {
                    EntryKind.Sequence -> allSequences.value
                        .firstOrNull { it.id == entry.refId }
                        ?.let { db.sequenceDao().upsert(it.copy(sortOrder = idx)) }
                    EntryKind.Group -> currentGroups.value
                        .firstOrNull { it.id == entry.refId }
                        ?.let { db.groupDao().upsertGroup(it.copy(order = idx)) }
                    EntryKind.Timer -> allTimers.value
                        .firstOrNull { it.id == entry.refId }
                        ?.let { db.timerDao().upsert(it.copy(position = idx)) }
                }
            }
        }
    }

    /** 新建面板。 */
    fun createPanel(name: String) {
        viewModelScope.launch {
            val pid = db.panelDao().upsert(Panel(name = name, sortOrder = panels.value.size))
            _selectedPanelIndex.value = panels.value.size
            pid
        }
    }

    fun renamePanel(panel: Panel, name: String) {
        viewModelScope.launch { db.panelDao().upsert(panel.copy(name = name)) }
    }

    fun deletePanel(panel: Panel) {
        viewModelScope.launch {
            db.panelDao().deleteById(panel.id)
            _selectedPanelIndex.value = 0
        }
    }

    /** 保存面板成员（整体重建关联）。 */
    fun savePanelMembers(panel: Panel, name: String, memberIds: Set<Long>) {
        viewModelScope.launch {
            db.panelDao().upsert(panel.copy(name = name))
            db.panelDao().refsOf(panel.id).forEach { db.panelDao().removeRef(panel.id, it.timerId) }
            db.panelDao().upsertRefs(
                memberIds.mapIndexed { idx, tid -> PanelTimerRef(panel.id, tid, idx) },
            )
        }
    }

    /** 新建组。 */
    fun createGroup(name: String, timerIds: List<Long>) {
        val panel = currentPanel.value ?: return
        viewModelScope.launch {
            val gid = db.groupDao().upsertGroup(
                TimerGroup(panelId = panel.id, name = name, order = currentGroups.value.size),
            )
            db.groupDao().upsertMembers(
                timerIds.mapIndexed { idx, tid -> GroupMember(gid, tid, idx) },
            )
        }
    }

    /** 保存组：改名 + 重建成员。 */
    fun saveGroup(group: TimerGroup, name: String, memberIds: Set<Long>) {
        viewModelScope.launch {
            db.groupDao().upsertGroup(group.copy(name = name))
            db.groupDao().clearMembers(group.id)
            db.groupDao().upsertMembers(
                memberIds.mapIndexed { idx, tid -> GroupMember(group.id, tid, idx) },
            )
        }
    }

    fun deleteGroup(group: TimerGroup) {
        viewModelScope.launch {
            db.groupDao().clearMembers(group.id)
            db.groupDao().deleteGroup(group.id)
        }
    }

    /** 把序列分配到当前面板（或移出）。 */
    fun assignSequence(sequence: StepSequence, toCurrentPanel: Boolean) {
        val panel = currentPanel.value ?: return
        viewModelScope.launch {
            db.sequenceDao().upsert(
                sequence.copy(panelId = if (toCurrentPanel) panel.id else null),
            )
        }
    }

    /** 新建序列并挂到当前面板，随后打开编辑器。 */
    fun createSequence() {
        val panel = currentPanel.value ?: return
        viewModelScope.launch {
            val id = db.sequenceDao().upsert(
                StepSequence(name = "新序列", panelId = panel.id),
            )
            _editingSequenceId.value = id
        }
    }

    /** 保存序列：改名 + 超时 + 整体重建步骤。 */
    fun saveSequence(
        sequence: StepSequence,
        name: String,
        timeoutMillis: Long,
        steps: List<Pair<String, Long>>,
    ) {
        viewModelScope.launch {
            val panel = currentPanel.value
            val seqId = db.sequenceDao().upsert(
                sequence.copy(
                    name = name,
                    timeoutMillis = timeoutMillis,
                    panelId = sequence.panelId ?: panel?.id,
                ),
            )
            db.sequenceDao().steps(seqId).forEach { db.sequenceDao().deleteStep(it.id) }
            steps.forEachIndexed { idx, (label, duration) ->
                db.sequenceDao().upsertStep(
                    Step(
                        sequenceId = seqId,
                        durationMillis = duration,
                        label = label.ifBlank { null },
                        order = idx,
                    ),
                )
            }
            _editingSequenceId.value = null
        }
    }

    /** 批量启动选中的计时器。 */
    fun startSelected() {
        val chosen = currentTimers.value.filter { it.id in _selectedIds.value }
        chosen.forEach { t ->
            runner.clear(t.id)
            if (t.kind == TimerKind.Stopwatch) runner.startStopwatch(t) else runner.start(t)
        }
    }

    /** 批量删除选中的计时器。 */
    fun deleteSelected() {
        val panel = currentPanel.value ?: return
        val ids = _selectedIds.value
        viewModelScope.launch {
            ids.forEach { id ->
                runner.clear(id)
                db.panelDao().removeRef(panel.id, id)
            }
            _selectedIds.value = emptySet()
        }
    }

    companion object {
        /** 用 Application 里的单例构造。 */
        fun factory(
            db: AppDatabase,
            runner: PanelRunner,
            settings: SettingsRepo,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PanelsViewModel(db, runner, settings) as T
            }
        }
    }
}
