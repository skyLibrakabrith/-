package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.duration.DurationSlider

/** 新建 / 重命名面板。 */
@Composable
fun PanelNameDialog(
    initialName: String = "",
    title: String = "新建面板",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("面板名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 新建 / 编辑计时器：名称 + 图形化时长滑块。 */
@Composable
fun TimerEditDialog(
    initialLabel: String = "",
    initialDuration: Long = 60_000L,
    title: String = "新建计时器",
    onDismiss: () -> Unit,
    onConfirm: (label: String, durationMillis: Long) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var duration by remember { mutableLongStateOf(initialDuration) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("时长", style = MaterialTheme.typography.labelLarge)
                DurationSlider(
                    durationMillis = duration,
                    onDurationChange = { duration = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim(), duration) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * DDL 倒推录入：用户选一个截止时刻，自动算出剩余时长。
 *
 * 输入用「几小时几分钟后」的快捷档 + 精确到分钟的目标时刻，
 * 下方 [DeadlineTimeline] 实时显示剩余量与 24h 阈值。
 */
@Composable
fun DeadlineDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, deadlineEpochMillis: Long) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    // 用「距现在多少毫秒」表达，避免日期选择器依赖
    var offsetMillis by remember { mutableLongStateOf(60L * 60 * 1000) }

    val now = System.currentTimeMillis()
    val deadline = now + offsetMillis

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("倒计时到某个时刻") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("距离目标时刻", style = MaterialTheme.typography.labelLarge)
                DurationSlider(
                    durationMillis = offsetMillis,
                    onDurationChange = { offsetMillis = it },
                    nowEpochMillis = now,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "目标：" + DeadlineCalculator.describe(
                        java.time.Instant.ofEpochMilli(deadline),
                        java.time.Instant.ofEpochMilli(now),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim(), deadline) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
