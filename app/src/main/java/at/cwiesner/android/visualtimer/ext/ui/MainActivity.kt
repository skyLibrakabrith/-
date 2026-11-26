package at.cwiesner.android.visualtimer.ext.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import at.cwiesner.android.visualtimer.ext.ExtApplication
import at.cwiesner.android.visualtimer.ext.settings.SettingsRepo
import at.cwiesner.android.visualtimer.ext.settings.ThemeMode
import at.cwiesner.android.visualtimer.ext.ui.alerts.AlertsScreen
import at.cwiesner.android.visualtimer.ext.ui.archive.ArchiveScreen
import at.cwiesner.android.visualtimer.ext.ui.home.HomeScreen
import at.cwiesner.android.visualtimer.ext.ui.panels.PanelAction
import at.cwiesner.android.visualtimer.ext.ui.panels.PanelsScreen
import at.cwiesner.android.visualtimer.ext.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 直接给系统栏上纯黑底：enableEdgeToEdge 会让它们透明，
        // 与 Scaffold 之间露出一条底色黑带
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        ensureNotificationPermission()

        val app = application as ExtApplication

        // 顺序注意：app 必须先声明，settings 才能引用它。
        // （本节开头是普通 Kotlin 代码不是 Composable，局部变量只是普通顺序执行；
        //   但仍要遵守「先定义后使用」，这正是 AGENTS.md「Compose 局部声明有顺序」那条坑。）
        val settings = app.settings

        // 有未处理提醒时把亮度拉满，离开提醒后还原
        lifecycleScope.launch {
            app.panelRunner.alerting.collect { ids ->
                runCatching {
                    val attrs = window.attributes
                    if (ids.isEmpty()) {
                        attrs.screenBrightness =
                            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else {
                        attrs.screenBrightness = 1f
                    }
                    window.attributes = attrs
                }
            }
        }

        // 屏幕常亮：按设置决定是否加 FLAG_KEEP_SCREEN_ON
        lifecycleScope.launch {
            settings.keepScreenOn.collect { on ->
                if (on) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            val mode by settings.themeMode.collectAsState(
                initial = ThemeMode.PureBlack,
            )
            // 闪烁节奏由警报页的滑杆决定，这里订阅后直接喂给覆盖层
            val flashInterval by settings.flashIntervalMillis.collectAsState(
                initial = SettingsRepo.DEFAULT_FLASH_INTERVAL,
            )
            var flashPreviewTick by remember { mutableIntStateOf(0) }

            // 数据库打不开（迁移缺失）时，AppApplication 会把一句人话放进来。
            // 这里只负责**弹一次**，然后立刻把标志清掉，避免每次重组都重复提示。
            val dbError by app.databaseOpenError.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(dbError) {
                val msg = dbError ?: return@LaunchedEffect
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                app.databaseOpenError.value = null
            }

            ExtTheme(mode = mode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScaffold(onPreviewFlash = { flashPreviewTick++ })
                    ScreenFlashOverlay(
                        alerting = app.panelRunner.alerting,
                        pulseOnMillis = SettingsRepo.FLASH_ON_MILLIS.toInt(),
                        pulseOffMillis = flashInterval.toInt(),
                        previewTick = flashPreviewTick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** Android 13+ 必须运行时申请通知权限，否则前台服务通知不显示。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Dest(
    val label: String,
    val icon: ImageVector,
) {
    Home("时钟", Icons.Filled.AccessTime),
    Panels("面板", Icons.Filled.GridView),
    // 归档夹在「面板」和「警报」之间：面板是「现在正在用的」，
    // 归档是「已经发生过什么」，警报是「怎么提醒我」——
    // 从"使用 → 回看 → 配置"这条动线走，比放到最后一个更顺手。
    Archive("归档", Icons.Filled.PieChart),
    Alerts("警报", Icons.Filled.Notifications),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
private fun MainScaffold(onPreviewFlash: () -> Unit = {}) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    // 两个「已在当前页时再点一次」的切换信号。
    // 面板页切 卡片/列表，警报页切 振动/铃声 —— 底边栏本身就是切换器，
    // 不额外占页面里的按钮。
    var panelViewToggleTick by remember { mutableIntStateOf(0) }
    var alertsModeToggleTick by remember { mutableIntStateOf(0) }
    // 长按底边栏「面板」→ 在按钮上方弹出菜单；点了某一项后，
    // 用「动作 + 自增信号」两个东西通知 PanelsScreen 去执行。
    // 两个都要：pendingPanelAction 说明做什么，tick 保证连点同一项也能再次触发
    // （如果只传枚举，连点两次同一个 Action 时 LaunchedEffect 的 key 没变就不会重跑）。
    var panelActionsTick by remember { mutableIntStateOf(0) }
    var pendingPanelAction by remember { mutableStateOf<PanelAction?>(null) }

    // 各页当前视图。**由导航层持有**：页面组件切标签时会被销毁重建，
    // 视图状态放在页面内部就会丢，表现为「从别处进来变成列表视图」。
    var panelShowsList by rememberSaveable { mutableStateOf(false) }
    // PanelsScreen 的视图开关：交给它一个 MutableState，由这里持有
    val panelListModeState = rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver(
            save = { it.value },
            restore = { mutableStateOf(it) },
        ),
    ) { mutableStateOf(false) }
    var alertsShowsSound by rememberSaveable { mutableStateOf(false) }
    val dests = Dest.entries

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = ExtColors.PureBlack,
                contentColor = ExtColors.Primary,
            ) {
                dests.forEachIndexed { index, dest ->
                    // 自己画导航项，而不是用 NavigationBarItem。
                    // 原因：NavigationBarItem 内部自带 selectable(clickable)，
                    // 再在外面挂一层 combinedClickable 就会变成两层点击、互相抢事件
                    // —— 这个坑在面板卡片上已经踩过一次。
                    // 现在每个项只有**一个**点击层，长按/单击都能稳定区分。
                    val icon = when {
                        dest == Dest.Panels && panelShowsList -> Icons.Filled.Checklist
                        dest == Dest.Alerts && alertsShowsSound -> Icons.Filled.MusicNote
                        else -> dest.icon
                    }
                    NavItem(
                        modifier = Modifier.weight(1f),
                        selected = selected == index,
                        icon = icon,
                        label = dest.label,
                        onClick = {
                            // 已经在当前页 → 切换该页的视图；否则先跳过去。
                            // 跳转**不会**重置任何页的视图。
                            when {
                                dest == Dest.Panels && selected == index ->
                                    panelViewToggleTick++
                                dest == Dest.Alerts && selected == index ->
                                    alertsModeToggleTick++
                                else -> selected = index
                            }
                        },
                        onLongClick = if (dest == Dest.Panels) {
                            // 长按「面板」→ 展开按钮上方的菜单（见下方 menuContent）
                            {
                                if (selected != index) selected = index
                            }
                        } else null,
                        menuContent = if (dest == Dest.Panels) {
                            {
                                // 菜单项：点了就发一个动作信号给 PanelsScreen。
                                // 先保证当前就在面板页（长按时已切过去），再发信号。
                                //
                                // 只剩两项：「面板管理」移到顶栏右上角图标，
                                // 「新建截止时刻」删掉（DDL 的主入口在首页「创建 DDL」，
                                // 见 PanelAction 的注释）。
                                DropdownMenuItem(
                                    text = { Text("新建序列") },
                                    onClick = {
                                        pendingPanelAction = PanelAction.NewSequence
                                        panelActionsTick++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("🧠 整理念头") },
                                    onClick = {
                                        pendingPanelAction = PanelAction.Compile
                                        panelActionsTick++
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // ---- 页面状态保留 ----
            //
            // 原来直接写 `when (dests[selected]) { ... }`：条件组合在换标签时会把
            // 旧页面整个从组合中移除，remember 全部丢失，新页面从零组合 ——
            // 表现为「每次打开一个界面会先渲染一下」：
            // collectAsState 先画出默认值，等 DataStore 的 Flow 发射后才跳到真实值。
            //
            // 这**不是**为了省内存（销毁重建反而更慢）。
            //
            // 现在用 SaveableStateHolder 按页面 key 保存状态：
            // 离开标签时把 saveable 状态存起来，回来时原样恢复，
            // 于是不再有「先默认值再跳真实值」的闪动。
            //
            // 注意：它只保留 rememberSaveable 的状态。普通 remember 仍然会丢 ——
            // 那些本来就该丢（属于一次性 UI 状态），需要跨标签保留的请改用
            // rememberSaveable，或者像 listModeState 那样提到导航层。
            val stateHolder = rememberSaveableStateHolder()
            val current = dests[selected]
            stateHolder.SaveableStateProvider(key = current.name) {
                when (current) {
                    Dest.Home -> HomeScreen()
                    Dest.Panels -> PanelsScreen(
                        toggleViewSignal = panelViewToggleTick,
                        listModeState = panelListModeState,
                        actionsSignal = panelActionsTick,
                        pendingAction = pendingPanelAction,
                        // 消费掉动作：否则 PanelsScreen 重新组合（从别的标签切回来）
                        // 时会把旧动作再执行一遍，表现为「自己弹出面板管理窗口」。
                        onActionConsumed = { pendingPanelAction = null },
                    )
                    Dest.Archive -> ArchiveScreen()
                    Dest.Alerts -> AlertsScreen(
                        onPreviewFlash = onPreviewFlash,
                        toggleModeSignal = alertsModeToggleTick,
                        onModeChanged = { showsSound -> alertsShowsSound = showsSound },
                    )
                    Dest.Settings -> SettingsScreen()
                }
            }
        }
    }
}

/**
 * 底边栏的一项。
 *
 * 只挂**一个** [androidx.compose.foundation.combinedClickable]：
 * 单击切页/切视图，长按给需要额外入口的页面用（目前只有「面板」）。
 * 不用 NavigationBarItem 是因为它内部已经有一层 selectable，
 * 再叠一层点击会互相抢事件。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    /**
     * 长按菜单。
     *
     * 用 [DropdownMenu] 而不是居中的 AlertDialog：它是**锚定在本项上方**弹出的，
     * 长按哪个按钮就从哪个按钮旁边冒出来，位置和手指对得上。
     * 居中弹窗会让视线从底边栏跳到屏幕正中，而且挡住整个面板（看不出自己在操作哪一页）。
     *
     * 只有「面板」这一项传值，其余为 null —— 菜单不出现时 DropdownMenu
     * 本身不会占位也不会拦截事件。
     */
    menuContent: (@Composable ColumnScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 菜单展开状态放在 NavItem 内部：每个按钮各管各的，
    // 不需要让 MainScaffold 知道"现在哪个菜单开着"。
    var menuExpanded by remember { mutableStateOf(false) }

    // ⚠️ modifier（含调用方的 weight(1f)）必须挂在**最外层** Box 上。
    //
    // Row 的 weight 只对其**直接子项**有效。为此项加了一层 Box 来承载
    // DropdownMenu 之后，如果 weight 还留在内层 Column 上，Row 看到的是
    // 没有 weight 的 Box —— 四个按钮就各按自身内容宽度挤在左边，
    // 表现为「底边栏全部挤在左下角」。
    Box(modifier = modifier) {
        Column(
            // 高度必须**写死**，不能用 fillMaxHeight()。
            // NavigationBar 内部是 defaultMinSize 而不是固定高度约束，
            // 一旦子项 fillMaxHeight，它们会撑满整个可用高度 ——
            // 底边栏吃掉整屏，内容区高度变成 0，界面就只剩一条底边栏。
            modifier = Modifier
                .height(64.dp)
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (menuContent != null) {
                        {
                            onLongClick?.invoke()
                            menuExpanded = true
                        }
                    } else {
                        onLongClick
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 选中指示：M3 的 NavigationBarItem 会画一个胶囊底色包住图标，
            // 自绘时也要补上，否则「选中」只靠图标变色，几乎看不出来。
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (selected) ExtColors.PrimaryDim else androidx.compose.ui.graphics.Color.Transparent,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) ExtColors.Primary else ExtColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = if (selected) ExtColors.Primary else ExtColors.TextSecondary,
            )
        }

        if (menuContent != null) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = ExtColors.Surface,
                // 菜单锚定在这个 Box（= 本按钮）的左上角。
                // 底边栏贴着屏幕底部，菜单向下展开必然超出屏幕 ——
                // DropdownMenu 自带"放不下就向上翻转"的处理，所以这里
                // **不需要**手动加负偏移去翻方向，只微调一点间距让它别贴太紧。
                offset = DpOffset(0.dp, (-8).dp),
            ) {
                menuContent()
            }
        }
    }
}
