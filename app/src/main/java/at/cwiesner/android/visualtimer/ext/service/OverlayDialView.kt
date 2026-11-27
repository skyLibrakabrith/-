package at.cwiesner.android.visualtimer.ext.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator

/**
 * 悬浮窗里用的迷你圆盘。
 *
 * 悬浮窗跑在没有 Activity、没有 Compose 生命周期的环境里，
 * 所以这里用原生 View + `onDraw` 手绘 —— 和主界面的 `PieTimerView`
 * 保持同一套视觉语言：实心扇形 + 中央时间。
 *
 * 只画必要的东西（底圆、扇形、文字），避免悬浮窗频繁重绘时掉帧。
 */
class OverlayDialView(context: Context) : View(context) {

    /** 剩余比例 0..1。 */
    var progress: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 是否处于**超时**状态（倒计时走完后还在继续累加）。
     *
     * 超时后 `progress` 恒为 0，扇形不再变化 —— 画面上就是一潭死水，
     * 分不清"超时中"还是"卡住了"。所以超时要额外画一段绕圆环转的弧。
     */
    var overdue: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 扇形颜色。 */
    var arcColor: Int = COLOR_NORMAL
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 中央文字。 */
    var label: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 圆盘下方的名称。 */
    var caption: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TRACK
        style = Paint.Style.FILL
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NORMAL
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CAPTION
        textAlign = Paint.Align.CENTER
    }

    private val spinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()

    /**
     * 超时旋转弧的角度由一个**时间驱动**的循环推算，而不是 Animator。
     *
     * 为什么不用 ValueAnimator：这个 View 会被 Service 反复创建/丢弃，
     * Animator 需要在 onAttachedToWindow / onDetachedFromWindow 里配对启停，
     * 漏一处就是内存泄漏或空转。用 `System.currentTimeMillis()` 算角度 +
     * 每次 onDraw 里 `postInvalidateDelayed` 自驱动，没有生命周期要管，
     * 而且 Service 不画的时候自然会停（不再 invalidate）。
     */
    private fun spinAngleDeg(): Float =
        ((System.currentTimeMillis() % SPIN_PERIOD_MS).toFloat() / SPIN_PERIOD_MS) * 360f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (58 * density).toInt()
        val captionH = (14 * density).toInt()
        setMeasuredDimension(size, size + captionH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = width.toFloat()
        val radius = size / 2f - 2f * density
        val cx = width / 2f
        val cy = radius + 2f * density

        // 底圆
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 实心扇形
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        arcPaint.color = arcColor
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep > 0f) {
            canvas.drawArc(rect, -90f, sweep, true, arcPaint)
        }

        // 超时：绕外沿画一段转动的弧，表示"还在走"
        if (overdue) {
            val ringR = radius * 1.02f
            rect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            spinPaint.color = arcColor
            spinPaint.strokeWidth = (size * 0.06f).coerceAtLeast(1.5f * density)
            canvas.drawArc(rect, spinAngleDeg() - 90f, 46f, false, spinPaint)
        }

        // 超时时自我驱动下一帧；不超时就不排，避免 Service 闲置时白耗电
        if (overdue) postInvalidateDelayed(FRAME_MS)

        // 中央时间
        textPaint.textSize = 11f * density
        val metrics = textPaint.fontMetrics
        val textY = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, cx, textY, textPaint)

        // 名称
        if (caption.isNotEmpty()) {
            captionPaint.textSize = 9f * density
            canvas.drawText(
                caption,
                cx,
                size + 9f * density,
                captionPaint,
            )
        }
    }

    companion object {
        private const val COLOR_TRACK = 0xFF1A1A1A.toInt()
        /** 旋转一圈的周期，与面板大盘一致（1.4 秒）。 */
        private const val SPIN_PERIOD_MS = 1400L
        /** 超时的重绘间隔 ≈ 30fps。 */
        private const val FRAME_MS = 33L
        /** 未设置弧色时的默认值（`arcColor` 的初始值）。 */
        val COLOR_NORMAL = 0xFF7C9EFF.toInt()
        private const val COLOR_CAPTION = 0xFF8A8A8A.toInt()

        // 原来的 `colorFor(progress)` 已删除：
        // 它按 progress 硬编码三档阈值取色，**完全不读设置里的变色模式** ——
        // 用户报的正是「面板时钟配色应用到悬浮窗上」。
        // 现在颜色由 `TimerOverlayService.pickOverlayColor` 调面板同一个
        // `pickColor` 算出，这里不再保留第二套规则（两套规则必然漂移）。

        /** 圆盘中央的紧凑文本：`5:00` / `1:23`。 */
        fun labelFor(remainingMillis: Long): String =
            DeadlineCalculator.formatRemaining(remainingMillis)
    }
}
