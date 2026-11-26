# 架构与设计逻辑审查（Visual Timer Ext）

> 审查方式：**纯静态阅读**（本机无 JDK / Android SDK / Gradle，不能编译）。
> 辅助手段：`tools/resolve.js`、`tools/unused2.js`、`tools/checkbraces.js`、`tools/kotlin_lint.js`，
> 外加一个自写的"函数体逐字重复"扫描脚本（临时目录运行，未改动仓库）。
> 报告日期：2026-11-26。被审代码：`app/src/main/java/.../ext/`，**75 个 .kt / 约 14,570 行**。
>
> **本报告不建议引入 ViewModel / Hilt / Navigation-Compose / 多模块工程。**
> AGENTS.md 里"刻意精简依赖"的选择是对的，下面所有改法都在这个约束内可落地。

---

## 0. 先看这里：文档与代码已经漂移的四处

这些不是挑剔文档：**过期描述直接影响后续判断**（§2.23）。

| 文档写的 | 代码实际 | 证据 |
|---|---|---|
| Room **版本 6**，v1→v6 | **version = 7**，6 个迁移（1→2 … 6→7） | `data/AppDatabase.kt:207`、`:328` |
| 已知问题 #1：`fallbackToDestructiveMigration()` 静默清库 | 已改成 `fallbackToDestructiveMigrationOnDowngrade()` + 启动期捕获 | `data/AppDatabase.kt:329`、`ExtApplication.kt:502-509` |
| **无单元测试**，`app/src/test` 不存在 | **4 个测试文件 / 65 个 `@Test`** | `app/src/test/java/.../StepSequenceEngineTest.kt` 等 |
| 61 个 Kotlin 文件 | 75 个（含 test） | 实测 |

其中"无单元测试"这条最有害：它让后续改动倾向于"复制一份 private 函数"
（§2.10 的注释就是活证据），而不是"抽到 domain 加测试"。

---

## 1. 现状架构图（实际依赖方向，不是 README 里的愿景）

### 1.1 包与规模

```
ext/
├── ExtApplication.kt            567   ← 唯一的"编排层"，混了 5 种职责（§3.1）
├── data/                        445   Room 实体 + 6 个 DAO + 全部迁移
├── domain/                      651   PanelRunner / StepSequenceEngine / DeadlineCalculator（纯 Kotlin）
├── settings/SettingsRepo.kt     465   DataStore
├── alarm/                       597   振动 / 闪屏 / 手电筒 / 耳机声 / AlertDispatcher
├── service/                    1003   4 个前台服务 + 2 个 Receiver + 1 个自绘 View
├── io/                          476   BackupFormat（DTO/ref）+ BackupManager（SAF）
├── llm/                         816   HttpURLConnection 客户端 + 路由 + 提示词 + JSON 解析
├── panels/PanelState.kt         262   ← **已死代码**（§3.4）
└── ui/                         ~7000  12 个子包
    ├── panels/    PanelsScreen 1348 / SequenceCell 586 / PanelDialogs 497 / TimerCells 402 …
    ├── sequences/ SequencesScreen 428 / SequenceRunnerScreen 254 / SequenceFocusScreen 163 …
    ├── wheel/     WheelTimerView 542 / NumberWheel 296 / HmsWheelPicker 228
    └── settings/  SettingsScreen 594 / AiSettingsCard 255
```

### 1.2 实际依赖图

```
                     ┌───────────────────────────┐
                     │       ui/  (12 个子包)      │
                     └───────────────────────────┘
     85 处 DAO 直调 │  ~110 处 settings 直读 │            │
          │                     │             │            │
          ▼                     ▼             ▼            ▼
     ┌────────┐          ┌─────────┐    ┌────────┐   ┌──────────┐
     │ data/  │          │settings/│    │ service/│   │  alarm/  │
     └────────┘          └─────────┘    └────────┘   └──────────┘
          ▲                                  │  ▲          ▲
          │                                  │  │读 App 上   │
          │                                  │  │的 3 个缓存 │
     ┌────┴────────┐                         │  │          │
     │  domain/    │                         ▼  │          │
     │ (纯 Kotlin)  │◄───────────────┌────────────┴──┐     │
     └─────────────┘                │ ExtApplication │─────┘
                                    │    (567 行)    │
                                    └────────────────┘
```

### 1.3 这张图里最重要的三条事实

1. **`ui/` 是事实上的"应用层"**：既是表现层，又是用例层。UI 里直接调 DAO **85 处**
   （`PanelsScreen` 65 / `SequencesScreen` 12 / `HomeScreen` 6 / `AlertsScreen` 2）。
2. **`domain/` 是唯一分层成立的地方**：三个文件只依赖 `data/` 的实体，不碰 Android、不碰 Compose。
   65 个单测全在它身上 —— 这是全工程唯一"可验证"的部分。
3. **`service/` 没有自己的状态**，靠读 `ExtApplication` 上的 3 个 `@Volatile` 缓存
   （`seqOverlayInfo` / `flashIntervalMillis` / `database`）工作，缓存维护逻辑全写在 Application 里。

### 1.4 `ui/panels/PanelState.kt` 是一块没有接上的死代码

`rememberPanelState()` 全工程**零调用**（grep 只命中自身声明 `PanelState.kt:42`）。
它的内容**逐行复制**在 `PanelsScreen` 里：

| 逻辑 | PanelState.kt（死） | PanelsScreen.kt（活跃） |
|---|---|---|
| 面板订阅 | `:49-50` | `:119-124` |
| 当前面板计时器 | `:56-58` | `:128` |
| allTimers / allRefs | `:60-63` | `:239-242` |
| groups / allGroupMembers | `:65-69` | `:244-248` |
| 设置项 5 连 | `:81-85` | `:111-117` |
| gridEntries | `:222-236` | `:282-296` |
| persistOrder | `:239-261` | `:344-364` |
| runningCountByPanel | `:127-137` | `:389-399` |
| stepsBySequence | `:117-124` | `:369-376` |

**后果**：AGENTS.md 把"数据订阅已抽到 PanelState.kt"记为已完成项，它其实是**没接上的副本**。
以后谁去改 `PanelState.kt` 都不会有可见效果，而两份会持续漂移。
这是"看起来重构过、其实没重构"的典型形态，比不做更危险。

---

## 2. 设计逻辑问题清单

> 格式：**问题 → 证据 → 后果 → 建议**。⚠️ = 用户可感知的行为错误；📐 = 一致性/可维护性；🧹 = 死代码。

### ⚠️ 2.1 提前提醒（DDL lead reminder）全链路是死的

**证据**

1. 建 DDL 时写库并塞给运行时：`PanelsScreen.kt:1289-1296`（`clearTimer` + `upsertAll`）、
   `:1298`（`runner.setLeadMinutes`）、`:1303`（`markLeadsBaseline`）。
2. `PanelRunner` 能力齐全：`PanelRunner.kt:96` `leadMinutesOf`、`:98` `setLeadMinutes`、
   `:255-261` tick 里发 `_leadAlerts`。
3. `ExtApplication` 订阅了它并弹通知 + 震动 + 声音：`ExtApplication.kt:363-369`。
4. **但是**：
   - `setLeadMinutes` 全工程只有 `PanelsScreen.kt:1300` 一处调用；
   - 冷启动时 `leadMinutesOf` 永远是空 map，**没有任何代码从 `deadline_reminders` 表读回档位**
     （`deadlineReminderDao().forTimer` 与 `.pending()` 在 `AppDatabase.kt:175` / `:178`
     定义后**零调用**）；
   - `markFired()`（`AppDatabase.kt:191`）也**零调用**，`fired` 列永远是 `false`；
   - 带提前提醒创建 DDL 的唯一入口 `PendingDeadline`（`PanelsScreen.kt:1345-1349`）
     只能通过 `showDeadline` 打开（`:1217`），而 `showDeadline` 置 true **全工程零处**；
   - `TimerEditDialog` 的"截止时刻"模式（`PanelDialogs.kt:247-270`、`:296`）返回的
     `deadlineEpoch` 在新建分支里**只用于 `startUntil`、从不落库**（`PanelsScreen.kt:1172-1193`、`:1269-1275`）。

**后果**：一个功能从 UI 到 DB 到运行时每层都写了，但两端没接上。用户要么进不去（无入口），
要么重启后提醒静默消失。更糟的是 `deadline_reminders` 一直被导出进备份
（`io/BackupManager.kt:105`、`:204-213`），制造"功能在正常工作"的错觉。

**建议**（2 次提交）：

1. `PanelRunner` 增加 `fun attachLeads(timerId: Long, leads: List<Long>)`，由**调用方**
   从 `deadlineReminderDao().forTimer()` 读出后交进来（domain 不碰 DAO）。
2. `ExtApplication.onCreate` 增加 `restoreDeadlines()`：查
   `kind='Deadline' AND deadlineEpochMillis > now` 的计时器重建 `TimerRuntime` 并 `attachLeads`。
   这一步同时修掉 §2.21 的冷启动问题。
3. 给 `showDeadline` 一个入口（面板长按菜单加一项），**或**删掉 `DeadlineDialog` + `PendingDeadline`
   整条路径，让 `TimerEditDialog` 成为唯一 DDL 入口（§2.15 的重复也一并消失）。

---

### ⚠️ 2.2 警报配置选了也不生效：`AlertProfile` 只用了 1/4 个字段

**证据**

- 到点震动**确实**从 profile 取波形：`ExtApplication.kt:426-435` ✅
- 提前提醒的震动**硬编码**：`ExtApplication.kt:366` `longArrayOf(0L, 300L, 150L, 300L)`
- `screenFlash` 可在 UI 修改（`AlertsScreen.kt:134-141`），但到点闪烁**不看它**：
  应用内只看 `alerting` 集合非空（`ScreenFlashOverlay.kt:61-79`），
  后台只看 `canDrawOverlays`（`AlertOverlayService.kt:67`）。
- `torchFlash` 可在 UI 修改（`AlertsScreen.kt:152-159`），但 `TorchAlerter.start()`
  只在 `AlertDispatcher` 里被调（`alarm/AlertDispatcher.kt:53`），
   而 `AlertDispatcher` **只在警报页预览时被 new**（`AlertsScreen.kt:85-86`）。
- `soundEnabled` 同上，且 `AlertDispatcher.kt:56-57` 明写"本版本不实现音频播放"。

**后果**：用户在警报页拨了「屏幕闪烁 开/关」「手电筒 开/关」，**到点毫无反应**。
典型的"UI 承诺了、实现没兑现"。

**建议**：把 `ExtApplication.observeAlerts()`（`ExtApplication.kt:330-413`）里的三处
换成应用级的 `AlertDispatcher` 实例（拆成 `AppAlertDispatcher(context, scope)`，
闪屏复用 `ScreenFlashOverlay` 的 `alerting` 流、手电筒直接 `TorchAlerter.start`）。
**更保守的替代**：先把 `AlertsScreen.kt:134-160` 两个 Switch 删掉——别留改了没效果的开关。

---

### ⚠️ 2.3 「已读」粒度与通知不一致：通知点了不停提醒

**证据**

- `PanelRunner.acknowledge(id)` 是按 id 的（`PanelRunner.kt:124-126`）✅
- 到点通知在 `rt.timerId` 上（`ExtApplication.kt:478`），但它的 `PendingIntent`
  只是 `getLaunchIntentForPackage`（`ExtApplication.kt:453`）—— **打开 App 不等于 acknowledge**。
- 唯一会停提醒的路径是用户去点计时器卡片（`PanelsScreen.kt:685-698` 走 `stop`/`resume`/`start`）。
- `clearAll()` 是**全清**（`PanelRunner.kt:329-332`），任何批量操作都可能顺带清掉其他计时器的提醒。

**后果**：A、B 同时到点，用户从通知进 App 处理了 A，B 的通知还在，
但震动是否恢复取决于上一次 `startLoopingVibration` 的循环还在不在跑
（`ExtApplication.kt:372-380` 只在集合内容变化时启停）—— 行为不确定。

**建议**：给 `TimerRuntime` 加 `acknowledged: Boolean`；
通知的 `PendingIntent` 改成指向一个 `BroadcastReceiver`，收到即 `acknowledge(timerId)`；
Application 侧改成"`alerting` 里存在 `!acknowledged` 的条目才震动"。一处改动修一类问题。

---

### ⚠️ 2.4 计时器改名在从列表编辑时会把运行时整条清掉

**证据**

- `PanelRunner.updateLabel()`（`PanelRunner.kt:300-304`）只被 `PanelsScreen.kt:581` 调用（全屏改名）。
- 列表模式单击 → `TimerEditDialog` → `db.timerDao().upsert(...)`（`PanelsScreen.kt:1138-1155`）
  → 然后 `runner.clear(editingTimer.id)`（`:1158`），**运行时整条删除**。

**后果**：计时器**正在跑**时从列表改名，`clear()` 丢掉运行时状态（`PanelRunner.kt:324-327`），
倒计时归零，正在响的提醒也会被 acknowledge。用户视角是"只改了个名字，计时器被重置了"。

**建议**：`:1158` 改成按情况分支——
运行时不存在 → 什么都不用做；时长没变 → `runner.updateLabel()`；时长真变了 → 才 `clear()`。
前提是把"改名"和"改时长"拆成两个不同的 `onConfirm` 语义。

---

### ⚠️ 2.5 同一个「已过期」语义有 3 个判据，全屏和卡片给出相反结论

**证据**

| 判据 | 定义 | 位置 |
|---|---|---|
| `expired` | `overdueMillis > 0 \|\| (remaining <= 0 && total > 0)` | `PanelRunner.kt:42-43` |
| `isOverdue` | `expired && running` | `PanelRunner.kt:52` |
| 全屏中央控件 | `runtime?.isOverdue == true` | `TimerFocusScreen.kt:93` |
| 卡片状态行 | `(runtime?.overdueMillis ?: 0L) > 0L` | `TimerCells.kt:140`、`:147` |
| 卡片"重置"按钮 | `!running \|\| runtime.isOverdue` | `TimerCells.kt:154` |
| 全屏按钮 | `if (running) onPause else onStart` | `TimerFocusScreen.kt:276` |
| 盘面颜色 | `overdueMillis > 0` | `WheelTimerView.kt:106` |

AGENTS.md 专门写了一节"必须用 `isOverdue` 不能用 `expired`"，但 6 个调用点用了 3 种判据。

**两个可复现的后果**

1. **已超时 → 暂停 → 进全屏**：中央控件显示"暂停"（`TimerFocusScreen.kt:116` 的 `showInner`
   用的是 `isOverdue = false`），而卡片状态行仍显示"超时 03:12"（`TimerCells.kt:140`）。
2. **暂停 → 全屏点中央按钮**（`TimerFocusScreen.kt:235-241`）：`overdue=false`、`running=false`
   → 走 `onStart()` → `PanelsScreen.kt:551-565` 判断 `cur != null && !cur.running && !cur.expired`
   → `expired` 为 true 使条件不成立 → 落到 `else`：`runner.clear(); runner.start()` = **重置**。
   这正是 AGENTS.md 说已修好的 bug，在**全屏中央按钮**这条路径上又回来了；
   网格上的同一操作走 `PanelsScreen.kt:693-698`（`running → stop`，`else → resume`），**逻辑不同**。

**建议**：把点击语义收敛为 `PanelRunner` 上唯一方法
`fun tap(timer: TimerItem): TapResult`，`TimerCells` / `TimerFocusScreen` /
`DraggableGrid.onClick` / 悬浮窗按钮全调它。这是本报告**收益/风险比最高**的一条：
纯 Kotlin、可直接用 `StepSequenceEngineTest.kt:54` 那套 `runTest` 写法加单测。

---

### ⚠️ 2.6 列表模式"删除"只解除面板关联，不删计时器，文案却说"已删除"

**证据**

- 卡片删除 `PanelsScreen.kt:757-772`：`runner.clear` + `panelDao().removeRef`，**无 `deleteById`**。
- 列表批量删除 `PanelsScreen.kt:610-624`：同样只 `removeRef`，然后 `toast("已删除 $n 个计时器")`。
- 对照：拆解流程**已经改对了**——`PanelsScreen.kt:999-1000` 明确加了 `deleteById`，
  且注释写着"只 removeRef 会留下孤儿行"。

**后果**：删掉的计时器仍在库里，于是
(1) 被 `BackupManager.buildBackup()` 全量导出（`BackupManager.kt:72`）；
(2) 出现在面板编辑器的添加列表里（无 `deleteById` 的那批仍为 `allTimers`）；
(3) 批量删除**没有确认框**。

**建议**：抽 `PanelActions.deleteTimer(panelId, timerId)`，内部固定
`runner.clear → removeRef → deleteById`，卡片与列表两处都走它，并补确认框。

---

### ⚠️ 2.7 「组」功能：代码 300+ 行，实际入口几乎不可达

**证据**

- `gridEntries` 包含 `EntryKind.Group`（`PanelsScreen.kt:282-296`、`PanelState.kt:224-236`）。
- 建组唯一入口 `onCreateGroup`（`PanelsScreen.kt:1044`）挂在 `PanelEditorDialog` 上，
  而该按钮要求**选中 ≥2 个计时器**（`PanelEditorDialog.kt:289` `selected.size >= 2`）；
  `PanelEditorDialog` 本身只能从 `PanelManagerDialog.onEdit`（`PanelsScreen.kt:847`）进入，
  这条路径**不携带任何计时器选中状态**。

**后果**：`groups` 实际恒为空，`TimerGroupCell.kt`（152 行）与组相关分支是"理论可达、实际不可达"。
同时它给 `backup`、`MIGRATION_2_3`、两个 DAO 带来持续维护负担。

**建议**：先确认功能取舍。不做 → 删 `EntryKind.Group` 分支（`PanelsScreen.kt:737-751`）、
`TimerGroupCell.kt`、UI 侧 `groupDao` 调用（迁移不能删，保留）。要做 → 在面板管理里补一个明确入口。

---

### 📐 2.8 时长上限三套口径，且 DDL 可以超过盘面量程

**证据**

- 盘面 `PieTimerView(maxMinutes = 60)`（`WheelTimerView.kt:60`、`:100`），
  **5 个调用点全部硬编码 60**（`TimerCells.kt:113`、`:329`、`TimerGroupCell.kt:106`、
  `PanelDialogs.kt:233`、`TimerFocusScreen.kt:209`）。
- `DurationSlider` 是 10 秒 .. **6 小时**（`DurationSlider.kt:43-44`）。
- `HmsWheelPicker` 无上限。
- `HomeScreen.createTimer()` 把"目标时刻 − 现在"直接写库（`HomeScreen.kt:138-145`、`:245`）。

**后果**

1. 首页建 8 小时计时器 → 盘面 `progress` 起始就是 1.0，但刻度只画 60 分钟
   （`WheelTimerView.kt:238`），看起来"一直满格不动"。
2. `ColorMode.Interval` 的桶数是 `totalMillis / intervalMinutes`（`WheelTimerView.kt:447-452`），
   8 小时按 5 分钟一档 = 96 桶，`trafficStepColor` 给出 96 个几乎一样的颜色 —— **等间隔模式对长时长失效**，
   与 AGENTS.md「三种模式全部走同一条红绿灯色带」的承诺不符（色带只有 3 个锚点，96 桶是退化的）。
3. `TimerFocusScreen` 拖动改时长时 `maxMillis = 1 小时`（`WheelTimerView.kt:99-100` +
   `TimerFocusScreen.kt:210`），3 小时的计时器在全屏视图里**根本无法用拖动调整**。

**建议**：新建 `domain/DurationRange.kt`（纯 Kotlin，可单测）：
```kotlin
object DurationRange {
    const val MIN = 10_000L
    const val MAX = 6 * 3_600_000L
    fun dialMinutesFor(totalMillis: Long): Int =
        if (totalMillis <= 3_600_000L) 60 else ((totalMillis / 3_600_000L) + 1).toInt() * 60
}
```
5 个调用点改传 `DurationRange.dialMinutesFor(total)`。一处改动、五个调用点受益。

---

### 📐 2.9 任务点名的 `fractionDivisions` 已修好；真正还漂的是「阈值色阶」

**先说结论**：`fractionDivisions` 的三处范围**已经统一**，这点必须写清楚，避免误伤：

- 唯一定义 `SettingsRepo.kt:441-443`（`MIN=2 / MAX=12 / DEFAULT=4`）
- 读夹取 `:176-178`、写夹取 `:337`
- 滚轮值域由常量生成 `SettingsScreen.kt:73-75`
- 盘面取色 `WheelTimerView.kt:461-464`、色带预览 `NumberWheel.kt:250-253`

残留小问题：UI 首帧默认值仍写死 `4`（`SettingsScreen.kt:94`、`PanelsScreen.kt:116`、
`PanelState.kt:84`、`WheelTimerView.kt:76`），与 `DEFAULT_FRACTION_DIVISIONS` 无引用关系。

**真正还在各写一套的是「变色阈值的颜色阶」**：

| 环节 | 取值 | 位置 |
|---|---|---|
| 解析时夹取 | `p.coerceIn(0, 3)` | `SettingsRepo.kt:452` |
| 文档语义（注释） | 0=主色 1=琥珀 2=橙 3=红 | `SettingsRepo.kt:55` |
| 设置页色点 | **`ExtColors.Palette[idx]`**（8 色调色板） | `SettingsScreen.kt:222` |
| 面板把阈值传给盘面 | **`ExtColors.Palette[idx]`**（同样 8 色） | `PanelsScreen.kt:340-344`、`PanelState.kt:111-115` |
| 新阈值档位分配 | `thresholds.size.coerceIn(0, 3)`，**不校验重复** | `SettingsScreen.kt:468`、`SettingsRepo.kt:318-324` |
| 盘面实际取色 | 按"剩余落到第 i 档就用第 i 个颜色" | `WheelTimerView.kt:483-507` |

**后果**：默认值 `DEFAULT_THRESHOLDS = "60:0,10:1,3:2,1:3"`（`SettingsRepo.kt:432`）在设置页画成
**绿 / 黄绿 / 黄 / 橙**（Palette 前 4 个），而设计意图是 **绿 / 琥珀 / 橙 / 红**。
于是设置页色点与盘面颜色**不是同一套**——正是 AGENTS.md 在 `PaletteStrip` 注释里
（`NumberWheel.kt:233-237`）说已经修过的那个问题，但**只修了 Fraction 模式，Threshold 模式没修**。

**建议**：`ColorThreshold.paletteIndex` 改名 `colorStep`，取值改为 `0..(TrafficAnchors.size-1)`，
新增 `const val THRESHOLD_COLOR_STEPS`，设置页色点与盘面**都走 `ExtColors.trafficStepColor`**。
新增阈值时按"当前档位 +1 mod STEPS"分配，保证不重复。

---

### 📐 2.10 时长/时刻格式化 6 个实现，其中 2 个逐字相同

脚本比对（去注释 + 去空白后逐字比较顶层函数体）唯一命中的一组：

```
ui/panels/TimerFocusScreen.kt:49   fun clockOf(epochMillis: Long)
ui/sequences/SequenceRunnerScreen.kt:182  fun clockOf(epochMillis: Long)   ← 逐字相同
```

其余：

| 语义 | 实现 | 位置 |
|---|---|---|
| `HH:mm` | **3 份** | `ui/common/Formatters.kt:6`、`TimerFocusScreen.kt:49`、`SequenceRunnerScreen.kt:182` |
| 倒计时 `1h23m` | `formatRemaining` | `DeadlineCalculator.kt:91-102` |
| 大号 `1:23:45` | `formatBig` | `WheelTimerView.kt:386-393` |
| 合计 `2h10m` | `formatTotalDuration` | `SequenceDuration.kt:51-60` |
| `MM:SS` | `formatMmSs` | `SequenceOverlayService.kt:344-348` |
| `X分Y秒` | **2 份** | `SequenceOverlayService.kt:339-342`、`SequenceRunnerScreen.kt:176-179` |

`SequenceOverlayService.kt:344` 的注释本身就是诊断结论：
"照 SequenceRunnerScreen 的写法，不跨包引用 UI 层的 private 函数"——
**因为函数是 private 的，所以只能抄一份**。这是分层没做对之后的应对方式。

**建议**：`ui/common/Formatters.kt` 升级为 `util/TimeFormat.kt`（只依赖 `java.util`，无 Android 依赖），
6 个实现收进去，`service/` 与 `ui/` 共用。净删约 40 行。

---

### 📐 2.11 阈值模式下暂停仍会变色（AGENTS.md 承诺的不跳变只对两种模式成立）

**证据**

- `pickColor()` 顶层守卫：`if (!running && frac >= 0.999f) return ExtColors.TrafficStart`（`WheelTimerView.kt:433`）。
- `Threshold` 无配置走 `trafficColor(frac)`（`:439`）✅；
  **有配置**走 `colorForRemaining()`，而它第一行是 `if (!running) return ExtColors.Primary`（`:489`）。

**后果**：阈值模式有配置时，暂停一个跑到一半（橙色）的计时器，颜色跳回主色蓝。
这正是 AGENTS.md 说"用户明确要求不要"的现象。

**建议**：删掉 `colorForRemaining` 第 489 行那一句，把"未运行不跳色"的守卫留在 `pickColor` 顶层一处。

---

### 📐 2.12 序列暂停：引擎有完整实现和注释、卡片有状态展示、UI 没有入口

**证据**

- `StepSequenceEngine.pause()` / `resume()` 实现完整（`StepSequenceEngine.kt:129-151`），
  注释里复盘过一次"空转卡死"事故。
- `Phase.Running.paused` 被卡片使用（`SequenceCell.kt:278-279`、`:287`）。
- 但 `SequenceFocusScreen` 只有"开始 / 下一阶段 / 停止 / 重置"（`SequenceFocusScreen.kt:154-161`），
  `grep '\.pause\(\)' / '\.resume\(\)'` 在 `ui/` **零命中**。
- `StepSequenceEngineTest` 15 个用例里**没有一个**覆盖 pause/resume。

**后果**：`SequenceCell` 会显示"· 已暂停"，但**没有任何路径能进入它**。

**建议**：补 UI（收益大、不改引擎）：`SequenceFocusScreen` 的 `isActive` 分支加「暂停 / 继续」，
并在 `StepSequenceEngineTest` 补 2 个用例（暂停不扣时间、恢复接着扣）。

---

### 📐 2.13 三个设置项"存得有、读得没有"

| 设置 | 定义 | 写入 | 有效读取 | 结论 |
|---|---|---|---|---|
| `ringtoneUri` | `SettingsRepo.kt:185` | `AlertsScreen.kt:245`、`:338` | **无**：`ExtApplication.kt:141-142` 从不设置它 | 🧹 用户选的铃声永不生效 |
| `soundEnabled` | `SettingsRepo.kt:188` | `AlertsScreen.kt:276` | **无**：`HeadphoneSoundAlerter.kt:60` 不看它 | 🧹 关了总开关到点仍会响 |
| `backgroundEnabled` | `SettingsRepo.kt:197` | `SettingsScreen.kt:324` | **无**（只在 `SettingsScreen.kt:99`、`:320`） | 🧹 开关无任何效果 |

**建议**：`ringtoneUri` 与 `soundEnabled` 照 `ExtApplication.observeFlashSetting()`（`:176-180`）
的写法接上（约 8 行）。`backgroundEnabled` 建议**删开关**——它的语义与 `PanelRunner` 常驻设计矛盾。

---

### 📐 2.14 改提醒配置后，正在跑的计时器仍用旧配置

- `TimerRuntime.alertProfileId` 只在 `start` / `startStopwatch` / `startUntil` 时从实体复制
  （`PanelRunner.kt:170`、`:190`、`:223`）。
- 用户改配置：`PanelsScreen.kt:1116` `upsert(alertProfileId = pid)`，**没有对应的运行时同步方法**
  （`PanelRunner` 上根本没有 `updateAlertProfile`）。
- 对照 `PanelRunner.updateLabel` 的注释（`:291-299`），它精确描述了同一个坑，**但只对 label 做了同步**。

**建议**：照 `updateLabel` 加 `updateAlertProfile(timerId, profileId)`（6 行），在 `:1116` 之后调用。

---

### 📐 2.15 同一"计时器"两条创建路径，字段规则不同

| | 面板里新建 | 首页快捷建 |
|---|---|---|
| 代码 | `PanelsScreen.kt:1127-1160` / `:1166-1195` | `HomeScreen.kt:228-256` |
| `kind` | 固定 `Countdown`（即使选了截止时刻） | 按按钮传 |
| `deadlineEpochMillis` | **不写**（只拿去 `startUntil`） | 写入 |
| `locked` | 不写（默认 false） | 即 DDL **也不写**（默认 false） |
| 落哪个面板 | 当前面板 | **第一个面板** |

**后果**：
1. 面板里选"截止时刻"建的计时器**不是 DDL**（`kind = Countdown`、`deadlineEpoch = null`），
   重启后只是"剩余 8 小时"的普通倒计时，**与实际截止时刻脱钩**，用户以为建了 DDL。
2. 首页建的 DDL `locked = false`，靠 `isEditable` 的 `kind != Deadline` 兜住
   （`TimerItem.kt:47-48`），但 `locked` 列从此有了第三种语义，与 `MIGRATION_6_7`
   的回填规则（`AppDatabase.kt:305`）不一致。

**建议**：合并到 `PanelActions.createTimer(...)`，三个字段的规则只写一次：
```
kind   = if (deadlineEpoch != null) Deadline else Countdown
locked = kind == Deadline
duration = if (deadlineEpoch != null) deadlineEpoch - now else 用户时长
```

---

### 📐 2.16 `locked` 的回填在写回路径上被抹掉

`TimerItem.isEditable` 的双保险设计是对的（`TimerItem.kt:35-48`），这条经验值得保留。但仍有第三种写法：

- `PanelsScreen.kt:1152`：`locked = keepDeadline && editingTimer.locked` —— **直接读 `locked` 再写回**。
- `PanelsScreen.kt:1256`：`commitDeadline` 里硬编码 `locked = true`。

**后果**：一条 `locked = false` 的老 DDL（v6 前数据 / 备份导入数据）被编辑保存后 `locked` 仍是 `false`，
正是 `MIGRATION_6_7`（`AppDatabase.kt:299-307`）刚花力气回填、`isEditable` 注释
（`TimerItem.kt:38-40`）刚警告过的那类"静默退回"。

**建议**：`:1152` 改成 `locked = keepDeadline`（DDL 一律锁）；
或给 `TimerItem` 加 `fun withLockRule() = copy(locked = kind == Deadline)`，所有 upsert 前过一遍。

---

### 📐 2.17 `timeoutAutoAdvance` 是死字段，且与产品要求冲突

- 字段：`StepSequence.kt:59-60`；备份 DTO 也带着它（`BackupFormat.kt:86`、`:222`）。
- 引擎**完全不读**（`StepSequenceEngine.kt` 全文无 `autoAdvance`）。
- 文档明确要求"超时**不会自动跳步**，必须用户点击"（`StepSequenceEngine.kt:30-31`）。

**建议**：从备份 DTO 里删掉（`ignoreUnknownKeys = true` 保证旧文件仍能导入）；
实体字段保留但标 `@Deprecated` 并注明"永远 false"（Room 不支持删列）。

---

### 📐 2.18 删除一律"父删子留"，孤儿行会进备份并让计时器凭空消失

| 删除动作 | 级联情况 | 位置 |
|---|---|---|
| 删面板 | **不删** `panel_timers` / `timer_groups` / `group_members`，不置空 `sequences.panelId` | `PanelsScreen.kt:863`、`:1036-1041` |
| 删序列 | 删 steps ✅ | `PanelsScreen.kt:312-333`、`SequencesScreen.kt:166-186` |
| 删组 | 删 members ✅ | `PanelsScreen.kt:1065-1071` |
| 删计时器 | **不删** `deadline_reminders` / `group_members` | §2.6 |
| 替换式导入 `wipeAll` | 删 6 类，**漏 `panel_timers`** | `BackupManager.kt:229-244` |

Room 里**没有任何 `@ForeignKey`**（`AppDatabase.kt:194-209`）——这是有意选择（避免清库时约束爆炸），
代价是每条删除路径都必须手工级联，**目前只有 2/5 条做到了**。

**最具体的后果**：删面板残留的 `timer_groups.panelId` 指向不存在的面板 →
`observeGroups(panelId)` 查不到，但 `allGroupMembers` 仍返回这些成员（`PanelsScreen.kt:247`）→
`groupedTimerIds` 把它们排除在网格外（`:292` `timers.filter { it.id !in groupedTimerIds }`）
→ **计时器凭空消失**。

**建议**：新建 `data/CascadeDelete.kt`（4 个纯函数），按
`面板 → (refs, groups, group_members, sequences.panelId) → 计时器 → (reminders)` 顺序写死；
7 条删除入口全调它。同时给 `wipeAll` 补 `panel_timers`（`observeAllRefs()` 已存在，`AppDatabase.kt:58`）。
**单次改动约 60 行，消灭 5 个缺口。**

---

### 📐 2.19 Room 迁移链逐条核对 + 一处更隐蔽的降级清库风险

| 迁移 | 内容 | 风险 |
|---|---|---|
| 1→2 | `timers` + `kind DEFAULT 'Countdown'`、`+ deadlineEpochMillis` | 可接受（老数据本就看不出 DDL） |
| 2→3 | 建 `timer_groups` / `group_members` | 纯加法 ✅ |
| 3→4 | `sequences` + `panelId` | 纯加法，老序列 `panelId = NULL`（不在任何面板里）✅ |
| 4→5 | 建 `deadline_reminders` | 纯加法 ✅ |
| 5→6 | `timers` + `locked DEFAULT 0` | ❌ **老 DDL 全成可编辑**（已在 6→7 回填） |
| 6→7 | `steps` + `difficulty` / `context`；`UPDATE timers SET locked=1 WHERE kind='Deadline' AND locked=0` | ⚠️ 回填规则与写回路径不一致（§2.16） |

**关于 `fallbackToDestructiveMigrationOnDowngrade()`**（`AppDatabase.kt:329`）：
注释（`:319-320`）说"仅在降级安装时允许破坏性回退，属于用户主动行为"。
但 CI 产物分发（AGENTS.md 描述的装机流程）里回滚旧 APK 很常见，而它**静默删库**——
这正是 AGENTS.md 已知问题 #1 描述的坏行为，只是触发条件缩小了。
`ExtApplication.kt:502-509` 那段精心写的"迁移失败提示"**接不住它**（它不抛异常，它删表）。

**建议**：删掉这一行（1 行）。降级时 Room 抛异常 → 被 `ExtApplication.kt:502-509` 捕获 →
弹"数据库打不开，请先备份再反馈"——**同一份代码已经设计好这个交互，白用不用。**

**另：`vibrationRepeatIndex` 也是死字段**——`AlertProfile.vibrationRepeatIndex`（`AlertProfile.kt:22`）
默认 `-1`、播种时也写 `-1`（`ExtApplication.kt:516`），但 `AlertDispatcher.kt:47` 在 `loop=true` 时
强制成 `0`，而真正在用的 `ExtApplication.startLoopingVibration` **完全忽略它**、硬编码
`repeatIndex = 0`（`ExtApplication.kt:435`）。字段存在、有默认值、有 UI 语义，实际永远被当成"无限循环"。

---

### 📐 2.20 跨层循环依赖：`panels/` 依赖 `ui/`，而它本该是被 `ui/` 使用的层

```
panels/PanelState.kt:27   import ...ui.ExtColors          ← 全局可变单例
panels/PanelState.kt:28   import ...ui.panels.EntryKind   ← UI 类型
panels/PanelState.kt:29   import ...ui.panels.PanelEntry  ← UI 类型
            ↓
ui/panels/  （PanelEntry 与 ExtColors 的定义处）
```

`panels` 包只有一个文件，它不是"从 UI 抽出的状态层"，而是**依赖 UI 类型、且没被使用的副本**。
即使把它接上，它也不可能是"逻辑层"——AGENTS.md 明确说 `ExtColors` 不要在 Composable 之外长期缓存。

**建议**：**删除 `panels/PanelState.kt`**（-262 行）。
要抽状态，正确边界是 `panels/PanelActions.kt`：只依赖 `data/` + `domain/` + `settings/`，
暴露 `suspend fun`，不碰任何 Compose 类型（见 §3.3）。

---

### 📐 2.21 计时器运行时在进程重启后全部丢失（"序列状态未持久化"的同类问题）

- `PanelRunner._runtimes` 是纯内存 `MutableStateFlow`（`PanelRunner.kt:67`），
  没有任何持久化，`ExtApplication.onCreate` 也不做恢复（`ExtApplication.kt:165-174`）。
- `BootReceiver` 是空实现 + `// TODO`（`BootReceiver.kt:16`），且**即使实现了也帮不上**：
  它只处理 `ACTION_BOOT_COMPLETED`（`:15`），杀进程再打开时不会触发。
- README「已知限制」只写了"**序列**状态未持久化"，但**普通计时器和 DDL 同样不持久化**。

**后果**：进程被杀（低内存、强制停止、系统回收）后，所有正在跑的计时器与 DDL 一起消失；
一个"差 2 小时到期"的 DDL 从此不再提醒。这是这类 App 用户最难原谅的一类问题。

**建议**：与 §2.1 合并做——`ExtApplication.onCreate` 里加 `restoreRuntimes()`：
查所有 `kind='Deadline'` 且 `deadlineEpochMillis > now` 的，重建为 DDL 运行时；
普通倒计时按 README 的既定取舍**可以不恢复**，但**DDL 必须恢复**（它的语义是绝对的）。
`BootReceiver` 可以保持"什么都不做"，只要它不做假动作。

---

### 📐 2.22 既有约定"新增代码要记日志"没落实，而检查脚本看不见自己

- `runCatching` 共 **64** 处（AGENTS.md 写的是 50 处，实际更多）；`logCatching*` 只有 **9** 处，**全在 `ExtApplication.kt`**。
- `ui/` 里 **0 处** `logCatching`，`PanelsScreen.kt:373`、`:393` 仍是裸 `runCatching`。
- `tools/unused2.js` 第 15 行是 `if (unused.length > 1) console.log(...)` ——
  **"只有一个未使用 import"的文件永远静默通过**。实测漏报 1 处：
  `ui/sequences/SequencesScreen.kt:45`（`StepSequenceEngine` 真未使用）。
  (`resolve.js` 报的 `PanelsScreen.kt:55` `ConfirmDeleteDialog` 是**误报**，`:1097` 确实在用，
  说明该脚本的匹配规则本身也不精确。)

**建议**：`unused2.js` 的 `> 1` 改成 `> 0`（1 行），再补误报规则。
**会撒谎的检查比没有检查更危险**——AGENTS.md 要求"推送前三项全绿"，而其中一项漏报的恰好是它自己声称在管的东西。

### 📐 2.23 文档与代码漂移

见 §0 表格，另加 `AGENTS.md:100-101` 的 Room 版本（写 6、实际 7）与
`AGENTS.md:28` 的"无单元测试"（实际 65 个 `@Test`）。这两条直接改变接手者的判断。

---

## 3. 模块化问题清单

### 3.1 🔴 `ExtApplication` 承担 5 种职责（567 行）

| 职责 | 行号 | 该属于 |
|---|---|---|
| (a) 依赖容器（DB / Settings / Runner / Engine 的 lazy） | `:43-163` | ✅ 保留，这是对的 |
| (b) 到点提醒编排（通知 / 震动 / 声音 / 拉起悬浮窗） | `:330-492` | `alarm/`（已有 `AlertDispatcher`，却只被 UI 预览用） |
| (c) 序列悬浮窗数据聚合 | `:184-326` | `domain/`（纯文本映射）+ `service/` |
| (d) 4 个前台服务启停编排 | `:220-237`、`:399-412` | `service/OverlayManager` |
| (e) 默认数据播种 + DB 打开错误处理 | `:496-524` | `data/` |

**(c) 是最典型的错位**，任务里举的例子完全成立：
`ExtApplication.kt:252-290` 的 `buildSequenceOverlayInfo()` 把 `Phase`
翻译成 10 个**展示字段**（`stepCaption` / `countdownText` / `awaitingConfirm` …）——这是视图模型；
翻译结果类型 `SequenceOverlayInfo` 被声明成 `internal` 放在 Application 层（`:548-567`），
注释（`:125-127`）自己解释"public 属性不能暴露 internal 类型（编译错误）"——
**编译器报警是对的，被压下去了**。
为了拼一个序列名，还引入了 3 个 `@Volatile` 字段（`:301-326`）；
为了判断是否显示，**每 200ms 查一次 `Settings.canDrawOverlays`**（`:217-219`）。

**后果**：Application 层直接连 `Room DAO`、`DataStore`、`Settings.canDrawOverlays`、
`NotificationManager`、`Vibrator`、4 个 Service 的 start/stop —— **它没有一行可被单测覆盖**，
而它恰恰是全局最复杂的逻辑。

**建议（3 步，每步独立可验证）**

1. `domain/SequenceOverlayText.kt`：`fun overlayTextOf(phase: Phase, name: String): SequenceOverlayInfo?`，
   把 `:252-290` 原样搬走 + **加 4 个单测**（Running / AwaitingConfirm / Idle / Finished）。
   类型归属正确后，`internal` 的限制自然消失。**不动任何 Android 代码，风险极低。**
2. 200ms 轮询（`:200-244`）改成
   `combine(phase, overlayEnabled, sequenceDao().observeAll())` + `distinctUntilChanged()`：
   序列名直接来自 Room，3 个 `@Volatile` 字段全部删除；`canDrawOverlays` 改成仅在
   `shouldShow` 变化时查一次。
3. `service/OverlayManager.kt` 统一 4 个服务的启停，Application 只留一行调用。

### 3.2 🔴 `service/` 4 个前台服务各自实现一遍同样的东西

| 功能 | 份数 | 位置 |
|---|---|---|
| `createChannel()` | **4** | `TimerOverlayService.kt:259`、`SequenceOverlayService.kt:352`、`AlertOverlayService.kt:268`、`SequenceService.kt:38` |
| `buildNotification()` | **4** | `TimerOverlayService.kt:270`、`SequenceOverlayService.kt:364`、`AlertOverlayService.kt:277`、`SequenceService.kt:54` |
| 拖动实现 | **2** | `TimerOverlayService.kt:126-143`、`SequenceOverlayService.kt:195-232` |
| `openApp` / `PendingIntent.getActivity` | **4** | `TimerOverlayService.kt:271-279`、`SequenceOverlayService.kt:332-336`、`AlertOverlayService.kt:260-264`、`SequenceService.kt:55-62` |
| `SDK_INT >= O ? startForegroundService : startService` | **3** | `TimerOverlayService.kt:297-301`、`SequenceOverlayService.kt:417-421`、`AlertOverlayService.kt:312-316` |
| `canDrawOverlays` 早退 | **2** | `TimerOverlayService.kt:69-74`、`SequenceOverlayService.kt:101-106` |

**建议**：新建 `service/OverlaySupport.kt`（约 80 行无状态工具：`channelOf` / `ongoingNotification` /
`startOrStop` / `attachDrag`）。4 个服务各删 20-60 行，不改变架构。

### 3.3 🔴 `ui/` 直接调 DAO 85 处，`PanelsScreen` 一个函数 1276 行

**规模事实**：`fun PanelsScreen(...)` 从 `:72` 到 `:1348`，**单函数 1276 行**，内含
**14 类业务写操作**、**16 个 `var` 对话框开关**（`:124-166` 一片）、**22 个 `collectAsState`**。

**它已经在犯这类错，而且同一个坑踩了两遍**：

- `:133-152` 的对话框开关全是普通 `remember`，切标签即丢
  （`MainActivity.kt:296-298` 只用 `SaveableStateProvider` 保 `rememberSaveable`）。
  于是"填了一半的计时器编辑框 / 正在确认的 DDL 第二步"在切标签后**静默丢失**。
- `:230-235` 的 `LaunchedEffect(toggleViewSignal)` 用 `if (toggleViewSignal > 0)` 判断，
  而该 signal **整个 Activity 生命周期单调递增、不复位**。`PanelsScreen` 被销毁重建时
  effect 首次执行且信号仍 > 0 → **视图模式莫名翻转一次**。`:231` 的注释声称"首次进入不会触发"——
  **这是错的**，只在"Activity 第一次创建且用户没点过"时成立。
  同一文件 `:179-210` 用 `lastHandledActionTick` 专门解决了这个问题（注释还详细写了这个坑），
  **但视图切换那条路径没照做**。

**跨层依赖清单**

| ui 文件 | 依赖 | 依据 |
|---|---|---|
| `PanelsScreen.kt` | `data` 6 个 DAO、`settings` 5 个 Flow、`domain` 3 个类型 | `:107-119`、`:128`、`:366` |
| `HomeScreen.kt` | `data` 2 个 DAO、`domain.PanelRunner/TimerKind`、直接写库 | `:57`、`:193`、`:209`、`:228-256` |
| `SettingsScreen.kt` | `io.BackupManager`、`service.TimerOverlayService`、`android.provider.Settings` | `:51-52`、`:103`、`:376-380` |
| `AlertsScreen.kt` | `alarm.AlertDispatcher`、`alarm.HeadphoneSoundAlerter`、`data.alertProfileDao` | `:37-38`、`:138` |

其中 **UI 反向控制 service** 最该注意：`SettingsScreen.kt:376-380` 在 UI 里直接
`TimerOverlayService.start/stop`，而 `ExtApplication.kt:399-412` 也在做同一件事。**两处都在启停同一个服务。**

**建议（不引 ViewModel 的可行做法）**：本项目已有 AGENTS.md 认可的 `rememberXxxState` 模式（§1.4，
只是用错了）。正确做法是**把"动作"也一起抽**：

```kotlin
// panels/PanelActions.kt —— 只依赖 data / domain / settings，零 Compose 类型
class PanelActions(
    private val db: AppDatabase,
    private val runner: PanelRunner,
    private val engine: StepSequenceEngine,
    private val scope: CoroutineScope,     // 调用方传 rememberCoroutineScope()
) {
    suspend fun createTimer(...): Long            // §2.15 的唯一建法
    suspend fun deleteTimer(panelId, timerId)     // §2.6
    suspend fun renameTimer(timerId, label)       // §2.4
    suspend fun deleteSequence(seqId)             // 级联 + engine.stop
    suspend fun deletePanel(panelId)              // §2.18
    suspend fun persistOrder(ordered)             // 一份实现
    fun tapTimer(timer: TimerItem)                // §2.5 的唯一点击语义
}
```

`PanelsScreen` 里 65 处 DAO 调用会降到 ~0。**先只搬 4 个已确认有 bug 的**
（`deleteTimer` / `renameTimer` / `persistOrder` / `tapTimer`），每个都能上单测。

### 3.4 🟠 重复实现清单（逐条核对过）

| # | 逻辑 | A | B | 差异 |
|---|---|---|---|---|
| 1 | `deleteSequenceCascade` | `PanelsScreen.kt:312-333` | `SequencesScreen.kt:166-186` | 两份都在注释里承认"和另一处保持一份实现"，**然后各写一份**；A 少清 `running`，B 少清 `focusSequenceId` |
| 2 | `persistOrder` | `PanelsScreen.kt:344-364`（活跃） | `PanelState.kt:239-261`（死） | 逐字相同 |
| 3 | `gridEntries` | `PanelsScreen.kt:282-296` | `PanelState.kt:224-236` | 相同 |
| 4 | 22 个数据订阅 | `PanelsScreen.kt:119-380` | `PanelState.kt:49-137` | 相同（已死） |
| 5 | 面板成员重建 | `PanelsScreen.kt:1019-1032`（逐个 `removeRef` + `upsertRefs`） | `BackupManager.kt:162-170` | A **无事务**，部分失败会丢成员 |
| 6 | 序列步骤重建 | `PanelsScreen.kt:452-470`（删光再插） | `BackupManager.kt:176-183` | A **无事务** |
| 7 | 计时器创建 | `PanelsScreen.kt:1166-1195` | `HomeScreen.kt:228-256` | 字段规则不同（§2.15） |
| 8 | DDL 创建 | `PanelsScreen.kt:1240-1309`（`commitDeadline`） | `PanelDialogs.kt:247-299` + `PanelsScreen.kt:1172-1193` | 一个写 `locked=true` + reminders，一个都不写 |
| 9 | 悬浮窗配色 | `service/OverlayDialView.kt:128-132`（十六进制字面量） | `ui/theme/ExtTheme.kt:44-49` | 两份字面量，主题切换时悬浮窗不跟着变 |

### 3.5 🟠 职责错位的类（逐个点名）

| 类 | 错位点 |
|---|---|
| `ExtApplication.kt`（567） | 5 种职责（§3.1） |
| `PanelsScreen`（单函数 1276 行） | 订阅 + 22 状态 + 14 类写操作 + 渲染（§3.3） |
| `PanelDialogs.kt`（497） | 对话框直接 `new AiFeatures`（`:96`）并调估时（`:152-165`）——领域逻辑写在 UI 文件里 |
| `HomeScreen.kt` | 时钟 + **计时器落库**（`:228-256`，`private fun` 无法复用） |
| `SettingsScreen.kt`（594） | 设置 + **备份导入导出**（`:114-138`）+ **启停悬浮窗服务**（`:376-380`）+ 权限引导（`:396-421`） |
| `alarm/AlertDispatcher` | 声明"UI 层只调用 dispatch/stopAll"（`:16`），**实际只有 UI 预览在用**，真正的到点警报在 Application 里手写（§2.2） |
| `service/SequenceService.kt`（95） | 只有一个调用方 `SequenceAlarmReceiver.kt:17`，而 `ACTION_ALARM`（`:22`）**全工程无发送方** |
| `service/SequenceAlarmReceiver.kt`（24） | Manifest 注册了（`AndroidManifest.xml:100-102`），**死代码** |
| `service/BootReceiver.kt`（18） | `// TODO`（`:16`），每次开机空跑 |

**两个 Receiver + `SequenceService` 合计 137 行是 100% 死代码**，但会出现在用户的通知设置里。
**建议**：删 `SequenceAlarmReceiver` 与 `SequenceService`（含 Manifest 条目）。

### 3.6 🟠 `domain/` 被 UI 打穿

1. **`PanelRunner` 的 lead 数据来自 UI**：`leadMinutesOf` 由 `PanelsScreen.kt:1300` 写入，
   却被 `domain` 的 `tick()` 读取（`PanelRunner.kt:255-261`）。
   一个纯 Kotlin 类的行为取决于 UI 有没有记得调用 —— **这就是 §2.1 那个 bug 的结构性原因**：
   没人会想到"冷启动时要重新喂一遍"。
2. **`DeadlineCalculator.formatRemaining` 承担了表现层职责**（`"${h}h%02dm"`），
   且被 `ui` / `service` 共 12 处调用。格式化属于表现层。

**建议**：`PanelRunner.start(timer, leads)` 把 leads 变成显式参数；
`formatRemaining` 搬到 §2.10 的 `util/TimeFormat.kt`。

### 3.7 🟠 备份导出存了两份面板顺序，导入只读其中一份

- `buildBackup()` 同时导出 `panelRefs`（`BackupManager.kt:59`、`:83`、`:112`）
  和 `PanelDto.timerRefs`（`:80-82`），后者**丢失 order**。
- 导入时 `:162-170` **只读 `timerRefs`**，`backup.panelRefs` **完全忽略**。
- 同时 `TimerItem.position` 原样导入（`BackupFormat.kt:195`），而网格排序用的是 `position`
  （`PanelsScreen.kt:293`），`order` 用的是 `mapIndexed` 下标（`BackupManager.kt:166`）。
  **两套顺序，导入后可以不一致 → 面板内顺序与导出前不同。**

另：`BackupFile.CURRENT_SCHEMA = 2` 的注释描述了 v1→v2 的迁移意图（`BackupFormat.kt:38-44`），
但代码里**只有 `:135-139` 的"版本太高则拒绝"，没有任何按版本分支的迁移函数**。

**建议**：schema 升到 3 时删掉 `panelRefs`（`ignoreUnknownKeys = true` 保证 v2 仍可导入），
并用 order 覆盖 position。

---

## 4. 最危险的三个位置

### 🥇 第 1 位：`ui/panels/PanelsScreen.kt` 的单一巨型 Composable（`:72-1348`，单函数 1276 行）

**为什么最危险**

1. **规模**：比 `ExtApplication` 还大一倍多。任何 `var` 作用域写错、任何 `LaunchedEffect` 的 key 写错，
   都在整页生效。
2. **它已经在犯这类错，且同一个坑踩了两遍**：`:133-152` 的 7 个对话框开关是普通 `remember`
   （切标签丢输入）；`:230-235` 的视图切换判据在组件重建后会重复执行（视图莫名翻转），
   而同文件 `:179-210` 已经为**同样的模式**写过正解却没推广过去。
3. **它是唯一同时握有 DAO、Runner、Engine、Settings 的地方**：§2.4 / §2.5 / §2.15 / §2.18
   的规则都在这里各自为政，新增任何一个功能都会长出第 N 套实现。
4. **行数最多且 0% 可测**：`ui/panels/` 下 7 个文件共约 3572 行，**一行都无法被现有测试覆盖**。

**下一次最可能出的 bug**：再加一个 `var` 或再往 `onClick` 加一个分支 → 状态串台 /
点击语义出现第 4 种实现。

**最小改法**：抽 §3.3 的 4 个动作（-200 行且可测）；7 个开关改 `rememberSaveable`（7 × 1 行）；
`:230-235` 照 `:179-210` 补 `lastHandledViewTick`（3 行）。

### 🥈 第 2 位：`domain/PanelRunner.kt` 与它在 `ui` 里的 6 个调用点（`PanelRunner.kt:124-332`）

**为什么第二危险**

1. **它是全局唯一计时真源**：错了则卡片、全屏、悬浮窗、通知、震动**五个出口同时错**。
2. **同一个动作已有 3 种判据**（§2.5 的表），而 AGENTS.md 专门为它写了一整节 ——
   说明这个文件历史事故密度最高。
3. **仍有一个活的重置 bug**：已超时 → 暂停 → 全屏点中央 → `clear() + start()`（§2.5 场景 2）。
   这条路径用户**每天都走**。
4. **`expired` 对正计时器恒 false**（`total == 0` 使 `:42-43` 的两个分支都不成立），
   而 `startStopwatch` 造出的正是 `total = 0`（`:186`）。任何想给正计时加提醒的代码都会踩空。
5. **`tick()` 双分支**（`:246-250`）：非 deadline 分支用 delta 累加，delta 来自
   `System.currentTimeMillis()` 差值（`:138-143`）—— 用户改系统时间时它会跳变，而 DDL 分支反而稳。

**最小改法**：新增 `fun tap(timer: TimerItem): TapResult` 作为唯一点击语义并替换 4 个调用点，
补 6 个 `runTest`（`StepSequenceEngineTest.kt:54` 的 `newEngine` 写法可直接照抄）。

### 🥉 第 3 位：`AppDatabase.kt` 的迁移链 + `BackupManager.kt` 的导入（`AppDatabase.kt:322-332`、`BackupManager.kt:126-244`）

**为什么第三危险**：前两位是"看得见的错"，这一位是**"数据没了但你不知道"**。

1. `fallbackToDestructiveMigrationOnDowngrade()`（`AppDatabase.kt:329`）在**降级安装**时**静默删库**。
   CI 产物分发场景里回滚旧 APK 是常事，而 `ExtApplication.kt:502-509` 的提示**接不住它**。
2. `wipeAll()` 漏删 `panel_timers`（`BackupManager.kt:229-244`）。
   目前 `replaceExisting` 在 UI 上恒为 false（`SettingsScreen.kt:132`），属"埋着的雷"。
3. **导入没有事务**：`import()`（`:126-227`）是一串独立 DAO 调用，中途失败（`:226` 会捕获并返回 `Failed`）
   **已写入的部分不回滚**——用户看到"导入失败"，库里已经多了一半数据。
4. `CURRENT_SCHEMA` 无版本分支：靠"新字段带默认值"蒙混，字段**语义**一改就没有兼容路径。

**最小改法**（三项各 1 行）：删 `fallbackToDestructiveMigrationOnDowngrade()`；
`import()` 主体包 `db.withTransaction { }`；`wipeAll()` 补 `panel_timers`。

---

## 5. 建议的演进顺序（按收益/风险排，不要一次全改）

> 原则：**先只改行为不搬文件；再搬纯函数并加测试；最后拆大文件。**
> 每一条独立成一次提交，遵循 AGENTS.md「推得碎比推得多重要」。

| 步 | 改动 | 收益 | 风险 | 验证方式 |
|---|---|---|---|---|
| **0** | 修 §0 的四处过期文档（版本 7、65 个测试、fallback 已改、75 个文件） | 防下一个人按错文档推迁移 | **零** | 文档 diff |
| **1** | 删 `panels/PanelState.kt`（-262）+ 删 `SequenceAlarmReceiver` / `SequenceService`（-119 + 2 条 Manifest） | 消除"假重构"与死服务 | **极低**（全工程零调用，已 grep 确认） | `resolve.js` 全绿 |
| **2** | 删 `fallbackToDestructiveMigrationOnDowngrade()`；`import()` 包 `withTransaction`；`wipeAll` 补 `panel_timers` | 挡掉两条静默毁数据路径 | **低**（不碰业务逻辑） | 手工降级安装试 |
| **3** | 抽 `util/TimeFormat.kt`，合并 §2.10 的 6 个格式化实现 | -40 行；`service` 不再抄 UI 的 private | **低** | 纯函数单测 |
| **4** | `PanelRunner.tap()` 统一 4 个调用点；`expired` 补 stopwatch 说明 | 修 §2.5 的全屏重置活 bug | **低**（纯 Kotlin） | `runTest` 单测 |
| **5** | `PanelsScreen.kt:133-152` 开关改 `rememberSaveable`；`:230-235` 补 `lastHandledViewTick` | 修"切标签丢输入"与"视图莫名翻转" | **低** | 真机试 |
| **6** | 改名不再 `clear()`（§2.4）；改提醒配置同步运行时（§2.14）；`locked = keepDeadline`（§2.16） | 修"改个名计时器被重置" | **中**（要真机） | 真机 |
| **7** | 新建 `panels/PanelActions.kt`，先迁 4 个已确认有 bug 的动作 | `PanelsScreen` -200 行且可测 | **中** | 单测 + 真机 |
| **8** | `DurationRange` 统一三套时长口径（§2.8）；阈值色阶走 `trafficStepColor`（§2.9） | 修长计时器"满格不动"、设置页色不对 | **中** | 真机看盘面 |
| **9** | 接上提前提醒全链路 + DDL 冷启动恢复（§2.1 + §2.21） | 一个写满全栈却没接通的功能真正可用 | **中高**（后台通知） | 真机 |
| **10** | `ExtApplication` 三步拆解（§3.1） | 降到 ~250 行，首次有可测的编排逻辑 | **中**（涉及服务） | 单测（步骤 1）+ 真机 |
| **11** | `AlertDispatcher` 接上到点警报（§2.2） | 修"警报页开关拨了没反应" | **中**（振动/手电筒） | 真机 |
| **12** | 抽 `data/CascadeDelete.kt` 统一 5 条删除路径（§2.18） | 消灭"计时器凭空消失" | **中** | 内存库单测 + 真机 |
| **13** | `PanelsScreen` 继续拆成 `PanelsTopBar` / `PanelGridView` / `PanelDialogsHost` | 文件规模可控 | **高**（无测试） | 真机逐项回归 |

**不建议做的**（明确说"别动"）：

- ❌ ViewModel / Hilt / Navigation-Compose —— 与 AGENTS.md 的刻意选择冲突；
  当前痛点（§3.1/§3.3）的根因是"动作逻辑写在 UI 里"，不是"缺脚手架"。
  `PanelActions`（普通 class + `rememberCoroutineScope`）能解决 80%。
- ❌ 拆 Gradle 多模块 —— 75 个文件、1 个 module，只会让 CI 更慢。
- ❌ 给实体加 `@ForeignKey` —— 会改变迁移行为；当前问题是"删除路径没级联"，
  `CascadeDelete` 集中处理比数据库约束更灵活（外键在"部分导入"场景会很痛）。

---

## 6. 附：本次量化统计（可复核）

| 项 | 数值 | 采集方法 |
|---|---|---|
| Kotlin 文件 / 行数 | 75 / 14,570 | 递归枚举 + 行计数 |
| `runCatching` | 64 | grep |
| `Log.*` | 25 | grep |
| `logCatching*` | 9，**全在 `ExtApplication.kt`** | grep |
| `ui/` 直接 DAO 调用 | 85（PanelsScreen 65） | grep `(db\|database)\.\w+Dao\(\)` 按文件聚合 |
| `appScope.launch` | 9 | grep |
| `while (true)` / `while (isActive)` 常驻循环 | 9 | grep |
| 单元测试 | 4 文件 / 65 个 `@Test` | grep `@Test` |
| 逐字重复函数体 | 1 组（`clockOf` × 2） | 自写脚本：去注释去空白后比对 |
| 未使用 import | 2（`unused2.js` 漏报 1，误报 1） | 自写逐条扫描 |
| 四个静态检查 | 全部通过 | `resolve` / `unused2` / `checkbraces` / `kotlin_lint` |

**静态检查全绿 ≠ 没有 bug**：本报告 §2.1–§2.7 全部是"能编译、检查也过"的行为错误。
