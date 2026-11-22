package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView

/**
 * 组卡片（桌面式文件夹图标）。
 *
 * 仿手机桌面上的「文件夹」：一个圆角方块，内部用 3 列网格排布
 * 最多 [MAX_ICONS] 个迷你表盘。点卡片进入该组。
 *
 * 之所以做成文件夹而不是继续用 Tab + 大网格：
 * 面板一多，Tab 会被挤成滚动条，用户失去整体感。
 * 文件夹形式能在一个屏幕上同时看到所有组的状态。
 */
private const val MAX_ICONS = 9
private const val ICONS_PER_ROW = 3

@Composable
fun PanelGroupTile(
    panel: Panel,
    timers: List<TimerItem>,
    runtimeById: Map<Long, TimerRuntime>,
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runningCount = timers.count { runtimeById[it.id]?.running == true }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.86f)
            .clip(RoundedCornerShape(18.dp))
            .background(ExtColors.Surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 标题行：名称 + 运行中数量
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = panel.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ExtColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (runningCount > 0) {
                Text(
                    text = "$runningCount",
                    fontSize = 10.sp,
                    color = ExtColors.Primary,
                )
            }
        }

        // 迷你表盘网格
        val shown = timers.take(MAX_ICONS)
        val rows = (shown.size + ICONS_PER_ROW - 1) / ICONS_PER_ROW
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空组", fontSize = 10.sp, color = ExtColors.TextDim)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (r in 0 until rows.coerceAtLeast(1)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (c in 0 until ICONS_PER_ROW) {
                                val idx = r * ICONS_PER_ROW + c
                                val timer = shown.getOrNull(idx)
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (timer != null) {
                                        val rt = runtimeById[timer.id]
                                        MiniDial(
                                            timer = timer,
                                            runtime = rt,
                                            colorMode = colorMode,
                                            intervalMinutes = intervalMinutes,
                                            fractionDivisions = fractionDivisions,
                                            thresholdMinutes = thresholdMinutes,
                                            thresholdColors = thresholdColors,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 超出 9 个时提示
        if (timers.size > MAX_ICONS) {
            Text(
                text = "还有 ${timers.size - MAX_ICONS} 个",
                fontSize = 9.sp,
                color = ExtColors.TextDim,
            )
        }
    }
}

/** 组卡片里的单个迷你表盘。 */
@Composable
private fun MiniDial(
    timer: TimerItem,
    runtime: TimerRuntime?,
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
) {
    val running = runtime?.running == true
    val remain = if (running) (runtime?.remainingMillis ?: timer.durationMillis) else timer.durationMillis
    val total = if (running) (runtime?.totalMillis ?: timer.durationMillis) else timer.durationMillis

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PieTimerView(
            totalMillis = total,
            remainingMillis = remain,
            running = running,
            onDurationChange = null,
            modifier = Modifier.fillMaxSize(),
            maxMinutes = 60,
            centerFontSize = 9f,
            innerDial = false,
            showCenterText = true,
            colorMode = colorMode,
            intervalMinutes = intervalMinutes,
            fractionDivisions = fractionDivisions,
            thresholdMinutes = thresholdMinutes,
            thresholdColors = thresholdColors,
            overdueMillis = runtime?.overdueMillis ?: 0L,
            fillParent = true,
        )
    }
}

/** 组卡片里用来标出整体状态的小色点。 */
@Composable
internal fun StatusDot(running: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (running) ExtColors.Primary else ExtColors.TextDim),
    )
}
