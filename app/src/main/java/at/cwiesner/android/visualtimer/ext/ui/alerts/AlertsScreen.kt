package at.cwiesner.android.visualtimer.ext.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.alarm.AlertDispatcher
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import kotlinx.coroutines.launch

/**
 * 警报配置界面。
 *
 * 每种振动模式用 [VibrationWaveform] 画成波形条 —— 不用文字名字也能分辨节奏。
 * 点击卡片试听（震动 + 屏幕闪烁），开关直接改库。
 */
@Composable
fun AlertsScreen(
    modifier: Modifier = Modifier,
    onPreviewFlash: () -> Unit = {},
) {
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
    val settings = remember { SettingsRepo(context) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { FlashTimingCard(settings = settings) }

        items(profiles, key = { it.id }) { profile ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        dispatcher?.previewVibration(profile.vibrationPattern)
                        onPreviewFlash()
                    },
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

/**
 * 屏幕闪烁节奏。
 *
 * 只有「暗场间隔」一个旋钮：真正决定观感的是两次亮起之间隔多久，
 * 亮起时长固定在 [SettingsRepo.FLASH_ON_MILLIS]。
 * 数值越大越不刺眼，2000ms 时基本是「一眼一亮」，适合放在桌上当指示。
 */
@Composable
private fun FlashTimingCard(settings: SettingsRepo) {
    val saved by settings.flashIntervalMillis.collectAsState(
        initial = SettingsRepo.DEFAULT_FLASH_INTERVAL,
    )
    // 拖动时只改本地草稿，松手才落库，避免每帧写一次 DataStore
    var draft by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("屏幕闪烁节奏", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "亮 " + SettingsRepo.FLASH_ON_MILLIS + " 毫秒，再停 " +
                    draft.toLong() + " 毫秒才亮下一次。" +
                    if (saved.toLong() >= 1200) "现在的节奏已经很温和了。" else "",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = {
                    scope.launch { settings.setFlashIntervalMillis(draft.toLong()) }
                },
                valueRange = SettingsRepo.MIN_FLASH_INTERVAL.toFloat()..
                    SettingsRepo.MAX_FLASH_INTERVAL.toFloat(),
                // 每 200ms 一档，共 9 档，拖起来能停稳
                steps = 8,
            )
            Text(
                "想确认效果？点下面任意一条警报卡片，会震一下并闪三次。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
