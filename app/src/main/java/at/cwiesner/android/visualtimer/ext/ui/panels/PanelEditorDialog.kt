package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 面板编辑器。
 *
 * 一个面板 = 一组计时器的集合。这里能做的事：
 * - 改面板名
 * - 勾选哪些计时器属于这个面板（勾上加入、取消勾选移出）
 * - 删除面板
 *
 * 列表展示**全部**计时器而不是只展示已加入的，
 * 因为用户真正的心智模型是「从这个大池子里挑几个放进来」，
 * 而不是「先建好再一个个加」。已加入的排在前面。
 */
@Composable
fun PanelEditorDialog(
    panelName: String,
    allTimers: List<TimerItem>,
    memberIds: Set<Long>,
    onDismiss: () -> Unit,
    onSave: (name: String, memberIds: Set<Long>) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(panelName) }
    var selected by remember { mutableStateOf(memberIds) }

    // 已加入的排前面，其余按创建顺序
    val ordered = remember(allTimers, memberIds) {
        allTimers.sortedByDescending { it.id in memberIds }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("编辑面板") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("面板名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "已选中 ${selected.size} / ${allTimers.size} 个计时器",
                    fontSize = 12.sp,
                    color = ExtColors.Primary,
                    fontWeight = FontWeight.Medium,
                )

                if (allTimers.isEmpty()) {
                    Text(
                        "还没有任何计时器。先回到网格点右下角添加。",
                        fontSize = 12.sp,
                        color = ExtColors.TextDim,
                    )
                }

                ordered.forEach { timer ->
                    val checked = timer.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ExtColors.Surface)
                            .clickable {
                                selected = if (checked) selected - timer.id else selected + timer.id
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + timer.id else selected - timer.id
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                timer.label ?: "未命名",
                                fontSize = 13.sp,
                                color = ExtColors.TextPrimary,
                            )
                            Text(
                                DeadlineCalculator.formatRemaining(timer.durationMillis),
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
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), selected) },
            ) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDelete) {
                    Text("删除面板", color = ExtColors.Red)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 小号分段标签，用在编辑器里做「全选 / 全不选」。 */
@Composable
internal fun MiniChip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ExtColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, color = ExtColors.TextSecondary)
    }
}
