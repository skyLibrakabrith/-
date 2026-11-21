# Visual Timer Ext

Visual Timer 的功能扩展版。独立应用，包名 `at.cwiesner.android.visualtimer.ext`，
可与原版 Visual Timer 共存安装、互不干扰。

## 这个项目做了什么

把「独孤计时器」的几个功能概念，用全新的 Kotlin 代码重新实现，
叠加在 Visual Timer 的使用习惯之上。**没有复用任何第三方 APK 的代码或资源**，
只借鉴了功能概念（功能概念本身不受版权保护）。

| 功能 | 说明 |
|---|---|
| **多面板** | 一个面板是一组计时器，用网格展示，每格用圆环表达剩余比例 |
| **逐步间隔** | 一串按顺序执行的阶段；每步结束**等你点击**才进下一步 |
| **超时后正计时** | 每步结束后先显示超时倒计时，到点后转为**正计时**（+MM:SS），持续提醒你已经拖了多久 |
| **DDL 倒推** | 选定一个截止时刻，自动算出剩余时长；时间轴可视化并标出 24h 阈值 |
| **震动 + 屏幕闪烁** | 默认完全静音。振动模式用**波形条**呈现（宽度按真实时间比例），不依赖文字命名 |
| **手电筒** | **默认关闭**，需在警报页手动开启 |
| **导入 / 导出** | JSON 备份，走 SAF 文件选择器，**不需要任何存储权限** |

## 可视化设计

三个核心组件都不依赖读数字：

- `VibrationWaveform` — 把「关-开-关-开」毫秒数组画成实心块序列，疏密一眼可辨
- `MiniTimerRing` — 弧长 = 剩余比例，颜色随阶段从主色变警示色
- `DeadlineTimeline` — 时间轴填充条 = 剩余时间占 24h 的比例，并标出可靠上界

## 权限说明

```xml
VIBRATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE, SCHEDULE_EXACT_ALARM,
USE_EXACT_ALARM, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
FLASHLIGHT, CAMERA(required=false)
```

**没有 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`。**
导入导出用 SAF，只需要用户对具体文件的授权，不需要整个存储的授权。

## 构建

### 方式一：GitHub Actions（推荐，无需本地环境）

1. 建一个空仓库，把本目录全部内容推上去（包含 `.github/`）
2. 推送到 `main` 分支后自动触发构建
3. 在仓库的 **Actions → Build APK → Artifacts** 下载 `visual-timer-ext-debug-apk`

也可以手动触发：Actions 页面 → Build APK → Run workflow。

### 方式二：本地 Android Studio

1. Android Studio 打开本目录
2. 首次打开会提示补全 Gradle wrapper，点同意（或执行 `gradle wrapper --gradle-version 8.11.1`）
3. Build → Build Bundle(s)/APK(s) → Build APK(s)

### 方式三：命令行

```bash
gradle wrapper --gradle-version 8.11.1   # 仅首次
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 签名

debug 构建使用 Android 默认调试证书。若要正式分发：

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias visualtimer

apksigner sign --ks release.jks --out app-release.apk app-debug.apk
```

`apksigner` 需要 v2 签名（Android 11+ 强制要求），不要只用 jarsigner 做 v1。

## 工程结构

```
app/src/main/java/at/cwiesner/android/visualtimer/ext/
├── ExtApplication.kt          应用入口，植入默认警报配置
├── data/                      Room 实体 + DAO + 数据库
│   ├── TimerItem.kt           TimerItem / Panel / PanelTimerRef
│   ├── StepSequence.kt        Step / StepSequence
│   ├── AlertProfile.kt        振动模式定义
│   ├── VibrationPresets.kt    内置波形
│   ├── AppDatabase.kt         Room 数据库与 4 个 DAO
│   └── Converters.kt          List<Long> ←→ String
├── domain/
│   ├── DeadlineCalculator.kt  DDL 倒推 + 24h 阈值判定
│   └── StepSequenceEngine.kt  逐步间隔状态机（含超时正计时）
├── alarm/
│   ├── VibrationAlerter.kt    波形播放 + 预览裁剪
│   ├── FlashAlerters.kt       屏幕闪烁 + 手电筒
│   └── AlertDispatcher.kt     统一分发
├── io/
│   ├── BackupFormat.kt        JSON DTO（用临时 ref 避免主键冲突）
│   └── BackupManager.kt       SAF 导入导出
├── service/
│   ├── SequenceService.kt     前台服务保活
│   ├── SequenceAlarmReceiver.kt
│   └── BootReceiver.kt
└── ui/
    ├── MainActivity.kt        底部导航
    └── theme/ExtTheme.kt
    ├── panels/                MiniTimerRing, PanelsScreen
    ├── sequences/             SequenceRunnerScreen, SequencesScreen
    ├── alerts/                VibrationWaveform, AlertsScreen
    ├── duration/              DeadlineTimeline
    └── settings/              SettingsScreen
```

## 已知限制

- **面板/序列的增删改 UI 尚未完成**（列表与运行界面已就绪，编辑界面留 TODO）
- **序列状态未持久化**：进程被杀后需重新开始（`BootReceiver` 已留恢复钩子）
- **声音未实现**：按需求默认静音，`soundEnabled` 字段保留但未接音频播放
- 手电筒在部分 ROM 上需要 CAMERA 权限，代码已做 `isAvailable()` 探测降级

## 许可

本项目代码为原创实现。功能概念来自公开的计时器产品形态，不含任何第三方 APK 的代码、
资源或文案。图标为程序生成的简单矢量。
