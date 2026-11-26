# 工程踩坑清单（历史错误复盘）

> 本文只记录**本仓库真实发生过**的错误。每一条都挂 commit hash 或 `文件:行号`，
> 没发生过的类别会明确写「未发生」，不拿最佳实践凑数。
>
> 数据来源：`git log --oneline` 全部 102 个提交，其中 **27 个是 `fix:` 开头的**。
> 生成时用 `node tools/resolve.js` / `unused2.js` / `checkbraces.js` / `kotlin_lint.js`
> 在当前 HEAD 上复跑过，四项全绿（0 问题），所以下面列的**都是已修复的历史错误**，
> 不是现存缺陷。

## 怎么读这份文档

一共 **17 条**：任务给定的 10 类（第 1–10 条，全部逐条核过，其中第 2 条**未发生过**已标注），
加 **4 条任务清单外、但本仓库真实发生过的**（第 11a–11d 条）。
另有第 5 条拆成 4 个子类（5a–5d），因为它们的根因和修法完全不同。

每条都是同一结构：**现象 → 根因 → 自查方法 → 是否可工具化**。
最后是「二、工具化汇总表」「三、提交前检查清单（8 步，可照着做）」「四、总结」「附录（复现方法）」。

## 为什么会有这份文档

本机没有 JDK / Android SDK / 模拟器，**所有编译验证都只能靠 CI（一轮 1–4 分钟）**。
这个约束决定了本项目的错误分布：**语义错误（编译不过、运行不对）占绝大多数，
而静态结构错误极少**——因为结构错误能被本地四个脚本拦住，语义错误拦不住。

统计一下 27 个 fix 提交的成因分布（按 commit 归因，一个 commit 可以算多类，故百分比之和非 100）：

| 成因 | commit 数 | 占比 |
|---|---|---|
| 编译错误（Unresolved reference / import / 作用域 / API 不存在） | 11 | ~41% |
| 运行时逻辑错误（状态机、索引、Flow 订阅、手势） | 10 | ~37% |
| Compose 布局/作用域错误 | 5 | ~19% |
| 数据层错误（迁移、清库） | 2 | ~7% |
| 工具链错误（脚本/推送本身 bug） | 3 | ~11% |

**结论先行：最高频的返工来源是「编译错误」，其中绝大多数是漏 import。**
这一点在下面第 1 条有详细数据。

---

# 一、逐条踩坑清单

每条结构：**现象 → 根因 → 自查方法（写代码时能自己查掉的）→ 是否可工具化**。

## 1. 漏 import（最高频，真实发生 4 次）

**现象**：CI 报一串 `Unresolved reference: Box / height / clip / Color / FontWeight`。

**真实出处**：

- `49a7b35` — CI #90 一次报 **9 条** Unresolved reference。`AiDialogs.kt` 8 条
  （新增 `LevelSlider` 用了 `Box` / `height` / `RoundedCornerShape` / `clip` /
  `background` / `clickable` / `Color` / `FontWeight`，外加改 `collectAsState` 时漏了它的 import），
  `PanelsScreen.kt` 1 条。
- `1c03a66` — 抽屉里用了 `TextButton` 没导入。
- `d4f4fa3` — `ScreenFlashOverlay` 用 `by rememberUpdatedState(...)` 缺 `getValue`，
  CI 报 `State<Int> has no method getValue`；`AlertsScreen` 缺
  `rememberLauncherForActivityResult` / `ActivityResultContracts`。
- `5943887` — 同一次改动里「写了 `Dp` 但没 import」。

**根因**：本机不能编译，IDE 的自动 import 也没有。**新增一整个 Composable
（尤其从别处复制一小段 UI）时，新增符号的 import 必然全漏**——因为写的时候注意力
全在逻辑上。注意 `getValue` 这类**被委托语法隐式用到**的 import 最容易漏：
代码里根本看不到 `getValue` 这个词，只看到 `by`。

**自查方法**：写完一个**新增文件或新增 Composable** 后，花 30 秒做一次「符号 → import」对账：
把这段代码里出现的 Compose 大写开头的组件名和小写开头的 Modifier 扩展，逐个往上翻
import 区确认。特别检查三类隐形符号：`by` 需要的 `getValue`/`setValue`、
`remember { mutableStateOf }` 需要的 `mutableStateOf`、`Modifier.xxx` 需要的扩展函数。

**是否可工具化**：**部分可以，但必须限定在白名单上，不能做通用规则。**

- 通用规则（扫所有标识符与 import 表比对）**不可行**：`49a7b35` 的 commit message 里
  作者已经否掉了这条路——「大量限定名写法是合法的」，误报率会很高。
  实测佐证：当前工程 12 个常见 Compose 符号 × 75 个 `.kt` 文件 = **900 个组合**，
  朴素裸用检测命中 **0 条**（因为现在全是对的，工具在干净代码上无输出，
  但一旦写成 `androidx.compose.ui.graphics.Color.RED` 这种限定名就会被误判为「用了但没 import」）。
- **可行的做法**：只查**白名单**里的高频 Compose 符号，且要求「大写开头 + 后接 `(` 或 `<`」
  的调用形式，排除 `X.Y` 限定形式。这样判据是安全的，代价是覆盖不全（宁可漏报）。
- **更划算的替代**：`getValue` / `setValue` 这类已被 `tools/unused2.js:4` 显式跳过
  （`skip = new Set(['getValue','setValue'])`），说明作者早就知道这两个是特例。
  如果要加检查，应该反过来做：**检查「用了 `by` 但没 import getValue」**——
  判据是 `/\bby\s+(remember|rememberUpdatedState|collectAsState)/` 且文件无 `import ...getValue`，
  这个判据几乎不可能误报，建议加。

## 2. JVM 泛型擦除导致重载冲突

**现象**：`fun f(List<A>)` 与 `fun f(List<B>)` 不能并存。

**真实出处**：**发生过 1 次**（本文件的初稿误判为"未发生"，已更正）。
修复提交 `6a298fe`，对应的 CI 失败是 **#94**：

```
e: ui/sequences/SequenceDuration.kt:24:1 Platform declaration clash:
   The following declarations have the same JVM signature
   (totalDurationMillis(Ljava/util/List;)J)
```

`fun totalDurationMillis(steps: List<Step>)` 与
`fun totalDurationMillis(drafts: List<StepDraft>)` 并存。Kotlin 前端靠泛型参数能分辨，
但 **JVM 会擦除泛型**，两者字节码签名都是 `totalDurationMillis(List):long`。

**为什么初稿漏了它**：检索时用了中文关键词 `擦除` / `重载`，
而那条提交信息写的是「JVM 签名冲突」—— 关键词没命中，就误判成"未发生"。
教训：查历史要用**错误码原文**（这里 `Platform declaration clash`）或
`git log -S`，不要只用自己脑补的译名。

**自查方法**：写同名函数给两个不同类型参数时，若参数是 `List<T>` / `Map<K,V>` /
`Set<T>` 这类**泛型容器**，直接换函数名（`totalDraftDurationMillis`），
或用 `@JvmName`。不要依赖"调用点能按静态类型区分"—— 那只是 Kotlin 前端的事。

**是否可工具化**：可以，且误报极低：同一文件内出现同名函数且参数个数相同、
参数类型去掉泛型参数后文本相同 → 报错。属于**建议加**的类型。

## 3. public 属性暴露 internal 类型（真实发生 1 次）

**现象**：`e: ExtApplication.kt:126:9 Property 'public' exposes its 'internal' type 'SequenceOverlayInfo'`。

**真实出处**：`5d135a4`（CI #93）。子智能体把 `SequenceOverlayInfo` 声明成 `internal`，
但 `ExtApplication` 里对应的 `seqOverlayInfo` 属性忘了写 `internal`，默认就是 public。
当前修复位置见 `app/src/main/java/at/cwiesner/android/visualtimer/ext/ExtApplication.kt:130` 的
`internal var seqOverlayInfo`。

**根因**：可见性**成对出现**。新增一个 internal 类型（尤其是「只给某个包读」的快照/内部模型），
会连带产生一串持有它的属性/函数，每一处都要同步降级。这是**改一个点、需要改一串**的典型。
值得注意这个 commit 的规模信号：`8756f65` 一次加了约 1588 行新代码，
`5d135a4` 的 message 明确写「其余 1588 行新代码这次**一次编译通过**（只有这一条错误）」——
说明大改动的风险恰好集中在**可见性这种横切关注点**上。

**自查方法**：新增一个 `internal` 类型后，立刻全局搜索它的类型名，
把每个 `val`/`var`/`fun` 返回它或接收它的地方都过一遍，决定 public 还是 internal。
用一句简单规则：**「这个类型为什么 internal」的答案，同样适用于所有暴露它的成员。**

**是否可工具化**：**可以，但要说清证据强度。**

判据：先收集所有 `internal class/object/interface` 的名字进集合 `I`，
再扫**类型位置**（`var x: T` / `val x: T` / `fun f(): T`），若 `T ∈ I` 且该成员没写
`internal`/`private`，即报警。

**实测（诚实版）**：当前工程 internal 类型只有 3 个
（`StepsPayload`、`GroupsPayload`、`SequenceOverlayInfo`），而**真正被引用的只有 1 处**：
`ExtApplication.kt:130` 的 `internal var seqOverlayInfo: SequenceOverlayInfo? = null`
（`:252` 的 `buildSequenceOverlayInfo` 是 `private`，也正确）。
另外两个 payload 类型只在 `AiResult.kt:57/61` **定义处**出现，没有任何成员引用它们。

**实测命中 0 条**——但必须说明：这个「0」的证据强度**弱于第 8 条**，因为样本只有 1 个引用点，
不是「很多样本都没误报」，而是「几乎没有样本」。所以：
- **判据本身是对的**（只看类型位置、不扫局部变量，不会像行级正则那样误伤 `apply{}`）；
- 但**历史收益只有 1 次**（`5d135a4`），如果只按「防止的返工次数」排序，它**不该排第一**。
- 放在「值得加」的理由是**实现成本极低**（约 20 行、无需配平），不是「收益最大」。

**按收益重排后的结论：第 8 条 `collect{…first()}` 才是第一优先级**，
因为它的 bug 顺序敏感、静默无声、用户实测才暴露；第 3 条排第二，属于顺手加。

## 4. 漏逗号（真实发生 1 次 —— 但工具化失败）

**现象**：CI #99 `Expecting an element`。

**真实出处**：`5d5865d`，`PanelsScreen.kt` 的 `AiCompileDialog(` 调用处：

```kotlin
AiCompileDialog(
    onDismiss = { showCompileDialog = false },
    // 草稿按面板分开存：没点「整理」就退出，下次打开原样恢复
    panelId = currentPanel?.id          // ← 少了这个逗号
    onApply = { groups ->
```

修复后当前状态在 `app/src/main/java/at/cwiesner/android/visualtimer/ext/ui/panels/PanelsScreen.kt:861`
（`panelId = currentPanel?.id,`）。

**根因**：**中间插了一行注释**。原本写 `name = value,` 是有肌肉记忆的，
但这次是在两个已有参数之间**插入**一个新参数，插入时把逗号漏了。
Kotlin 的尾随逗号是合法的，所以「最后一个参数不用逗号」的直觉在这里是错的。

**自查方法**：**新增命名实参后，检查它是「行首」还是「行尾」**——
只要你插入的不是最后一个实参，就必须补逗号。养成「一律写尾随逗号」的习惯，
这样插入新参数时，前一个参数**天然**已经带逗号。

**是否可工具化**：**不可行，已撤回。这是本文最重要的否定结论。**

仓库里试过在 `tools/kotlin_lint.js` 加「漏逗号」检查，判据是
「当前行形如 `name = 值`，下一行又是新实参」，结果在 `apply { }` / `buildJsonObject { }`
这类块上**误报 129 次**，已撤回。

我按这个判据在当前代码上**实测复现**了误报量级（脚本见附录）。注意这个数字会随代码变动
（两次测量分别是 137 和 139，差额来自同期其他改动），所以**看比例，不要记绝对值**：

| 指标 | 数量（约） |
|---|---|
| 朴素行级判据总命中 | **~138** |
| 其中块内合法写法（明确误报） | ~37（`apply` 9 / `let` 2 / 其他块 26） |
| 其中「疑似真漏逗号」 | ~102 |

而这 **~102 条全是误报**，全部来自块构建器和链式调用，抽查样例：

```
BackupFormat.kt:154     prettyPrint = true        ← Json { } 构建器
AlertOverlayService.kt:102  orientation = LinearLayout.VERTICAL   ← 不是 .apply 直接前一行
OverlayDialView.kt:62   color = COLOR_TRACK       ← 链式调用换行
```

**为什么会误报**：`apply { a = 1 \n b = 2 }`、`buildJsonObject { }`、`Json { }`、
`LinearLayout(this).apply { }` 里的赋值语句**不需要逗号**，而它们的语法形状
和命名实参一模一样（`name = value` + 下一行也是 `name = value`）。
行级正则**没有语法树**，无法区分「我在实参列表里」还是「我在 lambda 块里」。
要正确判断必须做括号/花括号配平 + 上下文栈 —— 那已经是一个简易 Kotlin parser 了，
超出「顺手的 pre-commit 检查」的合理成本，**而且配平本身写错就会引入新的漏报**。

**结论**：这条**接受漏报，不要工具化**。用「一律写尾随逗号」的书写纪律替代。
（配套：`?t=` 时 `tools/checkbraces.js` 已经能保证括号平衡，但平衡≠逗号齐全，
两者是不同的错误类。）

## 5. Compose 作用域错误（真实发生 3 次，三个不同子类）

### 5a. `Modifier.weight(1f)` 挂在非 Row/Column 直接子项（真实发生 1 次）

**真实出处**：`bbf9e70`（六处问题之第 3 条「底边栏挤在左下角」，作者标注为**自己引入的回归**）。
当前修复位置 `app/src/main/java/at/cwiesner/android/visualtimer/ext/ui/MainActivity.kt:355-369`。

**现象**：底边栏四个按钮全挤在左边。

**根因**：为了承载 `DropdownMenu` 给 `NavItem` 外面加了一层 `Box`，
但调用方的 `modifier`（含 `weight(1f)`）**留在了内层 `Column` 上**。
`Row` 的 `weight` 只对**直接子项**生效，`Row` 直接看到的是「没有 weight 的 Box」。
这是「加一层包装」时最高频的失误：**包装层引入了新的父节点，modifier 的传递被切断**。

**自查方法**：任何时候给一个 Composable **外面包一层**（Box / Surface / clickable 修饰），
就问一句「调用方传进来的 `modifier` 现在挂在哪一层？」——
**它必须挂在最外层**，内层需要时自己补 `fillMaxWidth()` 等。
判据化的动作：加包装层时，先写 `Outer(modifier = modifier) { Inner(modifier = Modifier...) }`。

**是否可工具化**：**可以，半自动。** 判据：找 `fun \w+\(\s*\)?\s*modifier: Modifier = Modifier` 的
Composable，检查函数体内**第一层**容器调用是否使用 `modifier = modifier`。
如果第一层用 `Modifier`（空）而深层才出现 `modifier = modifier`，报警。
误报来源：有些组件的 modifier 故意应用在特定层。**建议做成「只提示不阻断」**，
且只覆盖「第一层容器」这一种最常见形态。

### 5b. `Canvas` 的 DrawScope 变量在 Canvas 外引用（真实发生 1 次）

**真实出处**：`5943887`（CI #86：`WheelTimerView.kt:340 Unresolved reference 'diameter'`）。
当前修复见 `app/src/main/java/at/cwiesner/android/visualtimer/ext/ui/wheel/WheelTimerView.kt:134-145`
（把 `Box` 换成 `BoxWithConstraints`，用 `maxWidth` 当直径）。

**根因**：`diameter` 是 `Canvas` 的 `DrawScope` **接收者里的属性**，只在
`Canvas { ... }` 的 lambda 内可见。作者把「按直径算字号」的 `val` 写到了 Canvas **外面**。

**自查方法**：凡是 `Canvas`/`drawBehind`/`drawWithContent` 的 lambda 里出现的名字，
问一句「它是 lambda 的参数/接收者，还是外层的 `val`？」。
**要用 DrawScope 的尺寸，就别在 Canvas 外算**——把 `Box` 换成 `BoxWithConstraints`，
用 `maxWidth`/`maxHeight` 拿到同一个值。这是一条**有固定替代方案**的坑，记住替代方案即可。

**是否可工具化**：**可以但收益低。** 判据：`Canvas\s*\{` 块内出现 `diameter` / `size` /
`center` 等 DrawScope 专属名，且外层没有同名 `val`，报警。风险是 DrawScope 名字很多、
且块边界要靠括号配平（同第 4 条的困境）。**建议不做**，靠 `BoxWithConstraints` 的肌肉记忆。

### 5c. 局部声明有顺序 —— 局部函数 / LaunchedEffect 引用后面的 `val`（真实发生 2 次）

**真实出处**：
- `c1a7f93` 第 3 条：`PanelsScreen` 的 `persistOrder()` 新增在 204 行，`toast` 声明在 256 行
  —— 局部函数在**声明之前**被调用就编译失败，把 `toast` 前移到 191 行。
- `0b2592e`：`DraggableGrid` 的 `LaunchedEffect(pendingClickIndex, pendingClickAt)` 写在
  `val curOnClick by rememberUpdatedState(onClick)` **之前**，引用了后面才出现的名字。
  这是一个**单独的开 fix 提交**，说明当时 CI 因此挂了一轮。

**根因**：和 Kotlin 顶层声明不同，**函数体（尤其 Composable lambda）内的局部声明必须
先声明后使用**。新增一段逻辑时习惯性「插在最相关的位置」，而不是「插在依赖之后」。

**自查方法**：新增局部函数或 `LaunchedEffect` 时，把它引用的**所有名字**列一遍，
确认每个都在它**上面**。经验规律：`remember`/`rememberUpdatedState`/`collectAsState` 出来的
`val` 是一组「基础量」，**任何** effect / 局部函数都应该写在这组基础量之后。

**是否可工具化**：**可以，且误报率低。** 判据：在单个 `fun` 体内按顺序扫描
`^\s*val (\w+) by` 与 `^\s*(fun \w+|LaunchedEffect)`，若某个 effect/局部函数体内出现的标识符
在**后文**才被 `val ... by` 声明，报警。难点只在「函数体结束在哪」需要配平。
考虑到 `0b2592e` 这种错误**一次就挂一轮 CI**，如果已有配平代码可以复用（`checkbraces.js` 有），
这条值得加；否则先靠纪律。

### 5d. `LaunchedEffect` 首帧闪动

**真实出处**：有**相关但不同**的真实案例，需要精确区分：

- `0dfff3b` 明确了闪动的真正机制，但**没有把 `LaunchedEffect` 列为主因**：
  「`collectAsState(initial = 默认值)`（DataStore 的 Flow 首帧必然是默认值），
  于是画面先出默认值、再跳真实值」。
- `6194d9c` 的修法是「预热设置与警报配置，消除切标签时的闪动」。

`LaunchedEffect` 本身的**首帧闪动**（`LaunchedEffect` 在某帧跑之前 UI 用初值渲染一帧）
**未作为独立问题出现在任何 commit 里**。任务描述里这一条，在本仓库的**实际成因是
`collectAsState` 的 initial 值 + 条件组合销毁重建**，不是 `LaunchedEffect`。

**根因（真实版）**：两层叠加 ——
(1) `when (selected) { ... }` 是条件组合，切标签时旧页面整个移除，`remember` 全丢，
新页面从零组合；(2) 订阅的 Flow 首帧必然是 `initial =` 给的默认值。

**自查方法**：看到「先闪一下默认值再跳到真实值」，**先怀疑 `collectAsState(initial = ...)`**，
而不是 `LaunchedEffect`。修法见 `6194d9c`：在 Application 层把设置预热成 `StateFlow`，
页面用不带 `initial` 的版本。

**是否可工具化**：不需要。这是设计路线的选择，不是模式匹配能发现的错误。

## 6. 状态在重组/重建后丢失（真实发生 1 次，修了但只修好一半）

**真实出处**：`bbf9e70`（六处问题之第 1 条「从其他界面点回面板时自己弹窗」），
以及紧随其后的 `0dfff3b`（作者自己承认修法的局限）。

**现象**：从别的标签切回面板，会自己弹出「面板管理 / 整理念头」窗口。

**根因**：`LaunchedEffect(actionsSignal)` 在 `PanelsScreen` **首次组合时一定会执行一次**。
而 `panelActionsTick` / `pendingPanelAction` 由 `MainActivity` 持有、不会重置；
页面切标签被销毁重建后，首次执行就读到**上一次的旧动作**，重放一遍。

**修法**包括三处配套（缺一不可）：
1. `rememberSaveable` 记录 `lastHandledActionTick`（当前在
   `app/src/main/java/at/cwiesner/android/visualtimer/ext/ui/panels/PanelsScreen.kt:189`），
   用 `if (actionsSignal <= lastHandledActionTick) return` 挡住重放（`:194`）；
2. 消费后回调 `onActionConsumed()` 让外围置空；
3. **必须用 `rememberSaveable` 而不是 `remember`** —— 普通 `remember` 会跟着销毁一起丢，
   重建后又变成「从没处理过」。

**⛔ 这里有个更值得记的教训**：`0dfff3b` 的作者实测了各页面的 `rememberSaveable` 数量：

| 页面 | rememberSaveable | 普通 remember |
|---|---|---|
| PanelsScreen | 5 | 39 |
| AlertsScreen | 3 | 6 |
| SettingsScreen | **0** | 11 |
| HomeScreen | **0** | 5 |

于是 `SaveableStateHolder` **只救回了 5/44 的状态**。commit message 里作者写得诚实：
「**这不是为了省内存** —— 销毁重建反而更慢」「要彻底消除切标签时的跳变，需要……
那是下一步的事」。**教训：`rememberSaveable` 的覆盖度决定了任何「保留状态」方案的收益上限。
先数一遍 `remember` 有多少个，再决定值不值得上 `SaveableStateHolder`。**

**自查方法**：任何**跨越「页面可能被销毁重建」边界**必须存活的状态，
一律 `rememberSaveable`。判断「这个状态会不会跨重建丢」的最快方式是问：
「用户切到别的标签再切回来，这个值应该还在吗？」答案是「是」→ `rememberSaveable`。
另外：**「信号 + 一次性动作」这种模式，光靠 `LaunchedEffect(key)` 是不够的**，
必须配一个「已处理到哪个信号」的 saveable 水位线。

**是否可工具化**：**可以。** 判据：`^\s*var (\w+) by remember \{` → 提示「考虑 rememberSaveable」。
误报率**较高**（大量 `remember` 本就该是普通的，如动画中间态、临时 hover），
所以只适合做成**统计型提示**，不做逐条告警。
**真正可行的版本**：统计每个页面的 `rememberSaveable : remember` 比例，
比例过低（如 SettingsScreen 的 0:11）时提示一次。这是「体检」，不是「报错」。

## 7. 重复触发 / 回弹（真实发生 2 次，同一个 bug 修了两轮）

**真实出处**：`bbf9e70`（第 5 条「变色模式滚轮滚动卡顿」）修了一次，
`5c2b896`（「修滚轮方向不对称的跳格」）**又修了一次**——第一轮没修干净。

**现象**：第一轮是「每滚一格都被自己拽一下，表现为一顿一顿」；
第二轮是「往下滑两个值只切一个，往上滑一个却切两个」（方向不对称的跳变）。

**根因**：`LaunchedEffect(selected)` 在滚动结束后仍然 `scrollToItem`。
完整时序（`bbf9e70` 里写得很清楚）：

```
手指抬起 → isScrollInProgress: true→false → 吸附动画还在跑
→ 60ms 后读到位置 → onSelect(v) → DataStore → selected 变成 v
→ 这个 effect 重跑 → 此时吸附动画可能还没结束、firstVisibleItemIndex 仍停在中间值
→ 判定「不等于 target」→ scrollToItem 强行拽回去
```

**为什么第一轮没修好**：`bbf9e70` 的修法是「滚动中 return + 结束后 `delay(250)` 等吸附 +
只在明显偏离时才滚」——**全是时序猜测**（250ms 够不够？吸附动画多长？）。
`5c2b896` 才改用**显式状态标记**：记一个「刚才是不是用户滚的」时间戳 `recentUserScroll`，
用户滚动引起的 `selected` 变化一律不再 `scrollToItem`；只有真正的外部改值才滚。
当前实现见 `app/src/main/java/at/cwiesner/android/visualtimer/ext/ui/wheel/NumberWheel.kt`
的 `recentUserScroll`（`NumberWheel.kt:120`）与 `LaunchedEffect(selected)` 里的三处守卫（`:149`、`:155`）。

**⚠️ 最值钱的一条经验**：**用 `delay(固定毫秒)` 去「等动画结束」是靠不住的**，
属于猜测性的时序补丁；正确做法是**显式区分「这次变化是谁引起的」**
（用户滚动 vs 外部赋值），用状态标记而不是时间窗口。第二轮修完还剩 200ms 窗口，
但语义已经从「猜动画时长」变成「区分变化来源」，这才是根因级的修法。

**自查方法**：写 `LaunchedEffect(x)` 且有副作用（`scrollToItem` / 写库 / 回调）时，问：
「**x 的这次变化是我自己引起的吗？**」如果是，必须守卫掉，不能靠 `delay` 等。
如果代码里出现 `delay(1xx)` 来「等动画」，直接标为可疑。

**是否可工具化**：**可以，判据极简、几乎零误报。**
正则：`LaunchedEffect\s*\([^)]*\)\s*\{(?:(?!\}\s*$).)*delay\s*\(` （同块内出现 `delay`）
+ 块内出现 `scrollToItem` / `scrollTo` / `animateScrollTo`，报警「疑似用延时等待动画，改用状态标记」。
误报来源：`delay` 用于节流（debounce）是合法的 —— 但那种通常不在 `scrollTo` 同一块内。
**建议加，只提示不阻断。**

## 8. Flow 只取一次（真实发生 1 次，影响面最大）

**真实出处**：`8756f65`，ExtApplication 里悬浮窗自动起停。

```kotlin
// 错误写法
panelRunner.runtimes.collect { map ->
    val enabled = settings.overlayEnabled.first()   // ← 只取一次
    ...
}
```

**现象**：用户报「悬浮窗里没显示」。

**根因**：`first()` 是**一次性**取值，而 `collect` 只在 `runtimes` 变化时触发。
于是「**先启动计时器、再去设置里打开悬浮窗开关**」这条最常见的顺序**永远不生效**——
开关变了但 `runtimes` 没变，块根本不执行，开关等于白开。

修复：`combine(panelRunner.runtimes, settings.overlayEnabled)`（当前在
`app/src/main/java/at/cwiesner/android/visualtimer/ext/ExtApplication.kt:400-406`）。

**为什么这条最值钱**：这个 bug 的特征是**顺序敏感**——
先开开关再启动就正常，先启动再开开关就失灵。这类 bug **不会在开发自测时暴露**
（开发者通常按「先配置后使用」的顺序操作），只有真实用户按别的顺序操作才炸。
而且它**没有任何日志、没有崩溃**，只能靠用户口述。

**自查方法**：看到 `collect { ... .first() }` 或 `collect { ... .value }` 这种
「在 A 的 collect 里同步读 B」的写法，立刻判定为错——**只有在 A 变化时才会读到 B**。
改成 `combine(A, B)`。一句话判据：**`collect` 块里出现的任何其他 Flow，都必须通过
`combine` 参与触发，不能在里面 `first()`。**

**是否可工具化**：**可以，而且这是最该加的一条。** 判据：
`\.collect\s*\{` 之后，**按花括号配平**求出块范围，块内出现 `\.first\s*\(\s*\)`，报警。

**已实测**：写出这条规则在当前工程上跑，命中 **0**（已修复，符合预期）；
同时因为限定在「collect 块内」，13 处 `.first()` 里 12 处合法用法
（`SettingsRepo.kt:306`、`ExtTheme.kt:100/127`、`LlmRouter.kt:104-110`、
`ExtApplication.kt:422`）**一条都没被误报**，剩下 1 处是 `ExtApplication.kt:388` 的注释
（真实代码里已改成 `combine`）。**误报率 0 / 13。**
**建议加。**（唯一前提是括号配平要写对 —— 可以直接复用 `checkbraces.js` 的思路。）

## 9. 无限循环空转（真实发生 1 次，被明确记录为「致命 bug」）

**真实出处**：`8756f65`，`StepSequenceEngine` 的暂停实现。
commit message 原文：「**子智能体的实现有致命 bug，已重写**」。

```kotlin
// 错误写法
val ticked = withTimeoutOrNull(TICK_MILLIS) { _paused.first { !it } }
if (!ticked) remaining -= TICK_MILLIS
```

**根因**：`_paused` **初始为 `false`**，`first { !it }` 会**立即返回**（因为当前值就满足条件），
于是 `withTimeoutOrNull` 立刻返回 true（没超时）→ 不扣时间 → 循环疯狂空转、
`remaining` 永不减少，序列卡死。

**这个写法错在哪**：作者想表达「等 100ms，或者等到暂停标志变真」，
但 `first { !it }` 谓词写反了 —— 应该是 `first { it }`（等暂停变真）。
**而更根本的问题是：`withTimeoutOrNull + 立即满足的条件`，这个组合本身就没有「等满时长」的效果。**

现修法（当前 `app/src/main/java/at/cwiesner/android/visualtimer/ext/domain/StepSequenceEngine.kt:245-249`）
回到最直白的「先 `delay(TICK_MILLIS)`，再看 `_paused.value` 要不要扣时间」。
commit 里明确断言：「未暂停时与改动前**完全等价**」，并给了状态机模拟对照单测的硬断言：
`steps(1000) advance(300) → remaining=700`。

**自查方法**：任何 `withTimeoutOrNull(T) { flow.first { pred } }`，先问：
**`pred` 在当前初始值上成立吗？** 成立 → `first` 立即返回 → `withTimeoutOrNull` 就没起作用。
一句话规则：**`withTimeoutOrNull` 里 `first{}` 的谓词，必须在起始状态下为 false**，
否则这个「超时」是假的。

**是否可工具化**：**不能。** 这需要知道运行时初始值（`_paused` 初值 false），
静态无法判断谓词在初始状态下是否为真。**这是「必须靠单元测试或状态机模拟」的典型**。
本仓库的正确应对方式是 `0dfff3b` 之前就有的做法：补 `StepSequenceEngineTest`
（`fca4b4f`、`8756f65` 都补了单测），用 `runTest` 断言 `remaining` 的递减。
**这类错误唯一可靠的防线是测试，不是工具。**

## 10. Room 迁移漏回填（真实发生 1 次，用户实测到）

**真实出处**：`5c2b896`（记为「DDL 老数据未锁定」），`8756f65` 又把它列进「上次遗留」。

**现象**：用户实测「DDL 还是可以编辑」——明明 DDL 应该是锁定的。

**根因**：`MIGRATION_5_6` 当初只是 `ADD COLUMN locked INTEGER NOT NULL DEFAULT 0`。
**升级到 v6 之前就存在**的 DDL 计时器，`locked` 全部落成 0，从没回填过。
新代码读 `locked`，读到的全是默认值 → 行为**静默退化**成「可编辑」。

**修法是双保险**（这一点值得学）：
1. v6→v7 迁移里补 `UPDATE timers SET locked = 1 WHERE kind = 'Deadline' AND locked = 0`
   （当前 `app/src/main/java/at/cwiesner/android/visualtimer/ext/data/AppDatabase.kt:305`）；
2. 新增 `TimerItem.isEditable`（= `!locked && kind != Deadline`，
   当前 `app/src/main/java/at/cwiesner/android/visualtimer/ext/data/TimerItem.kt:47`），
   界面一律用它而不再直接读 `locked`。

理由写在源码注释里，值得原样记住：**`kind` 从 DDL 诞生就是 `Deadline`，是语义事实；
而 `locked` 是可回填字段，漏了就会静默退回。凡是「靠可回填字段决定行为」的做法，
一旦没回填就静默失真。**

**自查方法**：写 `ADD COLUMN x DEFAULT <非空语义值>` 时，问：
**这个默认值对「升级前就存在的行」是正确的吗？** 如果 `x` 表达的是一条规定
（是否锁定 / 是否开启 / 是否已读），而老行显然应该取另一个值，就必须补 `UPDATE ... WHERE`。
配套：**别让 UI 直接读这个新字段**，加一个派生属性（如 `isEditable`），
让「语义事实」和「可回填缓存」都参与判断。

**是否可工具化**：**可以，判据清晰。** 在 `AppDatabase.kt` 中，
若某个迁移块含 `ADD COLUMN (\w+) .* DEFAULT ([^,)]+)`，且该迁移块内**没有**
`s*execSQL\("UPDATE ` 语句，则提示「新增带默认值的列，确认是否需要回填」。
误报来源：确实不需要回填的列（如 `difficulty` 可空、默认 NULL，语义正确）——
所以做成**提示性**检查（每次新增列都提醒想一遍），而不是错误。
本仓库自己就是反例：`MIGRATION_6_7` 里 `difficulty`/`context` 两列**不该**回填，
同时**又**需要回填 `locked`，两者在同一个迁移里。**这正是「需要人来判断」的证据。**

## 11. 顺带发现的四类（任务清单里没有，但本仓库真实发生过）

### 11a. 引用了不存在的 API（真实发生 2 次）

**真实出处**：
- `9dfdba9`：给 `combinedClickable` 传了 `hasDoubleClick` / `doubleClickTimeoutMillis`，
  CI 报 `None of the following candidates is applicable`。本版本（BOM 2024.12.01 → Compose 1.7.5）
  的签名没有这两个参数。当前 `DraggableGrid.kt` 里有注释明确写着
  「本版本 Compose 的 `combinedClickable` **没有** `hasDoubleClick` / `doubleClickTimeoutMillis`」。
- `bbf9e70` / `8756f65`：想用 `Text` 的 `autoSize`，查证后确认是 **compose-ui 1.8.0** 才有的 API，
  本项目是 1.7.5，用了会编译失败，改用「按直径算字号 + `maxLines=1` + 限宽」。

**根因**：本机没有 SDK 源码和 IDE 提示，**API 是否存在只能靠记忆或查文档**。
最危险的是「我记得有这个参数」——新版 Compose 的参数很容易记串。

**自查方法**：给 Compose API 传**不常见的参数**（尤其是 `xxxTimeoutMillis` / `hasXxx` / `autoSize`
这类听起来很合理的名字）前，先查本项目的 Compose 版本
（`gradle/libs.versions.toml` 的 BOM 2024.12.01 → compose-ui **1.7.5**），
确认该参数在 1.7.5 存在。**宁可少传参数用默认值，也不要传一个「应该存在」的参数。**

**是否可工具化**：**不能**（没有 SDK 的类型信息）。但可以加一条**白名单黑名单**：
把已知「1.8+ 才有」的 API（`autoSize`、`TextAutoSize`）和
「本版本没有的参数」（`hasDoubleClick`、`doubleClickTimeoutMillis`）写成硬编码黑名单，
在 `kotlin_lint.js` 里查。**成本极低、零误报**，值得加 —— 这正是 `kotlin_lint.js`
现有规则的思路（它已经在查 `Sequence.size`、`@OptIn` 连写）。

### 11b. `@OptIn` 连写两行（真实发生 1 次）

**真实出处**：`1c03a66`。

**根因**：`@OptIn` **不是可重复注解**，连写两行报 `This annotation is not repeatable`。
必须写成 `@OptIn(A::class, B::class)`。

**是否可工具化**：**已在做。** `tools/kotlin_lint.js:74-81` 已有这条规则
（「@OptIn 连写两行 —— 它不是可重复注解」），当前工程命中 0 条。这是**工具化成功**的范例：
判据明确（`^\s*@OptIn\(` 后 2 行内再出现 `^\s*@OptIn\(`）、零误报。

### 11c. 层叠点击 / `NavigationBarItem` 自带点击层（真实发生 3 次，同一根因）
**真实出处**：
- `0a549f2`：卡片内层自挂 `combinedClickable` + 网格层也挂 → 内层先消费第一下，
  外层事件序列残缺 → 表现「点一下直接进全屏」。
- `2de5861`：底边栏改自绘 `NavItem`，原因就是「`NavigationBarItem` 内部自带
  `selectable(clickable)`，外面再挂 `combinedClickable` 就是两层点击抢事件 ——
  这个坑在面板卡片上已经因为同样的问题出过」。
- `c6642f2`：卡片同时挂 `onLongClick`（弹框）和网格层 `detectDragGesturesAfterLongPress`
  （拖拽）→ 长按被卡片先吃掉，拖拽起不来。修法是**长按只用于拖拽**。

**根因**：**同一个指触区域上不允许有两层手势消费**。这是 Compose 手势里最反直觉的一点：
两层 `clickable` 不会「合并」，只会竞争，且竞争结果依赖内层先拿到事件。

**自查方法**：给一个 Composable 挂点击/长按前，先问：**它的父级或子级已经挂了吗？**
特别注意 `NavigationBarItem` / `ListItem` / `Card` 这类**自带点击层**的组件，
以及「网格层统一处理 + 卡片自己处理」的分工是否重复。

**是否可工具化**：**不能。** 需要知道组件树结构和第三方组件是否自带点击层，
静态文本分析做不到。这条只能靠 `AGENTS.md` 里已经写下的「四条铁律」约束。

### 11d. 单元测试「挂死」——无限循环协程 + `runTest`（真实发生 1 次，代价 6 小时）

**真实出处**：`5d7edd3`（CI #74）。

**现象**：**Build debug APK 成功**，但 `Run unit tests` 永远 `in_progress`
——**不报错、不失败**，一直等到 GitHub 的 **6 小时**默认上限才超时。
这是本文所有错误里**单个代价最大**的一次：6 小时白等，而且日志里没有任何错误可看。

**根因**：`StepSequenceEngine.runStep` 的第二阶段是 `while (isActive) { delay(...) }`
无限循环，只有 `stop()` 或取消 scope 才停。测试把引擎建在**普通 `runTest` scope** 上，
测试体结束后 `runTest` 会**等待该协程完成**——而它永远不会完成，于是挂死。

**修法**（两处，第二处是兜底）：
1. 引擎改建在 `backgroundScope` 上（测试结束时自动取消），
   对应「引擎还挂着但我们不管了」的语义；
2. CI 的 unit test 步骤加 `timeout-minutes: 10`，
   **让「挂住」变成一次快速失败，而不是等 6 小时**。

**自查方法**：写测试时，凡是**产品或被测代码里有 `while (isActive)` / 无限 `collect`**
（引擎、ticker、监听器）的，一律建在 `backgroundScope` 上，
**不要**建在 `this`（`TestScope`）上。判据：`runTest { val engine = StepSequenceEngine(...) }`
这种直接建在测试体里的写法，只要引擎自带 tick 协程，就一定会挂。

**是否可工具化**：**可以，而且很值。** 判据：`runTest\s*\{` 块内出现
`StepSequenceEngine(` / 任何已知自带 tick 的类，但**没有** `backgroundScope`
→ 提示「引擎建在 TestScope 上会挂死，改用 backgroundScope」。误报率极低（判据是明确的白名单类名）。

**更通用的教训**：**「无限循环 + 测试框架等待」= 挂死，而不是失败。**
挂死比失败更糟：失败有日志、有行号；挂死只有超时。所以**任何 CI 都必须设 step 级 timeout**
——本仓库是在踩过 6 小时之后才加的。

---

# 二、工具化建议汇总（含误报率评估）

任务要求：**诚实评估误报率，不是所有规则都值得加**。基于在真实代码上的实测：

| # | 规则 | 可工具化 | 实测/预估误报 | 建议 |
|---|---|---|---|---|
| 1 | 漏 import（白名单符号） | 部分 | 白名单化后低；通用版**不可行** | 只做 `getValue` 专项：**加** |
| 2 | 泛型擦除重载冲突 | ✅ | 低（同一文件内同名 + 参数个数同 + 去掉泛型参数后文本同） | **加**（真实发生过 1 次：`6a298fe` / CI #94） |
| 3 | public 暴露 internal 类型 | ✅ | 实测 0 命中，但**样本仅 1 个引用点**（证据弱）；判据只看类型位置，理论低误报 | 成本低，**顺手加** |
| 4 | 漏逗号 | ❌ | 朴素判据实测 **~138 命中，且「疑似真漏」的 ~102 条也全是误报** | **不要加**（已撤回，正确决定） |
| 5a | `weight` 挂在非直接子项 | 部分 | 中（需判断第一层容器） | 只提示，可选 |
| 5b | DrawScope 变量越界 | 部分 | 低但要括号配平 | 收益低，**不加** |
| 5c | 局部声明顺序 | ✅ | 低 | 有配平能力则**加** |
| 6 | `remember` vs `rememberSaveable` | 统计型 | 逐条告警误报**高** | 只做比例体检 |
| 7 | `LaunchedEffect` + `delay` + `scrollTo` | ✅ | 极低 | **加**（只提示） |
| 8 | `collect { … .first() }` | ✅ | **实测 0 误报 / 13 处 `.first()`** | **最该加** |
| 9 | `withTimeoutOrNull` + 立即满足的谓词 | ❌ | — | 靠**测试**，不能工具化 |
| 10 | 迁移新增列未回填 | 提示型 | 有合法例外（`difficulty`） | 只提示 |
| 11a | 引用不存在的 API（黑名单） | ✅ | 零 | **加** |
| 11b | `@OptIn` 连写 | ✅ 已有 | 零 | 已实现 |
| 11d | 引擎建在 `TestScope` 上导致测试挂死 | ✅ | 极低（白名单类名） | **加** |

**三条最高性价比（建议优先落地）**：
1. **`collect { … .first() }`**（第 8 条）—— 实测 **0 误报 / 13 处样本**，且这条 bug 在真实用户操作顺序下才暴露、静默无日志，危害最大。**优先加。**
2. **已知不存在 API 黑名单**（第 11a 条）—— 完全硬编码（`autoSize`、`hasDoubleClick` 等），零误报、零逻辑。**顺手加。**
3. **public 暴露 internal 类型**（第 3 条）—— 判据安全、约 20 行，但历史样本只有 1 个引用点，**收益证据弱**。成本低可以加，别为它排工期。

**明确不要做**：漏逗号（第 4 条）。误报 > 漏报的规则会让团队**开始忽略工具输出**，
这比没有工具更糟 —— 而 `49a7b35` 的 commit message 已经记录过这个判断。

**排序说明**：上表按「误报率安全度」排，不按「能防多少返工」排。
若按防返工排序，答案在第四节：漏 import（4 次）> Flow/时序（3 次，其中 1 次修了两轮）> 迁移回填（1 次但用户实测）。
**注意「不能工具化」不等于「不重要」**——第 9 条空转那个 bug 是「致命 bug」，
但它只能靠单测拦住，而本仓库恰好从 `fca4b4f` 起就有了 `StepSequenceEngineTest`。

---

# 三、提交前检查清单

> 每一步都是**可执行动作**，不是「注意质量」。
> 前 4 步是机械动作（2 分钟），第 5–7 步是判断动作（3 分钟）。

## 第 0 步：本地静态检查（机械，30 秒）

```bash
node tools/resolve.js      # 期望 broken internal imports: 0
node tools/unused2.js      # 期望 REAL unused imports: 0
node tools/checkbraces.js  # 期望 括号全部平衡
node tools/kotlin_lint.js  # 期望 未发现问题
```

⚠️ 三个脚本硬编码 `root='vte/app/src/main/java'`，**必须在含 `vte/` 子目录的父目录跑**。
仓库根就是 `vte/` 的内容，所以要建临时目录 + junction（见 `AGENTS.md`）。

**四项全绿只说明没有「结构性」错误，不代表能编译。** 下面 5 步才是真正防 CI 失败的。

## 第 1 步：新增/修改文件 → import 对账（防第 1 条，命中率最高）

只对**本次新增的文件**和**新增的 Composable** 做：
把这段代码里出现的每个 Compose 组件名（大写开头）和 Modifier 扩展（小写开头）列出来，
逐个确认 import 区有它。**必须专门检查这 3 个隐形符号**：

- 用了 `by` 委托 → 有没有 `import androidx.compose.runtime.getValue`（和需要赋值时的 `setValue`）
- 用了 `mutableStateOf` / `mutableIntStateOf` / `collectAsState` → 各自的 import
- 用了 `Modifier.clip` / `background` / `clickable` / `height` → 都是**扩展函数**，各自 import

## 第 2 步：新增命名实参 → 一律写尾随逗号（防第 4 条，不依赖工具）

**动作**：写完每个函数的调用后，看一眼**你现在插入的那个参数是不是最后一个**。
只要不是最后一个，就必须补逗号。长期习惯：**所有多行参数列表一律写尾随逗号**，
这样以后插入新参数时前一个参数天然带逗号。

## 第 3 步：新增 internal 类型 → 全局搜类型名（防第 3 条）

**动作**：`git diff` 里新增了 `internal class/object/interface` 时，
用类型名全局搜一次，把每个持有它的 `val`/`var`/`fun` 都过一遍，
同步写 `internal`。**「这个类型为什么 internal」的答案，同样适用于所有暴露它的成员。**

## 第 4 步：新增 Canvas / 多行参数 / 局部函数 → 检查作用域与顺序（防第 5 条）

- 用了 `Canvas` 里的 `size`/`center`/`diameter` → 这些值**不能**在 Canvas 外算；
  要尺寸就把 `Box` 换 `BoxWithConstraints` 拿 `maxWidth`。
- 新增局部函数或 `LaunchedEffect` → 它引用的**每个名字**都必须声明在它**上面**。
  经验规律：`remember`/`rememberUpdatedState` 出来的 `val` 是一组基础量，
  **所有 effect 和局部函数都写在它们之后**。
- 给某个 Composable **外面包了一层** → 检查 `modifier` 是否仍挂在**最外层**。

## 第 5 步：新增 Flow 订阅 → 检查 combine（防第 8 条，顺序敏感 bug）

**动作**：本次改动里如果新增了 `collect` 块，扫一遍块内有没有 `.first()` 或 `.value`。
有 → 改成 `combine(A, B)`。
判据：**`collect` 块里出现的任何其他 Flow，都必须通过 `combine` 参与触发。**

## 第 6 步：新增 `LaunchedEffect` / `withTimeoutOrNull` → 三问（防第 7、9 条）

1. **「x 的这次变化是我自己引起的吗？」** 是 → 必须用状态标记守卫掉，
   不许用 `delay(1xx)` 等动画。（第 7 条，修了两轮才修对）
2. **`withTimeoutOrNull(T) { flow.first { pred } }` 里，`pred` 在初始值上成立吗？**
   成立 → 超时是假的、循环会空转。谓词必须在起始状态下为 **false**。（第 9 条）
3. 这个副作用是「一次性」的吗？如果是跨重建的「信号 + 动作」模式，
   必须有 `rememberSaveable` 的水位线，光靠 `LaunchedEffect(key)` 不够。（第 6 条）

## 第 7 步：改测试 / 改 Room / 传 Compose 参数 → 三项专项确认（防第 11d、10、11a 条）

- **写测试**：被测类里有 `while (isActive)` / 无限 `collect`（引擎、ticker、监听器）→
  一律建在 `backgroundScope`，**不要**建在 `runTest` 的 `this` 上，否则测试挂死而非失败。
  （`5d7edd3`：CI 白白等了 **6 小时**。现在 `build.yml:59` 有 `timeout-minutes: 10` 兜底，但别依赖它。）
- **改迁移**：新增 `ADD COLUMN x DEFAULT ...` 时问：**这个默认值对「升级前就存在的行」正确吗？**
  表达的是一条规定的字段（是否锁定/开启/已读），老行往往该取别的值 → 补 `UPDATE ... WHERE`。
  并且**别让 UI 直读这个字段**，加派生属性（如 `isEditable`）让语义事实也参与判断。
- **传 Compose 参数**：确认该项目 compose-ui 是 **1.7.5**（BOM 2024.12.01）。
  已知不存在：`autoSize`（1.8+）、`combinedClickable` 的 `hasDoubleClick` /
  `doubleClickTimeoutMillis`。**宁可少传参数用默认值。**

## 第 8 步：写 commit message 时说清「这是哪类错误」

本仓库的 fix commit message 质量很高（写清了 CI 编号、根因、验证方式），
这本身是最有价值的资产 —— 上面几乎每一行证据都来自它们。
**继续坚持**：`fix: xxx（修 CI #NN 编译失败）` / `fix: xxx（真实 bug，用户报）`，
并说明「怎么验证的」（CI 编号 / 模拟 / 单测）。

---

# 四、一句话总结

**这个项目的错误分布是由「无 JDK」这个前提塑造的**：结构性问题（括号、坏 import、未用 import）
被四个本地脚本兜住，所以历史上**几乎不出现**；反复失败的全是**语义问题**——
漏 import、作用域、时序、订阅、迁移回填。

其中**最值钱的三条**（能防止最多返工）：

1. **漏 import**（4 次：`49a7b35`/`1c03a66`/`d4f4fa3`/`5943887`）——
   每次都是一整轮 1–4 分钟 CI 白白浪费，且 `getValue` 这类隐形符号
   永远不会被肉眼发现。**防线 = 第 1 步的 import 对账（纯人工，30 秒）。**
2. **Flow 只取一次 / 时序守卫用 `delay` 猜**（第 8、7 条）——这两类 bug **顺序敏感 + 静默无声**，
   开发自测必过、用户一操作就炸，且第 7 条**修了两轮**才修对。**防线 = 第 5、6 步的「三问」。**
3. **迁移漏回填**（第 10 条）——唯一一次**用户实测发现**的数据层问题，
   `DEFAULT 0` 让新规则对老数据静默失效。**防线 = 第 7 步的「默认值对老行正确吗」。**

**另有一条单次代价最大**：**测试挂死**（`5d7edd3`，第 11d 条）——CI **6 小时**才超时，
且全程无任何错误日志。它不常发生，但一旦发生就是最贵的。防线 = 第 7 步的 `backgroundScope`。

**最后一点方法论**：本文最有价值的产出可能不是这些条目本身，而是**「哪些规则不值得工具化」**
的实测证据（第 4、9 条）。在一个**不能本地编译**的项目里，很容易产生「写个脚本什么都查」
的冲动；但 `129` 次误报的教训说明，**工具的误报率会摧毁团队对工具的信任**。
正确的分工是：**能判据化的才工具化（第 8、11a、11d 条），靠语义和测试的交给纪律与单测。**

---

# 附录：本文数据的复现方法

所有结论都可以复跑。**不需要 JDK**，只用 node / python。

## A. 工具脚本（三个脚本共用同一个 root 约定）

```bash
# 三个既有脚本硬编码 root='vte/app/src/main/java'，必须在含 vte/ 子目录的父目录跑
TMP=$(mktemp -d)
ln -s "$(pwd)"       "$TMP/vte"     # Windows 用 mklink /J
cp -r tools          "$TMP/tools"
cd "$TMP"
node tools/resolve.js      # broken internal imports: 0
node tools/unused2.js      # REAL unused imports: 0
node tools/checkbraces.js  # 括号全部平衡
node tools/kotlin_lint.js  # 未发现问题
```

## B. 「collect 块内出现 .first()」规则（第 8 条，本文首推）

核心逻辑：从 `.collect {` 起按花括号配平求块范围，再在块内搜 `.first()`。

```js
for (let i = 0; i < lines.length; i++) {
  if (!/\.collect\s*\{/.test(lines[i])) continue;
  let depth = 0, started = false, end = i;
  for (let k = i; k < lines.length; k++) {
    for (const ch of lines[k]) {
      if (ch === "{") { depth++; started = true; }
      else if (ch === "}") depth--;
    }
    if (started && depth <= 0) { end = k; break; }
  }
  const block = lines.slice(i, end + 1).join("\n").replace(/\/\/[^\n]*/g, "");
  if (/\.first\s*\(\s*\)/.test(block)) report(f, i + 1);
}
```

实测输出：命中 **0**；同时列出全部 13 处 `.first()`，确认 12 处合法用法（含注释 1 处）
**全部未被误报**。

## C. 「漏逗号」规则的误报测量（第 4 条，本文首推「不要做」）

判据：当前行匹配 `^\s*([A-Za-z_]\w*)\s*=\s*(?!=).+$`，下一行匹配 `^\s*[A-Za-z_]\w*\s*=\s*(?!=)`，
且当前行不以 `,` `(` `=` `->` 结尾。

再按「前一行是否是 `{` / `apply {` / `buildJsonObject {`」粗分块内与块外，
验证块外那批也全是误报（来自 `Json { }`、链式 `.apply { }`、`LinearLayout(...)` 换行等）。
**关键结论：块内/块外的朴素划分救不了它，因为误报主体不在块首行，而在整个 lambda 体内部。**

## D. 索引公式的算术验证（第 7 条相关，`NumberWheel`）

`numberwheel` 的坐标关系（视口 3 格、高亮条占 `[H, 2H)`、中心 `1.5H`）：

```python
def rnd(x): return int(math.floor(x + 0.5))          # Kotlin roundToInt
def item_center(f, o, j, H=40): return -o + (j - f) * H + H / 2

# 几何真值：中心离 1.5H 最近的列表项（暴力解，避免推导出错）
def truth(f, o, n, H=40):
    t = 1.5 * H
    return min(range(n + 2), key=lambda j: abs(item_center(f, o, j, H) - t))
```

**本人在写本文时连续推错两次**（先是把「骑跨」当成「整格命中」，又漏了
`valueIndex(L) = L-1` 这层换算），最后靠**暴力最近项 + 作者在 commit 里记录的 280/280**
双重对照才定下来。**这恰好是第 7 条要表达的教训：纯算术的索引关系，肉眼推导极易错，
必须写模拟；而模拟本身也很容易写错，所以模拟要尽量用「暴力解」而不是「推导公式」做基准。**

## E. 已知的局限（诚实声明）

- 本文**没有**验证过这些修复在真机上的效果 —— 本机无 adb / 模拟器，
  `c6642f2`、`bbf9e70` 等多个 commit 也明确写了「实际手感需装机确认」。
- 第 1 条「漏 import」的 4 次里，`5943887` 的 `Dp` 漏 import 与 `diameter` 作用域错误是**同一次 CI 失败**，
  归类时算作 2 条（import 1 次 + 作用域 1 次），存在轻微重复计数。
- 成因分布表按 commit 数归因、一个 commit 可算多类，所以百分比之和不为 100。
- 误报率是**对当前 HEAD 的一次测量**，不是恒定常数；代码变化后需重跑。
