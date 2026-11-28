package at.cwiesner.android.visualtimer.ext.ui.archive

import at.cwiesner.android.visualtimer.ext.data.TimerSession
import kotlin.math.hypot

/**
 * 24 小时表盘的纯几何。
 *
 * **为什么把几何从 Composable 里抽出来**：这里面全是"算错了界面就悄悄歪掉"的
 * 角度/半径换算，没法靠肉眼在真机上逐个验证。抽成纯函数/纯数据类，
 * 就能用单元测试把手算结果钉死（见 `DayPieGeometryTest`），
 * Composable 只剩「把结果画出来」。
 *
 * ## 坐标系
 *
 * Compose `drawArc` 的 `startAngle` 以 **3 点钟方向为 0°、顺时针为正**。
 * 表盘要求 **0:00 在正上方**，于是基准角是 **-90°**：
 *
 * ```
 * startAngle = -90 + (当天已过秒数 / 当天总时长) * 360
 * sweepAngle = (时长 / 当天总时长) * 360
 * ```
 *
 * 注意分母用的是 [ArchiveDates.daySpanMillis]（当天真实长度）而不是固定 24h：
 * 夏令时切换日那天只有 23h 或 25h，用 24h 当分母会让 03:00 之类的时刻和刻度对不上。
 *
 * ## 为什么不做「碰撞检测」式的同心环分配
 *
 * 成熟图表库对时间轴用「区间图着色」（区间图是完美图，贪心就能求出最少层数），
 * 但结果**不稳定**：同一批数据只要多出一条记录，所有弧段的层号可能整体平移，
 * 用户昨天看到的「蓝的是写周报」今天就变了色。归档页是让人**凭位置回忆**的界面，
 * 位置必须可预测。所以这里用一条更笨但确定的规则：
 *
 * - 按 `startedAt` 升序，依次分配到 0、1、2…（0 = 最外环）
 * - `step = 0.8 / min(count, 5)`，最内保留 20% 半径放中心文字
 * - 超过 5 条时从 ring 1 开始复用（不回到最外环：最外环最显眼，
 *   让前 5 条各占一层，后面的叠在里面）
 *
 * 复用层会用递减的 alpha + [TimerSession.id] 作为「种子」做**确定性的**抖动
 * （见 [jitteredRadius]），让同一层里的多条记录不会完全盖住彼此 ——
 * 用 id 当种子是为了让抖动结果在一帧一帧重绘之间保持不变，
 * 否则每次重组都会看到弧段微微跳动。
 */
internal object DayDialGeometry {

    /** 基准角：0:00 指向正上方。Compose 的 0° 在 3 点钟方向，-90° 即 12 点钟方向。 */
    const val BASE_ANGLE: Float = -90f

    /** 一圈的角度。 */
    const val FULL_SWEEP: Float = 360f

    /** 可用半径里分给"环带"的外边界。内侧从 [INNER_FRACTION] 起算，中间留给中心文字。 */
    const val OUTER_FRACTION: Float = 0.8f

    /**
     * 环带的**内**边界（半径比例）。
     *
     * 中心要放两行字（"5 小时 23 分" + "12 条"），20% 半径装不下 ——
     * 这版 Compose（1.7.5）没有 autoSize，只能靠几何留够空间。
     * 所以环带实际只占 `[0.25, 0.80]`，即半径的 55%，中心空出 25%。
     */
    const val INNER_FRACTION: Float = 0.25f

    /** 最多用几层环（再多的记录就复用内层，靠 alpha/抖动区分）。 */
    const val MAX_RINGS: Int = 5

    /**
     * 一层环的宽度（半径比例）。
     *
     * `count = 1` 时也只占 1/5 的环宽（而不是画满整条环带）：
     * 单条记录如果画成一整圈粗环，看上去像一个"填满的饼"，
     * 用户会误以为选中了什么。留细一点更像"时间轴上的一个刻度"。
     */
    val ringStepFraction: Float = (OUTER_FRACTION - INNER_FRACTION) / MAX_RINGS.toFloat()

    /**
     * 第 [ring] 层的内外半径（相对画布半径的 0..1 比例）。ring 0 = **最外**环。
     *
     * 从外往里铺：`outer = 0.8 - ring * step`，相邻层严丝合缝（本层内 = 下一层外）。
     * 用从外往里而不是从里往外，是因为单条记录时层号恒为 0 ——
     * 它应该落在**最外面**那条细环上（贴着刻度数字），而不是中心空地的边上。
     */
    fun ringBounds(ring: Int): RadialBounds {
        val r = ring.coerceIn(0, MAX_RINGS - 1)
        val k = ringStepFraction
        val outer = OUTER_FRACTION - r * k
        return RadialBounds(inner = outer - k, outer = outer)
    }

    /**
     * 记录 → 层号。**纯函数、只看下标和条数**，保证同一批数据永远得到同一组层位。
     *
     * @param orderIdx 按 startedAt 升序后的下标
     * @param count 当天条数
     */
    fun ringOf(orderIdx: Int, count: Int): Int = when {
        count <= MAX_RINGS -> orderIdx.coerceIn(0, MAX_RINGS - 1)
        // 超过 5 条：只有前 5 条留在各自的外层，其余全部压进 ring 1。
        // 不循环用 0..4 的原因见类注释：位置必须可预测，且最外环要留给"最早开始的"。
        orderIdx < MAX_RINGS -> orderIdx
        else -> 1
    }

    /**
     * 同一层里有多条记录时的透明度。
     *
     * 单层放一条 → 不透明；复用层里按"这一层再往里第几个"递减，
     * 最底一档 0.45 仍能看出颜色（再淡就和 [at.cwiesner.android.visualtimer.ext.ui.ExtColors.Track]
     * 分不出来了）。
     */
    fun alphaOf(orderIdx: Int, count: Int): Float {
        if (count <= MAX_RINGS) return 1f
        if (orderIdx < MAX_RINGS) return 1f
        val overflowIdx = (orderIdx - MAX_RINGS) % MAX_RINGS
        // 依次 0.9 / 0.75 / 0.6 / 0.45 循环再叠回去时取更淡的 0.35
        return (0.90f - overflowIdx * 0.15f).coerceAtLeast(0.35f)
    }

    /** 层宽（半径比例），用于抖动幅度。 */
    fun ringWidthFraction(): Float = ringStepFraction

    /**
     * 复用层里的确定性抖动：把弧段在环内上下挪一点点。
     *
     * @param seed 用 `TimerSession.id`（稳定、不随重组变）
     * @param amplitude 抖动幅度（半径 px）
     * @return -amplitude..amplitude 之间的偏移
     */
    fun jitteredRadius(seed: Long, amplitude: Float): Float {
        if (amplitude <= 0f) return 0f
        // 取 id 的低位散列到 [-1, 1]：乘个大质数再取小数部分，
        // 相邻 id 就不会挤在同一个偏移上（弧形排列时看得出彼此错开）。
        val h = ((seed * 2654435761L) ushr 11).and(0xFFFFL).toFloat()
        val unit = h / 65535f * 2f - 1f
        return unit * amplitude
    }

    /**
     * 时刻 → 表盘角度（度）。
     *
     * @return `0:00 -> -90`，`6:00 -> 0`，`12:00 -> 90`，`18:00 -> 180`
     */
    fun angleOf(millisIntoDay: Long, daySpanMillis: Long): Float {
        val span = daySpanMillis.coerceAtLeast(1L)
        val frac = millisIntoDay.toFloat() / span.toFloat()
        return BASE_ANGLE + frac * FULL_SWEEP
    }

    /** 表盘角度（度）→ 当天已过的比例，归一化到 `[0, 1)`。 */
    fun fractionOfAngle(angleDeg: Float): Float {
        val f = ((angleDeg + 90f) % FULL_SWEEP + FULL_SWEEP) % FULL_SWEEP / FULL_SWEEP
        return f
    }
}

/** 一条记录在表盘上的弧段。 */
internal data class DialArc(
    /** 对应记录（`null` 只出现在内部计算临时结果里，列表里不会有）。 */
    val session: TimerSession,
    /** 在按 startedAt 排序后的下标，用于取色/取层。 */
    val orderIdx: Int,
    /** 层号，0 = 最外环。 */
    val ring: Int,
    /** 透明度（同一层复用时的递减）。 */
    val alpha: Float,
    /** 起止角（度，Compose drawArc 约定）。 */
    val startAngle: Float,
    val sweepAngle: Float,
)

/** 环带的半径区间（相对画布半径的比例）。 */
internal data class RadialBounds(
    val inner: Float,
    val outer: Float,
)

/**
 * 把当天窗口内的记录切成**表盘弧段**。
 *
 * 跨午夜的记录会切成两段（今天的尾巴 + 明天开头那一点不该出现在今天的盘上，
 * 所以这里只做**裁剪**：两端都钳进 [dayStart, dayEnd)）。
 *
 * 返回结果按 `orderIdx` 升序，**与输入顺序无关** —— 输入顺序换了也不会影响层位。
 */
internal fun buildDialArcs(
    sessions: List<TimerSession>,
    dayStart: Long,
    dayEnd: Long,
    /**
     * 每条会话的暂停区间（sessionId → `[from, to)` 列表）。
     *
     * 用户要求「圆盘上把暂停的那段空出来」。做法是把一条记录的弧按暂停区间
     * **切成多段连续的子弧**，中间留空 —— 环位（`ring`）仍然按记录算，
     * 所以同一次计时切开的几段看起来还是同一圈。
     */
    pausesBySession: Map<Long, List<Pair<Long, Long>>> = emptyMap(),
): List<DialArc> {
    val span = (dayEnd - dayStart).coerceAtLeast(1L)
    val sorted = sessions.sortedWith(compareBy({ it.startedAt }, { it.id }))
    val count = sorted.size
    val out = ArrayList<DialArc>(count)
    sorted.forEachIndexed { idx, s ->
        // 两端都钳到当天窗口：跨午夜的记录只画露在这一天的部分，
        // 没露的（比如昨天 23:00 到昨天 23:30）连一段都不该画。
        val from = s.startedAt.coerceIn(dayStart, dayEnd)
        val to = s.endedAt.coerceIn(dayStart, dayEnd)
        if (to <= from) return@forEachIndexed
        val ring = DayDialGeometry.ringOf(idx, count)
        val alpha = DayDialGeometry.alphaOf(idx, count)

        // 没有暂停信息：保持原样，一条记录一段弧
        val pauses = pausesBySession[s.id].orEmpty()
        if (pauses.isEmpty()) {
            out += DialArc(
                session = s,
                orderIdx = idx,
                ring = ring,
                alpha = alpha,
                startAngle = DayDialGeometry.angleOf(from - dayStart, span),
                sweepAngle = (to - from).toFloat() / span.toFloat() * DayDialGeometry.FULL_SWEEP,
            )
            return@forEachIndexed
        }

        // 有暂停：把 [from, to) 减去暂停区间，得到若干"真正在计时"的片段
        var cursor = from
        val pieces = mutableListOf<Pair<Long, Long>>()
        for ((pf, pt) in pauses.sortedBy { it.first }) {
            val a = pf.coerceIn(from, to)
            // to = 0（仍在暂停中）视为一直停到会话结束
            val b = (if (pt <= 0L) to else pt).coerceIn(from, to)
            if (b <= a) continue
            if (a > cursor) pieces += cursor to a
            if (b > cursor) cursor = b
        }
        if (cursor < to) pieces += cursor to to
        if (pieces.isEmpty()) return@forEachIndexed

        pieces.forEach { (a, b) ->
            out += DialArc(
                session = s,
                orderIdx = idx,
                ring = ring,
                alpha = alpha,
                startAngle = DayDialGeometry.angleOf(a - dayStart, span),
                sweepAngle = (b - a).toFloat() / span.toFloat() * DayDialGeometry.FULL_SWEEP,
            )
        }
    }
    return out
}

/** 命中测试结果。 */
internal data class DialHit(
    val arc: DialArc,
    /** 点击点到圆心的距离（pane 的 px），便于排查误触。 */
    val radiusPx: Float,
    /** 点击对应的当天已过比例 0..1。 */
    val dayFraction: Float,
)

/**
 * 极坐标命中测试。
 *
 * 思路与需求里给的一致：
 * ```
 * r     = hypot(dx, dy)                    // 落在哪一层
 * angle = atan2(dx, -dy) 归一化到 [0, 360)  // 对应哪一刻
 * ```
 * `atan2(dx, -dy)` 的好处是它直接把「正上方 = 0、顺时针为正」的坐标系返回给你，
 * 不用再减 90°。
 *
 * 判定顺序：**先角度后半径**。原因是同一天里不同层可以有重叠的角度区间
 * （尤其是复用层），但角度命中是"唯一"的 —— 先锁定量程，再去比半径，
 * 就不会出现"点在一个 0.3 秒宽的弧缝上却命中了隔壁层"的怪现象。
 *
 * 容差：
 * - 角度 ±[ANGLE_TOLERANCE_DEG]：太细的弧（几秒钟的记录）手指根本点不中
 * - 半径 [RING_TOLERANCE_FRACTION]：允许点在两层交界的细缝上
 *
 * 多层同时命中时取**层号最小（最外）**的那个 —— 最外环永远最显眼，
 * 用户下意识点的就是看到的那个。
 *
 * @param cx 圆心 x（px）
 * @param cy 圆心 y（px）
 * @param radius 表盘满半径（px，对应半径比例 1.0）
 * @return 命中的弧段；没点中任何弧段返回 null（调用方据此不弹详情）
 */
internal fun hitTestDial(
    arcs: List<DialArc>,
    tapX: Float,
    tapY: Float,
    cx: Float,
    cy: Float,
    radius: Float,
): DialHit? {
    if (radius <= 0f) return null
    val dx = tapX - cx
    val dy = tapY - cy
    val r = hypot(dx, dy)
    if (r > radius) return null

    // 角度换算。**必须和 [DayDialGeometry.angleOf] 用同一套基准**，否则点击命中会整体错位。
    //
    // `atan2(dx, -dy)` 给的是「以**正上方**为 0°、顺时针」的角度；
    // 而 [DayDialGeometry.angleOf] 产出的是 **drawArc 角度** ——
    // drawArc 的 0° 在**3 点钟方向**（顺时针），0:00 要落在正上方就得 `-90`。
    //
    // 所以同一点上，atan2 比 angleOf **大 90°**，这里减回去。
    //
    // 这个 bug 之前真实存在过：命中测试算出的角度比弧段实际角度大 90°，
    // 于是点 9:30（52.5°）的弧段被当成 142.5°（14:30 方向），命中失败或命中错的记录。
    // 单测 `点9点方向的弧段能命中` / `命中半径落在盘边缘也能算出来` 就是被它弄挂的。
    var angleDeg = Math.toDegrees(kotlin.math.atan2(dx, -dy).toDouble()).toFloat() - 90f
    angleDeg = (angleDeg % 360f + 360f) % 360f
    // 当天已过的比例。用 fractionOfAngle 而不是 angleDeg/360 ——
    // 后者把「drawArc 角度」直接当比例，会差 90°（即 6 小时）。
    val fraction = DayDialGeometry.fractionOfAngle(angleDeg)

    val rFrac = r / radius
    val tol = RING_TOLERANCE_FRACTION

    var best: DialHit? = null
    for (arc in arcs) {
        if (!angleWithin(angleDeg, arc.startAngle, arc.sweepAngle)) continue
        val b = DayDialGeometry.ringBounds(arc.ring)
        // 环带宽度不足时容差会大于带宽，coerce 保证容差不把外侧的弧段也吞进来
        if (rFrac < b.inner - tol || rFrac > b.outer + tol) continue
        if (best == null || arc.ring < best.arc.ring) {
            best = DialHit(arc = arc, radiusPx = r, dayFraction = fraction)
        }
    }
    if (best != null) return best

    // 角度/半径都没命中任何弧段时返回 null（不"就近吸附"到别的记录上：
    // 弹出一条用户没点的记录，比什么都不弹更让人困惑）
    return null
}

/** 点击角度是否落在 `[startAngle, startAngle + sweepAngle]` 内（含容差）。 */
private fun angleWithin(tapDeg: Float, startDeg: Float, sweepDeg: Float): Boolean {
    if (sweepDeg <= 0f) return false
    // 转成"相对弧起点的偏移"，再归一化到 [0, 360)
    val rel = ((tapDeg - startDeg) % 360f + 360f) % 360f
    // 一整圈（>= 359.99）的弧段没有"缝"，任何角度都命中
    if (sweepDeg >= 359.99f) return true
    val tol = ANGLE_TOLERANCE_DEG.coerceAtMost(sweepDeg * 0.5f + 0.5f)
    return rel <= sweepDeg + tol || rel >= 360f - tol
}

/** 角度容差（度）：约等于表盘上 2 分钟的宽度。 */
internal const val ANGLE_TOLERANCE_DEG: Float = 0.5f

/** 半径容差（相对半径的比例）：约等于最外环宽度的 1/5。 */
internal const val RING_TOLERANCE_FRACTION: Float = 0.02f
