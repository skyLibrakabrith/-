package at.cwiesner.android.visualtimer.ext.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 主题模式。 */
enum class ThemeMode(val label: String) {
    /** 纯黑：背景 #000000，OLED 上不发光的深色。 */
    PureBlack("纯黑"),

    /** 深灰：背景 #121212，对比稍柔和。 */
    DarkGray("深灰"),

    /** 浅色。 */
    Light("浅色"),

    /** 跟随系统。 */
    System("跟随系统"),
}

/**
 * 变色模式。
 */
enum class ColorMode(val label: String, val desc: String) {
    /** 阈值模式：剩余时间进入某个阈值就切换成对应颜色。 */
    Threshold("阈值", "剩余时间进入指定阈值时变色"),

    /** 间隔模式：每经过 N 分钟换一种颜色，循环往复。 */
    Interval("等间隔", "每过 N 分钟换一种颜色，循环使用调色板"),

    /** 比例模式：把表盘按剩余比例等分，落在哪一份就用该份颜色。 */
    Fraction("表盘比例", "表盘按剩余比例分成 N 份，每份一种颜色"),
}

/**
 * 变色阈值：剩余时间进入该档位后，圆盘切换成对应颜色。
 *
 * @property minutesLeft 剩余多少分钟时触发
 * @property paletteIndex 调色板下标（0=主色 1=琥珀 2=橙 3=红）
 */
data class ColorThreshold(
    val minutesLeft: Long,
    val paletteIndex: Int,
)

/**
 * 应用设置。
 *
 * 用 DataStore 存，避免自己处理 SharedPreferences 的异步问题。
 */
class SettingsRepo(private val context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.appDataStore

    /** 主题模式。 */
    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        ThemeMode.entries.firstOrNull {
            it.name == prefs[KEY_THEME]
        } ?: ThemeMode.PureBlack
    }

    /** 变色阈值，按剩余时间从大到小排列。 */
    val thresholds: Flow<List<ColorThreshold>> = store.data.map { prefs ->
        val raw = prefs[KEY_THRESHOLDS] ?: DEFAULT_THRESHOLDS
        parseThresholds(raw)
    }

    /** 变色模式。 */
    val colorMode: Flow<ColorMode> = store.data.map { prefs ->
        ColorMode.entries.firstOrNull { it.name == prefs[KEY_COLOR_MODE] }
            ?: ColorMode.Threshold
    }

    /** 等间隔模式的间隔分钟数。 */
    val intervalMinutes: Flow<Long> = store.data.map { prefs ->
        prefs[KEY_INTERVAL_MINUTES]?.toLongOrNull()?.takeIf { it > 0 } ?: 5L
    }

    /** 比例模式把表盘分成几份。 */
    val fractionDivisions: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_FRACTION_DIVISIONS]?.toIntOrNull()?.coerceIn(2, 12) ?: 4
    }

    /** 圆盘上是否显示中央文字。 */
    val showCenterText: Flow<Boolean> = store.data.map { it[KEY_CENTER_TEXT] ?: true }

    /** 是否启用振动（总开关）。 */
    val vibrationEnabled: Flow<Boolean> = store.data.map { it[KEY_VIBRATE] ?: true }

    /** 悬浮窗：在别的应用上层显示正在跑的计时器。 */
    val overlayEnabled: Flow<Boolean> = store.data.map { it[KEY_OVERLAY] ?: false }

    /** 后台运行：退出界面后继续走表并报警。 */
    val backgroundEnabled: Flow<Boolean> = store.data.map { it[KEY_BACKGROUND] ?: true }

    /** 屏幕常亮：计时界面不自动熄屏。 */
    val keepScreenOn: Flow<Boolean> = store.data.map { it[KEY_KEEP_SCREEN_ON] ?: false }

    /** 网格列数：1=单列大表盘，2=两列，3=三列小表盘。 */
    val gridColumns: Flow<Int> = store.data.map {
        (it[KEY_GRID_COLUMNS]?.toIntOrNull() ?: 2).coerceIn(1, 8)
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setThresholds(list: List<ColorThreshold>) {
        store.edit {
            it[KEY_THRESHOLDS] = list
                .sortedByDescending { t -> t.minutesLeft }
                .joinToString(",") { t -> "${t.minutesLeft}:${t.paletteIndex}" }
        }
    }

    suspend fun setColorMode(mode: ColorMode) {
        store.edit { it[KEY_COLOR_MODE] = mode.name }
    }

    suspend fun setIntervalMinutes(minutes: Long) {
        store.edit { it[KEY_INTERVAL_MINUTES] = minutes.coerceAtLeast(1).toString() }
    }

    suspend fun setFractionDivisions(divisions: Int) {
        store.edit { it[KEY_FRACTION_DIVISIONS] = divisions.coerceIn(2, 12).toString() }
    }

    suspend fun setShowCenterText(enabled: Boolean) {
        store.edit { it[KEY_CENTER_TEXT] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        store.edit { it[KEY_VIBRATE] = enabled }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        store.edit { it[KEY_OVERLAY] = enabled }
    }

    suspend fun setBackgroundEnabled(enabled: Boolean) {
        store.edit { it[KEY_BACKGROUND] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        store.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setGridColumns(n: Int) {
        store.edit { it[KEY_GRID_COLUMNS] = n.coerceIn(1, 8).toString() }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_THRESHOLDS = stringPreferencesKey("color_thresholds")
        private val KEY_COLOR_MODE = stringPreferencesKey("color_mode")
        private val KEY_INTERVAL_MINUTES = stringPreferencesKey("interval_minutes")
        private val KEY_FRACTION_DIVISIONS = stringPreferencesKey("fraction_divisions")
        private val KEY_CENTER_TEXT = booleanPreferencesKey("show_center_text")
        private val KEY_VIBRATE = booleanPreferencesKey("vibration_enabled")
        private val KEY_OVERLAY = booleanPreferencesKey("overlay_enabled")
        private val KEY_BACKGROUND = booleanPreferencesKey("background_enabled")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_GRID_COLUMNS = stringPreferencesKey("grid_columns")

        /** 默认：60 分钟起主色，10 分钟琥珀，3 分钟橙，1 分钟红。 */
        const val DEFAULT_THRESHOLDS = "60:0,10:1,3:2,1:3"

        fun parseThresholds(raw: String): List<ColorThreshold> =
            raw.split(",")
                .mapNotNull { part ->
                    val bits = part.trim().split(":")
                    if (bits.size != 2) return@mapNotNull null
                    val m = bits[0].toLongOrNull() ?: return@mapNotNull null
                    val p = bits[1].toIntOrNull() ?: return@mapNotNull null
                    ColorThreshold(m, p.coerceIn(0, 3))
                }
                .sortedByDescending { it.minutesLeft }
                .ifEmpty { parseThresholds(DEFAULT_THRESHOLDS) }
    }
}

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "visual_timer_ext_settings",
)

/** 未使用但留作扩展：整型设置键。 */
@Suppress("unused")
private val UNUSED_INT = intPreferencesKey("unused")
