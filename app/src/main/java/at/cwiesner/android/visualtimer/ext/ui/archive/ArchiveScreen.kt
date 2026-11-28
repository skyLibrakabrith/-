package at.cwiesner.android.visualtimer.ext.ui.archive

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.util.logCatching
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 归档页：按天回看「什么时候开始/结束了什么」。
 *
 * ## 页面结构
 *
 * ```
 *  ┌──────────────────────────────┐
 *  │  ‹   11-27 周四   ›   今天   │  ← 日期切换
 *  ├──────────────────────────────┤
 *  │          24 小时表盘          │  ← DayPieChart，点弧段看详情
 *  ├──────────────────────────────┤
 *  │   当天记录列表（可滚动）        │  ← 盘面难分辨时靠列表兜底
 *  ├──────────────────────────────┤
 *  │         导出（底部按钮）        │
 *  └──────────────────────────────┘
 * ```
 *
 * ## 为什么除了表盘还要一个列表
 *
 * 24 小时表盘回答的是"时间分布"（几点忙、有没有空档），
 * 但它**不擅长表达多条短记录的顺序和名字** —— 十条 2 分钟的记录在环上是十个小点。
 * 列表补的是"具体是哪几条"。两者相加才够用；只有盘面会让人点来点去才看清全貌，
 * 只有列表则失去了"一天里时间都去哪儿了"的一眼感知。
 *
 * ## 为什么当前日期用 `rememberSaveable` 存 `Long` 而不是 `LocalDate`
 *
 * `LocalDate` 不可 Parcelable，`rememberSaveable` 需要能存进 Bundle 的类型。
 * 存 `toEpochDay()`（天序号）既简单又稳定 —— 存 `dayStart` 毫秒也行，
 * 但时区变了它就不再是"零点"，而天序号永远是那一天。恢复时用
 * `LocalDate.ofEpochDay` 还原。
 *
 * ## 数据订阅为什么用 `observeBetween(一天)` 而不是全量
 *
 * 归档表会一直增长（每次到点都写一条）。全量订阅 `allOnce()` 是 `suspend` 一次性读取，
 * 没有 Flow 就做不到"删一条立刻从图上消失"。所以用 Flow 版，
 * 窗口固定为当天 `[零点, 次日零点)`，Room 会为这个查询维护观察，
 * 删除后自动重新发射 —— 不需要手动刷新。
 */
@Composable
fun ArchiveScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()

    // 今天（真实日期）。用来判断"现在看的是不是今天"、以及导出的快捷范围。
    //
    // 不写成 `remember { today }`：那样只在首次组合时算一次，
    // 应用挂在后台跨过午夜再回来，"今天"仍停在昨天 —— 「今天」按钮点了没反应、
    // 导出的「近 7 天」也少一天。所以定时重算：每分钟一次，
    // 只有日期真的变了才写 State（避免无谓重组）。
    var today by remember { mutableStateOf(ArchiveDates.today()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            val now = ArchiveDates.today()
            if (now != today) today = now
        }
    }

    // 存天序号而不是毫秒：见类注释
    var selectedEpochDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selectedDate = LocalDate.ofEpochDay(selectedEpochDay)
    val dayStart = remember(selectedEpochDay) { ArchiveDates.dayStart(selectedDate) }
    val dayEnd = remember(selectedEpochDay) { ArchiveDates.dayEnd(selectedDate) }

    // 只订阅当天窗口。key 变了会重新 collect（换了一天就换一个查询）。
    val sessionsFlow = remember(dayStart, dayEnd) { db.timerSessionDao().observeBetween(dayStart, dayEnd) }
    val sessions by sessionsFlow.collectAsState(initial = emptyList<TimerSession>())

    // 暂停区间：圆盘要把暂停那几段空出来。
    // 一次性查库（不是 Flow）—— 暂停区间不会在用户盯着归档页时变化，
    // 而且它只按当天这批 sessionId 查，量大不到哪去。
    var pausesBySession by remember { mutableStateOf<Map<Long, List<Pair<Long, Long>>>>(emptyMap()) }
    LaunchedEffect(sessions) {
        val ids = sessions.map { it.id }
        pausesBySession = if (ids.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                db.timerPauseDao().forSessions(ids)
                    .groupBy { it.sessionId }
                    .mapValues { (_, list) ->
                        // toMillis = 0 表示仍在暂停中：留 0，由 buildDialArcs 当作"停到会话结束"
                        list.map { it.fromMillis to it.toMillis }
                    }
            }.getOrDefault(emptyMap())
        }
    }

    // ---- 详情对话框 ----
    var detail by remember { mutableStateOf<TimerSession?>(null) }

    // ---- 导出 ----
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf<ArchiveExport?>(null) }
    // 最近一次预览请求。Dialog 每次组合都会回报一次请求，
    // 这里用 remember 挡住"同一个请求重复查库"。
    var lastPreviewRequest by remember { mutableStateOf<ExportRequest?>(null) }

    // 详情对话框里删掉之后要关掉它；删除本身走 DAO。
    val onDelete: (TimerSession) -> Unit = { s ->
        detail = null
        scope.launch {
            val ok = logCatching("archive-delete") { db.timerSessionDao().deleteById(s.id) }
            if (ok == null) {
                // logCatching 已把异常打进 logcat；这里再给用户一个可见反馈
                Toast.makeText(context, "删除失败，详情见 logcat", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 把当前快照分享出去。
     *
     * 用 `ACTION_SEND` + `text/plain`：
     * - 不需要 FileProvider（不产生文件）
     * - 不需要任何存储权限
     * - 微信/短信/备忘录/邮件全都接得住
     * 只分享文本，不分享"文件"，所以永远不会出现"对方打不开附件"。
     */
    val onShare: (ArchiveExport) -> Boolean = { export ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, export.subject)
            putExtra(Intent.EXTRA_TEXT, export.text)
        }
        val launched = logCatching("archive-share") {
            context.startActivity(
                Intent.createChooser(intent, export.subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }
        if (launched == null) {
            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            false
        } else {
            true
        }
    }

    // 预览生成：查库 → 拼文本。放在 LaunchedEffect 里（suspend），
    // 并把当前真实日期传进去 —— 查看历史某天时导出的"近 7 天"仍以真实今天为准。
    fun buildPreview(request: ExportRequest, todayRef: LocalDate) {
        if (lastPreviewRequest == request && exportPreview != null) return
        lastPreviewRequest = request
        scope.launch {
            val (fromDate, toDate) = resolveExportBounds(request, todayRef)
            val from = ArchiveDates.dayStart(fromDate)
            val until = ArchiveDates.dayEnd(toDate)
            val rows = logCatching("archive-export-query") {
                db.timerSessionDao().between(from, until)
            }.orEmpty()
            exportPreview = buildExportText(rows, from, until)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------- 顶部：日期切换 ----------------
        DateHeader(
            date = selectedDate,
            isToday = selectedDate == today,
            onPrev = { selectedEpochDay -= 1 },
            onNext = {
                // 不允许翻到未来：未来没有记录，翻过去只会看到空盘
                if (selectedDate < today) selectedEpochDay += 1
            },
            onToday = { selectedEpochDay = today.toEpochDay() },
        )

        // ---------------- 中部：表盘 ----------------
        DayPieChart(
            sessions = sessions,
            pausesBySession = pausesBySession,
            dayStart = dayStart,
            dayEnd = dayEnd,
            onArcTap = { detail = it },
        )

        if (sessions.isEmpty()) {
            Text(
                text = "这一天还没有计时记录。\n计时到点后会自动记在这里。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            // 列表兜底：盘面看不出的"具体是哪几条"在这里补上。
            // 最多显示 MAX_LIST_ROWS 条，剩下的一句话提示（避免这一天特别碎时页面无限长）。
            val shown = sessions.take(MAX_LIST_ROWS)
            shown.forEach { s ->
                SessionListRow(
                    session = s,
                    dayStart = dayStart,
                    dayEnd = dayEnd,
                    onClick = { detail = s },
                )
            }
            if (sessions.size > shown.size) {
                Text(
                    text = "还有 ${sessions.size - shown.size} 条，点表盘上的弧段查看",
                    fontSize = 11.sp,
                    color = ExtColors.TextDim,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        // ---------------- 底部：导出 ----------------
        Spacer(Modifier.height(4.dp))
        Text(
            text = "导出",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = ExtColors.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ExtColors.Surface)
                .clickable {
                    // 打开对话框时清掉上一次的预览，避免先显示旧范围的内容
                    exportPreview = null
                    lastPreviewRequest = null
                    exportOpen = true
                }
                .padding(vertical = 13.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
    }

    // ---------------- 详情 ----------------
    val detailSession = detail
    if (detailSession != null) {
        // 弧段颜色必须与表盘一致：用同一条排序规则算下标
        val daySessions = sessions
        val idx = daySessions.sortedWith(compareBy({ it.startedAt }, { it.id }))
            .indexOfFirst { it.id == detailSession.id }
        val color = if (idx >= 0) {
            ExtColors.trafficStepColor(idx, daySessions.size)
        } else {
            ExtColors.Primary
        }
        SessionDetailDialog(
            session = detailSession,
            arcColor = color,
            dayStart = dayStart,
            dayEnd = dayEnd,
            onDelete = { onDelete(detailSession) },
            onDismiss = { detail = null },
        )
    }

    // ---------------- 导出 ----------------
    if (exportOpen) {
        val shareNow by rememberUpdatedState(onShare)
        ExportDialog(
            today = today,
            preview = exportPreview,
            onRequestPreview = { req -> buildPreview(req, today) },
            onShare = { export ->
                if (shareNow(export)) exportOpen = false
            },
            onDismiss = { exportOpen = false },
        )
    }
}

/** 顶部日期切换条：‹ 日期 ›  + 「今天」。 */
@Composable
private fun DateHeader(
    date: LocalDate,
    isToday: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavArrow("‹", onPrev)
            Text(
                text = ArchiveDates.dayTitle(date),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = ExtColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            // 已经在今天时把「›」变成不可点（灰掉），而不是仍可点但无反应
            NavArrow("›", onNext, enabled = !isToday)
        }
        if (!isToday) {
            Text(
                text = "今天",
                fontSize = 13.sp,
                color = ExtColors.Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ExtColors.PrimaryDim)
                    .clickable(onClick = onToday)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun NavArrow(symbol: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) ExtColors.Surface else ExtColors.Track)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontSize = 16.sp,
            color = if (enabled) ExtColors.TextPrimary else ExtColors.TextDim,
        )
    }
}

/** 列表里的一行记录：`09:15–09:45  写周报  30 分`。 */
@Composable
private fun SessionListRow(
    session: TimerSession,
    dayStart: Long,
    dayEnd: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ExtColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = spanTextOf(session, dayEnd),
            fontSize = 12.sp,
            color = ExtColors.TextSecondary,
            modifier = Modifier.width(112.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = displayName(session),
            fontSize = 13.sp,
            color = ExtColors.TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = describeDuration(actualMillisInDay(session, dayStart, dayEnd)),
            fontSize = 12.sp,
            color = ExtColors.TextDim,
        )
    }
}

/** 列表最多显示多少条（超出的提示"点表盘"）。 */
private const val MAX_LIST_ROWS: Int = 20
