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

/**
 * 长按底边栏「面板」按钮后，弹出菜单里的动作。
 *
 * 菜单画在 MainActivity 的底边栏上（锚定按钮），但它要触发的逻辑都在
 * PanelsScreen 内部，所以用这个枚举 + 一个自增信号把点击送过去。
 * 与 [PanelEntry] 放在一起，是因为两者都是"面板页的操作词汇"。
 */
enum class PanelAction {
    /** 在当前面板里新建一个空序列。 */
    NewSequence,

    /** 打开面板管理（改名 / 增删 / 排序面板）。 */
    Manage,

    /** Compiler：把碎片念头整理成计时器放进当前面板。 */
    Compile,
}
