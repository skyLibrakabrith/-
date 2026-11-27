package at.cwiesner.android.visualtimer.ext.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.common.clockOf

/**
 * 点中某段弧之后的详情。
 *
 * ## 为什么不用通用 [at.cwiesner.android.visualtimer.ext.ui.common.ConfirmDeleteDialog]
 *
 * 那个组件是"问一句再删"的两按钮确认框，没有展示多个字段的位置。
 * 这里要做的是「先看清这条是什么，再决定删不删」——
 * 删的是**历史**，没有撤销入口，所以信息必须摆全：
 * 名字、类型、起止、时长、是否按时。
 *
 * ## 删除为什么还要二次确认
 *
 * 详情框里的「删除」直接调 `deleteById`，删掉就没了（历史表没有软删除）。
 * 但也不能再套一层确认弹窗 —— 详情框本身已经是"用户主动点开、看清了才操作"的场景，
 * 再叠一层会变成三连点。折中：按钮文案写「删除记录」（而不是「删除」），
 * 用红色，并把删除后的 Dialog 关闭由调用方负责。
 *
 * @param arcColor 与表盘上该弧段相同的颜色，用来做标题前的小圆点 ——
 *                让用户一眼确认"我点的是这一段"
 * @param onDelete 确认删除
 */
@Composable
internal fun SessionDetailDialog(
    session: TimerSession,
    arcColor: Color,
    dayStart: Long,
    dayEnd: Long,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inDay = actualMillisInDay(session, dayStart, dayEnd)
    val full = actualFullMillis(session)
    // 开始与结束是否不在同一个本地日期。判据抽在 ArchiveFormat.crossesMidnight 里，
    // 有单测盯着（这个判断之前写反过：`endedAt + 1 < dayEnd` 让每条记录都显示跨午夜）。
    val crosses = crossesMidnight(session)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(arcColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = displayName(session),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailRow("类型", kindLabel(session.kind))
                DetailRow(
                    "开始",
                    ArchiveDates.shortDate(ArchiveDates.localDateOf(session.startedAt)) +
                        " " + clockOf(session.startedAt),
                )
                DetailRow(
                    "结束",
                    ArchiveDates.shortDate(ArchiveDates.localDateOf(session.endedAt)) +
                        " " + clockOf(session.endedAt),
                )
                DetailRow("时长", describeDuration(inDay))
                // 跨午夜时"当天只画了这一段"，必须说清楚，否则用户以为记录被截断了
                if (crosses) {
                    DetailRow("完整时长", describeDuration(full))
                    Text(
                        text = "这条记录跨午夜，表盘上只画了落在这一天里的部分",
                        fontSize = 11.sp,
                        color = ExtColors.TextDim,
                    )
                }
                DetailRow("结果", completionLabel(session) + " · " + planDeltaText(session))
                DetailRow("计划", describeDuration(session.plannedMillis))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                // 危险操作用红色（与 ConfirmDeleteDialog 保持一致）
                Text("删除记录", color = ExtColors.Red)
            }
        },
        containerColor = ExtColors.PureBlack,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = ExtColors.TextDim,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = ExtColors.TextPrimary,
        )
    }
}
