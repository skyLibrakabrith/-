package at.cwiesner.android.visualtimer.ext.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.ui.alerts.AlertsScreen
import at.cwiesner.android.visualtimer.ext.ui.home.HomeScreen
import at.cwiesner.android.visualtimer.ext.ui.panels.PanelsScreen
import at.cwiesner.android.visualtimer.ext.ui.settings.SettingsScreen

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

        val settings = at.cwiesner.android.visualtimer.ext.settings.SettingsRepo(this)

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
                initial = at.cwiesner.android.visualtimer.ext.settings.ThemeMode.PureBlack,
            )
            ExtTheme(mode = mode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScaffold()
                    ScreenFlashOverlay(
                        alerting = app.panelRunner.alerting,
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
private fun MainScaffold() {
    var selected by remember { mutableIntStateOf(0) }
    // 每次自增表示「用户点了面板按钮，要求切换视图」
    var panelViewToggleTick by remember { mutableIntStateOf(0) }
    val dests = Dest.entries

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = ExtColors.PureBlack,
                contentColor = ExtColors.Primary,
            ) {
                dests.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            // 点「面板」时：如果已经在面板页，就切换卡片/列表视图；
                            // 否则先跳到面板页。这样底边栏就能完成视图切换，
                            // 不必再单独占一个快捷按钮。
                            if (dest == Dest.Panels && selected == index) {
                                panelViewToggleTick++
                            } else {
                                selected = index
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
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
                Dest.Panels -> PanelsScreen(toggleViewSignal = panelViewToggleTick)
                Dest.Alerts -> AlertsScreen()
                Dest.Settings -> SettingsScreen()
            }
        }
    }
}
