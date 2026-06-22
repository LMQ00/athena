# SwipeGuard — ColorOS 16 划卡保护白名单编辑工具

## 项目概览

| 项目 | 内容 |
|------|------|
| **名称** | SwipeGuard（划卡卫士） |
| **包名** | `com.swipeguard.xposed` |
| **目标** | 编辑 ColorOS 16 Athena 白名单，防止划卡时被系统杀死 |
| **核心机制** | 3 个 Xposed Hook：运行时拦截杀进程 + 配置文件 XML 注入 + Athena 自有 API 拦截 |
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
├── .pi/context/
│   ├── reverse-system-athena.md # 系统 Athena APK 逆向报告（MCP）
│   └── plan.md                  # DAG 任务计划
├── app/
│   ├── build.gradle.kts         # 应用模块 (namespace: com.swipeguard.xposed)
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── resources/
│           │   └── META-INF/xposed/
│           │       ├── module.prop        # LSPosed 识别
│           │       ├── java_init.list     # libxposed 入口声明
│           │       └── scope.list         # Hook 作用域列表
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
│               │   ├── OplusConfigHooks.kt     # Hook 1: 注入白名单到 OFreezer 配置
│               │   ├── SwipeKillHooks.kt       # Hook 2: AMS + Athena 杀进程拦截
│               │   ├── AthenaKillHooks.kt      # Hook 3: Athena 自有 API 拦杀
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
Hook 注入层 (OplusConfigHooks + SwipeKillHooks + AthenaKillHooks)
    ↓
ColorOS 16 Athena 系统服务
```

### 数据模型（极简）

`SwipeGuardConfig` — 唯一数据类：

```kotlin
@Serializable
data class SwipeGuardConfig(
    var enabled: Boolean = true,             // 模块总开关
    var protectedApps: Set<String> = emptySet(),  // 要保护的包名集合
    var whitelistCategory: String = "100",   // whitePkg category 编码
    val schemaVersion: Int = 1               // 向前兼容
)
```

`whitelistCategory` 写入 `<whitePkg category="..."/>` 三位独立编码：
- `100` = forcewhite（系统级强制白名单，不可被覆盖）
- `010` = oppo/oneplus 自有应用
- `001` = 第三方应用

### 3 个 Hook

| Hook | 目标方法 | 作用 |
|------|----------|------|
| **OplusConfigHooks** | `FileInputStream` 读取 `sys_elsa_config_list.xml` + `OplusSettings.readConfig` | 在系统读取 OFreezer 冻结策略时注入白名单条目到 XML，使用真 XML parser 防丢原条目 + 去重 |
| **SwipeKillHooks** | `ActivityManagerService.killBackgroundProcesses` + `forceStopPackage` + `r3.c.forceStopPackageAndSaveActivity` | 三路径拦截杀进程：AOSP 标准划卡杀 + OEM forceStop + Athena 直杀路径（OplusActivityManager），白名单 app 全部返回 null 跳过 |
| **AthenaKillHooks** | `OplusAthenaSystemService.athenaKill` / `athenaKill2` / `clearProcess` | 拦截 Athena 自有 Binder API 杀进程调用，作为 SwipeKillHooks 的补充保护层 |

> **SystemServiceHooks 已移除**：原 `OomAdjuster.applyOomAdjLocked` 辅助保活路径已被第三方墓碑模块接管（参见下方「冻结路径说明」），移除后避免 LMK 行为冲突。

### 配置同步流程

```
UI 进程                          system_server 进程
────────                         ────────────────
SwipeGuardViewModel
  → LocalConfigRepository
  → SharedPreferences  ──Binder──→ RemoteConfigRepository
  (JSON: SwipeGuardConfig)         → SwipeKillHooks.syncConfig(repo)
                                   → AthenaKillHooks.syncConfig(repo)
                                   → OplusConfigHooks.updateConfig(config)
```

UI 写入 SharedPreferences → 框架自动通过 Binder 回调通知 system_server → `ModuleMain.syncHooks()` 分发给各 Hook 重新加载配置快照。

## MCP 逆向分析

本项目的 Hook 策略基于对 **ColorOS 16 系统 Athena APK**（`com.oplus.athena` v6.0.1）的完整逆向分析。详细报告见 [.pi/context/reverse-system-athena.md](.pi/context/reverse-system-athena.md)。

### 关键发现

1. **真实 kill 调用链**：
   ```
   系统触发（划卡/内存压力）
     → r3.c.forceStopPackageAndSaveActivity(pkg, userId)  ← Athena 最终执行点
       → i3.h (OplusActivityManager 薄封装)
       → android.app.OplusActivityManager.forceStopPackage()
       → x3.d.killProcess()
       → Process.killProcess()
   ```
   这条路径**绕过 AOSP AMS**，因此 `SwipeKillHooks` 必须 Hook `r3.c.forceStopPackageAndSaveActivity` 才能拦截 ColorOS 的真实杀进程行为。

2. **多层白名单体系**（5 层独立白名单）：
   - L1 系统保护（`background_protect_list`，硬编码）
   - L2 运营商定制（`/etc/oplus_customize_whitelist.xml`）
   - L3 Always-Alive 常驻保活（`sys_alwaysalive_config_list.xml`）
   - L4 ELSA 白名单（`sys_elsa_config_list.xml` `<whitePkg>` — **本模块主攻层**）
   - L5 GuardElf 通知保护（`notify_whitelist.xml`）

3. **`<whitePkg category="..."/>` 语义**：
   - `100` = forcewhite：系统强制白名单，绝对不杀
   - `010` = oppo/oneplus 自有应用
   - `001` = 第三方应用
   - `OplusConfigHooks` 默认注入 `category="100"` 以获得最高优先级的保护

4. **Hook 点候选表**：逆向报告 §6 列出 **14 个候选 Hook 点**（6 个 kill 拦截点 + 4 个白名单检查点 + 3 个配置加载点 + 2 个回调点），当前实现了其中优先级最高的 3 条路径。

## 冻结路径说明

**SwipeGuard 不处理进程冻结**，原因如下：

- ColorOS 16 后台管理有两条独立路径：
  1. **Kill 路径**：划卡清理 → Athena 决定是否杀进程（由本模块拦截）
  2. **Freeze 路径**：内存压力 / 夜间深度休眠 → cgroup freezer 冻结进程

- 用户已安装**第三方墓碑模块**，该模块专门接管 OplusFreeze / PKMS 冻结路径。

- 原 `SystemServiceHooks.kt`（Hook `OomAdjuster.applyOomAdjLocked` 强制设置 `oom_score_adj = -17`）是冻结路径的辅助保活手段，与第三方墓碑的 LMK 行为存在冲突，且反射 AOSP `ProcessRecord` 内部字段易随系统升级失效。

- **决策**：t7 已删除 `SystemServiceHooks.kt` 并从 `ModuleMain.kt` 清理。如需恢复，可通过 git history（commit message "移除冻结相关辅助保活"）一键回滚。

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
- **线程安全**：`SwipeGuardConfig` 的读写热更新时整体替换引用；`OplusConfigHooks` 的 `streamStates` 使用 `WeakHashMap` + `synchronized` 块保护，合并为 `StreamState` 对象减少三次锁查询
- **API 兼容**：libxposed API v100 与旧版 XposedBridge 不兼容，必须使用 LSPosed
- **Hook 安装顺序**：`OplusConfigHooks` 先注入白名单 → `SwipeKillHooks` / `AthenaKillHooks` 再拦截 kill，形成「配置 → 拦截」闭环
- **Athena 混淆兼容**：`SwipeKillHooks` 的路径 3 尝试多个混淆类名（`r3.c` / `r3.d`）以兼容不同 Athena 版本，找不到类时仅 WARN 降级
