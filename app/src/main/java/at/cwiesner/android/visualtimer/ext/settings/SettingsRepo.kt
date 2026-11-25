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
 * AI 模式。
 *
 * 刻意做成三档而不是一个开关：本地 ollama 和云端 API 各有各的失败方式，
 * 用户需要能明确说"就走云端别回退"或"只用本地别联网"。
 */
enum class AiMode(val label: String) {
    /** 默认：完全不联网，三个入口都不出现。 */
    Off("关闭"),

    /** 云端优先，失败/超时自动回退到本地 ollama。 */
    CloudFirst("云端优先（失败回退本地）"),

    /** 只用本地 ollama，不发出任何外网请求。 */
    LocalOnly("仅本地 ollama"),
}

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

    /**
     * 变色模式。
     *
     * 默认是 [ColorMode.Fraction]（表盘按剩余比例分成 N 份，每份一种颜色）。
     * 之前默认阈值模式，问题是：在阈值之间整块盘面几乎不变色，
     * 扫一眼看不出"还剩多少"，得去读中间的数字。
     * 比例模式让盘面上颜色占的**面积**直接等于剩余比例，正是这个 App
     * 「不依赖读数字」的设计目标（见 README 的「可视化设计」）。
     */
    val colorMode: Flow<ColorMode> = store.data.map { prefs ->
        ColorMode.entries.firstOrNull { it.name == prefs[KEY_COLOR_MODE] }
            ?: ColorMode.Fraction
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

    /** 提醒铃声 URI；null 表示系统默认。 */
    val ringtoneUri: Flow<String?> = store.data.map { it[KEY_RINGTONE] }

    /** 是否启用耳机声音。 */
    val soundEnabled: Flow<Boolean> = store.data.map { it[KEY_SOUND] ?: true }

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

    /**
     * 屏幕闪烁的「暗场间隔」：亮一下之后停多久再亮下一次。
     *
     * 亮起时长固定为 [FLASH_ON_MILLIS]，只有这个间隔可调 ——
     * 用户反馈「一直在高频闪动」，真正刺眼的是间隔太短，
     * 所以只给一个旋钮，调大就是变慢。
     */
    val flashIntervalMillis: Flow<Long> = store.data.map {
        (it[KEY_FLASH_INTERVAL]?.toLongOrNull() ?: DEFAULT_FLASH_INTERVAL)
            .coerceIn(MIN_FLASH_INTERVAL, MAX_FLASH_INTERVAL)
    }

    // ---------------- AI（任务拆解 / 估时 / 念头整理） ----------------

    /**
     * AI 模式。
     *
     * 默认 [AiMode.Off]：**默认关闭**，不联网、不产生任何请求。
     * 用户必须主动在设置里配好端点才会启用。
     */
    val aiMode: Flow<AiMode> = store.data.map { prefs ->
        AiMode.entries.firstOrNull { it.name == prefs[KEY_AI_MODE] } ?: AiMode.Off
    }

    /** 云端 OpenAI 兼容端点，默认 DeepSeek。 */
    val aiCloudBaseUrl: Flow<String> = store.data.map {
        it[KEY_AI_CLOUD_BASE]?.takeIf { u -> u.isNotBlank() } ?: DEFAULT_AI_CLOUD_BASE_URL
    }

    val aiCloudModel: Flow<String> = store.data.map {
        it[KEY_AI_CLOUD_MODEL]?.takeIf { m -> m.isNotBlank() } ?: DEFAULT_AI_CLOUD_MODEL
    }

    /**
     * 云端 API key。
     *
     * 只存在本机 DataStore（明文，普通 App 读不到）。
     * **不会**写进导出备份，也不进日志。设置页会明确提示这一点。
     */
    val aiCloudKey: Flow<String> = store.data.map { it[KEY_AI_CLOUD_KEY] ?: "" }

    /** 本地 ollama 地址，例如 http://100.77.176.63:19931（Tailscale）。 */
    val aiLocalBaseUrl: Flow<String> = store.data.map {
        it[KEY_AI_LOCAL_BASE]?.takeIf { u -> u.isNotBlank() } ?: DEFAULT_AI_LOCAL_BASE_URL
    }

    val aiLocalModel: Flow<String> = store.data.map {
        it[KEY_AI_LOCAL_MODEL]?.takeIf { m -> m.isNotBlank() } ?: DEFAULT_AI_LOCAL_MODEL
    }

    /** 单次请求超时（秒）。云端慢、本地快，但统一一个上限够用。 */
    val aiTimeoutSeconds: Flow<Int> = store.data.map {
        (it[KEY_AI_TIMEOUT]?.toIntOrNull() ?: DEFAULT_AI_TIMEOUT_SECONDS)
            .coerceIn(MIN_AI_TIMEOUT, MAX_AI_TIMEOUT)
    }

    suspend fun setAiMode(mode: AiMode) {
        store.edit { it[KEY_AI_MODE] = mode.name }
    }

    suspend fun setAiCloudBaseUrl(url: String) {
        store.edit { it[KEY_AI_CLOUD_BASE] = url.trim() }
    }

    suspend fun setAiCloudModel(model: String) {
        store.edit { it[KEY_AI_CLOUD_MODEL] = model.trim() }
    }

    suspend fun setAiCloudKey(key: String) {
        store.edit { prefs ->
            val k = key.trim()
            if (k.isEmpty()) prefs.remove(KEY_AI_CLOUD_KEY) else prefs[KEY_AI_CLOUD_KEY] = k
        }
    }

    suspend fun setAiLocalBaseUrl(url: String) {
        store.edit { it[KEY_AI_LOCAL_BASE] = url.trim() }
    }

    suspend fun setAiLocalModel(model: String) {
        store.edit { it[KEY_AI_LOCAL_MODEL] = model.trim() }
    }

    suspend fun setAiTimeoutSeconds(sec: Int) {
        store.edit { it[KEY_AI_TIMEOUT] = sec.coerceIn(MIN_AI_TIMEOUT, MAX_AI_TIMEOUT).toString() }
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

    suspend fun setRingtoneUri(uri: String?) {
        store.edit { prefs ->
            if (uri == null) prefs.remove(KEY_RINGTONE) else prefs[KEY_RINGTONE] = uri
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        store.edit { it[KEY_SOUND] = enabled }
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

    suspend fun setFlashIntervalMillis(ms: Long) {
        store.edit {
            it[KEY_FLASH_INTERVAL] =
                ms.coerceIn(MIN_FLASH_INTERVAL, MAX_FLASH_INTERVAL).toString()
        }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_THRESHOLDS = stringPreferencesKey("color_thresholds")
        private val KEY_COLOR_MODE = stringPreferencesKey("color_mode")
        private val KEY_INTERVAL_MINUTES = stringPreferencesKey("interval_minutes")
        private val KEY_FRACTION_DIVISIONS = stringPreferencesKey("fraction_divisions")
        private val KEY_CENTER_TEXT = booleanPreferencesKey("show_center_text")
        private val KEY_VIBRATE = booleanPreferencesKey("vibration_enabled")
        private val KEY_RINGTONE = stringPreferencesKey("ringtone_uri")
        private val KEY_SOUND = booleanPreferencesKey("sound_enabled")
        private val KEY_OVERLAY = booleanPreferencesKey("overlay_enabled")
        private val KEY_BACKGROUND = booleanPreferencesKey("background_enabled")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_GRID_COLUMNS = stringPreferencesKey("grid_columns")
        private val KEY_FLASH_INTERVAL = stringPreferencesKey("flash_interval_millis")

        // AI
        private val KEY_AI_MODE = stringPreferencesKey("ai_mode")
        private val KEY_AI_CLOUD_BASE = stringPreferencesKey("ai_cloud_base_url")
        private val KEY_AI_CLOUD_MODEL = stringPreferencesKey("ai_cloud_model")
        private val KEY_AI_CLOUD_KEY = stringPreferencesKey("ai_cloud_key")
        private val KEY_AI_LOCAL_BASE = stringPreferencesKey("ai_local_base_url")
        private val KEY_AI_LOCAL_MODEL = stringPreferencesKey("ai_local_model")
        private val KEY_AI_TIMEOUT = stringPreferencesKey("ai_timeout_seconds")

        /**
         * AI 默认值。
         *
         * 本机 ollama 实际跑在 19931 —— 不是默认的 11434。
         * 原因是 Windows 的动态端口范围保留到 15000，而 11434 落在
         * netsh 排除段 11419-11518 里，环回和通配都绑不上。
         * 见机器记忆 AGENTS.md「端口」一节。
         */
        const val DEFAULT_AI_CLOUD_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_AI_CLOUD_MODEL = "deepseek-flash"
        const val DEFAULT_AI_LOCAL_BASE_URL = "http://100.77.176.63:19931"
        const val DEFAULT_AI_LOCAL_MODEL = "qwen3.5:4b"
        const val DEFAULT_AI_TIMEOUT_SECONDS = 20
        const val MIN_AI_TIMEOUT = 5
        const val MAX_AI_TIMEOUT = 120

        /** 亮起时长固定：再短就看不出来，再长就成了常亮。 */
        const val FLASH_ON_MILLIS: Long = 220L

        /** 暗场间隔的默认值与可调范围。 */
        const val DEFAULT_FLASH_INTERVAL: Long = 420L
        const val MIN_FLASH_INTERVAL: Long = 200L
        const val MAX_FLASH_INTERVAL: Long = 2000L

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
