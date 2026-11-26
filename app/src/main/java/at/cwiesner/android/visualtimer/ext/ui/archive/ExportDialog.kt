package at.cwiesner.android.visualtimer.ext.ui.archive

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import java.time.LocalDate

/**
 * 导出面板。
 *
 * ## 为什么不直接点一下就分享
 *
 * 归档页显示的是**某一天**，而"导出"多数时候想要的是**一段时间**
 * （周报、月报、某次项目）。如果直接导出当前这一天，用户每次都要先切到那一天再点导出，
 * 想导一周就得导 7 次 —— 所以这里先问范围。
 *
 * ## 为什么先预览再分享
 *
 * 文本是给人看的（粘到微信、备忘录），格式对不对用户自己一眼就知道。
 * 先给预览，就不用"分享出去 → 发现乱码/错位 → 再导一次"。
 * 预览区用等宽字体 [FontFamily.Monospace]，因为文本用空格对齐，
 * 非等宽字体下会歪。
 *
 * @param today 以哪一天为"今天"（由调用方传入，方便测试与"看历史时仍按真实今天算"）
 * @param preview 当前范围对应的预览结果；null 表示还在统计。
 *                **由调用方持有**：它来自一次挂起查库，Dialog 自己不该持有数据库访问。
 * @param onRequestPreview 范围变化时请外层去查库生成预览
 * @param onShare 用户点「分享」时回调（拿到已经生成好的文本）
 */
@Composable
internal fun ExportDialog(
    today: LocalDate,
    preview: ArchiveExport?,
    onRequestPreview: (ExportRequest) -> Unit,
    onShare: (ArchiveExport) -> Unit,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf(ExportRange.Last7) }
    // 自定义范围：默认与 Last7 一致（点进来就已经有一个合理值，而不是空白）
    var customStart by remember { mutableStateOf(today.minusDays(6)) }
    var customEnd by remember { mutableStateOf(today) }

    val request = ExportRequest(range, customStart, customEnd)

    // 请求随档位/日期变化而更新；key 用 data class，值没变就不会重复查库。
    LaunchedEffect(request) { onRequestPreview(request) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出计时记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "选择时间范围",
                    fontSize = 12.sp,
                    color = ExtColors.TextDim,
                )
                // 四个快捷档位，两行两列
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RangeChip(ExportRange.Last7, range) { range = it }
                    RangeChip(ExportRange.Last30, range) { range = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RangeChip(ExportRange.ThisMonth, range) { range = it }
                    RangeChip(ExportRange.Custom, range) { range = it }
                }
                if (range == ExportRange.Custom) {
                    CustomRangeEditor(
                        start = customStart,
                        end = customEnd,
                        today = today,
                        onStartChange = { customStart = it },
                        onEndChange = { customEnd = it },
                    )
                }
                Text(
                    text = preview?.let {
                        "共 ${it.sessionCount} 条 · 合计 ${describeTotal(it.totalMillisInRange)}"
                    } ?: "正在统计…",
                    fontSize = 12.sp,
                    color = ExtColors.TextSecondary,
                )
                // 预览：等宽字体 + 最多 12 行，避免弹窗撑爆
                val previewText = preview?.text
                if (!previewText.isNullOrEmpty()) {
                    val lines = previewText.trimEnd('\n').split("\n")
                    val shown = lines.take(PREVIEW_MAX_LINES)
                    Text(
                        text = shown.joinToString("\n") +
                            if (lines.size > shown.size) "\n…（还有 ${lines.size - shown.size} 行）" else "",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ExtColors.TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(8.dp))
                            .background(ExtColors.Surface)
                            .padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            // 预览为空（这段时间没有记录）时禁用「分享」：
            // 分享一段"这段时间没有计时记录。"出去没有意义，还会让人以为记录丢了。
            TextButton(
                onClick = { preview?.let { onShare(it) } },
                enabled = preview != null && !preview.isEmpty,
            ) { Text("分享") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = ExtColors.PureBlack,
    )
}

/**
 * 导出请求。
 *
 * **为什么不用 `Pair<ExportRange, Pair<LocalDate, LocalDate>>`**：可读性太差，
 * 且三个字段互为前提（只有 Custom 才用日期），包成一个类型之后
 * `equals`/`remember` 的语义也自然正确 —— 值一样的请求不会重复查库。
 */
internal data class ExportRequest(
    val range: ExportRange,
    val customStart: LocalDate,
    val customEnd: LocalDate,
)

/**
 * 把 [ExportRequest] 变成 `[from, until)`。
 *
 * 自定义范围要做一次"顺序纠正"：用户很可能先点结束再点开始，
 * 或者在日期选择器里把两端弄反。与其报错，不如直接对调 —— 结果一样。
 */
internal fun resolveExportBounds(request: ExportRequest, today: LocalDate): Pair<LocalDate, LocalDate> {
    if (request.range != ExportRange.Custom) {
        val (from, until) = exportRangeBounds(request.range, today)
        return ArchiveDates.localDateOf(from) to ArchiveDates.localDateOf(until - 1L)
    }
    val a = request.customStart
    val b = request.customEnd
    return if (a <= b) a to b else b to a
}

@Composable
private fun RangeChip(
    target: ExportRange,
    current: ExportRange,
    onPick: (ExportRange) -> Unit,
) {
    val selected = target == current
    Text(
        text = target.label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) ExtColors.PureBlack else ExtColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ExtColors.Primary else ExtColors.Track)
            .clickable { onPick(target) }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * 自定义日期编辑。
 *
 * 用左右减/加天数的按钮，而不是拉起系统的日期选择器：
 * 归档场景里往前翻基本是"几天前/上周"这种粗略量，
 * 加减按钮在**单击**这一步就够了，而 DatePickerDialog 要多两层确认。
 * （拉到一个月以外时确实不如日历直观，但那是 Rare case，宁可真机上手感稳。）
 */
@Composable
private fun CustomRangeEditor(
    start: LocalDate,
    end: LocalDate,
    today: LocalDate,
    onStartChange: (LocalDate) -> Unit,
    onEndChange: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DateStepper(
            label = "起",
            value = start,
            onMinus = { onStartChange(start.minusDays(1)) },
            onPlus = { onStartChange(start.plusDays(1).coerceAtMost(end)) },
        )
        DateStepper(
            label = "止",
            value = end,
            onMinus = { onEndChange(end.minusDays(1).coerceAtLeast(start)) },
            onPlus = { onEndChange(end.plusDays(1).coerceAtMost(today)) },
        )
    }
}

@Composable
private fun DateStepper(
    label: String,
    value: LocalDate,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 12.sp, color = ExtColors.TextDim)
        StepButton("−", onMinus)
        Text(
            text = ArchiveDates.iso(value),
            fontSize = 13.sp,
            color = ExtColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Text(
        text = symbol,
        fontSize = 15.sp,
        color = ExtColors.Primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ExtColors.Track)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/** 预览最多显示多少行（含表头）。 */
private const val PREVIEW_MAX_LINES: Int = 12
