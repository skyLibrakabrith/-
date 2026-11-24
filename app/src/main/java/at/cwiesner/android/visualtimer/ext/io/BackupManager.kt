package at.cwiesner.android.visualtimer.ext.io

import android.content.Context
import android.net.Uri
import at.cwiesner.android.visualtimer.ext.data.AppDatabase
import at.cwiesner.android.visualtimer.ext.data.DeadlineReminder
import at.cwiesner.android.visualtimer.ext.data.GroupMember
import at.cwiesner.android.visualtimer.ext.data.PanelTimerRef
import at.cwiesner.android.visualtimer.ext.data.TimerGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入 / 导出。
 *
 * **为什么用 SAF 而不是 WRITE_EXTERNAL_STORAGE**：
 * Android 10+ 的分区存储下，向公共目录写文件正逐步需要用户显式授权，
 * 而 SAF（Storage Access Framework）由系统弹出文件选择器，
 * 用户授权的是**具体文件**而非整个存储 → 不需要任何存储权限。
 *
 * 这与本项目「不加存储权限」的设计一致。
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
) {

    sealed interface Result {
        data class Ok(val counts: Map<String, Int>) : Result
        data class Failed(val message: String) : Result
    }

    // ---------- 导出 ----------

    suspend fun export(uri: Uri): Result = withContext(Dispatchers.IO) {
        runCatching {
            val backup = buildBackup()
            val text = BackupJson.encodeToString(BackupFile.serializer(), backup)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return@runCatching Result.Failed("无法打开输出流")
            Result.Ok(
                mapOf(
                    "timers" to backup.timers.size,
                    "panels" to backup.panels.size,
                    "sequences" to backup.sequences.size,
                    "steps" to backup.steps.size,
                    "alertProfiles" to backup.alertProfiles.size,
                    "groups" to backup.groups.size,
                    "deadlineReminders" to backup.deadlineReminders.size,
                ),
            )
        }.getOrElse { Result.Failed(it.message ?: it::class.java.simpleName) }
    }

    private suspend fun buildBackup(): BackupFile {
        val timers = mutableListOf<TimerItemDto>()
        val panels = mutableListOf<PanelDto>()
        val refs = mutableListOf<PanelTimerRefDto>()
        val seqs = mutableListOf<SequenceDto>()
        val steps = mutableListOf<StepDto>()
        val alerts = mutableListOf<AlertProfileDto>()
        val groups = mutableListOf<TimerGroupDto>()
        val reminders = mutableListOf<DeadlineReminderDto>()

        // 警报配置：主键即 ref
        db.alertProfileDao().observeAllOnce().forEach { p ->
            alerts += p.toDto("a${p.id}")
        }

        // 计时器
        db.timerDao().observeAllOnce().forEach { t ->
            timers += t.toDto("t${t.id}", t.alertProfileId?.let { "a$it" })
        }

        // 面板 + 关联
        db.panelDao().observeAllOnce().forEach { p ->
            val panelRef = "p${p.id}"
            val panelRefs = db.panelDao().refsOf(p.id)
            panels += p.toDto(panelRef).copy(
                timerRefs = panelRefs.map { "t${it.timerId}" },
            )
            panelRefs.forEach { refs += it.toDto(panelRef, "t${it.timerId}") }
        }

        // 序列 + 步骤
        db.sequenceDao().observeAllOnce().forEach { s ->
            val seqRef = "s${s.id}"
            seqs += s.toDto(seqRef)
            db.sequenceDao().steps(s.id).forEach { st ->
                steps += st.toDto("st${st.id}", seqRef, st.alertProfileId?.let { "a$it" })
            }
        }

        // 组 + 成员（成员顺序照搬，导出后组的构成不会散）
        val membersByGroup = db.groupDao().allMembersOnce().groupBy { it.groupId }
        db.groupDao().allGroupsOnce().forEach { g ->
            val memberRefs = membersByGroup[g.id].orEmpty()
                .sortedBy { it.order }
                .map { "t${it.timerId}" }
            groups += g.toDto("g${g.id}", memberRefs)
        }

        // DDL 提前提醒
        db.deadlineReminderDao().allOnce().forEach { r ->
            reminders += r.toDto("t${r.timerId}")
        }

        return BackupFile(
            timers = timers,
            panels = panels,
            panelRefs = refs,
            sequences = seqs,
            steps = steps,
            alertProfiles = alerts,
            groups = groups,
            deadlineReminders = reminders,
        )
    }

    // ---------- 导入 ----------

    /**
     * @param replaceExisting true 时先清空现有数据再导入；false 时追加（主键重映射）
     */
    suspend fun import(uri: Uri, replaceExisting: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@runCatching Result.Failed("无法读取文件")

                val backup = BackupJson.decodeFromString(BackupFile.serializer(), text)

                if (backup.schemaVersion > BackupFile.CURRENT_SCHEMA) {
                    return@runCatching Result.Failed(
                        "备份版本 ${backup.schemaVersion} 高于当前支持的 ${BackupFile.CURRENT_SCHEMA}",
                    )
                }

                if (replaceExisting) wipeAll()

                // 警报：ref → 新 id
                val alertIdMap = mutableMapOf<String, Long>()
                backup.alertProfiles.forEach { dto ->
                    alertIdMap[dto.ref] = db.alertProfileDao().upsert(dto.toEntity())
                }

                val timerIdMap = mutableMapOf<String, Long>()
                backup.timers.forEach { dto ->
                    timerIdMap[dto.ref] = db.timerDao().upsert(
                        dto.toEntity(dto.alertProfileRef?.let { alertIdMap[it] }),
                    )
                }

                // 面板：先全部落库拿到新 id，再写关联，
                // 这样序列 / 组才能按 panelRef 重新绑定到新面板
                val panelIdMap = mutableMapOf<String, Long>()
                backup.panels.forEach { dto ->
                    panelIdMap[dto.ref] = db.panelDao().upsert(dto.toEntity())
                }
                backup.panels.forEach { dto ->
                    val pid = panelIdMap.getValue(dto.ref)
                    val refList = dto.timerRefs.mapIndexedNotNull { idx, tr ->
                        timerIdMap[tr]?.let { tid ->
                            PanelTimerRef(panelId = pid, timerId = tid, order = idx)
                        }
                    }
                    if (refList.isNotEmpty()) db.panelDao().upsertRefs(refList)
                }

                backup.sequences.forEach { dto ->
                    val sid = db.sequenceDao().upsert(
                        dto.toEntity(dto.panelRef?.let { panelIdMap[it] }),
                    )
                    backup.steps.filter { it.sequenceRef == dto.ref }
                        .sortedBy { it.order }
                        .forEach { st ->
                            db.sequenceDao().upsertStep(
                                st.toEntity(sid, st.alertProfileRef?.let { alertIdMap[it] }),
                            )
                        }
                }

                // 组：面板与计时器都落库之后才能建，否则拿不到新 id
                backup.groups.forEach { dto ->
                    val pid = dto.panelRef?.let { panelIdMap[it] } ?: return@forEach
                    val gid = db.groupDao().upsertGroup(
                        TimerGroup(
                            panelId = pid,
                            name = dto.name,
                            order = dto.order,
                        ),
                    )
                    val members = dto.memberRefs.mapIndexedNotNull { idx, tr ->
                        timerIdMap[tr]?.let { tid ->
                            GroupMember(gid, tid, idx)
                        }
                    }
                    if (members.isNotEmpty()) db.groupDao().upsertMembers(members)
                }

                // DDL 提前提醒
                val reminders = backup.deadlineReminders.mapNotNull { dto ->
                    timerIdMap[dto.timerRef]?.let { tid ->
                        DeadlineReminder(
                            timerId = tid,
                            leadMinutes = dto.leadMinutes,
                            fired = dto.fired,
                        )
                    }
                }
                if (reminders.isNotEmpty()) db.deadlineReminderDao().upsertAll(reminders)

                Result.Ok(
                    mapOf(
                        "timers" to backup.timers.size,
                        "panels" to backup.panels.size,
                        "sequences" to backup.sequences.size,
                        "steps" to backup.steps.size,
                        "alertProfiles" to backup.alertProfiles.size,
                        "groups" to backup.groups.size,
                        "deadlineReminders" to backup.deadlineReminders.size,
                    ),
                )
            }.getOrElse { Result.Failed(it.message ?: it::class.java.simpleName) }
        }

    private suspend fun wipeAll() {
        // 顺序要紧：先清依赖方（提醒 / 组成员 / 面板关联），再清被依赖的实体，
        // 否则会留下一堆指向已删计时器的孤儿行
        db.deadlineReminderDao().allOnce()
            .map { it.timerId }
            .distinct()
            .forEach { db.deadlineReminderDao().clearTimer(it) }
        db.groupDao().allGroupsOnce().forEach {
            db.groupDao().clearMembers(it.id)
            db.groupDao().deleteGroup(it.id)
        }
        db.timerDao().observeAllOnce().forEach { db.timerDao().deleteById(it.id) }
        db.panelDao().observeAllOnce().forEach { db.panelDao().deleteById(it.id) }
        db.sequenceDao().observeAllOnce().forEach { db.sequenceDao().deleteById(it.id) }
        db.alertProfileDao().observeAllOnce().forEach { db.alertProfileDao().deleteById(it.id) }
    }
}
