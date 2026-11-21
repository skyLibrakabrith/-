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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
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
import at.cwiesner.android.visualtimer.ext.ui.alerts.AlertsScreen
import at.cwiesner.android.visualtimer.ext.ui.home.HomeScreen
import at.cwiesner.android.visualtimer.ext.ui.panels.PanelsScreen
import at.cwiesner.android.visualtimer.ext.ui.sequences.SequencesScreen
import at.cwiesner.android.visualtimer.ext.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {

    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureNotificationPermission()

        val settings = at.cwiesner.android.visualtimer.ext.settings.SettingsRepo(this)

        setContent {
            val mode by settings.themeMode.collectAsState(
                initial = at.cwiesner.android.visualtimer.ext.settings.ThemeMode.PureBlack,
            )
            ExtTheme(mode = mode) {
                MainScaffold()
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
    Sequences("序列", Icons.Filled.PlayCircle),
    Alerts("警报", Icons.Filled.Notifications),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
private fun MainScaffold() {
    var selected by remember { mutableIntStateOf(0) }
    val dests = Dest.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                dests.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
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
                Dest.Panels -> PanelsScreen()
                Dest.Sequences -> SequencesScreen()
                Dest.Alerts -> AlertsScreen()
                Dest.Settings -> SettingsScreen()
            }
        }
    }
}
