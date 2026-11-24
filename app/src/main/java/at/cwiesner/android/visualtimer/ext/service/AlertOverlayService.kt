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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo

/**
 * 到点闪烁悬浮窗。
 *
 * **存在的意义**：应用没打开、甚至根本不在前台时，也能弹出全屏闪烁提醒。
 * 之前的闪烁只能画在 Activity 上，用户切到别的应用就完全看不到。
 * 现在改由悬浮窗承担 —— 只要授予了「显示在其他应用上层」权限，
 * 任何界面下到点都能提醒。
 *
 * 交互：
 * 1. 先闪烁，同时显示计时器名称
 * 2. 闪完转常态，显示超时时长（正计时）
 * 3. 两个按钮：**回到应用** / **留在当前**
 * 4. 序列计时额外有 **关闭**，点击后进入下一阶段
 */
class AlertOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var root: View? = null
    private var flashJob: Runnable? = null
    private var timeJob: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private var titleView: TextView? = null
    private var timeView: TextView? = null
    private var flashOn = false

    private var timerId: Long = -1
    private var sequenceId: Long = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        titleView?.text = intent?.getStringExtra(EXTRA_TITLE) ?: "计时结束"
        timerId = intent?.getLongExtra(EXTRA_TIMER_ID, -1L) ?: -1L
        sequenceId = intent?.getLongExtra(EXTRA_SEQUENCE_ID, -1L) ?: -1L

        startForeground(NOTIFICATION_ID, buildNotification(titleView?.text?.toString() ?: "计时结束"))
        showOverlay()
        startFlashing()
        startTickingTime()
        return START_STICKY
    }

    override fun onDestroy() {
        flashJob?.let { handler.removeCallbacks(it) }
        flashJob = null
        // 超时计数的循环任务必须一起撤掉：它每 300ms 自我重投，
        // 漏掉这一步会在 Service 销毁后一直空转，把 timeView 和整个 Service 拖住不放
        timeJob?.let { handler.removeCallbacks(it) }
        timeJob = null
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    // ---------------- 悬浮窗 ----------------

    private fun showOverlay() {
        if (root != null) return
        val dp = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((28 * dp).toInt(), (24 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt())
            background = roundedBg(COLOR_CARD_FLASH)
        }

        titleView = TextView(this).apply {
            text = "计时结束"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        timeView = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 40f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }

        card.addView(titleView)
        card.addView(timeView)

        // 按钮行
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }

        // 序列：关闭 → 下一阶段
        if (sequenceId > 0L) {
            buttons.addView(button("下一阶段", primary = true) {
                val app = application as? ExtApplication
                // 先确认提醒，震动才会停
                app?.panelRunner?.acknowledge(timerId)
                app?.advanceSequence()
                stopSelf()
            })
        }

        buttons.addView(button("回到应用", primary = sequenceId <= 0L) {
            (application as? ExtApplication)?.panelRunner?.acknowledge(timerId)
            openApp()
            stopSelf()
        })
        buttons.addView(button("留在当前", primary = false) {
            // 「留在当前」就是关闭提醒 —— 只是不把用户拽回应用。
            // 三个按钮都会 acknowledge，区别只在是否打开应用。
            (application as? ExtApplication)?.panelRunner?.acknowledge(timerId)
            stopSelf()
        })

        card.addView(buttons)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        root = card
        runCatching { windowManager.addView(card, params) }
    }

    private fun removeOverlay() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private fun roundedBg(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 24 * resources.displayMetrics.density
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (primary) Color.parseColor("#001639") else Color.WHITE)
            background = roundedBg(
                if (primary) Color.parseColor("#7C9EFF") else Color.parseColor("#2A2A2A"),
            )
            gravity = Gravity.CENTER
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (8 * dp).toInt() }
        }
    }

    // ---------------- 闪烁 ----------------

    /**
     * 悬浮窗卡片的闪烁。
     *
     * 两个要点：
     * 1. **一直闪到用户处理**。之前只闪 8 次（8 × 160ms ≈ 1.3 秒）就停，
     *    后台提醒等于没提醒 —— 用户看一眼手机时早就闪完了。
     * 2. 节奏读设置里的「闪烁间隔」，和 Activity 内的 [ScreenFlashOverlay] 保持一致，
     *    否则同一个提醒在应用内外两种观感。
     */
    private fun startFlashing() {
        val interval = (application as? ExtApplication)?.flashIntervalMillis ?: SettingsRepo.DEFAULT_FLASH_INTERVAL
        flashJob = object : Runnable {
            override fun run() {
                flashOn = !flashOn
                root?.background = roundedBg(if (flashOn) COLOR_CARD_FLASH else COLOR_CARD_IDLE)
                handler.postDelayed(this, interval)
            }
        }.also { handler.post(it) }
    }

    // ---------------- 超时时间 ----------------

    private fun startTickingTime() {
        val app = application as? ExtApplication ?: return
        // 重复进来（比如再次收到 onStartCommand）时先撤掉旧任务，避免叠加出多条循环
        timeJob?.let { handler.removeCallbacks(it) }
        val ticker = object : Runnable {
            override fun run() {
                val rt = app.panelRunner.get(timerId)
                val overdue = rt?.overdueMillis ?: 0L
                timeView?.text = if (overdue > 0L) {
                    "已超时 " + DeadlineCalculator.formatRemaining(overdue)
                } else {
                    ""
                }
                handler.postDelayed(this, 300)
            }
        }
        timeJob = ticker
        handler.post(ticker)
    }

    private fun openApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "到点闪烁", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = open?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("计时结束")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "alert_flash"
        private const val NOTIFICATION_ID = 2003
        private val COLOR_CARD_FLASH = Color.parseColor("#FFF2F2F2")
        private val COLOR_CARD_IDLE = Color.parseColor("#E6000000")

        const val EXTRA_TITLE = "title"
        const val EXTRA_TIMER_ID = "timerId"
        const val EXTRA_SEQUENCE_ID = "sequenceId"

        fun start(context: Context, title: String, timerId: Long, sequenceId: Long = -1L) {
            if (!Settings.canDrawOverlays(context)) return
            val i = Intent(context, AlertOverlayService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TIMER_ID, timerId)
                putExtra(EXTRA_SEQUENCE_ID, sequenceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertOverlayService::class.java))
        }
    }
}
