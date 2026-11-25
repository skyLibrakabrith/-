package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 面板里的序列卡片。
 *
 * 与计时器 / 组**同尺寸**占一个格子（`aspectRatio(0.92f)`，别改成 1f：
 * DraggableGrid 的格高是按这个比例算的，卡片变方会撑高整行，拖拽的几何命中就跟着歪）。
 *
 * 盘面是一个**环形小表盘**，三种信息各占一层，互不抢位：
 *
 * | 位置 | 内容 | 颜色 |
 * |---|---|---|
 * | 外圈细环 | 已完成阶段数 / 总阶段数 | 主色（等待确认时转红） |
 * | 内圈粗环 | 当前阶段内已走 / 当前阶段时长 | 主色；已完成阶段淡色；未开始暗色 |
 * | 环上小圆点 | 每个阶段一格刻度，已完成实心、未开始空心 | 同环 |
 * | 中心 | 当前阶段剩余时间（单行、按直径缩放字号） | 文字色 |
 * | 底部 | 阶段进度圆点 + 名称 + 状态行 | — |
 *
 * **为什么不直接用「每阶段按时长切一段弧」**（旧的实现，也是用户反馈的「显示问题」）：
 * 1. 段长按时长占比算，不等于阶段进度。序列里各阶段时长差一倍很常见，
 *    用户看到的却是「第一段很短、第二段很长」，跟「走到第几步」对不上；
 * 2. 阶段的颜色边界不固定在 12 点方向，**同一个阶段随时间会漂**，
 *    肉眼无法判断「当前在哪一步」；
 * 3. 起始角与量角器错位一节，从 12 点开始数会数错一段；
 * 4. 每段用 `min(1f, 段弧长 − 2f)` 兜底：阶段数一多每段只有零点几度，
 *    全被拉成 1° 逐段累加 —— 累加值远超 360°，末尾越画越多，弧也越叠越亮；
 * 5. 阶段内进度弧（Round cap）画在已被整段涂亮的当前段上，
 *    4° 的段帽就有 2.4°，进度小的时候完全被吃掉，进度看起来「不走」；
 * 6. 中心 `formatRemaining` 可能输出 `1h00m`（**格式串里漏了 `%d` 的 h**），
 *    固定 13sp、不设限宽、不设 maxLines，在 3/4 列时直接溢出圆盘。
 *
 * 因此这里把「走一步」这件事放在**等分**的外圈上（一格一步，与时长无关），
 * 把「这一步还剩多少」放在内圈上（这一层本来就是连续量，按时长比例是对的）。
 */
@Composable
fun SequenceCell(
    sequence: StepSequence,
    steps: List<Step>,
    phase: StepSequenceEngine.Phase,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val model = buildSequenceDialModel(sequence, steps, phase, isActive)

    Card(
        // 点按交给 DraggableGrid 的卡片层，这里不再叠加一层，
        // 否则单击会被内层先消费，双击永远走不到外层。
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 盘面外层的这个 Box 只用来居中 + 撑满剩余高度；
            // 真正的直径在 SequenceDialView 里的 BoxWithConstraints 里量。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                SequenceDialView(
                    model = model,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 阶段刻度：最多画 MAX_DOTS 个点。20 格的序列一列 10 个小点已经看不清，
            // 再多就退化成一条线 —— 不如给个「1/20」的数字，所以超过就显示数字。
            if (model.totalSteps > 0) {
                StepDotsBar(
                    model = model,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                )
            }

            // 名称与状态行用 Row 并排：卡片很矮（竖屏 3 列时高度约 110dp），
            // 两行堆叠会把盘面挤得很小，并排两行只占一行的高度。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sequence.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = ExtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = model.statusText,
                    // 状态文字是次要信息，固定小号；长文本交给 ellipsis，
                    // 绝不 softWrap —— 换行会把整张卡片撑高。
                    fontSize = 9.sp,
                    color = model.statusColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .widthIn(max = 96.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 状态模型
// ---------------------------------------------------------------------------

/**
 * 卡片需要的全部展示状态。
 *
 * 抽出来是为了让 Composable 里只做布局：所有「哪个阶段算完成、中心该显示什么」
 * 的判断集中在一处，改一处即可，不用在几处 `when(phase)` 里反复对齐。
 *
 * 标 `@Immutable`：全部是 val 的基本类型 / String / Color，Compose 可以跳过重组。
 *
 * 注意 `accent` / `statusColor` 直接写 `Color`，**不要**在这里写全限定名
 * （`androidx.compose.ui.graphics.Color`）：文件头已经 import 了 `Color`，
 * 统一用它，少一处可能漏 import / 解析歧义的机会。
 */
@Immutable
private data class SequenceDialModel(
    val totalSteps: Int,
    /** 当前处于第几步（0 起）；-1 = 还没开始。等待确认时是**刚做完的那一步**。 */
    val currentIndex: Int,
    /** 外圈：已完成阶段数 / 总阶段数。 */
    val stepFraction: Float,
    /** 内圈：当前阶段已走 / 当前阶段时长。0 = 满格（刚开始），1 = 走完。 */
    val stepFractionDone: Float,
    /** 中心文字：这一步还剩多少（单行）。 */
    val centerText: String,
    /** 环的强调色：运行主色 / 等待确认红 / 空闲灰。 */
    val accent: Color,
    /** 底部状态行（「第 2/5 阶段 · 已暂停」之类）。 */
    val statusText: String,
    val statusColor: Color,
    /** 等待用户确认：中心文字换强调色，提示「要你点一下」。 */
    val awaiting: Boolean,
    /** 暂停：中心文字转灰，提示「时间没在走」。 */
    val paused: Boolean,
)

/**
 * 把引擎状态翻译成盘面状态。
 *
 * **中心显示什么**（这里刻意让步于「一眼能读」而不是「信息最全」）：
 * - Running：本阶段剩余（`formatRemaining`）；
 * - AwaitingConfirm：`+已拖时长` —— 与全屏序列视图的读数保持一致，
 *   用户放大进全屏再退回来不会看到两个不同的数字；
 * - Finished / Idle：不显示 0，而是显示状态词（完成 / 总时长），
 *   显示 "0s" 会被误读成「刚开始，还剩 0 秒」。
 *
 * 阶段数与 `steps` 不一致时（正常不会，但换面板的瞬间可能有一次旧值），
 * 一律以 `steps.size` 为准并把下标夹到范围内，避免画到数组外面。
 */
@Composable
private fun buildSequenceDialModel(
    sequence: StepSequence,
    steps: List<Step>,
    phase: StepSequenceEngine.Phase,
    isActive: Boolean,
): SequenceDialModel {
    val total = steps.size
    // 引擎传来的下标可能越界（例如刚切换序列、steps 还没跟上），先夹进 [0, total)。
    val rawIndex = when (phase) {
        is StepSequenceEngine.Phase.Running -> phase.stepIndex
        is StepSequenceEngine.Phase.AwaitingConfirm -> phase.finishedStepIndex
        else -> -1
    }
    val currentIndex = if (total == 0) -1 else rawIndex.coerceIn(0, total - 1)
    val running = phase as? StepSequenceEngine.Phase.Running
    val awaiting = phase as? StepSequenceEngine.Phase.AwaitingConfirm

    val accent = when {
        total == 0 -> ExtColors.TextDim
        awaiting != null -> ExtColors.Red
        running != null -> ExtColors.Primary
        else -> ExtColors.PrimaryDim
    }

    // 外圈进度整段量 = 已完成的步数。
    // 「等待确认」时那一步已经做完，所以按 currentIndex + 1 算 ——
    // 否则时间已经走完、盘面却还差一格，用户会以为没跑完。
    val stepFraction = when {
        total == 0 -> 0f
        // 已跑完整个序列（全部做完，只等最后一次点击）：满格
        awaiting != null -> (currentIndex + 1).toFloat() / total
        currentIndex < 0 -> 0f
        else -> currentIndex.toFloat() / total
    }

    // 内圈：本阶段已经走了多少。
    // 引擎的 `progress` 是「剩余比例」（满格=刚开始），和用户对「进度」的直觉相反，
    // 所以在这里取反：0 = 刚开始，1 = 走完，外圈和内圈方向一致。
    val stepFractionDone = when (val p = running) {
        null -> if (awaiting != null) 1f else 0f
        else -> (1f - p.progress).coerceIn(0f, 1f)
    }

    val centerText = when (phase) {
        is StepSequenceEngine.Phase.Running ->
            DeadlineCalculator.formatRemaining(phase.remainingMillis)
        is StepSequenceEngine.Phase.AwaitingConfirm ->
            "+" + DeadlineCalculator.formatRemaining(phase.elapsedMillis)
        is StepSequenceEngine.Phase.Finished -> "完成"
        // 空闲：显示整个序列的总时长，卡片静止时也有信息量
        else -> DeadlineCalculator.formatRemaining(steps.sumOf { it.durationMillis })
    }

    // 状态行文案。首页/面板要能回答「现在在第几步」—— 这是重新开始/停止的前提。
    val statusText = when {
        total == 0 -> "还没有阶段"
        awaiting != null -> {
            val base = "${currentIndex + 1}/$total 待确认"
            if (awaiting.overdue) "$base · 超时" else base
        }
        running != null -> {
            // 暂停标志挂在 Running 上；引擎在暂停时仍发 Running(paused = true)，
            // 所以这里必须显式区分，否则卡片看起来和运行中一模一样。
            val suffix = if (running.paused) " · 已暂停" else ""
            "第 ${currentIndex + 1}/$total 阶段$suffix"
        }
        isActive -> "已完成"
        else -> "$total 阶段"
    }
    val statusColor = when {
        total == 0 -> ExtColors.TextDim
        awaiting != null -> ExtColors.Red
        running != null && running.paused -> ExtColors.TextSecondary
        isActive -> accent
        else -> ExtColors.TextDim
    }

    return SequenceDialModel(
        totalSteps = total,
        currentIndex = currentIndex,
        stepFraction = stepFraction,
        stepFractionDone = stepFractionDone,
        centerText = centerText,
        accent = accent,
        statusText = statusText,
        statusColor = statusColor,
        awaiting = awaiting != null,
        paused = running?.paused == true,
    )
}

// ---------------------------------------------------------------------------
// 盘面
// ---------------------------------------------------------------------------

/**
 * 环形小表盘。
 *
 * 用 [BoxWithConstraints] 而不是直接在 Canvas 的 DrawScope 里算字号：
 * 字号要在 Compose 层（`.sp`）拿到，而 DrawScope 里的 `size` 只在绘制阶段存在，
 * 外面拿不到（WheelTimerView 早先就是在这里写崩的：CI 报 Unresolved reference）。
 * 这里的外层 Box 是横宽竖窄的矩形，所以直径必须取 `min(maxWidth, maxHeight)`，
 * 再用 `Modifier.size(dialSize)` 收成正圆 —— 不能像 WheelTimerView 那样直接拿 maxWidth，
 * 因为那边外面已经用 asSquareInside() 收成正方形了，这边没有。
 */
@Composable
private fun SequenceDialView(
    model: SequenceDialModel,
    modifier: Modifier = Modifier,
) {
    if (model.totalSteps == 0) {
        EmptyDial(modifier)
        return
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dialSize = min(maxWidth.value, maxHeight.value).dp
        // 字号跟着**直径**走，而不是跟着列数/写死 —— 2 列时卡片宽、字大；
        // 3/4 列时卡片变窄，字自动变小，不会溢出圆盘。
        // 注意不能用 autoSize / TextAutoSize：那是 compose-ui 1.8 的 API，
        // 本项目 BOM 2024.12.01 对应 1.7.5，写了直接编译失败。
        // 系数 0.30 的来源：中心可写宽度 ≈ 0.62 × 直径；
        // `1h00m` 这种 5 字符串宽约 2.4em ⇒ 字号上限 ≈ 直径 / 4。
        val centerFont = (dialSize.value * 0.30f).coerceIn(8f, 15f)

        val ringColor = ExtColors.Track
        // 已完成阶段：淡色。用 TextDim 再加 alpha，在纯黑/深灰/浅色三档主题里
        // 都比 Track 亮或暗一档，能分出「走过」和「没走」。
        val doneColor = ExtColors.TextDim
        // 当前阶段底色（内圈底层）：主色压暗，和高亮的进度弧区分开
        val currentBaseColor = model.accent.copy(alpha = 0.22f)
        val stepCoveredFrac = ceil(model.stepFraction * model.totalSteps)
            .coerceIn(0f, model.totalSteps.toFloat())

        Box(
            modifier = Modifier
                .size(dialSize)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.minDimension <= 0f) return@Canvas
                val diameter = size.minDimension
                val center = Offset(size.width / 2f, size.height / 2f)
                // 所有半径都以**内接正方形（直径）**为基准，圆环、刻度点、
                // 中心占位都共用同一个圆心；早期各层各用一套系数，看起来就是错位的。
                val outerR = diameter * 0.41f
                val innerR = diameter * 0.335f
                val outerStroke = diameter * 0.045f
                val innerStroke = diameter * 0.075f
                val dotR = diameter * 0.020f
                val dotOrbit = diameter * 0.465f

                // ---- 0. 底槽 ----
                // 内圈先铺满一整圈暗槽：它是「这一步的完整时长」，
                // 进度弧再盖上去，长度就是「已走」。这样无论进度多少，盘的轮廓始终完整。
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - innerR, center.y - innerR),
                    size = Size(innerR * 2f, innerR * 2f),
                    style = Stroke(width = innerStroke, cap = StrokeCap.Butt),
                )

                // 内圈底层：当前阶段在「整条序列」里的份额。
                // 用「一格 = 360/total 度」而不是按时长，保证每一格等宽 ——
                // 等宽才能一眼看出「走到第几步」，这是外圈进度的含义。
                // （当前阶段内这条底层弧还兼当进度弧的底色，所以画在当前格的位置上。）
                val segStart = -90f + 360f * model.currentIndex / model.totalSteps
                val segSweep = 360f / model.totalSteps
                drawArc(
                    color = currentBaseColor,
                    startAngle = segStart,
                    sweepAngle = (segSweep - 1.5f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = Offset(center.x - innerR, center.y - innerR),
                    size = Size(innerR * 2f, innerR * 2f),
                    style = Stroke(width = innerStroke, cap = StrokeCap.Butt),
                )

                // 内圈进度弧：本阶段已走比例，画在整个内圈上（从 12 点起），
                // 而不是只画在「当前格」那一小段里 —— 小表盘上 1/5 的一格弧长很短，
                // 里面的子弧几乎看不出来；画满一圈才看得出「时间在走」。
                // 用 Round cap 但起点从 0° 量，短进度也不会被帽吃掉。
                if (model.stepFractionDone > 0f) {
                    drawArc(
                        color = model.accent,
                        startAngle = -90f,
                        sweepAngle = 360f * model.stepFractionDone,
                        useCenter = false,
                        topLeft = Offset(center.x - innerR, center.y - innerR),
                        size = Size(innerR * 2f, innerR * 2f),
                        style = Stroke(width = innerStroke, cap = StrokeCap.Round),
                    )
                }

                // ---- 1. 外圈：走过多少步 ----
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - outerR, center.y - outerR),
                    size = Size(outerR * 2f, outerR * 2f),
                    style = Stroke(width = outerStroke, cap = StrokeCap.Butt),
                )
                if (model.stepFraction > 0f) {
                    drawArc(
                        color = model.accent,
                        startAngle = -90f,
                        sweepAngle = 360f * model.stepFraction,
                        useCenter = false,
                        topLeft = Offset(center.x - outerR, center.y - outerR),
                        size = Size(outerR * 2f, outerR * 2f),
                        style = Stroke(width = outerStroke, cap = StrokeCap.Round),
                    )
                }

                // ---- 2. 阶段刻度点 ----
                // 每步一格。已走过 = 实心淡色，当前 = 实心强调色且更大，
                // 未开始 = 描边空心。空心点用 track 色，和底槽同色系，不抢视线。
                for (i in 0 until model.totalSteps) {
                    val angleDeg = -90f + 360f * i / model.totalSteps
                    val rad = angleDeg * PI.toFloat() / 180f
                    val pos = Offset(
                        center.x + dotOrbit * cos(rad),
                        center.y + dotOrbit * sin(rad),
                    )
                    when {
                        i == model.currentIndex -> drawCircle(
                            color = model.accent,
                            // 当前那格画大一点：鼠标/手指去找「我在哪一步」时，
                            // 亮度和大小两个线索同时给，比只改颜色可靠。
                            radius = dotR * 1.35f,
                            center = pos,
                        )
                        i < stepCoveredFrac && i < model.currentIndex -> drawCircle(
                            color = doneColor,
                            radius = dotR,
                            center = pos,
                        )
                        else -> drawCircle(
                            color = ringColor,
                            radius = dotR,
                            center = pos,
                            style = Stroke(width = diameter * 0.012f),
                        )
                    }
                }
            }

            // ---- 3. 中心时间 ----
            // 三重保险保证放得进圆盘（本版 Compose 没有 autoSize，只能这样算）：
            // ① 字号按直径缩放（上面 centerFont）；
            // ② 单行 + 不折行；
            // ③ 显式限宽到内圈内径的 82%（内圈直径 = 0.67 × 直径）。
            // 少了 ③，遇到长串（`+1h00m`）仍会横向溢出被圆盘外的卡片裁掉。
            Text(
                text = model.centerText,
                fontSize = centerFont.sp,
                fontWeight = if (model.awaiting) FontWeight.Medium else FontWeight.Light,
                color = when {
                    model.awaiting -> ExtColors.Red
                    model.paused -> ExtColors.TextSecondary
                    else -> ExtColors.TextPrimary
                },
                maxLines = 1,
                softWrap = false,
                // 数字等宽：倒计时逐秒变化时文字不会左右抖动。
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = (dialSize * 0.56f).coerceAtLeast(8.dp))
                    .padding(horizontal = 1.dp),
            )
        }
    }
}

/** 空状态：序列一个阶段都没有。 */
@Composable
private fun EmptyDial(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dialSize = min(maxWidth.value, maxHeight.value).dp
        Box(
            modifier = Modifier
                .size(dialSize)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.minDimension <= 0f) return@Canvas
                val diameter = size.minDimension
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = diameter * 0.38f
                // 虚化的单圈 + 问号：明确表示「这里本来该有个盘，但没有内容」，
                // 比画一个完整空盘更好 —— 空盘会被误读成「进度 0% 正在等开始」。
                drawArc(
                    color = ExtColors.Track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = diameter * 0.06f, cap = StrokeCap.Round),
                )
            }
            Text(
                text = "?",
                fontSize = (dialSize.value * 0.34f).coerceIn(9f, 18f).sp,
                color = ExtColors.TextDim,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 底部阶段点
// ---------------------------------------------------------------------------

/** 底部最多画几个点；超过就改用「3/20」的数字，点太小反而没信息。 */
private const val MAX_DOTS = 10

/**
 * 阶段进度条：一排小点，或退化成数字。
 *
 * 为什么还要单独做一排点：小表盘外圈的刻度点直径只有 2–3dp，
 * 阶段一多（5 个以上）在卡片尺寸下就只剩「一圈虚线」。底部这排点保证
 * 「到第几步」在任何列数下都读得出来，且它占的高度固定（一行），
 * 不会把盘面挤压成不同大小。
 */
@Composable
private fun StepDotsBar(
    model: SequenceDialModel,
    modifier: Modifier = Modifier,
) {
    if (model.totalSteps > MAX_DOTS) {
        // 超过 MAX_DOTS 时改用数字。注意这里**不再重复"第几阶段"**：
        // 中心已经用「剩余时间」回答「还剩多久」，底部再报一遍步数会挤占名称，
        // 而且 20+ 格的序列外圈的点已经密到读不出来，这行只回答「总共有几步」。
        Text(
            text = "${model.totalSteps} 阶段",
            fontSize = 9.sp,
            color = ExtColors.TextDim,
            maxLines = 1,
            softWrap = false,
            modifier = modifier,
        )
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until model.totalSteps) {
            val color = when {
                i == model.currentIndex -> model.accent
                i < model.currentIndex -> ExtColors.TextDim
                else -> ExtColors.Track
            }
            Box(
                modifier = Modifier
                    .size(if (i == model.currentIndex) 5.dp else 4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
