package at.cwiesner.android.visualtimer.ext.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.alarm.AlertDispatcher
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import kotlinx.coroutines.launch

/**
 * 警报配置界面。
 *
 * 每种振动模式用 [VibrationWaveform] 画成波形条 —— 不用文字名字也能分辨节奏。
 * 点击卡片试听（震动 + 屏幕闪烁），开关直接改库。
 */
@Composable
fun AlertsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val db = app.database
    val scope = rememberCoroutineScope()

    val profiles by remember { db.alertProfileDao().observeAll() }
        .collectAsState(initial = emptyList<AlertProfile>())

    val activity = context as? android.app.Activity
    val dispatcher = remember(activity) {
        activity?.let { AlertDispatcher(it, scope) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(profiles, key = { it.id }) { profile ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { dispatcher?.previewVibration(profile.vibrationPattern) },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall)

                    VibrationWaveform(
                        pattern = profile.vibrationPattern,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        showLabels = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (profile.screenFlash) "屏幕闪烁 开" else "屏幕闪烁 关",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Switch(
                            checked = profile.screenFlash,
                            onCheckedChange = { on ->
                                scope.launch {
                                    db.alertProfileDao().upsert(profile.copy(screenFlash = on))
                                }
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (profile.torchFlash) "手电筒 开" else "手电筒 关（默认）",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Switch(
                            checked = profile.torchFlash,
                            onCheckedChange = { on ->
                                scope.launch {
                                    db.alertProfileDao().upsert(profile.copy(torchFlash = on))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
