package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 组编辑器。
 *
 * 长按面板里的组卡片进入。可以改组名、增减成员。
 * 只列出**本面板内**而且没被别的组占用的计时器，
 * 避免把跨面板的计时器误塞进来。
 */
@Composable
fun GroupEditorDialog(
    group: TimerGroup,
    candidateTimers: List<TimerItem>,
    memberIds: Set<Long>,
    occupiedBy: Map<Long, String>,
    onDismiss: () -> Unit,
    onSave: (name: String, memberIds: Set<Long>) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(group.id) { mutableStateOf(group.name) }
    var selected by remember(group.id) { mutableStateOf(memberIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("编辑组") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("组名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "成员 ${selected.size} 个（至少 1 个）",
                    fontSize = 12.sp,
                    color = ExtColors.Primary,
                    fontWeight = FontWeight.Medium,
                )

                candidateTimers.forEach { timer ->
                    val checked = timer.id in selected
                    // 已被别的组占用的不能选
                    val occupant = occupiedBy[timer.id]
                    val blocked = occupant != null && timer.id !in memberIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ExtColors.Surface)
                            .clickable(enabled = !blocked) {
                                selected = if (checked) selected - timer.id else selected + timer.id
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !blocked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + timer.id else selected - timer.id
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                timer.label ?: "未命名",
                                fontSize = 13.sp,
                                color = if (blocked) ExtColors.TextDim else ExtColors.TextPrimary,
                            )
                            Text(
                                buildString {
                                    append(DeadlineCalculator.formatRemaining(timer.durationMillis))
                                    if (blocked) append(" · 已在组「$occupant」")
                                },
                                fontSize = 11.sp,
                                color = ExtColors.TextDim,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                onClick = { onSave(name.trim(), selected) },
            ) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDelete) { Text("删除组", color = ExtColors.Red) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
