package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView

/** 时刻显示 HH:mm。 */
private fun clockOf(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

/**
 * 单个表盘的整页编辑视图。
 *
 * **性能要点**：拖动盘面时只更新本地 [draft]，**不写数据库**。
 * 之前的写法是每帧 `db.timerDao().upsert(...)`，Room 写盘造成明显卡顿。
 * 现在只在拖动结束（`onDurationCommit`）时提交一次。
 */
@Composable
fun TimerFocusScreen(
    timer: TimerItem,
    runtime: TimerRuntime?,
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    onDurationCommit: (Long) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = runtime?.running == true
    // 本地草稿：拖动期间只改这个，不触碰数据库
    var draft by remember(timer.id) { mutableLongStateOf(timer.durationMillis) }

    val remain = if (running) (runtime?.remainingMillis ?: draft) else draft
    val total = if (running) (runtime?.totalMillis ?: draft) else draft

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = timer.label ?: "未命名",
                fontSize = 15.sp,
                color = ExtColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = ExtColors.TextSecondary,
                )
            }
        }

        PieTimerView(
            totalMillis = total,
            remainingMillis = remain,
            running = running,
            onDurationChange = if (running) null else { d -> draft = d },
            onDurationCommit = if (running) null else { d ->
                draft = d
                onDurationCommit(d)
            },
            modifier = if (at.cwiesner.android.visualtimer.ext.ui.isLandscape()) {
                Modifier.fillMaxWidth(0.6f)
            } else {
                Modifier.fillMaxWidth()
            },
            maxMinutes = 60,
            stepMinutes = 1,
            centerFontSize = 56f,
            centerLabel = if (running) null else "拖动盘面调整时长",
            colorMode = colorMode,
            intervalMinutes = intervalMinutes,
            fractionDivisions = fractionDivisions,
            thresholdMinutes = thresholdMinutes,
            thresholdColors = thresholdColors,
            overdueMillis = runtime?.overdueMillis ?: 0L,
        )

        Text(
            text = if ((runtime?.overdueMillis ?: 0L) > 0L) {
                "已超时 " + DeadlineCalculator.formatRemaining(runtime!!.overdueMillis)
            } else if (running) {
                "剩余 " + DeadlineCalculator.formatRemaining(remain) +
                    " · " + clockOf(System.currentTimeMillis() + remain) + " 结束"
            } else {
                "时长 " + DeadlineCalculator.formatRemaining(draft) +
                    " · " + clockOf(System.currentTimeMillis() + draft) + " 结束"
            },
            fontSize = 13.sp,
            color = ExtColors.TextDim,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (running) {
                TextButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("暂停") }
            } else {
                TextButton(onClick = onStart, modifier = Modifier.weight(1f)) { Text("开始") }
            }
            TextButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("重置") }
        }
    }
}
