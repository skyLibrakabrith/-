package at.cwiesner.android.visualtimer.ext.ui.duration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 精确 DDL 时刻选择：日期 + 时 + 分。
 *
 * 刻意不用系统 MaterialDatePicker：系统的日期选择器在纯黑主题下会弹出
 * 自带白底的对话框，破坏一致性。这里是自绘的三段式数字选择。
 *
 * @param dateOffsetDays 0 = 今天，1 = 明天 …
 * @param hour 0..23
 * @param minute 0..59
 */
@Composable
fun DeadlineTimePicker(
    dateOffsetDays: Int,
    hour: Int,
    minute: Int,
    onDateChange: (Int) -> Unit,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxDays: Int = 30,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 日期 ----
        Text("日期", fontSize = 12.sp, color = ExtColors.TextDim)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("今天", "明天", "后天").forEachIndexed { idx, label ->
                SegmentChip(
                    text = label,
                    selected = dateOffsetDays == idx,
                    modifier = Modifier.weight(1f),
                ) { onDateChange(idx) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinnerBox(
                value = dateOffsetDays,
                range = 0..maxDays,
                suffix = " 天后",
                modifier = Modifier.weight(1f),
                onChange = onDateChange,
            )
        }

        // ---- 时刻 ----
        Text("时刻", fontSize = 12.sp, color = ExtColors.TextDim)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinnerBox(
                value = hour,
                range = 0..23,
                suffix = " 时",
                modifier = Modifier.weight(1f),
                onChange = onHourChange,
            )
            Text(":", fontSize = 24.sp, color = ExtColors.TextDim)
            SpinnerBox(
                value = minute,
                range = 0..59,
                suffix = " 分",
                modifier = Modifier.weight(1f),
                onChange = onMinuteChange,
            )
        }

        // ---- 常用快捷 ----
        Text("快捷", fontSize = 12.sp, color = ExtColors.TextDim)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(15 to "15分", 30 to "半小时", 60 to "1小时").forEach { (mins, label) ->
                SegmentChip(
                    text = label,
                    selected = false,
                    modifier = Modifier.weight(1f),
                ) {
                    // 快捷 = 以「现在 + N 分钟」反推时刻
                    val target = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.MINUTE, mins)
                    }
                    val todayCal = java.util.Calendar.getInstance()
                    val dayDiff = daysBetween(todayCal, target)
                    onDateChange(dayDiff.coerceIn(0, maxDays))
                    onHourChange(target.get(java.util.Calendar.HOUR_OF_DAY))
                    onMinuteChange(target.get(java.util.Calendar.MINUTE))
                }
            }
        }
    }
}

private fun daysBetween(from: java.util.Calendar, to: java.util.Calendar): Int {
    val a = from.clone() as java.util.Calendar
    val b = to.clone() as java.util.Calendar
    a.set(java.util.Calendar.HOUR_OF_DAY, 0)
    a.set(java.util.Calendar.MINUTE, 0)
    a.set(java.util.Calendar.SECOND, 0)
    b.set(java.util.Calendar.HOUR_OF_DAY, 0)
    b.set(java.util.Calendar.MINUTE, 0)
    b.set(java.util.Calendar.SECOND, 0)
    val diff = b.timeInMillis - a.timeInMillis
    return (diff / (24 * 60 * 60 * 1000L)).toInt()
}

/** 把「N 天后 + 时:分」换算成绝对时刻（epoch millis）。 */
fun deadlineEpochMillis(dateOffsetDays: Int, hour: Int, minute: Int): Long {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_MONTH, dateOffsetDays)
    cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
    cal.set(java.util.Calendar.MINUTE, minute)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Composable
private fun SegmentChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ExtColors.PrimaryDim else ExtColors.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) ExtColors.TextPrimary else ExtColors.TextSecondary,
        )
    }
}

/** 数值加减框：左右按钮 + 中间数字。 */
@Composable
private fun SpinnerBox(
    value: Int,
    range: IntRange,
    suffix: String,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ExtColors.Surface),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(44.dp)
                .clickable(interactionSource = interaction, indication = null) {
                    onChange((value - 1).coerceIn(range.first, range.last))
                },
            contentAlignment = Alignment.Center,
        ) { Text("−", fontSize = 20.sp, color = ExtColors.Primary) }

        Text(
            text = "%02d%s".format(value, suffix),
            fontSize = 16.sp,
            color = ExtColors.TextPrimary,
        )

        Box(
            modifier = Modifier
                .width(38.dp)
                .height(44.dp)
                .clickable(interactionSource = interaction, indication = null) {
                    onChange((value + 1).coerceIn(range.first, range.last))
                },
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = 20.sp, color = ExtColors.Primary) }
    }
}

/** 目标时刻的友好描述。 */
fun describeDeadline(dateOffsetDays: Int, hour: Int, minute: Int): String {
    val dayText = when (dateOffsetDays) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        else -> "${dateOffsetDays} 天后"
    }
    return "%s %02d:%02d".format(dayText, hour, minute)
}
