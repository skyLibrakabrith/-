package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.duration.DeadlineTimePicker
import at.cwiesner.android.visualtimer.ext.ui.duration.deadlineEpochMillis
import at.cwiesner.android.visualtimer.ext.ui.duration.describeDeadline
import at.cwiesner.android.visualtimer.ext.ui.wheel.HmsWheelPicker
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView

/** 时长输入模式。 */
private enum class DurationMode(val label: String) {
    Wheel("轮盘"),
    Precise("精确"),
    Deadline("截止时刻"),

    /** 正计时：不需要设定时长，从 0 往上数。 */
    Stopwatch("正计时"),
}

/**
 * 计时器编辑对话框。
 *
 * 三种设定方式并存，用顶部三个胶囊切换：
 * - **轮盘**：拖动大圆盘设定，最直观
 * - **精确**：时/分/秒三个滚轮
 * - **截止时刻**：选定 DDL 后自动倒推时长
 */
@Composable
fun TimerEditDialog(
    alertProfiles: List<at.cwiesner.android.visualtimer.ext.data.AlertProfile> = emptyList(),
    initialLabel: String = "",
    initialDuration: Long = 60_000L,
    title: String = "新建计时器",
    onDismiss: () -> Unit,
    onConfirm: (label: String, durationMillis: Long, deadlineEpochMillis: Long?, alertProfileId: Long?, isStopwatch: Boolean) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var duration by remember { mutableLongStateOf(initialDuration) }
    var mode by remember { mutableStateOf(DurationMode.Wheel) }
    var alertProfileId by remember { mutableStateOf<Long?>(null) }
    var showAlertPicker by remember { mutableStateOf(false) }

    // DDL 模式的状态
    var dateOffset by remember { mutableIntStateOf(0) }
    var hour by remember { mutableIntStateOf(0) }
    var minute by remember { mutableIntStateOf(0) }

    val now = System.currentTimeMillis()
    val deadline = deadlineEpochMillis(dateOffset, hour, minute)
    val deadlineResult = DeadlineCalculator.remainingUntil(
        java.time.Instant.ofEpochMilli(deadline),
        java.time.Instant.ofEpochMilli(now),
    )
    val deadlineValid = deadlineResult is DeadlineCalculator.Result.Ok

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        textContentColor = ExtColors.TextSecondary,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 模式切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DurationMode.entries.forEach { m ->
                        ModeChip(
                            text = m.label,
                            selected = mode == m,
                            modifier = Modifier.weight(1f),
                        ) { mode = m }
                    }
                }

                // 警报配置
                val currentProfile = alertProfiles.firstOrNull { it.id == alertProfileId }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ExtColors.Surface)
                        .clickable { showAlertPicker = true }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = if (currentProfile != null) {
                            "提醒：" + currentProfile.name
                        } else {
                            "提醒：默认（点击选择）"
                        },
                        fontSize = 13.sp,
                        color = ExtColors.TextSecondary,
                    )
                }

                when (mode) {
                    DurationMode.Wheel -> {
                        PieTimerView(
                            totalMillis = duration,
                            remainingMillis = duration,
                            running = false,
                            onDurationChange = { duration = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxMinutes = 60,
                            stepMinutes = 0.5f,
                        )
                    }

                    DurationMode.Precise -> {
                        HmsWheelPicker(
                            totalMillis = duration,
                            onTotalChange = { if (it > 0) duration = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    DurationMode.Stopwatch -> {
                        Text(
                            "从 0 开始往上计时，用于记录已经过了多久。不会到点，也没有提醒。",
                            fontSize = 13.sp,
                            color = ExtColors.TextSecondary,
                        )
                    }

                    DurationMode.Deadline -> {
                        DeadlineTimePicker(
                            dateOffsetDays = dateOffset,
                            hour = hour,
                            minute = minute,
                            onDateChange = { dateOffset = it },
                            onHourChange = { hour = it },
                            onMinuteChange = { minute = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = if (deadlineValid) {
                                "目标 " + describeDeadline(dateOffset, hour, minute) +
                                    " · 剩余 " + DeadlineCalculator.formatRemaining(
                                        (deadlineResult as DeadlineCalculator.Result.Ok)
                                            .remainingMillis,
                                    )
                            } else {
                                "目标时刻已过，请重新选择"
                            },
                            fontSize = 13.sp,
                            color = if (deadlineValid) ExtColors.Primary else ExtColors.Red,
                        )
                    }
                }

                // 底部汇总
                Text(
                    text = when (mode) {
                        DurationMode.Stopwatch -> "正计时"
                        DurationMode.Deadline ->
                            if (deadlineValid) "倒计时 " + DeadlineCalculator.formatRemaining(
                                (deadlineResult as DeadlineCalculator.Result.Ok).remainingMillis,
                            ) else "—"
                        else -> "时长 " + DeadlineCalculator.formatRemaining(duration)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ExtColors.TextPrimary,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode != DurationMode.Deadline || deadlineValid,
                onClick = {
                    when (mode) {
                        DurationMode.Deadline -> {
                            val remain = (deadlineResult as DeadlineCalculator.Result.Ok)
                                .remainingMillis
                            onConfirm(label.trim(), remain, deadline, alertProfileId, false)
                        }
                        DurationMode.Stopwatch ->
                            onConfirm(label.trim(), 0L, null, alertProfileId, true)
                        else -> onConfirm(label.trim(), duration, null, alertProfileId, false)
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showAlertPicker) {
        AlertProfilePickerDialog(
            profiles = alertProfiles,
            selectedId = alertProfileId,
            onDismiss = { showAlertPicker = false },
            onSelect = { alertProfileId = it },
        )
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ExtColors.PrimaryDim else ExtColors.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) ExtColors.TextPrimary else ExtColors.TextSecondary,
        )
    }
}

/** 新建 / 重命名面板。 */
@Composable
fun PanelNameDialog(
    initialName: String = "",
    title: String = "新建面板",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("面板名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** DDL 倒推对话框（独立入口，供菜单直接调用）。 */
@Composable
fun DeadlineDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, deadlineEpochMillis: Long) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var dateOffset by remember { mutableIntStateOf(0) }
    var hour by remember { mutableIntStateOf(0) }
    var minute by remember { mutableIntStateOf(0) }

    val now = System.currentTimeMillis()
    val deadline = deadlineEpochMillis(dateOffset, hour, minute)
    val result = DeadlineCalculator.remainingUntil(
        java.time.Instant.ofEpochMilli(deadline),
        java.time.Instant.ofEpochMilli(now),
    )
    val valid = result is DeadlineCalculator.Result.Ok

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("倒计时到某个时刻") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DeadlineTimePicker(
                    dateOffsetDays = dateOffset,
                    hour = hour,
                    minute = minute,
                    onDateChange = { dateOffset = it },
                    onHourChange = { hour = it },
                    onMinuteChange = { minute = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (valid) {
                        "剩余 " + DeadlineCalculator.formatRemaining(
                            (result as DeadlineCalculator.Result.Ok).remainingMillis,
                        ) + " · 目标 " + describeDeadline(dateOffset, hour, minute)
                    } else {
                        "目标时刻已过"
                    },
                    fontSize = 13.sp,
                    color = if (valid) ExtColors.Primary else ExtColors.Red,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(label.trim(), deadline) },
            ) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 警报配置选择器。
 *
 * 列出所有提醒配置，每条用波形条展示节奏 —— 用户不需要记「交响乐/心跳」
 * 这类名字，看图形就能分辨。
 */
@Composable
fun AlertProfilePickerDialog(
    profiles: List<at.cwiesner.android.visualtimer.ext.data.AlertProfile>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExtColors.PureBlack,
        titleContentColor = ExtColors.TextPrimary,
        title = { Text("选择提醒方式") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 默认项
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selectedId == null) ExtColors.PrimaryDim else ExtColors.Surface,
                        )
                        .clickable { onSelect(null); onDismiss() }
                        .padding(12.dp),
                ) {
                    Text(
                        "默认（用第一个配置）",
                        fontSize = 13.sp,
                        color = ExtColors.TextPrimary,
                    )
                }

                profiles.forEach { profile ->
                    val selected = profile.id == selectedId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) ExtColors.PrimaryDim else ExtColors.Surface)
                            .clickable { onSelect(profile.id); onDismiss() }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            profile.name,
                            fontSize = 13.sp,
                            color = ExtColors.TextPrimary,
                        )
                        at.cwiesner.android.visualtimer.ext.ui.alerts.VibrationWaveform(
                            pattern = profile.vibrationPattern,
                            modifier = Modifier.fillMaxWidth(),
                            height = 20.dp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}