package at.cwiesner.android.visualtimer.ext.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
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

    @Update
    suspend fun updateSteps(steps: List<Step>)

    @Query("DELETE FROM steps WHERE id = :id")
    suspend fun deleteStep(id: Long)
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
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timerDao(): TimerDao
    abstract fun panelDao(): PanelDao
    abstract fun sequenceDao(): SequenceDao
    abstract fun alertProfileDao(): AlertProfileDao
    abstract fun groupDao(): GroupDao
    abstract fun deadlineReminderDao(): DeadlineReminderDao

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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "visual-timer-ext.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
