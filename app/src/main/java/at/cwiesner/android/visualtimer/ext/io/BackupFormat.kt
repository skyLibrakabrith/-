package at.cwiesner.android.visualtimer.ext.io

import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备份文件根结构。
 *
 * 版本策略：`schemaVersion` 递增；导入时按版本走迁移函数。
 */
@Serializable
data class BackupFile(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val exportedAt: Long = System.currentTimeMillis(),
    val app: String = APP_TAG,
    val timers: List<TimerItemDto> = emptyList(),
    val panels: List<PanelDto> = emptyList(),
    val panelRefs: List<PanelTimerRefDto> = emptyList(),
    val sequences: List<SequenceDto> = emptyList(),
    val steps: List<StepDto> = emptyList(),
    val alertProfiles: List<AlertProfileDto> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
        const val APP_TAG = "visual-timer-extended"
    }
}

/**
 * 传输用 DTO。
 *
 * 与 Room 实体分开的原因：备份文件不应携带自增主键，
 * 导入到别的设备时主键会冲突。这里用**临时字符串 ID** 表达关联，
 * 导入时统一重映射。
 */
@Serializable
data class TimerItemDto(
    val ref: String,
    val label: String? = null,
    val durationMillis: Long,
    val alertProfileRef: String? = null,
    val position: Int = 0,
)

@Serializable
data class PanelDto(
    val ref: String,
    val name: String,
    val iconKey: String = "grid",
    val sortOrder: Int = 0,
    val timerRefs: List<String> = emptyList(),
)

@Serializable
data class PanelTimerRefDto(val panelRef: String, val timerRef: String, val order: Int = 0)

@Serializable
data class SequenceDto(
    val ref: String,
    val name: String,
    val timeoutMillis: Long = 30_000L,
    val timeoutAutoAdvance: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class StepDto(
    val ref: String,
    val sequenceRef: String,
    val durationMillis: Long,
    val label: String? = null,
    val alertProfileRef: String? = null,
    val order: Int = 0,
)

@Serializable
data class AlertProfileDto(
    val ref: String,
    val name: String,
    val vibrationPattern: List<Long> = emptyList(),
    val vibrationRepeatIndex: Int = -1,
    val screenFlash: Boolean = false,
    val torchFlash: Boolean = false,
    val soundEnabled: Boolean = false,
)

/** 序列化器配置：忽略未知字段，容忍未来版本多出的键。 */
val BackupJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---- 实体 ↔ DTO 映射 ----

fun AlertProfile.toDto(ref: String) = AlertProfileDto(
    ref = ref,
    name = name,
    vibrationPattern = vibrationPattern,
    vibrationRepeatIndex = vibrationRepeatIndex,
    screenFlash = screenFlash,
    torchFlash = torchFlash,
    soundEnabled = soundEnabled,
)

fun AlertProfileDto.toEntity() = AlertProfile(
    name = name,
    vibrationPattern = vibrationPattern,
    vibrationRepeatIndex = vibrationRepeatIndex,
    screenFlash = screenFlash,
    torchFlash = torchFlash,
    soundEnabled = soundEnabled,
)

fun TimerItem.toDto(ref: String, alertRef: String?) = TimerItemDto(
    ref = ref,
    label = label,
    durationMillis = durationMillis,
    alertProfileRef = alertRef,
    position = position,
)

fun TimerItemDto.toEntity(alertId: Long?) = TimerItem(
    label = label,
    durationMillis = durationMillis,
    alertProfileId = alertId,
    position = position,
)

fun Panel.toDto(ref: String) = PanelDto(
    ref = ref,
    name = name,
    iconKey = iconKey,
    sortOrder = sortOrder,
)

fun PanelDto.toEntity() = Panel(name = name, iconKey = iconKey, sortOrder = sortOrder)

fun StepSequence.toDto(ref: String) = SequenceDto(
    ref = ref,
    name = name,
    timeoutMillis = timeoutMillis,
    timeoutAutoAdvance = timeoutAutoAdvance,
    sortOrder = sortOrder,
)

fun SequenceDto.toEntity() = StepSequence(
    name = name,
    timeoutMillis = timeoutMillis,
    timeoutAutoAdvance = timeoutAutoAdvance,
    sortOrder = sortOrder,
)

fun Step.toDto(ref: String, seqRef: String, alertRef: String?) = StepDto(
    ref = ref,
    sequenceRef = seqRef,
    durationMillis = durationMillis,
    label = label,
    alertProfileRef = alertRef,
    order = order,
)

fun StepDto.toEntity(sequenceId: Long, alertId: Long?) = Step(
    sequenceId = sequenceId,
    durationMillis = durationMillis,
    label = label,
    alertProfileId = alertId,
    order = order,
)

fun PanelTimerRef.toDto(panelRef: String, timerRef: String) =
    PanelTimerRefDto(panelRef = panelRef, timerRef = timerRef, order = order)
