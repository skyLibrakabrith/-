package at.cwiesner.android.visualtimer.ext.ui.sequences

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.ConfirmDeleteDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "SequencesScreen"

/**
 * 逐步间隔序列列表 + 运行界面。
 *
 * 点某个序列进入 [SequenceRunnerScreen]。
 *
 * 手势分配（沿用 [at.cwiesner.android.visualtimer.ext.ui.panels.TimerCells] 里
 * TimerList 的成对规则，用户不用记两套）：
 * - **单击** → 进入编辑（这是本页唯一有意义的动作用一句话说不完的操作）
 * - **长按** → 切换选中（选中后顶部工具条出现「开始 / 删除」）
 * - 卡片右上角还有一颗删除图标，只删这一条
 *
 * 为什么删除了「单击直接开始跑」：原来单击在有阶段时直接启动序列，
 * 空序列时打开编辑器，同一个手势干两件事，用户永远不知道点下去会发生什么；
 * 而且没有任何删除入口。现在把"跑"收进选中后的显式按钮，语义唯一。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SequencesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()

    fun toastText(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 列表项第二行文案：把「还没读到步骤」和「确实没有阶段」分开显示。
     *
     * 这两种状态以前都被渲染成「还没有阶段」（null 和空列表合并成 orEmpty()），
     * 是本次 bug 在用户眼里的样子。单独提成局部函数，调用点就不会再写回 orEmpty()。
     */
    fun stepsLine(steps: List<Step>?): String = when {
        steps == null -> "正在读取阶段…"
        steps.isEmpty() -> "还没有阶段，点它进入编辑添加"
        else -> describeSequenceDuration(steps)
    }

    // 订阅全表（不过滤 panelId）：本页管的是「所有序列」，包括还没归到面板里的。
    // 用普通 collectAsState 而不是 collectAsStateWithLifecycle —— 后者的
    // lifecycle-runtime-compose 不在依赖里，加库要动 build.gradle.kts，超出本次改动范围。
    val sequences by remember { db.sequenceDao().observeAll() }
        .collectAsState(initial = emptyList<StepSequence>())

    var running by remember { mutableStateOf<StepSequence?>(null) }
    var editing by remember { mutableStateOf<StepSequence?>(null) }
    var editingSteps by remember { mutableStateOf<List<Step>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteTarget by remember { mutableStateOf<StepSequence?>(null) }
    // 用 Application 持有的引擎：悬浮窗的「下一阶段」按钮也要能推进它
    val engine = app.sequenceEngine
    val phase by engine.phase.collectAsState()

    // 当前序列的步骤数：DAO 查询是 suspend，不能放在 remember 里，用 LaunchedEffect
    var currentSteps by remember { mutableStateOf<List<Step>>(emptyList()) }
    var stepCount by remember { mutableStateOf(0) }
    LaunchedEffect(running?.id) {
        val list = running?.let {
            runCatching { db.sequenceDao().steps(it.id) }.getOrDefault(emptyList())
        } ?: emptyList()
        currentSteps = list
        stepCount = list.size
    }

    // 每个序列的阶段列表：列表项要显示「N 段 · 合计 X」，需要一个序列到步骤列表的映射。
    //
    // 这里原来是两个 LaunchedEffect 打架，是「保存之后显示『还没有阶段』」的根因：
    //   ① LaunchedEffect(sequences.map { it.id }) 里用 launch 逐个 collect observeSteps；
    //   ② LaunchedEffect(sequences) 里对 stepsBySequence 做 keys.retainAll(liveIds)。
    // 保存一个阶段从 0 变 N 的序列时，sequences **本身并没有变**（只改了 steps 表），
    // 所以只有 ① 被重启：旧协程取消、新协程重新订阅，而 ② 的 key 没变、不会被重启。
    // 关键在 ① 是异步的 —— 新订阅的首批数据要等 Room Flow 走完一次查询才到，
    // 而重组的同一帧里 stepsBySequence 还是**上一个已取消订阅留下的旧值**。
    // 新建序列时那个旧值恰好是空列表（新建前它就被 ② 清掉/从未写入过），
    // 于是这一帧渲染出 describeSequenceDuration(emptyList()) = 「还没有阶段」。
    // 之后数据到了映射才更新；但再触发一次 ① 的重启（比如列表因为别的原因换了一批
    // 相同 id 的新实例）又会闪回空态。UI 上看到的就是"存了却还是没阶段"。
    //
    // 修法：只留一个 LaunchedEffect，key 用**有内容比较**的 id 列表，
    // collect 直接并行写在 effect 体里（不额外 launch），这样
    //   - 取消与重订阅严格发生在同一处，不存在两个 effect 互相抢状态；
    //   - key 结构相等就不会重启，避免无意义的取消/重订阅丢首批数据；
    //   - 映射里的条目只在"该序列真的消失"时才删，而这一步由 keys 求交集完成，
    //     不依赖第二个 effect 的时序。
    val sequenceIds = remember(sequences) { sequences.map { it.id } }
    // 值用 List<Step>？，null = 这个序列的步骤还没加载出来（首帧 / 刚订阅上）。
    // 区分「加载中」与「确实没有阶段」是本 bug 的核心：以前两者都渲染成空，
    // 用户无法分辨"数据库里没存进去"和"界面还没读到"。
    var stepsBySequence by remember { mutableStateOf<Map<Long, List<Step>?>>(emptyMap()) }
    LaunchedEffect(sequenceIds) {
        // 重订阅时先把未知的条目清成 null，宁可短暂显示"加载中"也不要显示旧的阶段数
        stepsBySequence = sequenceIds.associateWith { stepsBySequence[it] }
        // 逐个序列并行订阅：某个序列的阶段被增删改时 Room 只推它这一路
        sequenceIds.forEach { seqId ->
            launch {
                db.sequenceDao().observeSteps(seqId).collect { list ->
                    // map 整体替换成新实例，Compose 才认得出来它变了
                    stepsBySequence = stepsBySequence + (seqId to list)
                }
            }
        }
    }

    /**
     * 彻底删除一个序列：先删它的全部阶段，再删序列本身。
     *
     * 为什么不只调 deleteById：steps 表对 sequences 没有外键（Room 侧也没有
     * ForeignKey 声明），删了序列之后那些步骤行会**永远留下来**成为孤儿行 ——
     * 它们不再属于任何序列，界面看不到，却会一直占库、并且被导出进备份。
     * 所以这里手工级联，顺序固定（先子后父），和 [at.cwiesner.android.visualtimer.ext.ui.panels.PanelsScreen]
     * 里删组（先 clearMembers 再 deleteGroup）保持同一套约定。
     */
    fun deleteSequenceCascade(seq: StepSequence) {
        scope.launch {
            try {
                db.sequenceDao().steps(seq.id).forEach { db.sequenceDao().deleteStep(it.id) }
                db.sequenceDao().deleteById(seq.id)
                // 正在跑的序列被删了：引擎还握着它的内存副本，不停止就会继续倒计时
                if (engine.activeSequenceId == seq.id) engine.stop()
                if (running?.id == seq.id) running = null
                // 编辑器里的正是这条：不关掉的话，点保存会用旧 id 把它重新 upsert 回来
                if (editing?.id == seq.id) editing = null
                selectedIds = selectedIds - seq.id
                deleteTarget = null
                toastText("已删除序列「" + seq.name + "」")
                Log.i(TAG, "序列已删除（含级联步骤）：id=" + seq.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 别静默吞掉：删除失败时用户至少能在 logcat 里看到原因
                Log.e(TAG, "删除序列失败 id=" + seq.id, e)
                toastText("删除失败，请重试")
            }
        }
    }

    val editTarget = editing
    if (editTarget != null) {
        SequenceEditorScreen(
            sequence = editTarget,
            steps = editingSteps,
            onDismiss = { editing = null },
            onSave = { name, timeout, stepDrafts ->
                scope.launch {
                    try {
                        // 新建时 editTarget.id == 0，@Upsert 会走 INSERT 并**返回自增 id**；
                        // 编辑已有序列时返回的就是它自己的 id。两种情况下都以下面这个 seqId 为准。
                        //
                        // 之前 bug 的另一半在这里：排序字段 sortOrder 从没被赋值，
                        // 所有序列都是 0，而 observeAll() 按 sortOrder 排 —— 新建的序列
                        // 只能排在 id 更大的那些后面，"新建了却不在列表里"就是这么来的。
                        // 面板里的序列沿用面板网格的位置；不属于面板的接在最后。
                        val keepSortOrder = if (editTarget.id == 0L) {
                            (db.sequenceDao().observeAllOnce().maxOfOrNull { it.sortOrder } ?: -1) + 1
                        } else {
                            editTarget.sortOrder
                        }
                        val seqId = db.sequenceDao().upsert(
                            editTarget.copy(
                                name = name,
                                timeoutMillis = timeout,
                                sortOrder = keepSortOrder,
                            ),
                        )
                        // 重建步骤：先删旧的再按新草稿写入。
                        // 必须用上面拿到的 seqId —— 用 editTarget.id 的话，新建时会往
                        // sequenceId = 0 写一批谁都找不到的孤儿步骤。
                        db.sequenceDao().steps(seqId).forEach {
                            db.sequenceDao().deleteStep(it.id)
                        }
                        stepDrafts.forEachIndexed { idx, d ->
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
                        editing = null
                        toastText("序列「" + name + "」已保存，" + stepDrafts.size + " 个阶段")
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Log.e(TAG, "保存序列失败", e)
                        toastText("保存失败，请重试")
                    }
                }
            },
            modifier = modifier,
        )
        return
    }

    val current = running
    if (current != null) {
        SequenceRunnerScreen(
            phase = phase,
            steps = currentSteps,
            stepCount = stepCount,
            onConfirm = { engine.confirm() },
            onSkip = { engine.skip() },
            onStop = {
                engine.stop()
                running = null
            },
            modifier = modifier,
        )
        return
    }

    val pendingDelete = deleteTarget

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("逐步间隔") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // 不立刻写库：取消编辑时不会留下空记录
                    editingSteps = emptyList()
                    editing = StepSequence(id = 0L, name = "新序列")
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建序列") },
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (sequences.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有序列。序列 = 一串按顺序执行、每步结束等你确认的阶段。\n点右下角新建。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部工具条：和面板列表视图的 TimerList 一样，只有选中时才出现批量操作，
                    // 平时不占注意力
                    if (selectedIds.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已选 ${selectedIds.size}/${sequences.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    // 只有选中的那一条能开始：一次跑两条序列没有意义
                                    val target = sequences.firstOrNull { it.id in selectedIds }
                                    if (target == null) {
                                        selectedIds = emptySet()
                                    } else {
                                        scope.launch {
                                            val steps = runCatching {
                                                db.sequenceDao().steps(target.id)
                                            }.getOrDefault(emptyList())
                                            if (steps.isEmpty()) {
                                                toastText("「" + target.name + "」还没有阶段，点它进入编辑")
                                            } else {
                                                engine.start(target, steps)
                                                running = target
                                                selectedIds = emptySet()
                                            }
                                        }
                                    }
                                },
                            ) { Text("开始") }
                            TextButton(
                                onClick = { deleteTarget = sequences.firstOrNull { it.id in selectedIds } },
                            ) { Text("删除") }
                            TextButton(onClick = { selectedIds = emptySet() }) { Text("取消") }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(sequences, key = { it.id }) { seq ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        // 单击 = 编辑。以前是有阶段就开跑、没阶段才进编辑，
                                        // 同一手势两种结果，用户没法预期
                                        onClick = {
                                            scope.launch {
                                                editingSteps = runCatching {
                                                    db.sequenceDao().steps(seq.id)
                                                }.getOrDefault(emptyList())
                                                editing = seq
                                            }
                                        },
                                        // 长按 = 选中（与 TimerList 一致）
                                        onLongClick = {
                                            selectedIds = if (seq.id in selectedIds) {
                                                selectedIds - seq.id
                                            } else {
                                                selectedIds + seq.id
                                            }
                                        },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(seq.name, style = MaterialTheme.typography.titleMedium)
                                        // 预计时间：阶段数 + 合计时长。
                                        // 三种状态分开显示，不再把"还没加载出来"画成「还没有阶段」——
                                        // 那正是用户看到"保存了还是没阶段"的直接原因。
                                        Text(
                                            stepsLine(stepsBySequence[seq.id]),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            "超时 " + DeadlineCalculator.formatRemaining(seq.timeoutMillis) +
                                                " · 超时后开始正计时，需手动进入下一阶段",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    // 单条删除入口：这条路径不经过选中，误触一次只会弹确认框
                                    IconButton(
                                        onClick = { deleteTarget = seq },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除序列",
                                            tint = ExtColors.OutlineStrong,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingDelete != null) {
        ConfirmDeleteDialog(
            title = "删除序列",
            text = "「" + pendingDelete.name + "」及其全部阶段会被永久删除，无法撤销。",
            onDismiss = { deleteTarget = null },
            onConfirm = { deleteSequenceCascade(pendingDelete) },
        )
    }
}
