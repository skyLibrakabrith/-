package at.cwiesner.android.visualtimer.ext.ui.sequences

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.data.Step
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import kotlinx.coroutines.launch

/**
 * 逐步间隔序列列表 + 运行界面。
 *
 * 点某个序列进入 [SequenceRunnerScreen]。
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

    val sequences by remember { db.sequenceDao().observeAll() }
        .collectAsState(initial = emptyList<StepSequence>())

    var running by remember { mutableStateOf<StepSequence?>(null) }
    var editing by remember { mutableStateOf<StepSequence?>(null) }
    var editingSteps by remember { mutableStateOf<List<Step>>(emptyList()) }
    // 用 Application 持有的引擎：悬浮窗的「下一阶段」按钮也要能推进它
    val engine = app.sequenceEngine
    val phase by engine.phase.collectAsState()

    // 当前序列的步骤数：DAO 查询是 suspend，不能放在 remember 里，用 LaunchedEffect
    var currentSteps by remember { mutableStateOf<List<Step>>(emptyList()) }
    var stepCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(running?.id) {
        val list = running?.let {
            runCatching { db.sequenceDao().steps(it.id) }.getOrDefault(emptyList())
        } ?: emptyList()
        currentSteps = list
        stepCount = list.size
    }

    val editTarget = editing
    if (editTarget != null) {
        SequenceEditorScreen(
            sequence = editTarget,
            steps = editingSteps,
            onDismiss = { editing = null },
            onSave = { name, timeout, stepPairs ->
                scope.launch {
                    // 新建时 id=0，upsert 会插入并返回真实 id
                    val seqId = db.sequenceDao().upsert(
                        editTarget.copy(name = name, timeoutMillis = timeout),
                    )
                    // 重建步骤：先删旧的再按新草稿写入
                    db.sequenceDao().steps(seqId).forEach {
                        db.sequenceDao().deleteStep(it.id)
                    }
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
                    editing = null
                    toastText("序列「" + name + "」已保存，" + stepPairs.size + " 个阶段")
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
                        "还没有序列。序列 = 一串按顺序执行、每步结束等你确认的阶段。\n点右下角新建，长按已有序列可编辑。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
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
                                    onClick = {
                                        scope.launch {
                                            val steps = db.sequenceDao().steps(seq.id)
                                            if (steps.isEmpty()) {
                                                editingSteps = emptyList()
                                                editing = seq
                                            } else {
                                                engine.start(seq, steps)
                                                running = seq
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        scope.launch {
                                            editingSteps = db.sequenceDao().steps(seq.id)
                                            editing = seq
                                        }
                                    },
                                ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(seq.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "超时 " + DeadlineCalculator.formatRemaining(seq.timeoutMillis) +
                                        " · 超时后开始正计时，需手动进入下一阶段",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
