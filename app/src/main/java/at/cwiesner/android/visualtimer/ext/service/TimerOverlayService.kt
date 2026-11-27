package at.cwiesner.android.visualtimer.ext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.settings.ColorThreshold
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.wheel.pickColor
import androidx.compose.ui.graphics.toArgb

/**
 * 悬浮窗服务：在别的应用上层显示正在运行的计时器。
 *
 * 用原生 View + WindowManager 而不是 Compose —— 悬浮窗没有 Activity
 * 生命周期，挂 ComposeView 要自己造 LifecycleOwner 和 SavedStateRegistry，
 * 成本远高于收益。这里就是一个深色圆角卡片加几行文本。
 *
 * 需要 `SYSTEM_ALERT_WINDOW` 权限（用户在系统里单独授予）。
 * 前台服务保活，通知栏常驻。
 */
class TimerOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var container: LinearLayout? = null
    private var updateThread: Thread? = null

    @Volatile
    private var runningFlag = true

    /** 拖动用的偏移量。 */
    private var offsetX = 0f
    private var offsetY = 0f
    private var initialX = 0
    private var initialY = 0

    private val app: ExtApplication?
        get() = applicationContext as? ExtApplication

    /**
     * 悬浮窗配色配置，跟随设置页的「变色模式」。
     *
     * ## 为什么要把设置拷到字段里
     *
     * 悬浮窗是 `Service`，绘制发生在 `post { }` 的 View 层，没有 Composable 的
     * `collectAsState` 可用。所以订阅一次设置、缓存成普通字段，
     * 设置变化时刷新并重绘一次。
     *
     * ## 为什么不自己写一套取色
     *
     * `AGENTS.md` 明确记着："三种模式（阈值 / 等间隔 / 表盘比例）**全部**走同一条
     * 红绿灯色带"。悬浮窗若各写一份，改一处就会和面板不一致 ——
     * 用户报的正是「面板时钟配色应用到悬浮窗上」。
     * 所以这里直接调面板用的同一个 `pickColor`，不复制逻辑。
     */
    private var colorMode: ColorMode = ColorMode.Fraction
    private var thresholdMinutes: List<Long> = emptyList()
    private var thresholdColors: List<androidx.compose.ui.graphics.Color> = emptyList()
    private var intervalMinutes: Long = 5L
    private var fractionDivisions: Int = 4

    /** 最近一次渲染用的运行时列表；设置变化时用它重绘，不必等下一次 tick。 */
    private var lastRuntimes: List<TimerRuntime> = emptyList()

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        observeColorSettings()
    }

    /**
     * 订阅设置里的配色项，变化时更新字段并重绘悬浮窗。
     *
     * 用 `combine` 而不是四个独立 collect：分开订阅会在用户改一项时
     * 触发多次无谓重绘。
     */
    private fun observeColorSettings() {
        val a = app ?: return
        settingsScope.launch {
            combine(
                a.settings.colorMode,
                a.settings.thresholds,
                a.settings.intervalMinutes,
                a.settings.fractionDivisions,
            ) { mode, thresholds, interval, divisions ->
                Quad(mode, thresholds, interval, divisions)
            }.collect { q ->
                colorMode = q.mode
                thresholdMinutes = q.thresholds.map { it.minutesLeft }
                // 阈值颜色来自固定的红绿灯色带（trafficStepColor 只依赖静态常量），
                // 所以可以在 Service 里安全取色，不依赖 composition。
                thresholdColors = q.thresholds.map {
                    ExtColors.trafficStepColor(it.paletteIndex, 4)
                }
                intervalMinutes = q.interval
                fractionDivisions = q.divisions
                // 立刻按新配色重绘，不等下一次 tick
                if (lastRuntimes.isNotEmpty()) mainHandlerUpdate(lastRuntimes)
            }
        }
    }

    private data class Quad(
        val mode: ColorMode,
        val thresholds: List<ColorThreshold>,
        val interval: Long,
        val divisions: Int,
    )

    /**
     * 按设置的变色模式算悬浮窗颜色。
     *
     * 直接复用面板的 `pickColor`（`ui/wheel/WheelTimerView.kt`）——
     * 面板和悬浮窗必须是同一套配色，否则用户一眼就能看出不一致。
     */
    private fun pickOverlayColor(
        remainingMillis: Long,
        totalMillis: Long,
        running: Boolean,
    ): Int = pickColor(
        remainingMillis = remainingMillis,
        totalMillis = totalMillis,
        running = running,
        mode = colorMode,
        thresholdMinutes = thresholdMinutes,
        thresholdColors = thresholdColors,
        intervalMinutes = intervalMinutes,
        fractionDivisions = fractionDivisions,
    ).toArgb()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            // 没有权限，只留一个通知提示
            startForeground(NOTIFICATION_ID, buildNotification("需要「显示在其他应用上层」权限"))
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("悬浮窗运行中"))
        showOverlay()
        startUpdating()
        return START_STICKY
    }

    override fun onDestroy() {
        // 不停这个协程会一直挂着设置订阅，Service 重建后重复订阅
        settingsScope.cancel()
        runningFlag = false
        updateThread?.interrupt()
        updateThread = null
        removeOverlay()
        super.onDestroy()
    }

    // ---------------- 悬浮窗视图 ----------------

    private fun showOverlay() {
        if (rootView != null) return

        val dp = resources.displayMetrics.density

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6000000"))
                cornerRadius = 14 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#3D3D3D"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * dp).toInt()
            y = (160 * dp).toInt()
        }

        // 拖动 + 松手吸附
        container?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    offsetX = event.rawX
                    offsetY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - offsetX).toInt()
                    params.y = initialY + (event.rawY - offsetY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 松手吸附到左右边缘。
                    //
                    // 原来这里是 `else -> false`，ACTION_UP 被吞掉 ——
                    // 悬浮窗停在手指松开的位置，贴着屏幕中间挡住内容，
                    // 用户每次都得手动挪开。这是"悬浮窗没吸附"的直接原因。
                    snapToNearestEdge(view, params)
                    true
                }
                else -> false
            }
        }

        rootView = container
        val added = runCatching { windowManager.addView(container, params) }
        if (added.isFailure) {
            android.util.Log.e("TimerOverlay", "addView 失败: ${added.exceptionOrNull()?.message}")
            rootView = null
            container = null
        }
    }

    /**
     * 把悬浮窗吸附到**最近的左右边缘**。
     *
     * 为什么只吸左右不吸上下：这是个竖向较长的卡片，贴着屏幕左边或右边时
     * 遮住的正文最少；吸到顶部/底部会和状态栏、导航栏重叠。
     * 所以纵向位置**保持用户拖到的地方**，只调整横向。
     *
     * 用动画而不是瞬间跳过去：直接赋值会让悬浮窗"闪现"到边缘，
     * 看起来像是被强制拽走。这里用 ValueAnimator 走 180ms，手感更自然。
     */
    private fun snapToNearestEdge(view: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val viewWidth = view.width.takeIf { it > 0 } ?: view.measuredWidth
        // 边距：贴边但留一点呼吸空间，完全贴死会碰到屏幕圆角/曲面
        val margin = (8 * metrics.density).toInt()

        val maxX = (screenWidth - viewWidth - margin).coerceAtLeast(margin)
        // 注：这里用「当前 x 更接近哪一边」来判断，而不是用中心点，
        // 因为 x 是容器左上角坐标 —— 用左上角判断会让宽卡片总是吸左边。
        val centerNow = params.x + viewWidth / 2
        val targetX = if (centerNow < screenWidth / 2) margin else maxX

        // 纵向做一次夹取，避免用户把卡片拖到屏幕外再也抓不回来
        val screenHeight = metrics.heightPixels
        val viewHeight = view.height.takeIf { it > 0 } ?: view.measuredHeight
        val minY = (-(resources.displayMetrics.density * 40)).toInt()
        val maxY = (screenHeight - viewHeight - margin).coerceAtLeast(minY)
        val targetY = params.y.coerceIn(minY, maxY)

        if (params.x == targetX && params.y == targetY) return

        val fromX = params.x
        val fromY = params.y
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                params.x = (fromX + (targetX - fromX) * f).toInt()
                params.y = (fromY + (targetY - fromY) * f).toInt()
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        anim.start()
    }

    private fun removeOverlay() {
        rootView?.let { v -> runCatching { windowManager.removeView(v) } }
        rootView = null
        container = null
    }

    // ---------------- 内容刷新 ----------------

    private fun startUpdating() {
        if (updateThread?.isAlive == true) return
        updateThread = Thread {
            while (runningFlag) {
                val list = app?.panelRunner?.snapshot()?.filter { it.running || it.expired } ?: emptyList()
                try {
                    mainHandlerUpdate(list)
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * 把运行中的计时器画成一排圆盘。
     *
     * 用 [OverlayDialView] 原生绘制，和主界面 PieTimerView 视觉一致：
     * 实心扇形 + 中央时间 + 下方名称。最多 6 个，两行三列。
     */
    private fun mainHandlerUpdate(list: List<TimerRuntime>) {
        // 记住这一批：设置变化时要按新配色重绘同一批计时器
        lastRuntimes = list
        val dp = resources.displayMetrics.density
        container?.post {
            val box = container ?: return@post

            if (list.isEmpty()) {
                box.removeAllViews()
                box.addView(
                    TextView(this).apply {
                        text = "暂无运行中的计时器"
                        setTextColor(Color.parseColor("#8A8A8A"))
                        textSize = 12f
                    },
                )
                return@post
            }

            val shown = list.take(6)
            val dialSize = (58 * dp).toInt()
            val gap = (8 * dp).toInt()
            val perRow = 3
            val rows = (shown.size + perRow - 1) / perRow

            // 结构不变时只更新数值，避免每次重绘都重建视图树
            if (box.childCount == rows && box.tag == shown.size) {
                var idx = 0
                for (r in 0 until rows) {
                    val row = box.getChildAt(r) as? LinearLayout ?: continue
                    for (c in 0 until row.childCount) {
                        val dial = row.getChildAt(c) as? OverlayDialView ?: continue
                        val rt = shown.getOrNull(idx) ?: break
                        dial.progress = if (rt.totalMillis > 0)
                            (rt.remainingMillis.toFloat() / rt.totalMillis).coerceIn(0f, 1f)
                        else 0f
                        // 按设置的变色模式取色（与面板同一套 pickColor）
                        dial.arcColor = pickOverlayColor(
                            remainingMillis = rt.remainingMillis,
                            totalMillis = rt.totalMillis,
                            running = rt.running,
                        )
                        dial.label = OverlayDialView.labelFor(rt.remainingMillis)
                        dial.caption = rt.label ?: "未命名"
                        idx++
                    }
                }
                return@post
            }

            box.removeAllViews()
            box.tag = shown.size
            var idx = 0
            for (r in 0 until rows) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    if (r > 0) setPadding(0, gap, 0, 0)
                }
                for (c in 0 until perRow) {
                    val rt = shown.getOrNull(idx) ?: break
                    val dial = OverlayDialView(this).apply {
                        progress = if (rt.totalMillis > 0)
                            (rt.remainingMillis.toFloat() / rt.totalMillis).coerceIn(0f, 1f)
                        else 0f
                        // 按设置的变色模式取色（与面板同一套 pickColor）
                        arcColor = pickOverlayColor(
                            remainingMillis = rt.remainingMillis,
                            totalMillis = rt.totalMillis,
                            running = rt.running,
                        )
                        label = OverlayDialView.labelFor(rt.remainingMillis)
                        caption = rt.label ?: "未命名"
                        layoutParams = LinearLayout.LayoutParams(dialSize, dialSize + (14 * dp).toInt())
                            .apply { if (c > 0) marginStart = gap }
                    }
                    row.addView(dial)
                    idx++
                }
                box.addView(row)
            }
        }
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = open?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("计时器悬浮窗")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "timer_overlay"
        private const val NOTIFICATION_ID = 2002
        const val ACTION_STOP = "at.cwiesner.android.visualtimer.ext.STOP_OVERLAY"

        fun start(context: Context) {
            val i = Intent(context, TimerOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimerOverlayService::class.java))
        }
    }
}
