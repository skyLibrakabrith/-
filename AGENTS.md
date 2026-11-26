# AGENTS.md

Android 计时器（Kotlin + Jetpack Compose），包名 `at.cwiesner.android.visualtimer.ext`，
可与原版 Visual Timer 共存安装。仓库 `skyLibrakabrith/-`（仓库名就是一个减号），分支 `main`。

**本机没有 JDK / Android SDK / adb / gradle** —— 所有编译验证都靠 GitHub Actions。
`pwsh` 在本机不是独立程序，用 PowerShell **5.1**（不支持内联 `if` 表达式）。

## Running / verifying

不能本地编译。验证 = 推到 `main` → 等 CI → 下载 artifact 装机。

```bash
# 推送前必跑（无编译器时的兜底，三项都应全绿）
node tools/resolve.js      # 工程内部 import 能否解析到真实声明
node tools/unused2.js      # 未使用的 import
node tools/checkbraces.js  # 花括号/圆括号平衡
```

> ⚠️ 这三个脚本硬编码 `root='vte/app/src/main/java'`，**要求从含 `vte/` 子目录的父目录运行**。
> 本仓库根目录就是 `vte/` 的内容，所以直接 `node tools/resolve.js` 会找不到文件。
> 两种跑法：① 把仓库 clone 到名为 `vte/` 的目录，在它的父目录执行；
> ② 临时建 `<tmp>/vte -> 本仓库`、`<tmp>/tools -> 本仓库/tools`，在 `<tmp>` 下执行。

- CI：`.github/workflows/build.yml`，push 即触发；约 30 秒开始、**1–4 分钟**出结果。
- 产物：Actions → Build APK → Artifacts → `visual-timer-ext-debug-apk`（`app-debug.apk`）。
- **手势 / 悬浮窗 / 警报 / 闪烁**等真机行为**无法在无设备环境验证**，改完必须装机试。
- **有单元测试**（`app/src/test`，65 个 `@Test`，覆盖 `AiJson` / `DeadlineCalculator` /
  `StepSequenceEngine` / `SequenceDuration`）。CI 里 `testDebugUnitTest` 是独立一步，
  **这是本机唯一能"真跑"的验证手段**，改纯逻辑务必补测试。
- **APK 可以直接覆盖安装**（2026-11-26 起）：`versionCode` 取 CI 的 `GITHUB_RUN_NUMBER`
  自动递增，且 CI 用仓库 secret `DEBUG_KEYSTORE_B64` 恢复固定 debug 证书。
  在此之前每次都得先卸载——原因是 versionCode 恒为 1 且每次的 debug 证书都不同。
  本地构建想指定版本号用 `-PversionCodeOverride=123`。

推得碎比推得多重要得多：一次一类改动，CI 失败时一次只修一处。

## Architecture

```
app/src/main/java/at/cwiesner/android/visualtimer/ext/
├── ExtApplication.kt     应用入口：持有 DB / PanelRunner / StepSequenceEngine / 告警
├── data/                 Room 实体 + DAO（迁移全在 AppDatabase.kt）
├── domain/               PanelRunner / StepSequenceEngine / DeadlineCalculator
├── alarm/                振动 / 闪屏 / 手电筒 / 耳机声音
├── service/              悬浮窗、告警悬浮窗、前台服务、开机恢复
├── io/                   导入导出（BackupFormat 定义 schema）
├── settings/SettingsRepo.kt  DataStore 设置项
├── panels/PanelState.kt  ⚠️ rememberPanelState() 零调用，内容被逐行复制在 PanelsScreen 里（死代码）
└── ui/                   界面（panels/sequences/wheel/alerts/settings/home/duration/theme）
```

规模：**75 个 Kotlin 文件 / 约 17,230 行**（含 app/src/test）。

### PanelRunner —— 倒计时为什么不会丢

挂在 `ExtApplication`（**不是** Composable 的 `remember`）。早期建在 `remember` 里，
切底部标签时组合被销毁，所有倒计时清空。自带 **200ms tick**（`TICK_MILLIS`），
对外暴露 `runtimes: StateFlow<Map<Long, TimerRuntime>>`，**界面只订阅、不驱动时间**。

`TimerRuntime` 有一对容易混淆的字段：

| 字段 | 含义 |
|---|---|
| `expired` | **历史事实**：曾经到过点。暂停后仍为 true |
| `isOverdue` | **当前态**：`expired && running`。判断"是否正在超时"必须用它 |

曾经用 `expired` 判断，导致"暂停后点一下变成重置"。
`resume()` 只翻转 `running`；`start()` 会重建整个 runtime（**等于重置**）。暂停后继续必须走 `resume`。

### 手势（DraggableGrid）—— 最脆弱的部分

两件事彻底分开，各用官方检测器，**绝不叠层**：

| 层 | 载体 | 职责 |
|---|---|---|
| 排序 | 网格 `detectDragGesturesAfterLongPress` | 长按进拖拽，**几何命中**换位 |
| 点按 | 每个卡片 `combinedClickable` | 单击 / 双击 / 长按 |

四条铁律，全是踩坑踩出来的：

1. **绝不能嵌套 `clickable` / `combinedClickable`**。卡片内部再挂一层，内层先消费第一下，
   外层事件序列残缺 → 双击错乱，表现是"点一下就直接进全屏"。
   所以 `TimerCell` / `SequenceCell` / `TimerGroupCell` **没有**自己的点击层，点按全交给网格。
2. 排序层**不消费**按下事件（不 `awaitFirstDown(requireUnconsumed = true)`）。
3. 换位用**真实矩形做几何命中**（被拖卡片矩形是否覆盖目标格子中心），不要用"位移 ÷ 估算格高"。
4. 回调闭包一律 `rememberUpdatedState`，否则 `pointerInput(Unit)` 里是过期 lambda。

### 表盘几何（WheelTimerView）

盘面**恒为居中的最大内接正方形**，直径 = `min(父宽, 父高)`（`Modifier.asSquareInside()`）。
圆、中央文字、内盘控件共用同一几何中心。`fillParent` 参数仍保留但**已无视觉效果**，只为不破坏签名。

### 变色

三种模式（阈值 / 等间隔 / 表盘比例）**全部**走同一条红绿灯色带：
绿 `#35D07F` → 黄 `#F2C14E` → 红 `#E5484D`，中间线性插值。
`ExtColors.trafficColor(fraction)` / `trafficStepColor(index, divisions)`。

**暂停时颜色不跳变**：`pickColor` 只在"完全没有进度"时返回起始绿，否则继续按剩余比例取色
（否则一暂停盘面由橙变绿，看起来像被重置）。

### 数据与备份

- Room 版本 **7**，9 个实体：`TimerItem` / `Panel` / `PanelTimerRef` / `Step` / `StepSequence` /
  `AlertProfile` / `TimerGroup` / `GroupMember` / `DeadlineReminder`。迁移 v1→v7 全在 `AppDatabase.kt`。
  **`ADD COLUMN ... DEFAULT` 之后要检查要不要回填**：v6 加 `locked` 时没回填，
  升级前就存在的老 DDL 全都变成可编辑（v7 才补上 `UPDATE timers SET locked=1 WHERE kind='Deadline'`）。
- 备份 `BackupFile.CURRENT_SCHEMA = 2`。v1 旧备份**仍可导入**——新字段一律带默认值。
- 导入时主键会重映射，跨实体引用一律用 `ref` 字符串（`"t<id>"` / `"p<id>"` / `"a<id>"`），
  **不要用原始 id**。
- 导入导出走 SAF 文件选择器，**不需要任何存储权限**。

### 警报与声音

- 振动：`VibrationAlerter`，到点后**持续循环**直到用户处理（`panelRunner.alerting` 非空即震）。
- 屏幕闪烁：应用内 `ScreenFlashOverlay`（全屏白脉冲）+ 后台 `AlertOverlayService`（悬浮窗卡片），
  **两处读同一个设置** `flashIntervalMillis`（暗场间隔 200–2000ms，默认 420）。
- 声音：`HeadphoneSoundAlerter` **只在检测到耳机时播放，绝不外放**
  （`USAGE_ASSISTANCE_SONIFICATION` + 输出设备类型白名单）。

## State / cache（"昨天还好用"时的排查入口）

| 项 | 位置 |
|---|---|
| DataStore 设置 | `visual_timer_ext_settings` |
| Room 数据库 | `visual-timer-ext.db` |
| 导入导出 | SAF，JSON，无需存储权限 |
| Gradle 缓存 | `~/.gradle`（CI 上由 `gradle/actions/setup-gradle` 管理） |

## Conventions

- 注释与文档字符串用**中文**，公开 API 用英文。保持一致。
- **不要引入** Navigation-Compose / ViewModel / Hilt。依赖刻意精简
  （曾为"模块化"引入 Navigation-Compose，实际没用上，白增大 APK）。
- 不要用 `NavigationBarItem` 的内部点击层；**不要给 `NavigationBar` 子项用 `fillMaxHeight()`**
  （`NavigationBar` 内部是 `defaultMinSize`，子项会撑满整屏，内容区高度变 0）。
  自绘 `NavItem` 高度写死 `64.dp`。
- `@OptIn` **不是可重复注解**：多个写成 `@OptIn(A::class, B::class)`，不能连写两行。
- `awaitEachGesture` 的 lambda 是 **restricted suspension scope**，里面不能调 `kotlinx.coroutines.delay`。
  要延迟就记状态，交给组合层的 `LaunchedEffect`。
- `ExtColors` 是**全局可变单例**（`object` + `internal set`），`ExtTheme` 在 composition 前同步赋值。
  **不要在 Composable 之外长期缓存 `ExtColors.xxx`**。
- `Modifier.layout { }` 是扩展函数，写成 `fun Modifier.xxx() = this.layout { ... }`。
- Compose **局部声明有顺序**：局部函数 / `LaunchedEffect` 引用后面的 `val` 会编译失败
  （"Unresolved reference"）。
- 全工程 50 处 `runCatching{}` 静默吞异常、只有 1 处 `Log`。新增代码请记日志，别让线上问题只能靠用户口述。
- **不要给 `.kt` / `build.gradle.kts` 加 UTF-8 BOM**（Gradle 脚本带 BOM 可能解析失败）。
  仓库根 `.gitattributes` 已固定 `eol=lf`。
- 小步提交，一次一类改动。

## 已知问题（接手先看）

| # | 问题 | 位置 |
|---|---|---|
| 1 | `fallbackToDestructiveMigrationOnDowngrade()` 会在**降级安装**时静默删库，且不抛异常 —— 上层那段提示接不住它 | `AppDatabase.kt:329` |
| 2 | 拖拽排序**不支持边缘自动滚动**（格子超出屏幕后拖不到远处） | `DraggableGrid` |
| 3 | 单击要等双击窗口（约 300ms）才响应；本版 Compose 的 `combinedClickable` **没有** `doubleClickTimeoutMillis` / `hasDoubleClick` 参数 | 手势 |
| 4 | `PanelsScreen` 是 **1348 行**巨型 Composable；`PanelState.kt` 的 `rememberPanelState()` 零调用（漂移中的副本） | `ui/panels/PanelsScreen.kt` |
| 5 | `tools/unused2.js` 有 `if (unused.length > 1)` —— **只有 1 个未使用 import 的文件会静默通过** | `tools/unused2.js` |

更完整的清单见 `docs/ENGINEERING_PITFALLS.md`（历史踩坑）与
`docs/ARCHITECTURE_REVIEW.md`（23 条设计逻辑问题 + 7 条模块化问题）。
