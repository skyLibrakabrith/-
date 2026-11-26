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
 *
 * ## 为什么只有三个动作
 *
 * 原来还有 `Manage`（面板管理）和 `NewDeadline`（新建截止时刻），都已移走：
 *
 * - **面板管理** → 顶栏右上角的图标按钮。它是"对整个面板集合的操作"，
 *   放在抽屉里和"往当前面板加东西"混在一起，语义不一致。
 * - **新建截止时刻** → 删掉。DDL 的主入口是首页的「创建 DDL」按钮
 *   （走 `DeadlineCreator`，会弹提前提醒对话框、自动开始、写提醒行）。
 *   面板页再留一个入口，等于让"创建 DDL"有两条实现路径，
 *   历史上正是这种分叉导致了「首页建的 DDL 不自动开始」那类 bug。
 *
 * 删枚举值时**必须同时删掉所有 when 分支**，否则 when 不再穷尽会编译失败
 * （这正好是我们要的保护：编译器会逼着你清理干净）。
 */
enum class PanelAction {
    /** 在当前面板里新建一个空序列。 */
    NewSequence,

    /** Compiler：把碎片念头整理成计时器放进当前面板。 */
    Compile,
}
