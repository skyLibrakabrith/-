package at.cwiesner.android.visualtimer.ext.ui.panels

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
import androidx.compose.material3.Card
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
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView

/**
 * 组卡片。
 *
 * **与单个计时器同尺寸**：占网格里的一个格子，`aspectRatio` 与普通计时器一致，
 * 内部把成员小盘并排铺开（最多 4 个，2×2）。
 *
 * 这样「成组」不会让布局变稀疏 —— 组和未成组的计时器在视觉上是同一量级，
 * 只是内部多了几个小盘。
 */
@Composable
fun TimerGroupCell(
    group: TimerGroup,
    members: List<TimerItem>,
    runtimeById: Map<Long, TimerRuntime>,
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val runningCount = members.count { runtimeById[it.id]?.running == true }

    Card(
        // 点按交给 DraggableGrid 的卡片层，这里不再叠加一层。
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                // 组内小盘网格
                val shown = members.take(MAX_IN_GROUP)
                val rows = (shown.size + 1) / 2
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("空组", fontSize = 10.sp, color = ExtColors.TextDim)
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (r in 0 until rows.coerceAtLeast(1)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    for (c in 0 until 2) {
                                        val timer = shown.getOrNull(r * 2 + c)
                                        Box(
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (timer != null) {
                                                PieTimerView(
                                                    totalMillis = runtimeById[timer.id]?.totalMillis
                                                        ?: timer.durationMillis,
                                                    remainingMillis = runtimeById[timer.id]
                                                        ?.remainingMillis ?: timer.durationMillis,
                                                    running = runtimeById[timer.id]?.running == true,
                                                    onDurationChange = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    maxMinutes = 60,
                                                    centerFontSize = 10f,
                                                    innerDial = false,
                                                    colorMode = colorMode,
                                                    intervalMinutes = intervalMinutes,
                                                    fractionDivisions = fractionDivisions,
                                                    thresholdMinutes = thresholdMinutes,
                                                    thresholdColors = thresholdColors,
                                                    overdueMillis = runtimeById[timer.id]
                                                        ?.overdueMillis ?: 0L,
                                                    fillParent = true,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 组名 + 运行中数量
                Text(
                    text = group.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = ExtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = buildString {
                        append("${members.size} 个")
                        if (runningCount > 0) append(" · $runningCount 运行")
                    },
                    fontSize = 9.sp,
                    color = if (runningCount > 0) ExtColors.Primary else ExtColors.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 一个组里最多同时显示几个成员小盘。 */
private const val MAX_IN_GROUP = 4
