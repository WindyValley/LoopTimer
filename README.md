# LoopTimer - 循环计时器应用

> 番茄工作法风格的循环计时器，支持自定义预设、工作/休息交替、闹钟提醒。

**最新版本：** `1.1.0`  
**最低支持：** Android 8.0 (API 26)  
**目标版本：** Android 14 (API 34)

---

## 目录

1. [项目概述](#项目概述)
2. [技术栈](#技术栈)
3. [项目结构](#项目结构)
4. [核心模块](#核心模块)
5. [数据存储](#数据存储)
6. [服务与广播](#服务与广播)
7. [UI 架构](#ui-架构)
8. [预设系统](#预设系统)
9. [重要实现细节](#重要实现细节)
10. [构建与发布](#构建与发布)
11. [版本历史](#版本历史)

---

## 项目概述

LoopTimer 是一款运行在 Android 上的循环计时器应用，采用番茄工作法理念：

- **工作阶段** → **休息阶段** → **工作阶段** → ... 循环往复
- 支持自定义循环次数
- 支持闹钟提醒（可设置最大持续时间）
- 支持自动开始下一阶段
- 可保存和加载预设配置

### 主要界面

| 界面 | 描述 |
|------|------|
| **MainActivity** | 主计时器界面，显示倒计时、开始/暂停、重置、跳过等功能 |
| **PresetListActivity** | 预设列表，管理（选择/编辑/删除）保存的预设 |
| **PresetEditActivity** | 预设编辑，新建或修改预设配置 |

---

## 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Kotlin |
| **UI 框架** | Jetpack Compose + Material 3 |
| **最小 SDK** | 26 (Android 8.0) |
| **目标 SDK** | 34 (Android 14) |
| **编译 SDK** | 35 |
| **Kotlin 版本** | 2.0.21 |
| **Compose BOM** | 2024.12.01 |
| **Gradle** | AGP 8.7.3 |
| **数据存储** | SharedPreferences + JSON (org.json) |

---

## 项目结构

```
LoopTimer/
├── app/
│   ├── build.gradle.kts          # App 模块构建配置
│   ├── proguard-rules.pro        # ProGuard 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 应用清单文件
│       ├── kotlin/com/looptimer/
│       │   ├── MainActivity.kt       # 主界面 (计时器 + 设置)
│       │   ├── TimerPreset.kt        # 预设数据类 + PresetsManager
│       │   ├── PresetListActivity.kt # 预设列表
│       │   ├── PresetEditActivity.kt # 预设编辑
│       │   ├── AlarmReceiver.kt      # 闹钟广播接收器
│       │   └── VERSION.kt           # 版本号常量
│       └── res/
│           ├── values/
│           │   ├── strings.xml       # 字符串资源
│           │   └── themes.xml        # 主题配置
│           └── drawable/             # 图标资源
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 项目设置 (仓库配置)
├── gradle.properties             # Gradle 属性 (版本号在此)
└── keystore.jks                  # 签名密钥 (不提交到仓库)
```

---

## 核心模块

### 1. TimerPreset.kt

预设数据模型及管理器。

```kotlin
data class TimerPreset(
    val id: String,
    val name: String,
    val workMinutes: Int,
    val workSeconds: Int,
    val breakMinutes: Int,
    val breakSeconds: Int,
    val loops: Int,
    val useAlarm: Boolean,
    val alarmDuration: Int,
    val autoStart: Boolean
)
```

**PresetsManager** 负责：
- 预设的 CRUD 操作（增删改查）
- 当前选中预设 ID 的管理
- 当前计时器设置（用于从预设恢复）
- 主题设置（深色/浅色）
- 底层使用 SharedPreferences + JSON 序列化

### 2. MainActivity.kt

包含三大块：

#### 2.1 应用类

```kotlin
class MainActivity : ComponentActivity()
- 初始化通知权限 (Android 13+)
- 处理来自通知的启动意图 (action = "start_timer")
- 管理 PresetsManager 实例
```

#### 2.2 TimerService (内部类)

前台服务，确保计时器在后台运行。

- 创建低优先级通知通道
- 支持 `START` / `STOP` 两个 Action
- 用户可通过通知栏按钮停止计时器

#### 2.3 AlarmController (object 单例)

管理闹钟的播放与停止：

- 使用 `MediaPlayer` 播放系统默认闹钟铃声（循环）
- 使用 `Vibrator` 震动（震动模式：响1秒停0.5秒，重复5次）
- 支持定时自动停止（alarmDuration > 0 时）
- `stopAlarm()` 全面清理 MediaPlayer 和 Vibrator

#### 2.4 LoopTimerScreen (Composable)

主界面 Composable。

**状态变量：**
| 变量 | 类型 | 描述 |
|------|------|------|
| `workMinutes/SECONDS` | Long | 工作时长 |
| `breakMinutes/SECONDS` | Long | 休息时长 |
| `loops` | Int | 总循环次数 |
| `currentLoop` | Int | 当前第几轮 |
| `isWorkPhase` | Boolean | 当前是否工作阶段 |
| `timeLeft` | Long | 剩余秒数 |
| `isRunning` | Boolean | 是否运行中 |
| `useAlarm` | Boolean | 是否启用闹钟 |
| `alarmDuration` | Int | 闹钟持续秒数 (-1=一直响) |
| `autoStart` | Boolean | 是否自动开始下一阶段 |
| `alarmPlaying` | Boolean | 闹钟是否正在响 |
| `isDarkTheme` | Boolean | 是否深色主题 |

**计时逻辑：**
1. 计时结束 → 判断是工作结束还是休息结束
2. 如果还有剩余轮次，切换阶段并重置 timeLeft
3. 如果是最后一轮工作结束 → 回到第1轮（完成）
4. 期间触发闹钟、发送通知

**通知点击处理：**
- 通知点击 → 发送 `start_timer` Intent 到 MainActivity
- 设置 `pendingStartTimerFromNotification = true`
- `LaunchedEffect` 轮询检测该标志 → 恢复计时

---

## 数据存储

### SharedPreferences 结构

| Key | 类型 | 描述 |
|-----|------|------|
| `presets` | String (JSON Array) | 所有预设列表 |
| `selected_preset` | String? | 当前选中预设 ID |
| `current_settings` | String (JSON) | 当前计时器设置（从预设加载） |
| `settings_timestamp` | Long | 设置更新时间戳（用于检测变更） |
| `dark_theme` | Boolean | 深色主题开关 |

### JSON 格式示例

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "番茄工作法",
  "workMinutes": 25,
  "workSeconds": 0,
  "breakMinutes": 5,
  "breakSeconds": 0,
  "loops": 4,
  "useAlarm": true,
  "alarmDuration": 30,
  "autoStart": true
}
```

---

## 服务与广播

### TimerService

| 属性 | 值 |
|------|---|
| 类型 | Foreground Service |
| FGS 类型 | `specialUse` (subtype: timer) |
| 通知渠道 ID | `timer_service_channel` |
| 通知 ID | `1003` |

**Actions：**
- `START` - 启动前台服务，显示持续通知
- `STOP` - 停止前台服务

### AlarmReceiver

广播接收器，处理闹钟相关 Intent：

| Action | 行为 |
|--------|------|
| `STOP_ALARM` | 停止闹钟 |
| `START_OR_STOP` | 停止闹钟 + 启动计时器服务 |
| 其他 | 启动闹钟（从 Intent 读取 duration 参数） |

---

## UI 架构

应用使用 **Jetpack Compose** 配合 **Material 3**。

### 主题切换

- 深色/浅色主题通过 `isDarkTheme: Boolean` 状态控制
- 颜色方案硬编码在 Composable 中（非 M3 DynamicColor）

### 关键 UI 组件

| 组件 | 位置 | 用途 |
|------|------|------|
| `LoopTimerScreen` | MainActivity.kt | 主计时器界面 |
| `PresetListScreen` | PresetListActivity.kt | 预设列表界面 |
| `PresetEditScreen` | PresetEditActivity.kt | 预设编辑界面 |
| `PresetCard` | PresetListActivity.kt | 预设列表项卡片 |
| `TimeInputRow` | MainActivity.kt | 时间输入行 |
| `LoopInputRow` | MainActivity.kt | 循环次数输入 |
| `AlarmSettingsPanel` | MainActivity.kt | 闹钟设置面板 |

---

## 预设系统

### 选择预设流程

```
PresetListActivity
    ↓ 选择预设
PresetsManager.setSelectedPresetId(id)
PresetsManager.saveCurrentTimerSettings(preset)  // 同时更新 timestamp
    ↓
MainActivity.onResume 触发
    ↓
LaunchedEffect(resumeTrigger) 检测 timestamp 变化
    ↓
重新加载设置到 UI
```

### ⚠️ 重要：预设热重载机制

**问题背景：** `LaunchedEffect(Unit)` 只在 Composable 首次组合时执行一次，无法响应预设变更。

**解决方案：** 使用 `LifecycleEventObserver` 监听 `ON_RESUME` 事件，每次从后台恢复时触发 `LaunchedEffect`，通过对比 `settingsTimestamp` 判断是否需要重新加载。

```kotlin
// MainActivity.kt 中
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            resumeTrigger++  // 递增触发器，激活 LaunchedEffect
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}

LaunchedEffect(resumeTrigger) {
    val timestamp = presetsManager.getSettingsTimestamp()
    if (timestamp > lastSettingsTimestamp) {
        // 重新加载预设...
        lastSettingsTimestamp = timestamp
    }
}
```

**这个机制是 2026-04-27 修复的 Bug，确保从预设列表返回时能立即看到最新设置。**

---

## 重要实现细节

### 1. 通知点击启动计时器

通知点击流程：

```
通知点击
    ↓
MainActivity.onNewIntent() 收到 "start_timer" action
    ↓
pendingStartTimerFromNotification = true
    ↓
LaunchedEffect(Unit) 轮询检测
    ↓
停止闹钟 → 计算下一阶段 → startTimer()
```

### 2. 闹钟与通知协同

- 计时结束 → 如果 `useAlarm=true` → 调用 `AlarmController.startAlarm()` 并显示通知
- 通知文本："点击开始计时" 或具体消息
- 点击通知 → 停止闹钟 → 自动开始下一阶段
- 用户手动点击"停止闹钟"按钮 → `AlarmController.stopAlarm()`

### 3. 时间格式输入

使用 `BasicTextField` 实现数字输入：
- 分：0-999
- 秒：0-59
- 循环次数：1-99
- 闹钟时长：0-300 秒（-1 表示一直响）

### 4. 前台服务保活

- 计时器运行时启动 `TimerService`（前台服务）
- 显示低优先级持续通知
- 用户可从通知栏停止计时器
- 计时结束或闹钟响起时停止服务

---

## 构建与发布

### 环境要求

- **Java JDK 17**
- **Android SDK** (已配置在 `C:\Users\Windy\android-sdk`)
- **Gradle Wrapper** (已包含在项目中)

### 本地构建

```bash
# Debug 构建
cd C:\Users\Windy\LoopTimer
.\gradlew assembleDebug

# Release 构建（需要签名环境变量）
.\gradlew assembleRelease
```

### 签名配置

Release 构建使用 `keystore.jks` 密钥库，签名参数通过环境变量传入：

| 环境变量 | 说明 |
|----------|------|
| `ANDROID_SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `ANDROID_SIGNING_KEY_ALIAS` | 密钥别名 |
| `ANDROID_SIGNING_KEY_PASSWORD` | 密钥密码 |

### 版本号管理

版本号在 `gradle.properties` 中统一管理：

```properties
VERSION_MAJOR=1
VERSION_MINOR=1
VERSION_PATCH=0
VERSION_NAME=1.1.0
```

⚠️ **注意：** `VERSION.kt` 中的 `PATCH` 值需手动与 `gradle.properties` 保持同步（这是历史遗留问题）。

### 输出路径

| 构建类型 | 路径 |
|----------|------|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| Release | `app/build/outputs/apk/release/app-release.apk` |

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| **1.1.0** | 2026-05-01 | 新增预设功能，支持保存/加载/管理多个计时器配置 |
| **1.0.2** | 2026-04-27 | 修复 Bug：选择预设后必须重启才能生效 |
| **1.0.1** | 2026-04-?? | 闹钟支持自定义持续时间 (-1=一直响) |
| **1.0.0** | 2026-04-?? | 初始版本，支持番茄工作法计时 |

---

## 开发者提示

### 调试技巧

1. **查看 Logcat 过滤 `looptimer`：**
   ```bash
   adb logcat -s looptimer:V
   ```

2. **安装当前构建：**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **清除应用数据（重置预设）：**
   ```bash
   adb shell pm clear com.looptimer
   ```

### 待优化项

- [ ] 国际化 (strings.xml 目前只有中文)
- [ ] `VERSION.kt` 和 `gradle.properties` 版本号同步自动化
- [ ] 备份/恢复预设功能
- [ ] 多语言支持
- [ ] M3 DynamicColor 支持

---

## 许可证

本项目仅供个人学习使用。