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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.alarm.AlertDispatcher
import at.cwiesner.android.visualtimer.ext.alarm.HeadphoneSoundAlerter
import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import kotlinx.coroutines.launch

/**
 * 警报配置界面。
 *
 * 每种振动模式用 [VibrationWaveform] 画成波形条 —— 不用文字名字也能分辨节奏。
 * 点击卡片试听（震动 + 屏幕闪烁），开关直接改库。
 */
/**
 * 警报页的两种呈现，由底边栏「警报」按钮再次点击切换。
 *
 * - [Vibration] 振动：每种配置一条波形卡，点卡片试听震动 + 闪烁
 * - [Sound]     铃声：贴合「声音只在耳机里响」——列出铃声选择与
 *               当前连接状态，点一行即试听
 */
enum class AlertsView { Vibration, Sound }

@Composable
fun AlertsScreen(
    modifier: Modifier = Modifier,
    onPreviewFlash: () -> Unit = {},
    /** 每次自增表示底边栏「警报」被再次点击，要求切换振动/铃声。 */
    toggleModeSignal: Int = 0,
    /** 视图变化时回传，供底边栏图标同步。 */
    onModeChanged: (Boolean) -> Unit = {},
) {
    // 同 PanelsScreen：rememberSaveable，跨标签往返保持视图。
    // 默认是**振动**——它是主提醒方式，铃声只在插耳机时才有。
    var view by rememberSaveable { mutableStateOf(AlertsView.Vibration) }
    LaunchedEffect(toggleModeSignal) {
        if (toggleModeSignal > 0) {
            view = if (view == AlertsView.Vibration) AlertsView.Sound else AlertsView.Vibration
        }
    }
    val showsSound = view == AlertsView.Sound
    LaunchedEffect(showsSound) { onModeChanged(showsSound) }
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

    if (view == AlertsView.Sound) {
        SoundView(
            profiles = profiles,
            settings = settings,
            onPreviewFlash = onPreviewFlash,
            modifier = modifier,
        )
        return
    }

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

/**
 * 铃声视图。
 *
 * 这里体现「声音只在连着耳机时播放，绝不经扬声器外放」这条约束：
 * 顶部直接显示当前耳机状态，没耳机时试听按钮是禁用的，
 * 而不是让人点了没反应、以为坏了。
 */
@Composable
private fun SoundView(
    profiles: List<AlertProfile>,
    settings: SettingsRepo,
    onPreviewFlash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val soundEnabled by settings.soundEnabled.collectAsState(initial = true)
    val ringtoneUri by settings.ringtoneUri.collectAsState(initial = null)

    // 每次进入这个视图重新探测一次耳机，插拔后切回来即可看到变化
    var headphoneOn by remember { mutableStateOf(false) }
    val alerter = remember { HeadphoneSoundAlerter(context) }
    LaunchedEffect(Unit) { headphoneOn = alerter.isHeadphoneConnected() }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("声音只在耳机里响", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (headphoneOn) {
                            "已检测到耳机，到点时会在这里出声。"
                        } else {
                            "当前没有耳机，到点**不会**外放 —— " +
                                "扬声器和音响都不会响。插上耳机后自动生效。"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("声音总开关", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = { on ->
                                scope.launch { settings.setSoundEnabled(on) }
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        enabled = headphoneOn && soundEnabled,
                        onClick = {
                            alerter.ringtoneUri = ringtoneUri?.let { android.net.Uri.parse(it) }
                            alerter.playIfHeadphone()
                            onPreviewFlash()
                        },
                    ) {
                        Text(if (headphoneOn) "试听铃声" else "插上耳机才能试听")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("当前铃声", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        describeRingtoneName(context, ringtoneUri),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "铃声在设置页选择。这里只负责在耳机里把它放出来。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        if (profiles.isNotEmpty()) {
            item {
                Text(
                    "各提醒配置的波形节奏（振动视图里可试听）",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            items(profiles, key = { it.id }) { profile ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.labelLarge)
                        VibrationWaveform(
                            pattern = profile.vibrationPattern,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            showLabels = false,
                        )
                    }
                }
            }
        }
    }
}

/** 铃声 URI → 可读名称。 */
private fun describeRingtoneName(context: android.content.Context, uri: String?): String {
    if (uri == null) return "系统默认"
    return runCatching {
        val u = android.net.Uri.parse(uri)
        android.media.RingtoneManager.getRingtone(context, u)?.getTitle(context)
    }.getOrNull() ?: "已选择"
}
