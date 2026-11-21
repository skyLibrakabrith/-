package at.cwiesner.android.visualtimer.ext.ui.sequences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import at.cwiesner.android.visualtimer.ext.ui.duration.DurationSlider

/**
 * 序列编辑器：名称 + 超时时长 + 有序步骤列表。
 *
 * 步骤用 [DurationSlider] 选时长，列表内可增删。
 */
@Composable
fun SequenceEditorScreen(
    sequence: StepSequence,
    steps: List<Step>,
    onDismiss: () -> Unit,
    onSave: (name: String, timeoutMillis: Long, steps: List<Pair<String, Long>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(sequence.name) }
    var timeout by remember { mutableLongStateOf(sequence.timeoutMillis) }
    var timeoutEnabled by remember { mutableStateOf(sequence.timeoutMillis > 0L) }
    // 用 (label, duration) 列表维护，保存时整体重建
    var draft by remember {
        mutableStateOf(steps.map { (it.label ?: "") to it.durationMillis })
    }
    var showAddStep by remember { mutableStateOf(false) }

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
            Text("阶段（${draft.size} 个）", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { showAddStep = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("添加")
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
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        "${index + 1}. " + (item.first.ifBlank { "未命名" }),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        DeadlineCalculator.formatRemaining(item.second),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                draft = draft + (label to duration)
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
