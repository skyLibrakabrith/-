package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * 面板编辑器（含组）。
 *
 * 三块内容：
 * 1. 面板名
 * 2. **已有的组**：显示组名与成员数，可删除；点击可把成员勾选出来再重组
 * 3. **计时器列表**：勾选决定谁属于这个面板；勾选后点「成组」把选中的绑成一个组
 *
 * 关键约束：一个计时器同时只能属于一个组。
 * 所以勾选时若某个计时器已在别的组里，会在列表里标出归属。
 */
@Composable
fun PanelEditorDialog(
    panelId: Long,
    panelName: String,
    allTimers: List<TimerItem>,
    memberIds: Set<Long>,
    groups: List<TimerGroup>,
    groupMembers: Map<Long, List<Long>>,
    allSequences: List<at.cwiesner.android.visualtimer.ext.data.StepSequence>,
    sequencePanelMap: Map<Long, Long?>,
    onDismiss: () -> Unit,
    onSave: (name: String, memberIds: Set<Long>) -> Unit,
    onDelete: () -> Unit,
    onCreateGroup: (name: String, timerIds: List<Long>) -> Unit,
    onDeleteGroup: (TimerGroup) -> Unit,
    onToggleSequence: (Long, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(panelName) }
    var selected by remember { mutableStateOf(memberIds) }
    var showGroupName by remember { mutableStateOf(false) }

    // 计时器 → 所属组名（用于在列表里标注）
    val groupNameById = remember(groups) { groups.associate { it.id to it.name } }
    val timerGroupName = remember(groups, groupMembers) {
        val map = mutableMapOf<Long, String>()
        groups.forEach { g ->
            groupMembers[g.id].orEmpty().forEach { tid ->
                map[tid] = g.name
            }
        }
        map
    }

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
                    .heightIn(max = 470.dp)
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

                // ---- 已有的组 ----
                if (groups.isNotEmpty()) {
                    Text(
                        "组（${groups.size}）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ExtColors.TextSecondary,
                    )
                    groups.forEach { g ->
                        val members = groupMembers[g.id].orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ExtColors.Surface)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(ExtColors.Amber),
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(
                                    g.name,
                                    fontSize = 13.sp,
                                    color = ExtColors.TextPrimary,
                                )
                                Text(
                                    "${members.size} 个成员",
                                    fontSize = 11.sp,
                                    color = ExtColors.TextDim,
                                )
                            }
                            IconButton(
                                onClick = { onDeleteGroup(g) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除组",
                                    tint = ExtColors.OutlineStrong,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                // ---- 计时器列表 ----
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
                    val owner = timerGroupName[timer.id]
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
                                buildString {
                                    append(DeadlineCalculator.formatRemaining(timer.durationMillis))
                                    if (owner != null) append(" · 组「$owner」")
                                },
                                fontSize = 11.sp,
                                color = if (owner != null) ExtColors.Amber else ExtColors.TextDim,
                            )
                        }
                    }
                }

                // ---- 序列 ----
                if (allSequences.isNotEmpty()) {
                    Text(
                        "序列（勾选加入本面板）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ExtColors.TextSecondary,
                    )
                    allSequences.forEach { sq ->
                        val belongsHere = sequencePanelMap[sq.id] == panelId
                        val belongsElsewhere =
                            sequencePanelMap[sq.id] != null && !belongsHere
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ExtColors.Surface)
                                .clickable {
                                    onToggleSequence(sq.id, !belongsHere)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = belongsHere || belongsElsewhere,
                                onCheckedChange = { on -> onToggleSequence(sq.id, on) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    sq.name,
                                    fontSize = 13.sp,
                                    color = ExtColors.TextPrimary,
                                )
                                Text(
                                    if (belongsHere) "已在本面板"
                                    else if (belongsElsewhere) "已在其他面板（勾选会移动过来）"
                                    else "未分配",
                                    fontSize = 11.sp,
                                    color = when {
                                        belongsHere -> ExtColors.Palette[2]
                                        belongsElsewhere -> ExtColors.Amber
                                        else -> ExtColors.TextDim
                                    },
                                )
                            }
                        }
                    }
                }

                // ---- 成组按钮 ----
                if (selected.size >= 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ExtColors.PrimaryDim)
                            .clickable { showGroupName = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "把选中的 ${selected.size} 个绑成一组",
                            fontSize = 13.sp,
                            color = ExtColors.TextPrimary,
                        )
                    }
                } else {
                    Text(
                        "勾选 2 个以上计时器即可绑成组",
                        fontSize = 11.sp,
                        color = ExtColors.TextDim,
                    )
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

    if (showGroupName) {
        var groupName by remember { mutableStateOf("新组") }
        AlertDialog(
            onDismissRequest = { showGroupName = false },
            containerColor = ExtColors.PureBlack,
            title = { Text("给这个组起名") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("组名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = groupName.isNotBlank(),
                    onClick = {
                        showGroupName = false
                        onCreateGroup(groupName.trim(), selected.toList())
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showGroupName = false }) { Text("取消") }
            },
        )
    }
}
