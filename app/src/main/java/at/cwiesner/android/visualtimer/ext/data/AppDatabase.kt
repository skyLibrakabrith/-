package at.cwiesner.android.visualtimer.ext.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

@androidx.room.TypeConverters(Converters::class, TimerKindConverter::class)
@Database(
    entities = [
        TimerItem::class,
        Panel::class,
        PanelTimerRef::class,
        Step::class,
        StepSequence::class,
        AlertProfile::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timerDao(): TimerDao
    abstract fun panelDao(): PanelDao
    abstract fun sequenceDao(): SequenceDao
    abstract fun alertProfileDao(): AlertProfileDao

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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "visual-timer-ext.db",
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
