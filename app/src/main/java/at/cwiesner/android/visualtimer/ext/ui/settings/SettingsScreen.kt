package at.cwiesner.android.visualtimer.ext.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.io.BackupManager
import at.cwiesner.android.visualtimer.ext.settings.ColorThreshold
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.settings.ThemeMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 四块：主题、变色阈值、显示、备份。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ExtApplication
    val scope = rememberCoroutineScope()
    val backup = remember { BackupManager(context, app.database) }
    val settings = remember { SettingsRepo(context) }

    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.PureBlack)
    val thresholds by settings.thresholds.collectAsState(initial = emptyList())
    val showCenterText by settings.showCenterText.collectAsState(initial = true)
    val vibrationEnabled by settings.vibrationEnabled.collectAsState(initial = true)

    var status by remember { mutableStateOf<String?>(null) }
    var showAddThreshold by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = when (val r = backup.export(uri)) {
                is BackupManager.Result.Ok ->
                    "导出成功：" + r.counts.entries.joinToString { "${it.key}=${it.value}" }
                is BackupManager.Result.Failed -> "导出失败：${r.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = when (val r = backup.import(uri, replaceExisting = false)) {
                is BackupManager.Result.Ok ->
                    "导入成功：" + r.counts.entries.joinToString { "${it.key}=${it.value}" }
                is BackupManager.Result.Failed -> "导入失败：${r.message}"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsCard(title = "主题") {
            Text(
                "深色两档：纯黑（OLED 不发光）与深灰（对比更柔和）。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { m ->
                    SelectChip(
                        text = m.label,
                        selected = themeMode == m,
                        modifier = Modifier.weight(1f),
                    ) {
                        scope.launch {
                            settings.setTheme(m)
                            toast("主题：" + m.label)
                        }
                    }
                }
            }
        }

        SettingsCard(title = "变色阈值") {
            Text(
                "剩余时间进入某个阈值后，圆盘切换成对应颜色。" +
                    "列表按剩余时长从多到少判断，先命中的生效。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
            thresholds.forEachIndexed { index, t ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ExtColors.Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(ExtColors.Palette[t.paletteIndex.coerceIn(0, 3)]),
                    )
                    Text(
                        text = "剩余 ≤ ${describeMinutes(t.minutesLeft)}",
                        fontSize = 13.sp,
                        color = ExtColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val next = thresholds.toMutableList().also { it.removeAt(index) }
                                settings.setThresholds(next)
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除阈值",
                            tint = ExtColors.OutlineStrong,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            TextButton(onClick = { showAddThreshold = true }) { Text("+ 添加阈值") }
        }

        SettingsCard(title = "显示") {
            ToggleRow(
                label = "圆盘中央显示剩余时间",
                checked = showCenterText,
                onChange = { scope.launch { settings.setShowCenterText(it) } },
            )
            ToggleRow(
                label = "警报启用震动",
                checked = vibrationEnabled,
                onChange = { scope.launch { settings.setVibrationEnabled(it) } },
            )
        }

        SettingsCard(title = "备份") {
            Text(
                "导出为 JSON，包含面板、序列、警报配置。使用系统文件选择器，不需要存储权限。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionChip("导出", Modifier.weight(1f), primary = true) {
                    exportLauncher.launch("visual-timer-ext-backup.json")
                }
                ActionChip("导入", Modifier.weight(1f), primary = false) {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }
            }
            status?.let {
                Text(it, fontSize = 12.sp, color = ExtColors.TextSecondary)
            }
        }

        Text(
            "默认静音：只用振动 + 屏幕闪烁提醒。手电筒默认关闭，需在警报页单独开启。",
            fontSize = 11.sp,
            color = ExtColors.TextDim,
        )
    }

    if (showAddThreshold) {
        AddThresholdDialog(
            onDismiss = { showAddThreshold = false },
            onConfirm = { minutes ->
                showAddThreshold = false
                scope.launch {
                    val nextIdx = thresholds.size.coerceIn(0, 3)
                    settings.setThresholds(thresholds + ColorThreshold(minutes, nextIdx))
                    toast("已添加阈值：剩余 ≤ ${describeMinutes(minutes)}")
                }
            },
        )
    }
}

private fun describeMinutes(m: Long): String = when {
    m >= 60 -> "${m / 60} 小时" + if (m % 60 != 0L) " ${m % 60} 分" else ""
    else -> "$m 分钟"
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ExtColors.TextPrimary,
            )
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = ExtColors.TextSecondary)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SelectChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ExtColors.PrimaryDim else ExtColors.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (selected) ExtColors.TextPrimary else ExtColors.TextSecondary,
        )
    }
}

@Composable
private fun ActionChip(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) ExtColors.Primary else ExtColors.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = if (primary) Color(0xFF001639) else ExtColors.TextPrimary,
        )
    }
}

@Composable
private fun AddThresholdDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf("5") }
    val minutes = text.toLongOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        title = { Text("添加变色阈值") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("剩余多少分钟时切换颜色", fontSize = 12.sp, color = ExtColors.TextDim)
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { it.isDigit() } },
                    label = { Text("分钟") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (minutes != null && minutes > 0) {
                    Text(
                        "剩余 ≤ ${describeMinutes(minutes)} 时变色",
                        fontSize = 12.sp,
                        color = ExtColors.Primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = minutes != null && minutes > 0,
                onClick = { minutes?.let(onConfirm) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
