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
import at.cwiesner.android.visualtimer.ext.data.TimerSession
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
    /**
     * 计时会话的归档历史。v3 起导出。
     *
     * 历史**没有** `ref`：它不被别的实体引用，导入时也不需要重映射，
     * 直接按行追加即可。
     */
    val sessions: List<TimerSessionDto> = emptyList(),
) {
    companion object {
        /**
         * v1 → v2：补齐组、DDL 提前提醒，以及计时器的类型 / 截止时刻 / 锁定状态，
         * 序列补上所属面板。
         *
         * v2 → v3：计时器补 `pinned`（是否固定在面板上），并新增归档历史 [sessions]；
         * 历史里的 `kind` 存字符串，导入按原样回写。
         *
         * 新字段全部带默认值，所以 **v1 / v2 的旧备份文件仍然能导入**，
         * 只是那部分信息本来就缺失，导入后按默认值处理。
         */
        const val CURRENT_SCHEMA = 3
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
    /**
     * 是否固定在面板上。v3 起导出。
     *
     * 默认 true 与实体一致：v1 / v2 的旧备份没有这个字段，
     * 导入后一律视为已固定，不会被当成「到点就自动移除」。
     */
    val pinned: Boolean = true,
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
    /**
     * 困难程度 1–5；null = 没标注。
     *
     * **带默认值**是硬要求：schema v2 的旧备份没有这个字段，
     * 靠默认值才能继续导入（导入逻辑里 `.copy()` 也会保留它）。
     */
    val difficulty: Int? = null,
    /**
     * 背景/理由。同样**带默认值** —— 旧备份没有这个字段也要能导入。
     */
    val context: String? = null,
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

/**
 * 一次计时会话的归档记录。
 *
 * 没有 `ref`：历史不被别的实体引用，导入时也不需要主键重映射，直接按行追加。
 * `kind` 用字符串而不是 [TimerKind] 枚举，原因同实体 [TimerSession]：
 * 归档是历史数据，不该被将来的枚举变更牵连。
 */
@Serializable
data class TimerSessionDto(
    val label: String? = null,
    val startedAt: Long,
    val endedAt: Long,
    val plannedMillis: Long,
    val kind: String = "Countdown",
    val completed: Boolean = false,
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
    kind = kind.name,
    deadlineEpochMillis = deadlineEpochMillis,
    locked = locked,
    // 导出漏这一行 → 导入后 pin 状态静默丢光（面板上被固定的全变成「到点自动移除」）
    pinned = pinned,
)

fun TimerItemDto.toEntity(alertId: Long?) = TimerItem(
    label = label,
    durationMillis = durationMillis,
    alertProfileId = alertId,
    position = position,
    kind = TimerKind.fromKey(kind),
    deadlineEpochMillis = deadlineEpochMillis,
    locked = locked,
    // 导入漏这一行 → 同上：v3 备份里的 pin 状态读进来又被默认值 true 覆盖
    pinned = pinned,
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
    difficulty = difficulty,
    context = context,
)

fun StepDto.toEntity(sequenceId: Long, alertId: Long?) = Step(
    sequenceId = sequenceId,
    durationMillis = durationMillis,
    label = label,
    alertProfileId = alertId,
    order = order,
    // 旧备份没有这些字段 -> 默认 null（未标注），不会报错
    difficulty = difficulty?.coerceIn(1, 5),
    context = context?.takeIf { it.isNotBlank() },
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

fun TimerSession.toDto() = TimerSessionDto(
    label = label,
    startedAt = startedAt,
    endedAt = endedAt,
    plannedMillis = plannedMillis,
    kind = kind,
    completed = completed,
)

/**
 * 历史归档导入时**原样回写**（不自增 id，主键交给数据库重新分配）。
 *
 * `kind` 是字符串，这里不做 [TimerKind.fromKey] 这类归一化：
 * 归一化会把「将来才认识的值」静默改写成 Countdown，历史就不是事实了。
 */
fun TimerSessionDto.toEntity() = TimerSession(
    label = label,
    startedAt = startedAt,
    endedAt = endedAt,
    plannedMillis = plannedMillis,
    kind = kind,
    completed = completed,
)
