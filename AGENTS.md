# SwipeGuard — ColorOS 16 划卡保护白名单编辑工具

## 项目概览

| 项目 | 内容 |
|------|------|
| **名称** | SwipeGuard（划卡卫士） |
| **包名** | `com.swipeguard.xposed` |
| **目标** | 编辑 ColorOS 16 Athena 白名单，防止划卡时被系统杀死 |
| **核心机制** | 3 个 Xposed Hook：运行时拦截 killBackgroundProcesses + 配置文件 XML 注入 + OomAdjuster 辅助保活 |
| **根目录** | `/data/data/com.termux/files/home/athena/` |

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **构建系统** | Gradle + AGP | Gradle 8.9 / AGP 8.7.3 |
| **语言** | Kotlin | JVM target 17 |
| **Android SDK** | compileSdk / targetSdk | 37 |
| **最低支持** | minSdk | 26 (Android 8.0) |
| **Xposed 框架** | libxposed API (现代 API) | v100 |
| **依赖管理** | Version Catalog | `gradle/libs.versions.toml` |
| **代码混淆** | ProGuard / R8 | 启用 (release) |
| **UI 方案** | Jetpack Compose + Material 3 | Compose BOM 2024.10.01 |
| **序列化** | kotlinx.serialization | JSON |

## 项目目录结构

```
athena/
├── AGENTS.md                    # 本项目上下文文档
├── plan.md                      # 任务计划与里程碑
├── README.md                    # 用户文档
├── build.gradle.kts             # 根构建脚本
├── settings.gradle.kts          # rootProject.name = "SwipeGuard"
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml       # 版本目录
│   └── wrapper/
├── app/
│   ├── build.gradle.kts         # 应用模块 (namespace: com.swipeguard.xposed)
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── module.prop        # LSPosed 识别
│           ├── res/values/strings.xml
│           └── java/com/swipeguard/xposed/
│               ├── SwipeGuardApplication.kt
│               ├── model/
│               │   └── SwipeGuardConfig.kt     # 极简数据模型
│               ├── data/
│               │   ├── IConfigRepository.kt    # 配置读写接口
│               │   ├── JsonCodec.kt            # JSON 编解码
│               │   ├── LocalConfigRepository.kt  # UI 进程侧仓库
│               │   └── RemoteConfigRepository.kt # Hook 进程侧仓库
│               ├── hook/
│               │   ├── ModuleMain.kt           # XposedModule 入口
│               │   ├── SwipeKillHooks.kt       # Hook 1: 拦截划卡杀进程
│               │   ├── OplusConfigHooks.kt     # Hook 2: 注入白名单到配置文件
│               │   ├── SystemServiceHooks.kt   # Hook 3: OomAdjuster 辅助保活
│               │   └── XmlPolicyBuilder.kt     # XML 白名单构建
│               └── ui/
│                   ├── MainActivity.kt         # 单屏入口 Activity
│                   ├── data/SwipeGuardViewModel.kt  # 单例 ViewModel
│                   ├── screens/SwipeGuardScreen.kt  # 主界面 Composable
│                   └── theme/                  # Material 3 主题
```

## 核心架构

```
UI 层 (Jetpack Compose)
    ↕ SharedPreferences
数据持久化层 (LocalConfigRepository / RemoteConfigRepository)
    ↕ XposedService 跨进程同步
Hook 注入层 (SwipeKillHooks + OplusConfigHooks + SystemServiceHooks)
    ↓
ColorOS 16 Athena 系统服务
```

### 数据模型（极简）

`SwipeGuardConfig` — 唯一数据类：

```kotlin
@Serializable
data class SwipeGuardConfig(
    var enabled: Boolean = true,           // 模块总开关
    var protectedApps: Set<String> = emptySet(),  // 要保护的包名集合
    val schemaVersion: Int = 1             // 向前兼容
)
```

### 3 个 Hook

| Hook | 目标方法 | 作用 |
|------|----------|------|
| **SwipeKillHooks** | `ActivityManagerService.killBackgroundProcesses` | 划卡时拦截杀进程调用，白名单 app 直接跳过 |
| **OplusConfigHooks** | `FileInputStream` 读取 `sys_elsa_config_list.xml` + `OplusSettings.readConfig` | 在系统读取冻结策略时注入白名单条目到 XML |
| **SystemServiceHooks** | `OomAdjuster.applyOomAdjLocked` | 将白名单 app 的 oom_score_adj 强制设为 -17（辅助保活） |

### 配置同步流程

```
UI 进程                          system_server 进程
────────                         ────────────────
SwipeGuardViewModel
  → LocalConfigRepository
  → SharedPreferences  ──Binder──→ RemoteConfigRepository
  (JSON: SwipeGuardConfig)         → SwipeKillHooks.config
                                   → OplusConfigHooks (闭包捕获)
                                   → SystemServiceHooks.config
```

UI 写入 SharedPreferences → 框架自动通过 Binder 回调通知 system_server → 各 Hook 重新加载配置快照。

## 构建命令

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease

# 产物位置
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

## 开发注意事项

- **容错原则**：所有 Hook 点必须 try-catch，单个 Hook 失败不得导致 system_server 崩溃
- **进程分离**：UI 进程与 Hook 进程分离，配置通过 XposedService 跨进程共享
- **ProGuard 规则**：`proguard-rules.pro` 已配置保留 `XposedModule` 子类入口
- **线程安全**：`SwipGuardConfig` 的读写使用 `@Synchronized` 保护；`OplusConfigHooks` 的劫持缓冲使用 `synchronized` 块
- **API 兼容**：libxposed API v100 与旧版 XposedBridge 不兼容，必须使用 LSPosed
