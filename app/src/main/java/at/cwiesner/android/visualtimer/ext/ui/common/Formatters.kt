package at.cwiesner.android.visualtimer.ext.ui.common

import java.util.Calendar

/** 时刻显示 `HH:mm`。 */
fun clockOf(epochMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "%02d:%02d".format(
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
    )
}

/** 提醒配置名；未绑定时回退到列表第一个配置名。 */
fun alertNameOf(
    profileId: Long?,
    profiles: List<at.cwiesner.android.visualtimer.ext.data.AlertProfile>,
): String {
    val p = if (profileId != null) {
        profiles.firstOrNull { it.id == profileId }
    } else {
        profiles.firstOrNull()
    }
    return p?.name ?: "默认"
}

/** 把分钟数说成人话：`1 天`、`2 小时 30 分`、`45 分钟`。 */
fun describeMinutes(m: Long): String = when {
    m >= 1440 -> "${m / 1440} 天"
    m >= 60 -> "${m / 60} 小时" + if (m % 60 != 0L) " ${m % 60} 分" else ""
    else -> "$m 分钟"
}
