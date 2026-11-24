package at.cwiesner.android.visualtimer.ext.io

import at.cwiesner.android.visualtimer.ext.data.AlertProfile
import at.cwiesner.android.visualtimer.ext.data.DeadlineReminder
import at.cwiesner.android.visualtimer.ext.data.Panel
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.data.TimerKind
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
    /** 面板内的组。v2 起导出，分组关系不再丢失。 */
    val groups: List<TimerGroupDto> = emptyList(),
    /** DDL 的提前提醒档位。v2 起导出。 */
    val deadlineReminders: List<DeadlineReminderDto> = emptyList(),
) {
    companion object {
        /**
         * v1 → v2：补齐组、DDL 提前提醒，以及计时器的类型 / 截止时刻 / 锁定状态，
         * 序列补上所属面板。
         *
         * 新字段全部带默认值，所以 **v1 的旧备份文件仍然能导入**，
         * 只是那部分信息本来就缺失，导入后按默认值处理。
         */
        const val CURRENT_SCHEMA = 2
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
    /** [TimerKind] 的枚举名。缺省按 Countdown 处理，兼容 v1 备份。 */
    val kind: String = "Countdown",
    val deadlineEpochMillis: Long? = null,
    val locked: Boolean = false,
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
    /** 所属面板的 ref；null 表示独立序列。导入时按新分配的面板 id 重新绑定。 */
    val panelRef: String? = null,
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

/**
 * 面板内的组。
 *
 * 成员用 timerRef 表达而不是原始 id：导入时主键会重映射，
 * 只有 ref 才有跨库的意义。
 */
@Serializable
data class TimerGroupDto(
    val ref: String,
    val name: String,
    val panelRef: String? = null,
    val order: Int = 0,
    val memberRefs: List<String> = emptyList(),
)

/** DDL 的提前提醒档位。 */
@Serializable
data class DeadlineReminderDto(
    val timerRef: String,
    val leadMinutes: Long,
    /**
     * 是否已经触发过。
     *
     * 导出时就原样带上：一个「提前 30 分钟」的档位如果已经响过，
     * 导入后不该再响一次。
     */
    val fired: Boolean = false,
)

/** 序列化器配置：忽略未知字段，容忍未来版本多出的键。 */val BackupJson: Json = Json {
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
    kind = kind.name,
    deadlineEpochMillis = deadlineEpochMillis,
    locked = locked,
)

fun TimerItemDto.toEntity(alertId: Long?) = TimerItem(
    label = label,
    durationMillis = durationMillis,
    alertProfileId = alertId,
    position = position,
    kind = TimerKind.fromKey(kind),
    deadlineEpochMillis = deadlineEpochMillis,
    locked = locked,
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
    panelRef = panelId?.let { "p$it" },
)

fun SequenceDto.toEntity(panelId: Long?) = StepSequence(
    name = name,
    timeoutMillis = timeoutMillis,
    timeoutAutoAdvance = timeoutAutoAdvance,
    sortOrder = sortOrder,
    panelId = panelId,
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

fun TimerGroup.toDto(ref: String, memberRefs: List<String>) = TimerGroupDto(
    ref = ref,
    name = name,
    panelRef = "p$panelId",
    order = order,
    memberRefs = memberRefs,
)

fun DeadlineReminder.toDto(timerRef: String) = DeadlineReminderDto(
    timerRef = timerRef,
    leadMinutes = leadMinutes,
    fired = fired,
)
