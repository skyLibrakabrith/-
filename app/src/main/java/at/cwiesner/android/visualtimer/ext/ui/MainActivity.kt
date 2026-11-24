package at.cwiesner.android.visualtimer.ext.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.settings.ThemeMode
import at.cwiesner.android.visualtimer.ext.ui.alerts.AlertsScreen
import at.cwiesner.android.visualtimer.ext.ui.home.HomeScreen
import at.cwiesner.android.visualtimer.ext.ui.panels.PanelsScreen
import at.cwiesner.android.visualtimer.ext.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 直接给系统栏上纯黑底：enableEdgeToEdge 会让它们透明，
        // 与 Scaffold 之间露出一条底色黑带
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        ensureNotificationPermission()

        val settings = SettingsRepo(this)

        val app = application as ExtApplication

        // 有未处理提醒时把亮度拉满，离开提醒后还原
        lifecycleScope.launch {
            app.panelRunner.alerting.collect { ids ->
                runCatching {
                    val attrs = window.attributes
                    if (ids.isEmpty()) {
                        attrs.screenBrightness =
                            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else {
                        attrs.screenBrightness = 1f
                    }
                    window.attributes = attrs
                }
            }
        }

        // 屏幕常亮：按设置决定是否加 FLAG_KEEP_SCREEN_ON
        lifecycleScope.launch {
            settings.keepScreenOn.collect { on ->
                if (on) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            val mode by settings.themeMode.collectAsState(
                initial = ThemeMode.PureBlack,
            )
            // 闪烁节奏由警报页的滑杆决定，这里订阅后直接喂给覆盖层
            val flashInterval by settings.flashIntervalMillis.collectAsState(
                initial = SettingsRepo.DEFAULT_FLASH_INTERVAL,
            )
            var flashPreviewTick by remember { mutableIntStateOf(0) }
            ExtTheme(mode = mode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScaffold(onPreviewFlash = { flashPreviewTick++ })
                    ScreenFlashOverlay(
                        alerting = app.panelRunner.alerting,
                        pulseOnMillis = SettingsRepo.FLASH_ON_MILLIS.toInt(),
                        pulseOffMillis = flashInterval.toInt(),
                        previewTick = flashPreviewTick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** Android 13+ 必须运行时申请通知权限，否则前台服务通知不显示。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Dest(
    val label: String,
    val icon: ImageVector,
) {
    Home("时钟", Icons.Filled.AccessTime),
    Panels("面板", Icons.Filled.GridView),
    Alerts("警报", Icons.Filled.Notifications),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
private fun MainScaffold(onPreviewFlash: () -> Unit = {}) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // 两个「已在当前页时再点一次」的切换信号。
    // 面板页切 卡片/列表，警报页切 振动/铃声 —— 底边栏本身就是切换器，
    // 不额外占页面里的按钮。
    var panelViewToggleTick by remember { mutableIntStateOf(0) }
    var alertsModeToggleTick by remember { mutableIntStateOf(0) }
    // 长按底边栏「面板」→ 打开面板操作抽屉
    var panelActionsTick by remember { mutableIntStateOf(0) }

    // 各页当前视图。**由导航层持有**：页面组件切标签时会被销毁重建，
    // 视图状态放在页面内部就会丢，表现为「从别处进来变成列表视图」。
    var panelShowsList by rememberSaveable { mutableStateOf(false) }
    // PanelsScreen 的视图开关：交给它一个 MutableState，由这里持有
    val panelListModeState = rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver(
            save = { it.value },
            restore = { mutableStateOf(it) },
        ),
    ) { mutableStateOf(false) }
    var alertsShowsSound by rememberSaveable { mutableStateOf(false) }
    val dests = Dest.entries

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = ExtColors.PureBlack,
                contentColor = ExtColors.Primary,
            ) {
                dests.forEachIndexed { index, dest ->
                    // 自己画导航项，而不是用 NavigationBarItem。
                    // 原因：NavigationBarItem 内部自带 selectable(clickable)，
                    // 再在外面挂一层 combinedClickable 就会变成两层点击、互相抢事件
                    // —— 这个坑在面板卡片上已经踩过一次。
                    // 现在每个项只有**一个**点击层，长按/单击都能稳定区分。
                    val icon = when {
                        dest == Dest.Panels && panelShowsList -> Icons.Filled.Checklist
                        dest == Dest.Alerts && alertsShowsSound -> Icons.Filled.MusicNote
                        else -> dest.icon
                    }
                    NavItem(
                        modifier = Modifier.weight(1f),
                        selected = selected == index,
                        icon = icon,
                        label = dest.label,
                        onClick = {
                            // 已经在当前页 → 切换该页的视图；否则先跳过去。
                            // 跳转**不会**重置任何页的视图。
                            when {
                                dest == Dest.Panels && selected == index ->
                                    panelViewToggleTick++
                                dest == Dest.Alerts && selected == index ->
                                    alertsModeToggleTick++
                                else -> selected = index
                            }
                        },
                        onLongClick = if (dest == Dest.Panels) {
                            // 长按「面板」→ 面板操作抽屉（新建序列 / 面板管理）
                            {
                                if (selected != index) selected = index
                                panelActionsTick++
                            }
                        } else null,
                    )
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when (dests[selected]) {
                Dest.Home -> HomeScreen()
                Dest.Panels -> PanelsScreen(
                    toggleViewSignal = panelViewToggleTick,
                    listModeState = panelListModeState,
                    actionsSignal = panelActionsTick,
                )
                Dest.Alerts -> AlertsScreen(
                    onPreviewFlash = onPreviewFlash,
                    toggleModeSignal = alertsModeToggleTick,
                    onModeChanged = { showsSound -> alertsShowsSound = showsSound },
                )
                Dest.Settings -> SettingsScreen()
            }
        }
    }
}

/**
 * 底边栏的一项。
 *
 * 只挂**一个** [androidx.compose.foundation.combinedClickable]：
 * 单击切页/切视图，长按给需要额外入口的页面用（目前只有「面板」）。
 * 不用 NavigationBarItem 是因为它内部已经有一层 selectable，
 * 再叠一层点击会互相抢事件。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) ExtColors.Primary else ExtColors.TextSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) ExtColors.Primary else ExtColors.TextSecondary,
        )
    }
}
