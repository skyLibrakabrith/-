package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/** 面板里一个可排序条目。三种类型共用一个 order 序列，才能互相穿插。 */
enum class EntryKind(val label: String) {
    Sequence("序列"),
    Group("组"),
    Timer("计时器"),
}

data class PanelEntry(
    val kind: EntryKind,
    val refId: Long,
    val name: String,
    val order: Int,
)

/**
 * 排序面板内容。
 *
 * 面板里混着三种东西：序列、组、单个计时器。它们存在三张不同的表里，
 * 各有自己的 order 字段。为了让它们能**互相穿插**排序，
 * 这里把它们拍平成一个统一列表，拖动时统一重排，
 * 保存时再按「先整体编号、再按类型回写」的方式落库。
 */
@Composable
fun PanelOrderDialog(
    entries: List<PanelEntry>,
    onDismiss: () -> Unit,
    onSave: (List<PanelEntry>) -> Unit,
) {
    var list by remember(entries) { mutableStateOf(entries.sortedBy { it.order }) }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in list.indices) return
        val mutable = list.toMutableList()
        val tmp = mutable[index]
        mutable[index] = mutable[target]
        mutable[target] = tmp
        list = mutable
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("调整顺序") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (list.isEmpty()) {
                    Text(
                        "这个面板还没有内容。",
                        fontSize = 12.sp,
                        color = ExtColors.TextDim,
                    )
                }
                list.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ExtColors.Surface)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 类型色点
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(kindColor(entry.kind)),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        ) {
                            Text(
                                entry.name,
                                fontSize = 13.sp,
                                color = ExtColors.TextPrimary,
                            )
                            Text(
                                entry.kind.label,
                                fontSize = 10.sp,
                                color = ExtColors.TextDim,
                            )
                        }
                        IconButton(
                            onClick = { move(index, -1) },
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = "上移",
                                tint = if (index > 0) ExtColors.Primary else ExtColors.TextDim,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { move(index, 1) },
                            enabled = index < list.size - 1,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = "下移",
                                tint = if (index < list.size - 1) ExtColors.Primary
                                else ExtColors.TextDim,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(list) }) { Text("保存顺序") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun kindColor(kind: EntryKind): Color = when (kind) {
    EntryKind.Sequence -> ExtColors.Palette[2]
    EntryKind.Group -> ExtColors.Amber
    EntryKind.Timer -> ExtColors.Primary
}

/** 让未使用的 import 保持安静（FontWeight 供后续扩展）。 */
@Suppress("unused")
private val UNUSED = FontWeight.Normal
