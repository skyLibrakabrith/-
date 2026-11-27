package at.cwiesner.android.visualtimer.ext.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import at.cwiesner.android.visualtimer.ext.service.TimerOverlayService
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.ui.wheel.NumberWheel
import at.cwiesner.android.visualtimer.ext.ui.wheel.PaletteStrip
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.settings.ColorThreshold
import at.cwiesner.android.visualtimer.ext.settings.ThemeMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlinx.coroutines.launch
import at.cwiesner.android.visualtimer.ext.ui.common.describeMinutes

/**
 * 表盘比例可选份数。
 *
 * **唯一来源**：滚轮的可选值、文案里的显示值、色带的格数全都从这个列表来。
 *
 * 之前滚轮写的是 `(2..8)`、SettingsRepo 允许 `2..12`、WheelTimerView 按 `2..12` 取色，
 * 三处范围各不相同。份数一旦超出某个范围，那一处就会静默夹一下，
 * 界面上就会看到"说的是一个数、画的是另一个数"。
 * 这里统一成一处，边界常量直接取自 SettingsRepo 的伴生对象。
 */
private val FractionDivisionValues: List<Int> = (
    SettingsRepo.MIN_FRACTION_DIVISIONS..SettingsRepo.MAX_FRACTION_DIVISIONS
    ).toList()

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
    val settings = app.settings   // 共享 + 预热，避免切标签时先画默认值

    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.PureBlack)
    val thresholds by settings.thresholds.collectAsState(initial = emptyList())
    val colorMode by settings.colorMode.collectAsState(initial = ColorMode.Fraction)
    val intervalMinutes by settings.intervalMinutes.collectAsState(initial = 5L)
    val fractionDivisions by settings.fractionDivisions.collectAsState(initial = 4)
    val showCenterText by settings.showCenterText.collectAsState(initial = true)
    val vibrationEnabled by settings.vibrationEnabled.collectAsState(initial = true)
    val soundEnabled by settings.soundEnabled.collectAsState(initial = true)
    val overlayEnabled by settings.overlayEnabled.collectAsState(initial = false)
    val backgroundEnabled by settings.backgroundEnabled.collectAsState(initial = true)
    val keepScreenOn by settings.keepScreenOn.collectAsState(initial = false)
    val gridColumns by settings.gridColumns.collectAsState(initial = 2)
    // 悬浮窗权限状态（用户可能在系统里改过，每次重组重新查）
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // toast 必须在所有 lambda 之前定义，否则 launcher 回调里引用不到
    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    var status by remember { mutableStateOf<String?>(null) }

    var showAddThreshold by remember { mutableStateOf(false) }

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

    // 导入模式选择（合并 / 覆盖）
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportMode by remember { mutableStateOf(false) }

    /**
     * 真正执行导入。
     *
     * 抽成局部函数是因为要在两个地方调（合并 / 覆盖），
     * 复制两份会让"结果文案怎么拼"这类细节漂移。
     */
    fun doImport(uri: Uri?, replaceExisting: Boolean) {
        // 接受可空：调用点在 AlertDialog 的 lambda 里，那里拿不到智能转换后的非空值。
        // 与其在调用点写 `if (x != null) doImport(x!!)`，不如在这里守卫一次。
        if (uri == null) return
        scope.launch {
            status = when (val r = backup.import(uri, replaceExisting = replaceExisting)) {
                is BackupManager.Result.Ok ->
                    (if (replaceExisting) "已覆盖导入：" else "已合并导入：") +
                        r.counts.entries.joinToString { "${it.key}=${it.value}" }
                is BackupManager.Result.Failed -> "导入失败：${r.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 先问「合并还是覆盖」，拿到选择再真正导入。
        //
        // 为什么不直接合并：两种语义差别很大（覆盖会清空现有全部数据），
        // 默认替用户决定任何一个都有风险。所以让用户明确选一次。
        pendingImportUri = uri
        showImportMode = true
    }

    if (showImportMode) {
        val uri = pendingImportUri
        AlertDialog(
            onDismissRequest = { showImportMode = false; pendingImportUri = null },
            title = { Text("导入备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "合并：保留现有数据，同名计时器**取时长更长**的那个。",
                        fontSize = 13.sp,
                    )
                    Text(
                        "覆盖：**清空现有全部数据**（含归档记录），完全变成备份里的状态。",
                        fontSize = 13.sp,
                        color = ExtColors.Red,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportMode = false
                        pendingImportUri = null
                        // 覆盖 = 先清空；合并 = 追加 + 冲突取更长
                        doImport(uri, replaceExisting = true)
                    },
                ) { Text("覆盖导入", color = ExtColors.Red) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showImportMode = false
                            pendingImportUri = null
                            doImport(uri, replaceExisting = false)
                        },
                    ) { Text("合并导入") }
                    TextButton(onClick = { showImportMode = false; pendingImportUri = null }) {
                        Text("取消")
                    }
                }
            },
        )
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

        SettingsCard(title = "变色模式") {
            // 说明文案必须和盘面实际用的色带一致。
            // 以前这里写的是 PaletteNames（绿/黄绿/黄/橙/红/蓝紫/天蓝/紫 8 色），
            // 但三种模式的盘面取色全都走 trafficStepColor（绿 → 黄 → 红 三锚点插值），
            // 文案在误导用户：让人以为"分 4 份 = 绿/黄绿/黄/橙"。
            Text(
                "决定圆盘颜色随剩余时间怎么变。三种模式统一走同一条红绿灯色带：" +
                    "绿 → 黄 → 红，按档位插值取色。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorMode.entries.forEach { m ->
                    SelectChip(
                        text = m.label,
                        selected = colorMode == m,
                        modifier = Modifier.weight(1f),
                    ) {
                        scope.launch {
                            settings.setColorMode(m)
                            toast("变色模式：" + m.label)
                        }
                    }
                }
            }
            Text(
                colorMode.desc,
                fontSize = 12.sp,
                color = ExtColors.Primary,
            )

            when (colorMode) {
                ColorMode.Threshold -> {
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
                                    .background(ExtColors.Palette[t.paletteIndex.coerceIn(0, ExtColors.Palette.size - 1)]),
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
                                        val next = thresholds.toMutableList()
                                            .also { it.removeAt(index) }
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

                ColorMode.Interval -> {
                    // 等间隔模式盘面走 trafficStepColor（绿→黄→红），文案同步
                    Text(
                        "每 ${describeMinutes(intervalMinutes)} 换一种颜色，按红绿灯色带推进",
                        fontSize = 13.sp,
                        color = ExtColors.TextPrimary,
                    )
                    NumberWheel(
                        values = remember { (1..60).toList() },
                        selected = intervalMinutes.toInt().coerceIn(1, 60),
                        onSelect = { scope.launch { settings.setIntervalMinutes(it.toLong()) } },
                        modifier = Modifier.fillMaxWidth(),
                        suffix = " 分",
                    )
                }

                ColorMode.Fraction -> {
                    // 文案、滚轮、色带三处必须取**同一个数**，且夹取区间与
                    // SettingsRepo 读写两端、WheelTimerView.pickColor() 完全一致。
                    //
                    // ⚠️ 关于用户报过的「选中 n 显示 n-1」：
                    // 那个偏差**不在这一层**。这里三处一直用的都是 shownDivisions，
                    // 滚轮写回的值也被 setFractionDivisions 夹到同一区间。
                    // 真正的断点在 NumberWheel 的写回路径：它算出「高亮条里是第几个
                    // LazyColumn 项」时漏了顶部占位造成的 +1，又过一次 valueIndex 的 -1，
                    // 于是屏上高亮 5 却回调 onSelect(4)。详见 NumberWheel.kt 文件头。
                    // 所以这次修的只有 NumberWheel，这里保持原样（本来是对的）。
                    val shownDivisions = fractionDivisions.coerceIn(
                        SettingsRepo.MIN_FRACTION_DIVISIONS,
                        SettingsRepo.MAX_FRACTION_DIVISIONS,
                    )
                    Text(
                        "表盘按剩余比例分成 $shownDivisions 份，每份一种颜色",
                        fontSize = 13.sp,
                        color = ExtColors.TextPrimary,
                    )
                    NumberWheel(
                        values = FractionDivisionValues,
                        selected = shownDivisions,
                        onSelect = { scope.launch { settings.setFractionDivisions(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        suffix = " 份",
                    )
                    // 色带格数 = shownDivisions，与滚轮选中值同源，见 PaletteStrip 的注释
                    PaletteStrip(divisions = shownDivisions)
                }
            }
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
            ToggleRow(
                label = "屏幕常亮（计时界面不自动熄屏）",
                checked = keepScreenOn,
                onChange = {
                    scope.launch {
                        settings.setKeepScreenOn(it)
                        toast(if (it) "已开启常亮" else "已关闭常亮")
                    }
                },
            )
            ToggleRow(
                label = "后台运行（切走继续走表）",
                checked = backgroundEnabled,
                onChange = {
                    scope.launch {
                        settings.setBackgroundEnabled(it)
                        toast(if (it) "后台运行已开启" else "后台运行已关闭")
                    }
                },
            )
        }

        // ---------- 悬浮窗 ----------
                SettingsCard(title = "表盘布局") {
            Text(
                "列数决定每个表盘的大小。大屏（平板/横屏）可以拉到 4-6 列，手机建议 2-3 列。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
            Text(
                "当前 $gridColumns 列",
                fontSize = 13.sp,
                color = ExtColors.Primary,
            )
            NumberWheel(
                values = remember { (1..8).toList() },
                selected = gridColumns.coerceIn(1, 8),
                onSelect = { scope.launch { settings.setGridColumns(it) } },
                modifier = Modifier.fillMaxWidth(),
                suffix = " 列",
            )
        }

        SettingsCard(title = "悬浮窗") {
            Text(
                "在别的应用上层显示正在运行的计时器。需要系统「显示在其他应用上层」权限。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
            ToggleRow(
                label = "启用悬浮窗",
                checked = overlayEnabled,
                onChange = { want ->
                    if (want && !Settings.canDrawOverlays(context)) {
                        // 没权限 → 引导去系统页授予
                        toast("请先授予「显示在其他应用上层」权限")
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    } else {
                        scope.launch {
                            settings.setOverlayEnabled(want)
                            if (want) {
                                TimerOverlayService.start(context)
                                toast("悬浮窗已开启")
                            } else {
                                TimerOverlayService.stop(context)
                                toast("悬浮窗已关闭")
                            }
                        }
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (canOverlay) "系统权限：已授予" else "系统权限：未授予",
                    fontSize = 12.sp,
                    color = if (canOverlay) ExtColors.Primary else ExtColors.Red,
                )
                TextButton(onClick = {
                    val ok = runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    if (ok.isFailure) {
                        // 有些 ROM 没有这个 Action，退回「设置首页」让用户自己找
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        toast("本机没有直达页，请在「设置 → 应用 → 特殊权限 → 显示在其他应用上层」里手动开启")
                    }
                }) { Text("去授权") }
            }
            // 手动刷新：从系统页返回后重新读权限状态
            TextButton(onClick = {
                canOverlay = Settings.canDrawOverlays(context)
                toast(if (canOverlay) "已获得悬浮窗权限" else "仍未获得权限")
            }) { Text("刷新权限状态") }
            TextButton(onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    toast("请先授予悬浮窗权限")
                } else {
                    TimerOverlayService.start(context)
                    toast("已尝试拉起悬浮窗，若看到卡片则正常")
                }
            }) { Text("测试悬浮窗") }
        }

        AiSettingsCard(settings = settings, toast = { toast(it) })

        SettingsCard(title = "备份") {
            Text(
                "导出为 JSON，包含**全部配置**（面板、计时器、序列、警报、分组）" +
                    "**和全部归档记录**。使用系统文件选择器，不需要存储权限。",
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
            // 降级安装会清空数据 —— 这是 Android 的平台限制，不是漏了迁移。
            //
            // `AppDatabase` 里挂了 `fallbackToDestructiveMigrationOnDowngrade()`：
            // 装了**更低版本**的 APK 时 Room 无法迁移，只能删库重建，而且**不抛异常**
            // （所以上层接不住、也没法提示）。这个取舍是刻意的：只保留"降级"这一种
            // 场景的破坏性回退，缺迁移仍然会抛异常并保留数据文件。
            //
            // 用户看不到这段代码，只能靠这里提醒 —— 否则换版本后"东西全没了"且无从追查。
            Text(
                "⚠️ 安装更低版本的 APK 会清空全部数据（系统限制，无法用迁移解决）。" +
                    "换版本前请先导出一份备份。",
                fontSize = 12.sp,
                color = ExtColors.Red,
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
