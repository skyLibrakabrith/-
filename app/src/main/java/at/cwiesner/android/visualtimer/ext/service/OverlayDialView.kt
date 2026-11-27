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

    private val rect = RectF()

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
