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
| **Xposed 框架** | libxposed API (现代 API) | 102.0.0 |
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
│               │   ├── OplusConfigHooks.kt     # 配置层: 注入 whitePkg + 持久化系统默认白名单
│               │   ├── WhitePkgLookupHooks.kt # 决策层: OFreezer 运行时 whitePkg 查询拦截
│               │   ├── SwipeKillHooks.kt       # 执行层: AMS+Athena+cgroup 杀进程拦截(7路径)
│               │   ├── AthenaKillHooks.kt      # API 层: Athena 自有 API 拦杀
│               │   ├── AthenaBinderHooks.kt    # Binder 层: Binder 入口拦截
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
    var userAdditions: Set<String> = emptySet(),  // 用户额外添加的包名
    var userRemovals: Set<String> = emptySet(),   // 用户从系统默认白名单中移除的包名
    var whitelistCategory: String = "100",   // whitePkg category 编码
    val schemaVersion: Int = 2               // 向前兼容
)
```

双层白名单架构：
- **系统默认白名单** (`systemDefaults`)：从 ColorOS `sys_elsa_config_list.xml` 中提取的 OEM 预设白名单，由 Hook 进程写入 `SharedPreferences` 的 `system_defaults` key
- **用户修改**：`userAdditions` 添加 / `userRemovals` 移除
- **有效白名单** = `(systemDefaults - userRemovals) + userAdditions`

旧数据迁移：schema v1 的 `protectedApps` → v2 的 `userAdditions`，由 `SwipeGuardConfig.fromJson()` 自动转换，`LocalConfigRepository.load()` 自动持久化。

`whitelistCategory` 写入 `<whitePkg category="..."/>` 三位独立编码：
- `100` = forcewhite（系统级强制白名单，不可被覆盖）
- `010` = oppo/oneplus 自有应用
- `001` = 第三方应用

### 5 个 Hook

| Hook | 目标方法 | 作用 |
|------|----------|------|
| **WhitePkgLookupHooks** | `g2/e$d.M/N/P` 运行时 whitePkg 查询 | **决策层保护**：在 OFreezer 做 kill 决策前拦截 whitePkg 查询，使 OFreezer 认为白名单应用在 `<whitePkg>` 列表中，跳过杀进程路径 |
| **OplusConfigHooks** | `FileInputStream` 读取 `sys_elsa_config_list.xml` + `OplusSettings.readConfig` | **配置层保护**：系统启动时劫持配置读取并注入 whitePkg 条目；同时从设备真实 XML 提取系统默认白名单并写回 SharedPreferences |
| **SwipeKillHooks** | 7 条 kill 路径拦截 | **执行层保护**：AMS `killBackgroundProcesses` + `forceStopPackage` + Athena `r3.c.forceStopPackageAndSaveActivity` + `OplusActivityManager.forceStopPackage` + `x3.d.killProcess` + `x3.d.killProcessGroup`（cgroup 级别）+ `Process.killProcess`（PID 反查） |
| **AthenaKillHooks** | `athenaKill` / `athenaKill2` / `athenaKill3` / `clearProcess` | 拦截 Athena 自有 API 杀进程调用 |
| **AthenaBinderHooks** | `IAthenaService.Stub.onTransact` | Binder 入口拦截（在 `com.oplus.athena` 进程运行） |

### 系统默认白名单自动提取

`OplusConfigHooks.hijackStream()` 在系统启动时从设备真实 `sys_elsa_config_list.xml` 提取 `systemDefaults`，自动写回 SharedPreferences：

```kotlin
val extractedDefaults = XmlPolicyBuilder.extractWhitePkgNames(originalXml)
persistExtractedDefaults(extractedDefaults)  // → SharedPreferences 的 KEY_CONFIG_JSON
```

确保所有 Hook 使用**设备真实的系统默认白名单**（而非硬编码的 `KNOWN_SYSTEM_DEFAULTS` 约 50 个包名），运营商定制和不同 ColorOS 版本的特殊系统应用也能被保护。

### 配置同步流程

```
UI 进程                          system_server 进程
────────                         ────────────────
SwipeGuardViewModel
  → LocalConfigRepository
  → SharedPreferences  ──Binder──→ RemoteConfigRepository
  (JSON: SwipeGuardConfig)         → WhitePkgLookupHooks.syncConfig(repo)
                                   → SwipeKillHooks.syncConfig(repo)
                                   → AthenaKillHooks.syncConfig(repo)
                                   → OplusConfigHooks.updateConfig(config)
                                   → AthenaBinderHooks.syncConfig(repo)
```

UI 写入 SharedPreferences → Binder 回调 → `ModuleMain.syncHooks()` 分发给 5 个 Hook。

## MCP 逆向分析

本项目的 Hook 策略基于对 **ColorOS 16 系统 Athena APK**（`com.oplus.athena` v6.0.1）的完整逆向分析。详细报告见 [.pi/context/reverse-system-athena.md](.pi/context/reverse-system-athena.md)。

### 关键发现

1. **完整 kill 调用链**（含所有已 Hook 拦截点）：
   ```
   系统触发（划卡/内存压力）
     → IAthenaService.clearProcess(Bundle)
       → h1 (AthenaKillerManagerService)
         → g2/e$d.M(pkg)?  ← WhitePkgLookupHooks 拦截 ★ 决策层
           → 是 → 跳过 kill ✓
           → 否 → r3.c.forceStopPackageAndSaveActivity(pkg, userId)
                    ← SwipeKillHooks 拦截 ★ 执行层
             → OplusActivityManager.forceStopPackage()
               ← SwipeKillHooks 拦截 ★ 执行层
             → x3.d.killProcess(clearInfo, ...)
               ← SwipeKillHooks 拦截 ★ 杀进程执行
             → x3.d.killProcessGroup(uid, pid, ...)
               ← SwipeKillHooks 拦截 ★ cgroup 保护
             → Process.killProcess(pid)
               ← SwipeKillHooks 拦截 ★ 最后防线
   ```
   决策层 + 执行层双层保护，新增白名单在运行时通过 WhitePkgLookupHooks 生效。

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

4. **Hook 点候选表**：逆向报告 §6 列出 **14 个候选 Hook 点**，当前实现了 5 个优先级最高的路径覆盖（新增 `WhitePkgLookupHooks` HK07 和 `killProcessGroup` HK03）。

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
- **API 兼容**：libxposed API 102.0.0 与旧版 XposedBridge 不兼容，必须使用 LSPosed
- **Hook 安装顺序**：`OplusConfigHooks` 先注入白名单 → `WhitePkgLookupHooks` 决策层拦截 → `SwipeKillHooks` / `AthenaKillHooks` 执行层拦截，形成「配置 → 决策 → 执行」三级保护
- **Athena 混淆兼容**：所有混淆类名提供多个候选以兼容不同 Athena 版本，找不到类时仅 WARN 降级。`android.app.OplusActivityManager` 是框架 API 不随混淆变化，是最可靠的拦截点
- **系统默认白名单持久化**：`OplusConfigHooks.hijackStream()` 提取的设备真实默认白名单自动写回 SharedPreferences，无需用户手动添加
- **cgroup 保护**：`SwipeKillHooks` 新增 `killProcessGroup` 路径（HK03），防止内核 cgroup 级别的杀进程绕过 Java 层拦截
