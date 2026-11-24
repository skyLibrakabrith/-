package at.cwiesner.android.visualtimer.ext.ui.panels

/**
 * 面板里一个可排序条目。
 *
 * 序列、组、计时器存在不同表里各有 order 字段，为了让它们能**互相穿插**排序，
 * 统一拍平成 [PanelEntry]，保存时再按类型回写各自的 order。
 */
enum class EntryKind(val label: String) {
    Sequence("序列"),
    Group("组"),
    Timer("计时器"),
}

data class PanelEntry(
    val kind: EntryKind,
    val refId: Long,
    val name: String,
    val order: Int,
)
