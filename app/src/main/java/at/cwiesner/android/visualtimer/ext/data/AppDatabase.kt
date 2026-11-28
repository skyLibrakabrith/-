package at.cwiesner.android.visualtimer.ext.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<TimerItem>>

    @Query("SELECT * FROM timers ORDER BY position ASC, id ASC")
    suspend fun observeAllOnce(): List<TimerItem>

    @Query("SELECT * FROM timers WHERE id = :id")
    fun observe(id: Long): Flow<TimerItem?>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun get(id: Long): TimerItem?

    @Upsert
    suspend fun upsert(item: TimerItem): Long

    @Delete
    suspend fun delete(item: TimerItem)

    @Query("DELETE FROM timers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PanelDao {
    @Query("SELECT * FROM panels ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<Panel>>

    @Query("SELECT * FROM panels ORDER BY sortOrder ASC, id ASC")
    suspend fun observeAllOnce(): List<Panel>

    @Upsert
    suspend fun upsert(panel: Panel): Long

    @Query("DELETE FROM panels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM panel_timers WHERE panelId = :panelId ORDER BY \"order\" ASC")
    fun observeRefs(panelId: Long): Flow<List<PanelTimerRef>>

    /** 全量关联：按组总览时一次拿齐，避免逐面板查询。 */
    @Query("SELECT * FROM panel_timers ORDER BY panelId ASC, \"order\" ASC")
    fun observeAllRefs(): Flow<List<PanelTimerRef>>

    @Upsert
    suspend fun upsertRefs(refs: List<PanelTimerRef>)

    @Query("DELETE FROM panel_timers WHERE panelId = :panelId AND timerId = :timerId")
    suspend fun removeRef(panelId: Long, timerId: Long)

    @Query("SELECT t.* FROM timers t INNER JOIN panel_timers r ON t.id = r.timerId WHERE r.panelId = :panelId ORDER BY r.\"order\" ASC")
    fun observePanelTimers(panelId: Long): Flow<List<TimerItem>>

    @Transaction
    @Query("SELECT * FROM panel_timers WHERE panelId = :panelId")
    suspend fun refsOf(panelId: Long): List<PanelTimerRef>
}

@Dao
interface SequenceDao {
    @Query("SELECT * FROM sequences ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<StepSequence>>

    @Query("SELECT * FROM sequences WHERE panelId = :panelId ORDER BY sortOrder ASC, id ASC")
    fun observeByPanel(panelId: Long): Flow<List<StepSequence>>

    @Query("SELECT * FROM sequences WHERE panelId IS NULL ORDER BY sortOrder ASC, id ASC")
    fun observeUnassigned(): Flow<List<StepSequence>>

    @Query("SELECT * FROM sequences ORDER BY sortOrder ASC, id ASC")
    suspend fun observeAllOnce(): List<StepSequence>

    @Query("SELECT * FROM sequences WHERE id = :id")
    suspend fun get(id: Long): StepSequence?

    @Upsert
    suspend fun upsert(seq: StepSequence): Long

    @Query("DELETE FROM sequences WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM steps WHERE sequenceId = :seqId ORDER BY \"order\" ASC")
    fun observeSteps(seqId: Long): Flow<List<Step>>

    @Query("SELECT * FROM steps WHERE sequenceId = :seqId ORDER BY \"order\" ASC")
    suspend fun steps(seqId: Long): List<Step>

    @Upsert
    suspend fun upsertStep(step: Step): Long

    /**
     * 批量插入新步骤，返回各自的自增 id。
     *
     * ## 为什么不能用 `upsertStep` 循环
     *
     * `Step.id` 是 `@PrimaryKey(autoGenerate = true)`，构造时**默认 0**。
     * `@Upsert` 对 id=0 的处理是"插入"，但连续多次传入 id=0 时，
     * Room 生成的是 `INSERT OR REPLACE` 语义 —— 后一条**覆盖**前一条，
     * 最终表里只剩下最后一个步骤。
     *
     * 用户看到的现象：「拆解应用以后只显示一个阶段」。
     *
     * 用 `@Insert` 批量插入时，Room 走真正的自增路径，每行拿到不同的 id，
     * 不会互相覆盖。而且是一个事务，比 N 次单条插入快得多。
     *
     * @return 新插入行各自的 id，顺序与入参一致
     */
    @Insert
    suspend fun insertSteps(steps: List<Step>): List<Long>

    @Update
    suspend fun updateSteps(steps: List<Step>)

    @Query("DELETE FROM steps WHERE id = :id")
    suspend fun deleteStep(id: Long)

    /**
     * 删除「孤儿步骤」：`sequenceId` 指向的序列已经不存在的那些行。
     *
     * ## 为什么需要
     *
     * 历史上有过一个 bug：保存序列时把 `@Upsert` 的返回值当成新 id 去写步骤，
     * 而 **Room 的 `@Upsert` 在「更新已有行」的分支返回 -1**（这是它的既定行为）。
     * 于是编辑已有序列时，所有步骤都被写成 `sequenceId = -1` ——
     * 谁都找不到它们，界面上表现为「序列里没有阶段」，而且还占着库。
     *
     * 代码已经修好，但**已经写坏的数据得清掉**，否则它们会一直留在库里、
     * 被导出进备份、跟着备份漂到别的设备上。
     *
     * 用一条 SQL 做集合运算，不要 `forEach { deleteById }`：步骤可能很多，
     * 逐条删既慢又可能出现「删到一半失败」的半坏状态。
     *
     * @return 删掉的行数（用于日志）
     */
    @Query("DELETE FROM steps WHERE sequenceId NOT IN (SELECT id FROM sequences)")
    suspend fun deleteOrphanSteps(): Int
}

/**
 * 保存序列并返回**可信的** id。
 *
 * ## 为什么不直接用 `upsert` 的返回值
 *
 * Room 的 `@Upsert` 有两套分支：
 * - **插入新行**（id = 0）→ 返回新生成的自增 id，可信
 * - **更新已有行**（id ≠ 0）→ 返回 **-1**
 *
 * -1 不是错误，是 Room 表示"这次做的是更新"的方式。但把它当外键写进
 * `steps.sequenceId`，就会产生一批指向 `sequenceId = -1` 的孤儿步骤 ——
 * 静默的数据损坏，界面上只表现为"阶段不见了"。
 *
 * 所以：已有实体（id ≠ 0）直接用它自己的 id，只有新建时才信 `upsert` 的返回值。
 */
internal fun resolveSavedSequenceId(entityId: Long, returnedId: Long): Long =
    if (entityId != 0L) entityId else returnedId

suspend fun SequenceDao.saveAndGetId(seq: StepSequence): Long =
    resolveSavedSequenceId(seq.id, upsert(seq))

@Dao
interface RuntimeSnapshotDao {
    @Query("SELECT * FROM runtime_snapshots")
    suspend fun allOnce(): List<RuntimeSnapshot>

    @Query("SELECT * FROM runtime_snapshots WHERE timerId = :timerId")
    suspend fun byId(timerId: Long): RuntimeSnapshot?

    @Upsert
    suspend fun upsert(snapshot: RuntimeSnapshot)

    @Query("DELETE FROM runtime_snapshots WHERE timerId = :timerId")
    suspend fun deleteById(timerId: Long)

    /** 清掉指向已删计时器的快照（启动时跑一次，见 ExtApplication 的恢复流程）。 */
    @Query("DELETE FROM runtime_snapshots WHERE timerId NOT IN (SELECT id FROM timers)")
    suspend fun deleteOrphans(): Int
}

@Dao
interface AlertProfileDao {
    @Query("SELECT * FROM alert_profiles ORDER BY id ASC")
    fun observeAll(): Flow<List<AlertProfile>>

    @Query("SELECT * FROM alert_profiles ORDER BY id ASC")
    suspend fun observeAllOnce(): List<AlertProfile>

    @Query("SELECT * FROM alert_profiles WHERE id = :id")
    suspend fun get(id: Long): AlertProfile?

    @Upsert
    suspend fun upsert(profile: AlertProfile): Long

    @Query("DELETE FROM alert_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM alert_profiles")
    suspend fun count(): Int
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM timer_groups WHERE panelId = :panelId ORDER BY \"order\" ASC, id ASC")
    fun observeGroups(panelId: Long): Flow<List<TimerGroup>>

    @Query("SELECT * FROM timer_groups")
    fun observeAllGroups(): Flow<List<TimerGroup>>

    /** 导出用的一次性快照（Flow 版拿不到当下的值）。 */
    @Query("SELECT * FROM timer_groups")
    suspend fun allGroupsOnce(): List<TimerGroup>

    @Upsert
    suspend fun upsertGroup(group: TimerGroup): Long

    @Query("DELETE FROM timer_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY \"order\" ASC")
    suspend fun members(groupId: Long): List<GroupMember>

    @Query("SELECT * FROM group_members")
    fun observeAllMembers(): Flow<List<GroupMember>>

    /** 导出用的一次性快照。 */
    @Query("SELECT * FROM group_members")
    suspend fun allMembersOnce(): List<GroupMember>

    @Upsert
    suspend fun upsertMembers(members: List<GroupMember>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: Long)

    @Query("SELECT t.* FROM timers t INNER JOIN group_members m ON t.id = m.timerId WHERE m.groupId = :groupId ORDER BY m.\"order\" ASC")
    suspend fun memberTimers(groupId: Long): List<TimerItem>
}

@Dao
interface DeadlineReminderDao {
    @Query("SELECT * FROM deadline_reminders WHERE timerId = :timerId")
    suspend fun forTimer(timerId: Long): List<DeadlineReminder>

    @Query("SELECT * FROM deadline_reminders WHERE fired = 0")
    suspend fun pending(): List<DeadlineReminder>

    /** 导出用的一次性快照。 */
    @Query("SELECT * FROM deadline_reminders")
    suspend fun allOnce(): List<DeadlineReminder>

    @Upsert
    suspend fun upsertAll(items: List<DeadlineReminder>)

    @Query("DELETE FROM deadline_reminders WHERE timerId = :timerId")
    suspend fun clearTimer(timerId: Long)

    @Query("UPDATE deadline_reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)
}

/**
 * 归档历史（[TimerSession]）。
 *
 * 只有追加和按范围读：历史是既成事实，不提供改。
 * 「某一天的记录」按 `startedAt` 的时间窗口取，
 * 所以范围查询统一用左闭右开 [from, to)，跨天时按天切窗口不会重复也不会漏。
 */
@Dao
interface TimerSessionDao {
    @Insert
    suspend fun insert(s: TimerSession): Long

    /** 导出用的一次性快照：Flow 版拿不到当下的值。 */
    @Query("SELECT * FROM timer_sessions ORDER BY startedAt ASC, id ASC")
    suspend fun allOnce(): List<TimerSession>

    @Query("SELECT * FROM timer_sessions WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<TimerSession>>

    @Query("SELECT * FROM timer_sessions WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt ASC")
    suspend fun between(from: Long, to: Long): List<TimerSession>

    /** 最早一条记录的时刻；表为空时返回 null（历史页据此决定「还能往回翻到哪」）。 */
    @Query("SELECT MIN(startedAt) FROM timer_sessions")
    suspend fun earliest(): Long?

    @Query("DELETE FROM timer_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ---------------- 「进行中」会话：暂停时占位，结束时原地更新 ----------------
    //
    // 为什么是 update 而不是"删掉重插"：会话行一旦换了 id，挂在它下面的
    // timer_pauses 就变成孤儿。原地更新能保证暂停区间始终跟着同一条记录。

    /** 把一条进行中的会话补成完整记录。 */
    @Query(
        "UPDATE timer_sessions SET endedAt = :endedAt, plannedMillis = :plannedMillis, " +
            "completed = :completed, ongoing = 0 WHERE id = :id",
    )
    suspend fun finishOngoing(id: Long, endedAt: Long, plannedMillis: Long, completed: Boolean)

    /** 更新进行中会话的 `endedAt`（每次暂停/恢复都要把区间推进）。 */
    @Query("UPDATE timer_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun touchOngoing(id: Long, endedAt: Long)

    /** 启动时清理：进程被杀留下的进行中记录一律收尾，否则归档页会一直挂着它。 */
    @Query("SELECT * FROM timer_sessions WHERE ongoing = 1")
    suspend fun allOngoing(): List<TimerSession>

    @Query(
        "SELECT * FROM timer_sessions WHERE startedAt >= :from AND startedAt < :to AND ongoing = 0 " +
            "ORDER BY startedAt ASC",
    )
    suspend fun betweenFinished(from: Long, to: Long): List<TimerSession>
}

/** 暂停区间。与 `TimerSession` 一对一挂靠（多对一），按开始时刻排序。 */
@Dao
interface TimerPauseDao {
    @Insert
    suspend fun insert(p: TimerPause): Long

    /** 封口：把「仍在暂停中」（`toMillis = 0`）的那条补上结束时刻。 */
    @Query("UPDATE timer_pauses SET toMillis = :toMillis WHERE id = :id")
    suspend fun close(id: Long, toMillis: Long)

    /** 某条会话当前还在进行中的那段暂停（最多一条）。 */
    @Query("SELECT * FROM timer_pauses WHERE sessionId = :sessionId AND toMillis = 0 LIMIT 1")
    suspend fun openPause(sessionId: Long): TimerPause?

    @Query("SELECT * FROM timer_pauses WHERE sessionId IN (:sessionIds) ORDER BY fromMillis ASC")
    suspend fun forSessions(sessionIds: List<Long>): List<TimerPause>

    @Query("DELETE FROM timer_pauses WHERE sessionId NOT IN (SELECT id FROM timer_sessions)")
    suspend fun deleteOrphans()

    @Query("DELETE FROM timer_pauses WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM timer_pauses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@androidx.room.TypeConverters(Converters::class, TimerKindConverter::class)
@Database(
    entities = [
        TimerItem::class,
        Panel::class,
        PanelTimerRef::class,
        Step::class,
        StepSequence::class,
        AlertProfile::class,
        TimerGroup::class,
        GroupMember::class,
        DeadlineReminder::class,
        TimerSession::class,
        RuntimeSnapshot::class,
        TimerPause::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timerDao(): TimerDao
    abstract fun panelDao(): PanelDao
    abstract fun sequenceDao(): SequenceDao
    abstract fun alertProfileDao(): AlertProfileDao
    abstract fun groupDao(): GroupDao
    abstract fun deadlineReminderDao(): DeadlineReminderDao
    abstract fun timerSessionDao(): TimerSessionDao

    abstract fun timerPauseDao(): TimerPauseDao

    abstract fun runtimeSnapshotDao(): RuntimeSnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2：计时器新增类型与截止时刻两列。
         * 用加法迁移而非重建表，保住用户已有的计时器数据。
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timers ADD COLUMN kind TEXT NOT NULL DEFAULT 'Countdown'")
                db.execSQL("ALTER TABLE timers ADD COLUMN deadlineEpochMillis INTEGER")
            }
        }

        /**
         * v3 → v4：序列增加所属面板。纯加法。
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sequences ADD COLUMN panelId INTEGER")
            }
        }

        /**
         * v2 → v3：新增「组」两张表。纯加法，不动已有数据。
         */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timer_groups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`panelId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`order` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_members` (" +
                        "`groupId` INTEGER NOT NULL, " +
                        "`timerId` INTEGER NOT NULL, " +
                        "`order` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `timerId`))",
                )
            }
        }

        /** v5 → v6：计时器增加 locked 列（DDL 锁定时长）。 */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timers ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 → v5：新增 DDL 提前提醒表。 */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deadline_reminders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timerId` INTEGER NOT NULL, " +
                        "`leadMinutes` INTEGER NOT NULL, " +
                        "`fired` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v6 → v7：步骤增加 difficulty（困难程度）与 context（背景/理由）两列；
         * 顺带**回填历史 DDL 的 locked 标记**。
         *
         * difficulty / context 都可空且默认 NULL：「没标注」本身就等于 null，
         * 语义正确，不需要回填猜测值。
         *
         * 关于 locked 回填：`MIGRATION_5_6` 当初只是
         * `ADD COLUMN locked INTEGER NOT NULL DEFAULT 0` —— 升级到 v6 **之前**
         * 就已存在的 DDL 计时器，locked 全部落成了 0。
         * 于是这些老 DDL 至今仍可被编辑（用户实测确认「DDL 还是可以编辑」）。
         * 这里按 kind 补一次，让历史数据和新规则一致。
         *
         * kind 是枚举存成字符串（TimerKindConverter），所以比较 'Deadline'。
         */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE steps ADD COLUMN difficulty INTEGER")
                db.execSQL("ALTER TABLE steps ADD COLUMN context TEXT")

                // 历史 DDL 回填：DDL 的时间由截止时刻决定，本来就不该可编辑
                db.execSQL("UPDATE timers SET locked = 1 WHERE kind = 'Deadline' AND locked = 0")
            }
        }

        /**
         * v7 → v8：再补一次 DDL 的 locked 回填。
         *
         * 为什么不复用 [MIGRATION_6_7] 里那条 UPDATE —— 迁移**每个版本只跑一次**。
         * v6→v7 那次只在「从 v6 升到 v7」时生效，对**之后**才产生的脏数据无能为力。
         *
         * 而 v7 之后确实又产生了脏数据：首页 `HomeScreen` 的「创建 DDL」按钮
         * 走的是 `createTimer()`，那条路径**没写 `locked = true`**。
         * 真机取证（adb 从手机拉的库）：
         *     id=1 kind=Deadline locked=0
         *     id=2 kind=Deadline locked=0
         * 两条都是首页建的。
         *
         * 首页那条路径本身已改走 [at.cwiesner.android.visualtimer.ext.domain.DeadlineCreator]
         * （会写 locked=true），这条迁移负责把**已存在的**脏数据修正过来。
         *
         * SQL 是幂等的：已经是 1 的行不会被改动。
         */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE timers SET locked = 1 WHERE kind = 'Deadline' AND locked = 0")
            }
        }

        /**
         * v8 → v9：新增归档历史表 `timer_sessions`，并给计时器加 `pinned` 列。
         *
         * 两张都是纯加法，不动已有数据。
         *
         * 关于 `pinned` 为什么不回填 —— 本次与 v6 加 `locked` 那次**不是**同一类坑：
         * - v6 那次写的是 `DEFAULT 0`，而 0（未锁定）对「升级前就存在的老 DDL」是**错的语义**，
         *   所以 v6→v7 才不得不补一条 `UPDATE timers SET locked = 1 WHERE kind = 'Deadline'` 去纠偏。
         * - 这次写的是 `DEFAULT 1`，而 1（已固定）对老数据**本身就是正确语义**：
         *   用户升级后，原有的计时器都还该好好待在面板上。
         * 既然 DDL 的默认值已经等于想要的值，再补一条 UPDATE 是多余动作，故不写。
         *
         * 以后凡是 `ADD COLUMN ... DEFAULT x`，判断标准都是同一个：
         * **x 对老数据的语义是否正确**，而不是「有没有回填」这个动作本身。
         */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timer_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`label` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER NOT NULL, " +
                        "`plannedMillis` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`completed` INTEGER NOT NULL)",
                )
                // DEFAULT 1 = 已固定：老数据升级后不被自动销毁，见上面的说明
                db.execSQL("ALTER TABLE timers ADD COLUMN pinned INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v9 → v10：新增运行时快照表 `runtime_snapshots`。
         *
         * 解决「所有卡片退出重进都丢失进度」—— 之前 `_runtimes` 纯内存，
         * 进程一死就空，只有 DDL 能按绝对截止时刻恢复。
         *
         * 新表**不需要回填**：没有快照的计时器按老行为处理
         * （从 `timers.durationMillis` / `TimerKind` 重建待命运行时）。
         * 这是一个纯增量表，老数据完全不受影响。
         *
         * 注意 `timerId` 是**主键但不是外键**：项目一贯不建外键
         * （避免级联删除误伤历史/快照），孤儿行由启动时的 `deleteOrphans()` 清。
         */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_snapshots` (" +
                        "`timerId` INTEGER NOT NULL, " +
                        "`remainingMillis` INTEGER NOT NULL, " +
                        "`overdueMillis` INTEGER NOT NULL, " +
                        "`running` INTEGER NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`stopwatchBaseMillis` INTEGER NOT NULL, " +
                        "`savedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`timerId`))",
                )
            }
        }

        /**
         * v10 → v11：归档支持「进行中 + 暂停区间」。
         *
         * 背景（用户需求）：暂停也要立刻归档（进程被杀不丢），但一次计时在归档里
         * 只能有一条；并且圆盘上要把暂停那段空出来。
         *
         * - `timer_sessions.ongoing`：暂停时先写一条 `ongoing = 1` 占位，
         *   结束时**原地更新**成完整记录，而不是新增一行。
         *   老记录本来就都是已完成的，回填 0 是正确语义。
         * - `timer_pauses`：一条会话下方挂若干 `[from, to)` 暂停区间，
         *   `to = 0` 表示仍在暂停中。不建外键，与 `timer_sessions` 一贯做法一致。
         */
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `timer_sessions` ADD COLUMN `ongoing` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timer_pauses` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`fromMillis` INTEGER NOT NULL, " +
                        "`toMillis` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_timer_pauses_sessionId` " +
                        "ON `timer_pauses` (`sessionId`)",
                )
            }
        }

        /**
         * 打开数据库。
         *
         * **刻意不用 `fallbackToDestructiveMigration()`**：那个兜底会在迁移写错、
         * 或版本号对不上时**静默把整库删掉重建** —— 用户的计时器、序列、分组全没了，
         * 而且毫无提示，事后只能靠导入备份救。
         *
         * 现在的行为：
         * - 缺迁移 → Room 抛出异常，由 [at.cwiesner.android.visualtimer.ext.ExtApplication] 捕获，
         *   记日志并提示用户「请先导出备份」。**数据文件原地保留，不清库。**
         * - 仅向下兼容（APK 被降级安装）时允许破坏性回退 —— 这种情况无法靠迁移解决，
         *   且属于用户主动行为。
         */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "visual-timer-ext.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
