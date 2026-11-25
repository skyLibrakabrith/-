package at.cwiesner.android.visualtimer.ext.ui.sequences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.common.AiSplitDialog
import at.cwiesner.android.visualtimer.ext.ui.duration.DurationSlider

/**
 * 编辑器里的一条阶段草稿。
 *
 * 不再用 `Pair<String, Long>`：AI 拆解会给出 difficulty 与 context，
 * 用 Pair 就只能把它们丢掉。换成具名类之后，
 * 加字段也只改构造点，不会牵动一堆解构声明。
 *
 * @property difficulty 1–5；null = 没标注
 * @property context 背景/理由；null = 没写
 */
data class StepDraft(
    val label: String,
    val durationMillis: Long,
    val difficulty: Int? = null,
    val context: String? = null,
)

/**
 * 序列编辑器：名称 + 超时时长 + 有序步骤列表。
 *
 * 步骤用 [DurationSlider] 选时长，列表内可增删、可上下移动。
 */
@Composable
fun SequenceEditorScreen(
    sequence: StepSequence,
    steps: List<Step>,
    onDismiss: () -> Unit,
    onSave: (name: String, timeoutMillis: Long, steps: List<StepDraft>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 全部以 sequence.id 为 key —— 否则切换到另一个序列时，
    // remember 会沿用上一个序列的草稿，保存出来内容就串了
    var name by remember(sequence.id) { mutableStateOf(sequence.name) }
    var timeout by remember(sequence.id) { mutableLongStateOf(sequence.timeoutMillis) }
    var timeoutEnabled by remember(sequence.id) {
        mutableStateOf(sequence.timeoutMillis > 0L)
    }
    var draft by remember(sequence.id) {
        mutableStateOf(
            steps.map {
                StepDraft(
                    label = it.label ?: "",
                    durationMillis = it.durationMillis,
                    difficulty = it.difficulty,
                    context = it.context,
                )
            },
        )
    }
    var showAddStep by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("序列名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("启用超时", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (timeoutEnabled) "超时后界面转红色强调（仍不会自动跳步）" else "不设超时，只有正计时",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = timeoutEnabled, onCheckedChange = { timeoutEnabled = it })
        }
        if (timeoutEnabled) {
            DurationSlider(
                durationMillis = if (timeout > 0) timeout else 30_000L,
                onDurationChange = { timeout = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("阶段（${draft.size} 个）", style = MaterialTheme.typography.titleSmall)
                // 预计总时长：增删阶段 / 拆解追加后立刻重算，用户能马上看到总时长变化。
                // 草稿为空时显示「合计 —」，而不是「合计 0s」
                Text(
                    describeDraftTotal(draft),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Magic ToDo：把一段话拆成有序阶段，直接追加到草稿里
                TextButton(onClick = { showSplitDialog = true }) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("拆解")
                }
                TextButton(onClick = { showAddStep = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加")
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (draft.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有阶段，点「添加」新建一个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(draft) { index, item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${index + 1}. " + (item.label.ifBlank { "未命名" }),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            DeadlineCalculator.formatRemaining(item.durationMillis),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        item.difficulty?.let { d ->
                                            Text(
                                                "难" + "▮".repeat(d.coerceIn(1, 5)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    // 背景：一行小字，没有就不占位
                                    item.context?.let { c ->
                                        Text(
                                            c,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }

                                // 上下移动：AI 给的顺序只是建议，用户调顺序是这条流程的关键一步。
                                // 用两个小按钮而不是拖拽 —— 拖拽在这个列表里容易误触，
                                // 而"上移一格"本来就更精确。
                                IconButton(
                                    enabled = index > 0,
                                    onClick = {
                                        draft = draft.toMutableList().also {
                                            val tmp = it[index]; it[index] = it[index - 1]; it[index - 1] = tmp
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "上移",
                                        tint = if (index > 0) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                    )
                                }
                                IconButton(
                                    enabled = index < draft.lastIndex,
                                    onClick = {
                                        draft = draft.toMutableList().also {
                                            val tmp = it[index]; it[index] = it[index + 1]; it[index + 1] = tmp
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "下移",
                                        tint = if (index < draft.lastIndex) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        draft = draft.toMutableList().also { it.removeAt(index) }
                                    },
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除阶段")
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    onSave(
                        name.trim().ifBlank { "未命名序列" },
                        if (timeoutEnabled) timeout.coerceAtLeast(5_000L) else 0L,
                        draft,
                    )
                },
                enabled = draft.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
        }
    }

    if (showAddStep) {
        StepEditDialog(
            onDismiss = { showAddStep = false },
            onConfirm = { label, duration ->
                showAddStep = false
                draft = draft + StepDraft(label = label, durationMillis = duration)
            },
        )
    }

    if (showSplitDialog) {
        AiSplitDialog(
            onDismiss = { showSplitDialog = false },
            onApply = { splitSteps ->
                showSplitDialog = false
                // 追加而不是替换：用户可能已经手填了几步，直接覆盖等于毁了他的输入
                draft = draft + splitSteps.map {
                    StepDraft(
                        label = it.title,
                        durationMillis = it.minutes * 60_000L,
                        difficulty = it.difficulty,
                        context = it.context,
                    )
                }
                // 序列名还是空的就顺手填上，省一次输入
                if (name.isBlank()) name = splitSteps.firstOrNull()?.title ?: name
            },
        )
    }
}

/** 单个阶段的编辑对话框。 */
@Composable
fun StepEditDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, durationMillis: Long) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var duration by remember { mutableLongStateOf(60_000L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加阶段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("阶段名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("时长", style = MaterialTheme.typography.labelMedium)
                DurationSlider(
                    durationMillis = duration,
                    onDurationChange = { duration = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim(), duration) }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
