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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import at.cwiesner.android.visualtimer.ext.ExtApplication

/**
 * 序列悬浮窗服务：在别的应用上层显示正在跑的序列。
 *
 * 和 [TimerOverlayService]（计时器悬浮窗）是两个**独立的**前台服务，各自有
 * 自己的通知渠道与通知 id —— 两者可能同时存在（计时器悬浮窗 + 序列悬浮窗），
 * 共用 id 会互相覆盖。
 *
 * 设计上和 TimerOverlayService 完全一致：
 * - **纯 View 代码，没有协程作用域**。Service 里不建协程去 collect，
 *   否则 Service 销毁时容易漏掉 Job，留下泄漏；
 * - 取值走「ExtApplication 里缓存一份」的模式（[ExtApplication.seqOverlayInfo]），
 *   这里只是每 300ms 用 Handler 轮询读缓存，再刷新几行 TextView；
 * - 结构不变时只改文字，不重建视图树。
 *
 * 显示内容：
 * - 第一行：序列名（悬浮卡片标题）
 * - 第二行：当前阶段名 + 「第 i 阶段」
 * - 第三行：阶段剩余时间（倒计时）；等用户确认时换成
 *   「已完成，等你确认」+ 正计时与超时读数
 *
 * 点击卡片 = 回到应用（不在这里做暂停/下一步等复杂按钮，保持简单：
 * 推进序列走 AlertOverlayService 的「下一阶段」或回到应用里点）。
 */
class SequenceOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var card: LinearLayout? = null

    /** 卡片上的三行文本；复用同一个对象，避免每拍都 new TextView。 */
    private var titleView: TextView? = null
    private var stepView: TextView? = null
    private var timeView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 轮询任务。
     *
     * 必须留引用：[onDestroy] 里要 `removeCallbacks` 掉它。它每拍自我重投，
     * 漏掉这一步就会在 Service 销毁后一直空转，把整个 Service 拖住不放
     * （AlertOverlayService 的 timeJob 就踩过同样的坑）。
     */
    private var tickJob: Runnable? = null

    /** 拖动用的偏移量（照 TimerOverlayService 的写法）。 */
    private var offsetX = 0f
    private var offsetY = 0f
    private var initialX = 0
    private var initialY = 0

    /** 本次手势是否已经构成拖动（拖动之后就不再当作点击）。 */
    private var dragging = false

    private val app: ExtApplication?
        get() = applicationContext as? ExtApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            // 没有「显示在其他应用上层」权限：只留一条通知说明，然后退出。
            startForeground(NOTIFICATION_ID, buildNotification("需要「显示在其他应用上层」权限"))
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("序列悬浮窗运行中"))
        showOverlay()
        startTicking()
        // 不返回 START_STICKY：系统重启这个服务时 onStartCommand 的 intent 是 null，
        // 我们也没法可靠判断当时序列是否还在跑 —— 序列的启停由 ExtApplication
        // 订阅 phase 来管，让它重新 startService 更可靠。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy，移除序列悬浮窗")
        tickJob?.let { handler.removeCallbacks(it) }
        tickJob = null
        // 保险：把本 handler 上残余的延时任务一起清掉
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    // ---------------- 悬浮窗视图 ----------------

    private fun showOverlay() {
        if (rootView != null) return

        val dp = resources.displayMetrics.density

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = cardBackground(COLOR_BG_RUNNING)
            isClickable = true
            // 卡片整体可点：点一下回到应用
            setOnClickListener { openApp() }
        }

        titleView = TextView(this).apply {
            text = "序列"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        stepView = TextView(this).apply {
            text = ""
            setTextColor(COLOR_TEXT_DIM)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        timeView = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.MONOSPACE
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }

        box.addView(titleView)
        box.addView(stepView)
        box.addView(timeView)

        val params = WindowManager.LayoutParams(
            (190 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // FLAG_NOT_FOCUSABLE：不抢输入焦点（否则别的应用收不到键盘）。
            // 注意不能用 FLAG_NOT_TOUCHABLE —— 那样就点不到、也拖不动了。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * dp).toInt()
            // 计时器悬浮窗在 y=160dp 处，序列卡片放它下面，避免一开始就叠在一起
            y = (300 * dp).toInt()
        }

        // 拖动：按下时记起点，移动超过阈值时改 LayoutParams。
        // 返回 false 表示「没消费」→ 没拖动时事件继续下发，onClick 才会触发；
        // 一旦真拖动过，就一直是 true（把这次手势里的点击彻底吃掉，
        // 否则拖完松手会误触发「回到应用」）。
        box.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    offsetX = event.rawX
                    offsetY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - offsetX
                    val dy = event.rawY - offsetY
                    if (!dragging && (kotlin.math.abs(dx) > DRAG_SLOP_PX ||
                            kotlin.math.abs(dy) > DRAG_SLOP_PX)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                            .onFailure { Log.w(TAG, "updateViewLayout 失败", it) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    val wasDragging = dragging
                    dragging = false
                    // 拖完这一下不当作点击
                    wasDragging
                }
                else -> {
                    dragging = false
                    false
                }
            }
        }

        card = box
        rootView = box
        val added = runCatching { windowManager.addView(box, params) }
        if (added.isFailure) {
            Log.e(TAG, "addView 失败（悬浮窗权限被撤销？）", added.exceptionOrNull())
            rootView = null
            card = null
            stopSelf()
            return
        }
        // 首帧立刻填上内容，不等第一拍轮询
        refresh()
    }

    private fun removeOverlay() {
        rootView?.let { v -> runCatching { windowManager.removeView(v) } }
        rootView = null
        card = null
        titleView = null
        stepView = null
        timeView = null
    }

    private fun cardBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 14 * resources.displayMetrics.density
        setStroke((1 * resources.displayMetrics.density).toInt(), COLOR_STROKE)
    }

    // ---------------- 内容刷新 ----------------

    /**
     * 每 300ms 轮询一次 [ExtApplication.seqOverlayInfo] 并刷新文字。
     *
     * 重复进来（START_STICKY / 再次 startService 走 onStartCommand）时先撤旧任务，
     * 否则会叠出多条循环。
     */
    private fun startTicking() {
        tickJob?.let { handler.removeCallbacks(it) }
        val job = object : Runnable {
            override fun run() {
                runCatching { refresh() }.onFailure { Log.w(TAG, "刷新序列悬浮窗失败", it) }
                handler.postDelayed(this, REFRESH_MILLIS)
            }
        }
        tickJob = job
        handler.post(job)
    }

    /**
     * 读缓存并刷新三行文本。
     *
     * 缓存为 null = 当前没有活动序列（Idle / Finished），理论上 ExtApplication
     * 此时会 stopService 掉我们；但 stopService 到 onDestroy 之间还有个空档，
     * 而且用户可能撤销了悬浮窗权限导致视图没了 —— 这些都按「收起」处理。
     */
    private fun refresh() {
        val view = card ?: return
        val info = app?.seqOverlayInfo

        if (info == null) {
            view.visibility = View.GONE
            view.background = cardBackground(COLOR_BG_RUNNING)
            return
        }
        view.visibility = View.VISIBLE

        titleView?.text = info.sequenceName

        if (info.awaitingConfirm) {
            // 本阶段已经结束，在等用户确认
            view.background = cardBackground(
                if (info.overdue) COLOR_BG_OVERDUE else COLOR_BG_CONFIRM,
            )
            stepView?.text = "已完成，等你确认"
            stepView?.setTextColor(if (info.overdue) COLOR_TEXT_ALERT else COLOR_TEXT_DIM)
            timeView?.setTextColor(if (info.overdue) COLOR_TEXT_ALERT else Color.WHITE)
            val sub = when {
                info.overdue -> "已超时 " + formatSec(info.overtimeMillis) + " · 点这里去确认"
                info.timeoutRemainingMillis > 0L ->
                    "还可等 " + formatSec(info.timeoutRemainingMillis) + " · 点这里去确认"
                else -> "点这里去确认"
            }
            timeView?.text = "+" + formatMmSs(info.elapsedMillis) + "\n" + sub
            // 上面用两行显示，字号放小一点，否则卡片会被撑得很高
            timeView?.textSize = 20f
        } else {
            view.background = cardBackground(COLOR_BG_RUNNING)
            timeView?.setTextColor(Color.WHITE)
            stepView?.setTextColor(COLOR_TEXT_DIM)
            // 暂停时明确写出来：否则数字冻在那里，用户分不清是暂停了还是卡住了
            stepView?.text = if (info.paused) info.stepCaption + " · 已暂停" else info.stepCaption
            timeView?.text = info.countdownText
            timeView?.textSize = 24f
        }
    }

    // ---------------- 工具 ----------------

    private fun openApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure { Log.w(TAG, "打开应用失败", it) }
    }

    /** 秒级格式化，用于「还可等 / 已超时」这类短读数。 */
    private fun formatSec(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return if (s >= 60) "${s / 60}分${s % 60}秒" else "${s}秒"
    }

    /** 正计时读数 `MM:SS`（照 SequenceRunnerScreen 的写法，不跨包引用 UI 层的 private 函数）。 */
    private fun formatMmSs(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "序列悬浮窗", NotificationManager.IMPORTANCE_MIN).apply {
                description = "逐步间隔序列运行时，悬浮窗的常驻通知"
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
            .setContentTitle("序列悬浮窗")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val TAG = "SequenceOverlay"

        /**
         * 渠道 id 刻意与其它服务区分开：
         * - TimerOverlayService -> "timer_overlay" / 通知 2002
         * - AlertOverlayService -> "alert_flash" / 通知 2003
         * - KeepAliveService     -> "timer_keepalive" / 通知 1001
         * - ExtApplication      -> "timer_alerts"（到点提醒）
         * 这里用 "sequence_overlay" / 通知 2004，不会互相覆盖。
         *
         * （原来的 SequenceService -> "sequence_running" / 1001 已删除：
         *  它依赖一个从未向 AlarmManager 注册过的 receiver，永远启动不了。）
         */
        private const val CHANNEL_ID = "sequence_overlay"
        private const val NOTIFICATION_ID = 2004

        /** 刷新间隔。阶段剩余时间只需要秒级精度，300ms 一拍足够。 */
        private const val REFRESH_MILLIS = 300L

        /** 拖动判定阈值（px）：超过它才算拖动，避免手指微抖把点击吃掉。 */
        private const val DRAG_SLOP_PX = 16f

        private val COLOR_BG_RUNNING = Color.parseColor("#E6000000")
        private val COLOR_BG_CONFIRM = Color.parseColor("#E62A2013")
        private val COLOR_BG_OVERDUE = Color.parseColor("#E64A1414")
        private val COLOR_STROKE = Color.parseColor("#3D3D3D")
        private val COLOR_TEXT_DIM = Color.parseColor("#B0B0B0")
        private val COLOR_TEXT_ALERT = Color.parseColor("#FF8A80")

        const val ACTION_STOP = "at.cwiesner.android.visualtimer.ext.STOP_SEQUENCE_OVERLAY"

        /** 启动悬浮窗。调用方负责先确认「悬浮窗」设置与系统权限。 */
        fun start(context: Context) {
            val i = Intent(context, SequenceOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SequenceOverlayService::class.java))
        }
    }
}
