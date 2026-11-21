package at.cwiesner.android.visualtimer.ext.ui.sequences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import kotlinx.coroutines.launch

/**
 * 逐步间隔序列列表 + 运行界面。
 *
 * 点某个序列进入 [SequenceRunnerScreen]。
 */
@Composable
fun SequencesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()

    val sequences by remember { db.sequenceDao().observeAll() }
        .collectAsState(initial = emptyList<StepSequence>())

    var running by remember { mutableStateOf<StepSequence?>(null) }
    val engine = remember { StepSequenceEngine(scope = scope, onStepFinished = { }) }
    val phase by engine.phase.collectAsState()

    // 当前序列的步骤数：DAO 查询是 suspend，不能放在 remember 里，用 LaunchedEffect
    var stepCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(running?.id) {
        stepCount = running?.let {
            runCatching { db.sequenceDao().steps(it.id).size }.getOrDefault(0)
        } ?: 0
    }

    val current = running
    if (current != null) {
        SequenceRunnerScreen(
            phase = phase,
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

    Box(modifier = modifier.fillMaxSize()) {
        if (sequences.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有序列。序列 = 一串按顺序执行、每步结束等你确认的阶段。",
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
                            .clickable {
                                scope.launch {
                                    val steps = db.sequenceDao().steps(seq.id)
                                    engine.start(seq, steps)
                                    running = seq
                                }
                            },
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
