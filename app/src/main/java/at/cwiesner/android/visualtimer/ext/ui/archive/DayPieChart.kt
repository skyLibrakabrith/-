package at.cwiesner.android.visualtimer.ext.ui.archive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.minDimension
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import at.cwiesner.android.visualtimer.ext.data.TimerSession
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * 24 小时表盘：**时间轴 + 同心环**。
 *
 * ## 它表达的到底是什么
 *
 * 一整圈 = 一整天，0:00 在正上方、顺时针推进 —— 与首页 `DayClockView` 同一套语汇。
 * 用户在两个页面看到的方向习惯必须一致，否则"3 点在这儿还是那儿"要重新学一遍。
 *
 * 每条计时记录画成**一段圆弧**：起点 = 开始时刻，扫过量 = 持续时长；
 * 到圆心的距离 = 落在第几层环。所以「几点开始」「持续多久」「和其它记录叠不叠」
 * 三个信息在一张图里同时可读，这正是条形列表做不到的。
 *
 * ## 环的分配规则
 *
 * 见 [DayDialGeometry] 类注释：按 `startedAt` 排序后依次 0、1、2…（0 = 最外环），
 * 最多 5 层，超出复用 ring 1。规则**完全确定**（不做碰撞检测、不随机），
 * 同一批数据每次打开都长一个样 —— 归档页是用来"凭位置回忆"的，位置飘了就没用了。
 *
 * ## 为什么相邻弧段之间要留缝
 *
 * 若首尾严丝合缝，就分不出"一条 4 小时的记录"和"三条连续的 1 小时记录"。
 * 所以每段两端各收 [ARC_GAP_DEG]（起点侧也收，只在终点收的话接缝仍然齐平）。
 * 代价是几秒钟的记录会被收成 0°，故扫过量下限为 [MIN_SWEEP_DEG] ——
 * 宁可画得略长，也不能让它整个消失。
 *
 * @param sessions 当天的记录（**未裁剪**；内部按 `[dayStart, dayEnd)` 裁剪）
 * @param dayStart 当天零点（含）
 * @param dayEnd 次日零点（不含）
 * @param onArcTap 点中某段弧时回调对应的记录
 */
@Composable
internal fun DayPieChart(
    sessions: List<TimerSession>,
    dayStart: Long,
    dayEnd: Long,
    onArcTap: (TimerSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 几何在这里一次算好后固定下来。重点是 hits 和绘制用**同一批** DialArc 对象，
    // 保证"看到的那一段"和"点中的那一段"严格是同一个。
    val arcs = remember(sessions, dayStart, dayEnd) { buildDialArcs(sessions, dayStart, dayEnd) }
    val totalMillis = remember(sessions, dayStart, dayEnd) {
        sessions.sumOf { actualMillisInDay(it, dayStart, dayEnd) }
    }

    // pointerInput 的 key 是 Unit（否则每次数据变化都要重建手势，AGENTS.md 铁律 4），
    // 所以闭包里能读到的东西必须是 State —— 用 rememberUpdatedState 包住。
    val arcsState by rememberUpdatedState(arcs)
    val onTapState by rememberUpdatedState(onArcTap)

    // 配色只能在组合期取：ExtColors 是全局可变单例，且切换主题时会整体替换。
    val trackColor = ExtColors.Track
    val outlineColor = ExtColors.OutlineStrong
    val tickMajorColor = ExtColors.TextDim
    val labelColor = ExtColors.TextDim
    val textPrimary = ExtColors.TextPrimary
    val textSecondary = ExtColors.TextSecondary

    val density = LocalDensity.current
    // 1.7.5 没有 autoSize / TextAutoSize（那是 1.8），字号只能按盘面尺寸自己算。
    // 先用盘面宽度记录像素，再换成 sp —— sp 会跟着系统字体缩放走，比写死 px 更合适。
    var dialWidthPx by remember { mutableLongStateOf(0L) }
    val hourLabelSizeSp = if (dialWidthPx > 0L) {
        with(density) { (dialWidthPx.toFloat() * HOUR_LABEL_SIZE_FRACTION).toSp() }
    } else {
        // 首帧还没量到尺寸时给个不会崩的兜底值
        9.sp
    }
    val measureText = rememberTextMeasurer(cacheSize = 8)
    val hourStyle = remember(hourLabelSizeSp, labelColor) {
        TextStyle(color = labelColor, fontSize = hourLabelSizeSp)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { dialWidthPx = it.width.toLong() }
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        // PointerInputScope 的 size 是 IntSize，转成 Size 再用 minDimension
                        val box = this.size.toSize()
                        val hit = hitTestDial(
                            arcs = arcsState,
                            tapX = off.x,
                            tapY = off.y,
                            cx = box.width / 2f,
                            cy = box.height / 2f,
                            radius = box.minDimension / 2f,
                        )
                        if (hit != null) onTapState(hit.arc.session)
                    }
                },
        ) {
            val diameter = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            // 留 6% 给外侧的小时数字
            val radius = diameter / 2f * 0.94f

            // ---- 1. 底层参考圆 ----
            drawCircle(
                color = trackColor,
                radius = radius * DayDialGeometry.OUTER_FRACTION,
                center = center,
                style = Stroke(width = diameter * 0.006f),
            )

            // ---- 2. 24 小时刻度：每 6 小时长刻度（带数字），其余短刻度 ----
            for (h in 0 until 24) {
                val angleRad = Math.toRadians((DayDialGeometry.BASE_ANGLE + h * 15f).toDouble())
                val isMajor = h % 6 == 0
                val isMid = !isMajor && h % 3 == 0
                val outer = radius * (DayDialGeometry.OUTER_FRACTION + 0.05f)
                val len = when {
                    isMajor -> diameter * 0.028f
                    isMid -> diameter * 0.016f
                    else -> diameter * 0.009f
                }
                drawLine(
                    color = if (isMajor || isMid) tickMajorColor else outlineColor,
                    start = Offset(
                        center.x + ((outer - len) * cos(angleRad)).toFloat(),
                        center.y + ((outer - len) * sin(angleRad)).toFloat(),
                    ),
                    end = Offset(
                        center.x + (outer * cos(angleRad)).toFloat(),
                        center.y + (outer * sin(angleRad)).toFloat(),
                    ),
                    strokeWidth = when {
                        isMajor -> diameter * 0.0045f
                        isMid -> diameter * 0.0030f
                        else -> diameter * 0.0015f
                    },
                    cap = StrokeCap.Round,
                )
            }

            // ---- 3. 弧段（最核心的一层） ----
            for (arc in arcs) {
                val bounds = DayDialGeometry.ringBounds(arc.ring)
                val ringWidth = bounds.outer - bounds.inner

                // 同一层有多条时才抖动，否则居中即可（密度不足时抖动会让单条看起来歪）
                val jitter = if (arcs.count { it.ring == arc.ring } > 1) {
                    DayDialGeometry.jitteredRadius(
                        seed = arc.session.id,
                        amplitude = ringWidth * 0.18f,
                    )
                } else {
                    0f
                }
                // 抖动不能把环推过最外边界
                val outerR = (bounds.outer - ringWidth * 0.09f + jitter)
                    .coerceAtMost(DayDialGeometry.OUTER_FRACTION)
                val innerR = outerR - ringWidth * 0.82f
                val midR = (outerR + innerR) / 2f * radius
                val strokeW = ((outerR - innerR) * radius).coerceAtLeast(diameter * 0.004f)

                drawArc(
                    color = ExtColors.trafficStepColor(arc.orderIdx, sessions.size),
                    startAngle = arc.startAngle + ARC_GAP_DEG,
                    sweepAngle = (arc.sweepAngle - ARC_GAP_DEG * 2f).coerceAtLeast(MIN_SWEEP_DEG),
                    useCenter = false,
                    topLeft = Offset(center.x - midR, center.y - midR),
                    size = Size(midR * 2f, midR * 2f),
                    style = Stroke(width = strokeW, cap = StrokeCap.Butt),
                    alpha = arc.alpha,
                )
            }

            // ---- 4. 中心空地：半径收到环带内边界之内，保证弧段不会压在文字上 ----
            val holeR = radius * (DayDialGeometry.INNER_FRACTION - 0.015f)
            drawCircle(color = ExtColors.PureBlack, radius = holeR, center = center)
            drawCircle(
                color = outlineColor,
                radius = holeR,
                center = center,
                style = Stroke(width = diameter * 0.003f),
            )

            // ---- 5. 外侧 0/3/6/…/21 点标注 ----
            // 文字绘制拆到独立函数：它需要 TextMeasurer，而 onDraw 是 restricted scope，
            // 闭包里捕获组合期的局部 val 会报 Unresolved reference（本文件踩过一次）。
            drawHourLabels(
                center = center,
                radius = radius,
                measurer = measureText,
                style = hourStyle,
            )
        }

        // ---- 6. 中心文字（用 Compose Text，能跟随系统字体与无障碍放大） ----
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Text(
                text = describeTotal(totalMillis),
                color = textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = if (sessions.isEmpty()) "无记录" else "${sessions.size} 条",
                color = textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * 画出外侧的 0/3/6/…/21 点标注。
 *
 * 抽成独立函数是因为它需要 [TextMeasurer]：`Canvas` 的 onDraw 是
 * restricted scope，闭包里捕获组合期的局部 `val` 会报 `Unresolved reference`，
 * 只有作为参数传进来的东西才用得到。
 */
private fun DrawScope.drawHourLabels(
    center: Offset,
    radius: Float,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val labelR = radius * HOUR_LABEL_RADIUS_FRACTION
    for (h in 0 until 24 step 3) {
        val angleRad = Math.toRadians((DayDialGeometry.BASE_ANGLE + h * 15f).toDouble())
        val lx = center.x + (labelR * cos(angleRad)).toFloat()
        val ly = center.y + (labelR * sin(angleRad)).toFloat()
        // TextMeasurer 没有 operator invoke，必须显式调 measure（踩过：写成 measurer(...) 报
        // "Unresolved reference 'measurer'"）
        val layout = measurer.measure(text = if (h < 10) "0$h" else "$h", style = style)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                lx - layout.size.width / 2f,
                ly - layout.size.height / 2f,
            ),
        )
    }
}

/** 相邻弧段之间留的缝（度）。0.6° ≈ 表盘上 2.4 分钟，细到看不出来但足以断开。 */
private const val ARC_GAP_DEG: Float = 0.6f

/** 极短记录的显示下限（度）：约 1 分钟宽，保证点得中、看得见。 */
private const val MIN_SWEEP_DEG: Float = 0.25f

/** 小时数字字号占盘面宽度的比例。 */
private const val HOUR_LABEL_SIZE_FRACTION: Float = 0.032f

/** 小时数字所在的半径（相对盘面半径）：略收进 6% 留白之内，避免被裁掉。 */
private const val HOUR_LABEL_RADIUS_FRACTION: Float = 0.94f
