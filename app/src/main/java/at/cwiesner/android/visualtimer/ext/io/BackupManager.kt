package at.cwiesner.android.visualtimer.ext.io

import android.content.Context
import android.net.Uri
import at.cwiesner.android.visualtimer.ext.data.TimerItem
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
                    "sessions" to backup.sessions.size,
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
        val sessions = mutableListOf<TimerSessionDto>()

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

        // 归档历史：全量带走。它没有 ref、也没有外键，
        // 和计时器行是脱钩的（行删了历史还在），所以照原样一条条存。
        // 暂停区间随会话一起导出，否则导回来圆盘上的空隙全没了
        val allSessions = db.timerSessionDao().allOnce()
        val pausesBySession = if (allSessions.isEmpty()) {
            emptyMap<Long, List<at.cwiesner.android.visualtimer.ext.data.TimerPause>>()
        } else {
            runCatching { db.timerPauseDao().forSessions(allSessions.map { it.id }) }
                .getOrDefault(emptyList())
                .groupBy { it.sessionId }
        }
        allSessions.forEach { s ->
            sessions += s.toDto(
                pauses = pausesBySession[s.id].orEmpty().map { pr ->
                    PauseRangeDto(fromMillis = pr.fromMillis, toMillis = pr.toMillis)
                },
            )
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
            sessions = sessions,
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

                // 合并模式下的同名判定：先把本地已有的计时器按 label 建索引。
                //
                // 只索引**非空 label**：空名/未命名的计时器无法可靠判重
                // （用户可能有好几个"未命名"），当作不同项全部导入。
                val localByLabel: MutableMap<String, TimerItem> =
                    if (replaceExisting) {
                        mutableMapOf()
                    } else {
                        db.timerDao().observeAllOnce()
                            .filter { !it.label.isNullOrBlank() }
                            .associateBy { it.label!! }
                            .toMutableMap()
                    }

                val timerIdMap = mutableMapOf<String, Long>()
                // 冲突统计，写进结果摘要让用户知道发生了什么
                var mergedLonger = 0
                var skippedShorter = 0
                backup.timers.forEach { dto ->
                    val label = dto.label?.takeIf { it.isNotBlank() }
                    val existing = label?.let { localByLabel[it] }
                    if (existing != null) {
                        // 同名冲突：**取时长更长那个**（用户明确要求）。
                        //
                        // 为什么不是"保留本地"或"用导入覆盖"：
                        // 备份常常是旧版本，直接覆盖会把用户后来调长的时长又改短；
                        // 一律保留本地则导入的更长配置进不来。
                        // 取更长是唯一"不会丢信息"的选择。
                        if (dto.durationMillis > existing.durationMillis) {
                            db.timerDao().upsert(
                                existing.copy(durationMillis = dto.durationMillis),
                            )
                            mergedLonger++
                        } else {
                            skippedShorter++
                        }
                        // 映射到**本地那条**的 id：后续的面板引用 / 提醒 / 组
                        // 都指向已有记录，不会产生第二条同名计时器
                        timerIdMap[dto.ref] = existing.id
                    } else {
                        val newId = db.timerDao().upsert(
                            dto.toEntity(dto.alertProfileRef?.let { alertIdMap[it] }),
                        )
                        timerIdMap[dto.ref] = newId
                        if (label != null) {
                            // 同一次导入里的重名也去重
                            localByLabel[label] = dto.toEntity(null).copy(id = newId)
                        }
                    }
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
                    // 批量插入，与拆解走同一条路径。
                    //
                    // 这里原本是 `upsertStep` 循环 —— 同一个 id=0 覆盖 bug，
                    // **导入的多个阶段也只会留下最后一个**。同一个坑不能在两个地方踩。
                    val steps = backup.steps.filter { it.sequenceRef == dto.ref }
                        .sortedBy { it.order }
                        .map { st ->
                            st.toEntity(sid, st.alertProfileRef?.let { alertIdMap[it] })
                        }
                    if (steps.isNotEmpty()) db.sequenceDao().insertSteps(steps)
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

                // 归档历史：表本身是「只追加」，重复导入只会多出几份同样的历史，
                // 不会覆盖或删掉别的记录，代价可以接受。
                backup.sessions.forEach { dto ->
                    // 先插会话拿到新 id，再把暂停区间挂上去（id 会重映射）
                    val newId = db.timerSessionDao().insert(dto.toEntity())
                    dto.pauses.forEach { pr ->
                        runCatching {
                            db.timerPauseDao().insert(
                                at.cwiesner.android.visualtimer.ext.data.TimerPause(
                                    sessionId = newId,
                                    fromMillis = pr.fromMillis,
                                    // 导入的记录一律视为已完成，所以"仍在暂停中"的区间
                                    // 统一按会话结束时刻封口，否则导进来又显示成进行中
                                    toMillis = if (pr.toMillis > 0L) pr.toMillis else dto.endedAt,
                                ),
                            )
                        }
                    }
                }

                Result.Ok(
                    mapOf(
                        "timers" to backup.timers.size,
                        "panels" to backup.panels.size,
                        "sequences" to backup.sequences.size,
                        "steps" to backup.steps.size,
                        "alertProfiles" to backup.alertProfiles.size,
                        "groups" to backup.groups.size,
                        "deadlineReminders" to backup.deadlineReminders.size,
                        "sessions" to backup.sessions.size,
                        // 冲突统计：让用户知道"合并"到底做了什么，
                        // 否则同名项悄悄消失/悄悄变长都没人知道
                        "合并(取更长)" to mergedLonger,
                        "跳过(本地更长)" to skippedShorter,
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
        // 归档历史也要清：这是"覆盖导入"，用户期望的是导入后的状态**完全等于**备份。
        // 不清的话旧记录会混在新记录里，归档页看起来像"多出一堆没见过的条"。
        db.timerSessionDao().allOnce().forEach { db.timerSessionDao().deleteById(it.id) }
        // 暂停区间挂在会话下面，会话清完这些就全是孤儿 —— 必须一起清，
        // 否则下次导入时旧区间可能挂到新会话 id 上（id 会重映射）
        db.timerPauseDao().deleteOrphans()
        // 运行时快照：计时器行都删了，快照必然全成孤儿
        db.runtimeSnapshotDao().deleteOrphans()
    }
}
