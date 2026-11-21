package at.cwiesner.android.visualtimer.ext.data

import androidx.room.TypeConverter

/**
 * Room 类型转换器。
 *
 * [AlertProfile.vibrationPattern] 是 List&lt;Long&gt;，
 * Room 不认识泛型集合，这里序列化成逗号分隔字符串。
 */
class Converters {

    @TypeConverter
    fun longListToString(value: List<Long>?): String =
        value?.joinToString(",") ?: ""

    @TypeConverter
    fun stringToLongList(value: String?): List<Long> =
        value?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()
}
