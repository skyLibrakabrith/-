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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 面板（组）管理。
 *
 * 列出所有面板及其计时器数量、运行中数量，支持新建 / 改名 / 删除。
 * 之前只能「面板为空时才能建」，面板一多就没入口了；这里补齐完整的管理路径。
 *
 * @param counts 面板 id → (成员数, 运行中数)
 */
@Composable
fun PanelManagerDialog(
    panels: List<Panel>,
    counts: Map<Long, Pair<Int, Int>>,
    onDismiss: () -> Unit,
    onOpen: (Panel) -> Unit,
    /** 点「编辑」进面板编辑器（成员/序列/组管理）。 */
    onEdit: (Panel) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Panel, String) -> Unit,
    onDelete: (Panel) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Panel?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("面板管理") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (panels.isEmpty()) {
                    Text(
                        "还没有面板。点下面的「新建面板」创建第一个。",
                        fontSize = 12.sp,
                        color = ExtColors.TextDim,
                    )
                }

                panels.forEach { panel ->
                    val (total, running) = counts[panel.id] ?: (0 to 0)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ExtColors.Surface)
                            .clickable { onOpen(panel) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                panel.name,
                                fontSize = 14.sp,
                                color = ExtColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                buildString {
                                    append("$total 个计时器")
                                    if (running > 0) append(" · $running 个运行中")
                                },
                                fontSize = 11.sp,
                                color = if (running > 0) ExtColors.Primary else ExtColors.TextDim,
                            )
                        }
                        IconButton(
                            onClick = { onEdit(panel) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "编辑面板",
                                tint = ExtColors.Primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { onDelete(panel) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = ExtColors.OutlineStrong,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ExtColors.PrimaryDim)
                        .clickable { showCreate = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = ExtColors.TextPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "新建面板",
                        fontSize = 13.sp,
                        color = ExtColors.TextPrimary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    if (showCreate) {
        SimpleNameDialog(
            title = "新建面板",
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                onCreate(name)
            },
        )
    }

    renaming?.let { target ->
        SimpleNameDialog(
            title = "重命名面板",
            initial = target.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRename(target, name)
            },
        )
    }
}

/** 单输入框对话框，供改名 / 新建复用。 */
@Composable
private fun SimpleNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text(title) },
        text = {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
