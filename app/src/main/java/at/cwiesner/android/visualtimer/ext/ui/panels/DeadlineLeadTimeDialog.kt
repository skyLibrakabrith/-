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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.LeadTimeOptions
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.describeMinutes

/**
 * 创建 DDL 后的前置提醒选择。
 *
 * DDL 一旦创建就**锁定并自动开始倒计时**，所以这里顺带问一句
 * 「要不要在到期前先提醒几次」。可多选，例如同时选 30 分钟和 5 分钟。
 *
 * 说明：只列出「比剩余时间短」的档位 —— 提前 2 小时提醒一个还剩 10 分钟的
 * DDL 没有意义，会误导用户。
 */
@Composable
fun DeadlineLeadTimeDialog(
    remainingMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val remainMinutes = remainingMillis / 60_000L
    // 候选：比剩余时长小的档位，按分钟升序展示
    val options = remember(remainMinutes) {
        LeadTimeOptions.all.filter { it < remainMinutes }.sorted()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("提前提醒") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "这个 DDL 还有 " + describeMinutes(remainMinutes) + "。",
                    fontSize = 12.sp,
                    color = ExtColors.TextSecondary,
                )
                Text(
                    "可多选，到点前会各提醒一次。不选则只在到期时提醒。",
                    fontSize = 12.sp,
                    color = ExtColors.TextDim,
                )

                if (options.isEmpty()) {
                    Text(
                        "剩余时间太短，没有可用的提前提醒档位。",
                        fontSize = 12.sp,
                        color = ExtColors.TextDim,
                    )
                }

                options.forEach { minutes ->
                    val checked = minutes in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (checked) ExtColors.PrimaryDim else ExtColors.Surface,
                            )
                            .clickable {
                                selected = if (checked) selected - minutes
                                else selected + minutes
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + minutes else selected - minutes
                            },
                        )
                        Text(
                            "提前 " + describeMinutes(minutes),
                            fontSize = 13.sp,
                            color = ExtColors.TextPrimary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList().sorted()) }) {
                Text(if (selected.isEmpty()) "不用提醒" else "确定")
            }
        },
        // 「跳过」必须等同于「不用提醒」：两者都是**创建 DDL 但不设提前提醒**。
        //
        // 原来这里调的是 onDismiss —— 而 onDismiss 在调用方是"取消整个创建"，
        // 于是用户点「跳过」时以为 DDL 已经建好了，实际什么都没建。
        // 点对话框外面才是取消。
        dismissButton = {
            TextButton(onClick = { onConfirm(emptyList()) }) { Text("跳过") }
        },
    )
}

