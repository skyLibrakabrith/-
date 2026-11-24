package at.cwiesner.android.visualtimer.ext.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 首页：24 小时大时钟。
 *
 * 在盘面上拖动选择目标时刻，下方实时显示「距离目标还有多久」，
 * 两个按钮直接把它变成倒计时或截止时刻计时器。
 * 目标时刻在后天之前会自动按「最近的将来」理解（已过的时刻顺延一天）。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()

    // 每秒刷新「现在」
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    // 未选择时默认「现在 + 30 分钟」
    var picked by remember { mutableStateOf<Long?>(null) }
    val target = picked ?: (now + 30 * 60_000L)
    val remaining = (target - now).coerceAtLeast(0L)

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    val panels by remember { db.panelDao().observeAll() }
        .collectAsState(initial = emptyList<Panel>())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 横屏时把时钟按可用高度约束宽度并居中，避免正方形盘面顶出屏幕
        val landscape = at.cwiesner.android.visualtimer.ext.ui.isLandscape()
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            DayClockView(
                nowEpochMillis = now,
                pickedEpochMillis = if (picked != null) target else null,
                onPick = { picked = it },
                modifier = if (landscape) {
                    Modifier.fillMaxWidth(0.62f)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
        }

        // 目标信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "目标 " + describeTarget(target, now),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ExtColors.TextPrimary,
                )
                Text(
                    text = "距离目标 " + DeadlineCalculator.formatRemaining(remaining),
                    fontSize = 13.sp,
                    color = ExtColors.TextSecondary,
                )
            }
        }

        // 快捷创建
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BigAction(
                text = "创建倒计时",
                modifier = Modifier.weight(1f),
                primary = true,
            ) {
                scope.launch {
                    createTimer(
                        db = db,
                        panels = panels,
                        kind = TimerKind.Countdown,
                        durationMillis = remaining,
                        deadline = null,
                        label = "到 " + clockText(target),
                    )
                    toast("已创建倒计时：剩余 " +
                        at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
                            .formatRemaining(remaining))
                }
            }
            BigAction(
                text = "创建 DDL",
                modifier = Modifier.weight(1f),
                primary = false,
            ) {
                scope.launch {
                    createTimer(
                        db = db,
                        panels = panels,
                        kind = TimerKind.Deadline,
                        durationMillis = remaining,
                        deadline = target,
                        label = "DDL " + clockText(target),
                    )
                    toast("已创建 DDL：" + describeTarget(target, now))
                }
            }
        }

        // 常用快速选项
        Text("快速", fontSize = 12.sp, color = ExtColors.TextDim)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                5 * 60_000L to "5 分",
                15 * 60_000L to "15 分",
                30 * 60_000L to "半小时",
                60 * 60_000L to "1 小时",
            ).forEach { (ms, label) ->
                SmallChip(label, Modifier.weight(1f)) {
                    // 点一下立刻创建并开始计时，不用再回面板点一遍
                    scope.launch {
                        val t = createTimer(
                            db = db,
                            panels = panels,
                            kind = TimerKind.Countdown,
                            durationMillis = ms,
                            deadline = null,
                            label = label,
                        )
                        app.panelRunner.start(t)
                        toast("已开始：$label")
                    }
                }
            }
            // 正计时：同样一键开始
            SmallChip("正计时", Modifier.weight(1f)) {
                scope.launch {
                    val t = createTimer(
                        db = db,
                        panels = panels,
                        kind = TimerKind.Stopwatch,
                        durationMillis = 0L,
                        deadline = null,
                        label = "正计时",
                    )
                    app.panelRunner.startStopwatch(t)
                    toast("正计时已开始")
                }
            }
        }

        if (panels.isEmpty()) {
            Text(
                "还没有面板。到「面板」页新建一个，这里创建的计时器会放进去。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Box(modifier = Modifier.height(24.dp))
    }
}

private suspend fun createTimer(
    db: at.cwiesner.android.visualtimer.ext.data.AppDatabase,
    panels: List<Panel>,
    kind: TimerKind,
    durationMillis: Long,
    deadline: Long?,
    label: String,
): at.cwiesner.android.visualtimer.ext.data.TimerItem {
    val panelId = panels.firstOrNull()?.id ?: run {
        val pid = db.panelDao().upsert(
            Panel(name = "默认面板", sortOrder = 0),
        )
        pid
    }
    val timerId = db.timerDao().upsert(
        TimerItem(
            label = label,
            durationMillis = durationMillis.coerceAtLeast(1000L),
            kind = kind,
            deadlineEpochMillis = deadline,
        ),
    )
    val existing = db.panelDao().refsOf(panelId)
    db.panelDao().upsertRefs(
        listOf(PanelTimerRef(panelId = panelId, timerId = timerId, order = existing.size)),
    )
    return db.timerDao().get(timerId)
        ?: TimerItem(id = timerId, label = label, durationMillis = durationMillis, kind = kind)
}

private fun describeTarget(target: Long, now: Long): String {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = target }
    val b = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    val t = clockText(target)
    return if (sameDay) "今天 $t" else "明天 $t"
}

@Composable
private fun BigAction(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) ExtColors.Primary else ExtColors.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) Color_OnPrimary else ExtColors.TextPrimary,
        )
    }
}

private val Color_OnPrimary = androidx.compose.ui.graphics.Color(0xFF001639)

@Composable
private fun SmallChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ExtColors.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = ExtColors.TextSecondary)
    }
}
