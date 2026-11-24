package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
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
 * 卡片式调序界面。
 *
 * 之前是纵向列表 + 上下箭头，条目一多要点几十次，很慢。
 * 现在改成**横向卡片流**：条目平铺成可左右滑动的卡片，
 * 点卡片上的 ← → 直接换位，一屏能看更多、操作更直觉。
 */
@Composable
fun PanelOrderDialog(
    entries: List<PanelEntry>,
    onDismiss: () -> Unit,
    onSave: (List<PanelEntry>) -> Unit,
) {
    var list by remember(entries) { mutableStateOf(entries.sortedBy { it.order }) }
    val state = rememberLazyListState()

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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "点卡片上的箭头左右移动，越靠前越先显示。",
                    fontSize = 12.sp,
                    color = ExtColors.TextDim,
                )

                if (list.isEmpty()) {
                    Text("这个面板还没有内容。", fontSize = 12.sp, color = ExtColors.TextDim)
                } else {
                    LazyRow(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(list) { index, entry ->
                            OrderCard(
                                index = index,
                                total = list.size,
                                entry = entry,
                                onMoveLeft = { move(index, -1) },
                                onMoveRight = { move(index, 1) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list) }) { Text("保存顺序") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun OrderCard(
    index: Int,
    total: Int,
    entry: PanelEntry,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    Column(
        modifier = Modifier
            .size(width = 118.dp, height = 134.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ExtColors.Surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "#${index + 1}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = ExtColors.Primary,
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(kindColorOf(entry.kind)),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = entry.name,
                fontSize = 12.sp,
                color = ExtColors.TextPrimary,
                maxLines = 2,
            )
            Text(
                text = entry.kind.label,
                fontSize = 10.sp,
                color = ExtColors.TextDim,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onMoveLeft,
                enabled = index > 0,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "前移",
                    tint = if (index > 0) ExtColors.Primary else ExtColors.TextDim,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onMoveRight,
                enabled = index < total - 1,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = "后移",
                    tint = if (index < total - 1) ExtColors.Primary else ExtColors.TextDim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun kindColorOf(kind: EntryKind): Color = when (kind) {
    EntryKind.Sequence -> ExtColors.Palette[2]
    EntryKind.Group -> ExtColors.Amber
    EntryKind.Timer -> ExtColors.Primary
}
