package at.cwiesner.android.visualtimer.ext.ui.home

import android.util.Log
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
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCreator
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.panels.DeadlineLeadTimeDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 首页：24 小时大时钟。
 *
 * 在盘面上拖动选择目标时刻，下方实时显示「距离目标还有多久」，
 * 两个按钮直接把它变成倒计时或截止时刻计时器。
 * 目标时刻在后天之前会自动按「最近的将来」理解（已过的时刻顺延一天）。
 *
 * 「创建 DDL」走两步：先弹 [DeadlineLeadTimeDialog] 问提前提醒档位，确认后才真正创建。
 * 为什么必须多这一步 —— 原来这个按钮直接调 `createTimer()`，而那条老路径只写了
 * `kind` 和 `deadlineEpochMillis`，漏掉 `locked = true`、`startUntil()` 和
 * `deadline_reminders` 落库，于是从首页建的 DDL 不锁定、不自动开始、永远不提醒。
 * 现在统一走 [DeadlineCreator]，和面板页共用同一份实现。
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

    // 已经点过「创建 DDL」、正在问提前提醒档位的中间态。
    // 此刻数据库里还没有任何东西，所以「取消」= 放弃创建，不需要回滚。
    // 必须在组合层（而不是对话框的 onClick 里）持有：对话框会随重组反复重建，
    // 局部状态活不过一次重组。
    var pendingDeadline by remember { mutableStateOf<PendingDeadline?>(null) }

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
                // 只记下待创建的目标，真正的落库交给下面的 DeadlineLeadTimeDialog 确认后执行。
                // 不再直接调 createTimer()：那条路径不写 locked / 不 startUntil / 不落提醒库。
                pendingDeadline = PendingDeadline(
                    label = "DDL " + clockText(target),
                    deadlineAt = target,
                )
            }
        }

        // DDL 的第二步：问提前提醒档位，确认后才创建。
        // 放在 Composable 体里（不在上面的 Row 里）是因为它是覆盖全屏的 AlertDialog。
        val pending = pendingDeadline
        if (pending != null) {
            DeadlineLeadTimeDialog(
                // 截止时刻可能已经过了（用户选中的是刚过去的一秒），至少给 1 秒。
                // 对话框内部按 /60_000 算剩余分钟，1 秒与 0 秒等价，这里只是防未来改动踩 0。
                remainingMillis = (pending.deadlineAt - now).coerceAtLeast(1000L),
                // 点对话框外面 = 放弃创建（此刻还没写库）
                onDismiss = { pendingDeadline = null },
                onConfirm = { leads ->
                    // 先关对话框再启动协程：否则用户在这几百毫秒里能再点一次确认，建出两条 DDL
                    pendingDeadline = null
                    scope.launch {
                        val ok = commitDeadline(
                            db = db,
                            app = app,
                            panels = panels,
                            label = pending.label,
                            deadlineAt = pending.deadlineAt,
                            leads = leads,
                        )
                        if (!ok) return@launch
                        toast(
                            if (leads.isEmpty()) "DDL 已创建并开始计时（未设提前提醒）"
                            else "DDL 已创建并开始计时，${leads.size} 个提前提醒",
                        )
                    }
                },
            )
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

/**
 * 首页「创建 DDL」的真正落库动作：跑 [DeadlineCreator.createDeadline]。
 *
 * @return true 表示创建成功（调用方据此决定要不要 toast）；false 表示失败，
 *   原因已经 toast 过、也记了日志，调用方不要再弹第二条提示。
 *
 * 为什么和 `createTimer` 并列、而不是复用它：
 * `createTimer` 是「普通倒计时」的入口（快捷按钮还在用），它只管写 kind + deadline；
 * 而 DDL 要的是「锁定 + 自动开始 + 提前提醒」四件事，那是 [DeadlineCreator] 的职责。
 * 两条路径的语义不同，硬合并会把普通倒计时的行为也一起改掉。
 */
private suspend fun commitDeadline(
    db: at.cwiesner.android.visualtimer.ext.data.AppDatabase,
    app: ExtApplication,
    panels: List<Panel>,
    label: String,
    deadlineAt: Long,
    leads: List<Long>,
): Boolean {
    return try {
        val now = System.currentTimeMillis()
        val panelId = resolvePanelId(db, panels)
        val timerId = DeadlineCreator.createDeadline(
            db = db,
            runner = app.panelRunner,
            panelId = panelId,
            label = label,
            deadlineAt = deadlineAt,
            leads = leads,
            // 首页没有「选提醒方式」这一步，用默认配置（null = 走全局默认）。
            alertProfileId = null,
            now = now,
        )
        // 留一条日志：用户报「建的 DDL 不跑 / 不提醒」时，logcat 里能直接看到
        // 是哪条路径建的、挂到哪个面板、几个提醒档位（本工程 runCatching 吞异常太多）。
        Log.i(
            TAG,
            "首页创建 DDL: id=$timerId panel=$panelId leads=$leads " +
                "remaining=${(deadlineAt - now) / 1000}s",
        )
        true
    } catch (e: Exception) {
        // 不捕获 CancellationException：那是界面被销毁时的正常取消，不该 toast。
        if (e is kotlinx.coroutines.CancellationException) throw e
        // 这里必须告诉用户：否则点了「确定」什么都没发生，看起来像按钮失灵。
        Log.e(TAG, "首页创建 DDL 失败", e)
        Toast.makeText(app, "创建失败：" + e.message, Toast.LENGTH_LONG).show()
        false
    }
}

/**
 * 取要挂计时器的面板 id：优先第一个已有面板，没有就自动建「默认面板」。
 *
 * 思路与 `createTimer` 里那段一致，但 `createTimer` 不能改（倒计时和快捷按钮还在用），
 * 所以这里单独写一份。
 */
private suspend fun resolvePanelId(
    db: at.cwiesner.android.visualtimer.ext.data.AppDatabase,
    panels: List<Panel>,
): Long {
    return panels.firstOrNull()?.id ?: db.panelDao().upsert(Panel(name = "默认面板", sortOrder = 0))
}

/**
 * 本文件的日志标签。DDL 创建流程有诊断日志，出问题时按这个 tag 过滤 logcat。
 */
private const val TAG = "HomeScreen"

/**
 * 「创建 DDL」两步创建的中间状态：已经选好截止时刻，正在问提前提醒档位。
 *
 * 此刻数据库里还没有任何东西，所以取消第二步 = 放弃创建，不需要回滚。
 * 注意和面板页的 `PendingDeadline` 同名但不同文件、不同包，
 * 两个都是 `private`，互不影响。
 */
private data class PendingDeadline(
    val label: String,
    val deadlineAt: Long,
)

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
