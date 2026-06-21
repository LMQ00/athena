# Athena — ColorOS 16 后台管理 Xposed 模块

## 项目概览

| 项目 | 内容 |
|------|------|
| **名称** | Athena（雅典娜） |
| **包名** | `com.athena.xposed` |
| **目标** | 通过 Hook ColorOS 16 的 OFreezer 策略读取路径，注入自定义白名单/黑名单，精确控制后台进程冻结行为 |
| **核心机制** | 参考 [OplusConfigHook](https://github.com/kyaryunha/OplusConfigHook)，Hook `FileInputStream` 读取 `/data/oplus/os/bpm/sys_elsa_config_list.xml`，返回修改后的策略 |
| **当前状态** | 项目骨架搭建完成，代码实现阶段进行中 |
| **根目录** | `/data/data/com.termux/files/home/athena/` |

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| **构建系统** | Gradle + AGP | Gradle 8.9 / AGP 8.7.3 |
| **语言** | Kotlin | Java 17 (JVM target) |
| **Android SDK** | compileSdk / targetSdk | 35 |
| **最低支持** | minSdk | 26 (Android 8.0) |
| **Xposed 框架** | libxposed API (现代 API) | 102.0.0 |
| **Xposed 框架** | libxposed Service | 102.0.0 |
| **依赖管理** | Version Catalog | `gradle/libs.versions.toml` |
| **代码混淆** | ProGuard / R8 | 启用 (release) |
| **UI 方案** | 待确定 — 原生 Android Theme / Material Design / Jetpack Compose | 待选型 |

## 项目目录结构

```
athena/
├── AGENTS.md                    # 本项目上下文文档 (当前文件)
├── plan.md                      # 全局任务计划与里程碑
├── build.gradle.kts             # 根构建脚本 (仅声明插件)
├── settings.gradle.kts          # 项目设置 (rootProject.name = "Athena")
├── gradle.properties            # Gradle 全局属性
├── gradle/
│   ├── libs.versions.toml       # 版本目录 (集中管理依赖版本)
│   └── wrapper/
│       └── gradle-wrapper.properties  # Gradle 8.9 wrapper 配置
├── app/
│   ├── build.gradle.kts         # 应用模块构建脚本
│   ├── proguard-rules.pro       # ProGuard/R8 保留规则 (XposedModule 入口)
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml   # 模块声明 (XposedService + MainActivity)
│           ├── res/
│           │   └── values/
│           │       └── strings.xml   # 应用名: "Athena"
│           └── java/                  # (待创建) 源码目录
│               └── com/athena/xposed/
│                   ├── model/         # 数据模型层 (已设计, 待实现)
│                   ├── data/          # 数据持久化层 (待实现)
│                   ├── engine/        # 策略匹配引擎 (待实现)
│                   ├── hook/          # Hook 注入层 (待实现)
│                   └── ui/            # 设置界面 (待实现)
```

## 核心架构

项目采用四层架构，自底向上分别为 **数据模型层 → 数据持久化层 → 策略匹配引擎层 → Hook 注入层**，外加 **UI 层** 作为用户交互入口。

### 1. 数据模型层 (`com.athena.xposed.model`)

已设计但尚未实现。核心数据类：

| 文件 | 类型 | 说明 |
|------|------|------|
| `ProtectionMode.kt` | `enum` | 保护模式：`WHITELIST` / `BLACKLIST` / `IM_KEEPALIVE` / `CUSTOM_FREEZE_CONFIG` |
| `DefaultPolicy.kt` | `enum` | 默认策略：`FOLLOW_SYSTEM` / `FORCE_EXCLUDE` / `FORCE_FREEZE` |
| `ProtectionResult.kt` | `enum` | 匹配结果枚举 |
| `AppEntry.kt` | `data class` | 单应用条目：`packageName`, `appName`, `processNames`, `mode`, `enabled` 等 |
| `ModuleConfig.kt` | `data class` | 模块全局配置：`globalEnabled`, `defaultPolicy`, `debugLog`, `nativeFileInjection` |
| `EntrySet.kt` | 集合类 | 带索引的 `AppEntry` 条目集合 |
| `FreezePolicy.kt` | `data class` | 最终冻结策略：`whitePkg`, `ffPkg`, `ffTimeoutMs`, `imPkg` 等 |
| `AthenaConfig.kt` | `data class` | 根容器，聚合所有配置 |

### 2. 数据持久化层 (`com.athena.xposed.data`)

| 组件 | 说明 |
|------|------|
| `LocalConfigRepository` | UI 进程侧的数据仓库，读写 `SharedPreferences`，序列化/反序列化 JSON |
| `RemoteConfigRepository` | Hook 进程侧的数据仓库，通过 `XposedService` 跨进程读取 UI 进程的配置 |
| **共享机制** | UI 写入 `SharedPreferences` → JSON 序列化 → `XposedService` 远端读取 → `PolicyMatcher.reload()` |

### 3. 策略匹配引擎层 (`com.athena.xposed.engine`)

| 组件 | 说明 |
|------|------|
| `PolicyMatcher` | 核心匹配引擎，接收包名/进程名输入，按优先级规则输出最终 `FreezePolicy` |

**匹配优先级规则**（从高到低）：

```
IM_KEEPALIVE (即时通讯保活)  >  WHITELIST (白名单)  >  BLACKLIST (黑名单)  >  DefaultPolicy (默认策略)
```

### 4. Hook 注入层 (`com.athena.xposed.hook`)

计划中的 Hook 入口与注入点：

| 组件 | 说明 |
|------|------|
| `ModuleMain` | 实现 `XposedModule` 接口，libxposed API v102 模块入口 |
| `OplusConfigHooks` | Hook `FileInputStream` 构造器，拦截对 `sys_elsa_config_list.xml` 和 `autostart_white_list.txt` 的读取，返回修改后的策略 XML |
| `SystemServiceHooks` | (可选增强) Hook `ActivityManagerService` / `ProcessList` / `OomAdjuster` 等相关方法 |

### 5. UI 层 (`com.athena.xposed.ui`)

| 组件 | 说明 |
|------|------|
| `MainActivity` | 已在 AndroidManifest 声明，待实现：全局开关、模式选择、白名单/黑名单编辑 |

## 关键业务规则

### 优先级匹配逻辑

```
输入: packageName + processName
  │
  ├─ 是否匹配 IM_KEEPALIVE 条目? → 是 → 返回 IM_KEEPALIVE 保护策略
  │
  ├─ 是否匹配 WHITELIST 条目?   → 是 → 返回 排除冻结 (EXCLUDE)
  │
  ├─ 是否匹配 BLACKLIST 条目?   → 是 → 返回 强制冻结 (FORCE_FREEZE)
  │
  └─ 未匹配任何条目 → 使用 DefaultPolicy (FOLLOW_SYSTEM / FORCE_EXCLUDE / FORCE_FREEZE)
```

### 数据流

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI 进程 (宿主)                           │
│  MainActivity ──> LocalConfigRepository                        │
│                        │                                        │
│                        ▼                                        │
│                  SharedPreferences                              │
│             (JSON 序列化 AthenaConfig)                          │
└────────────────────────┬────────────────────────────────────────┘
                         │ 跨进程读取 (XposedService)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Hook 进程 (Xposed 模块)                      │
│  RemoteConfigRepository ──> PolicyMatcher.reload()              │
│                                    │                            │
│                                    ▼                            │
│  Hook 回调中调用 getEffectiveConfig(pkgName, processName)       │
│  └─ 返回 FreezePolicy ──> 注入修改后的 XML 策略               │
└─────────────────────────────────────────────────────────────────┘
```

### 核心 Hook 路径

1. 拦截 `java.io.FileInputStream.<init>(File)` 构造器
2. 当文件路径为 `/data/oplus/os/bpm/sys_elsa_config_list.xml` 时：
   - 读取原始 XML 内容
   - 解析当前策略
   - 根据 `PolicyMatcher` 的匹配结果，注入自定义白名单/黑名单条目
   - 返回修改后的 XML 字节流
3. (可选) 额外 Hook `OplusSettings.readConfig` 相关方法增强兼容性

## 开发指南

### 环境要求

- Android Studio Koala / Ladybug 或更高版本
- JDK 17+
- Android SDK 35
- Gradle 8.9 (由 wrapper 自动管理)
- 已安装 LSPosed 或兼容 libxposed 的框架的真机或模拟器 (ColorOS 16)

### 如何构建

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建 (启用 R8 混淆和资源压缩)
./gradlew :app:assembleRelease

# 产物位置
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

### 如何添加新的 Hook 点

1. 在 `com.athena.xposed.hook` 包下创建新的 Hook 类，如 `NewSystemServiceHooks.kt`
2. 在类中实现 Hook 逻辑，优先使用 `XposedHelpers.findAndHookMethod` 或 libxposed 现代 API
3. 在 `ModuleMain` 中注册新的 Hook 类
4. 确保 try-catch 包裹所有 `Class.forName` 和反射调用，防止因 ColorOS OTA 导致的 ClassNotFoundException 崩溃

### 如何修改数据模型

1. 修改 `com.athena.xposed.model` 下对应的 data class 或 enum
2. 确保 JSON 序列化/反序列化兼容旧格式（使用 `@JvmStatic` 默认值处理新增字段）
3. 更新 `AthenaConfig` 根容器
4. 同步更新 `PolicyMatcher` 中的匹配逻辑
5. 更新 UI 层对应的编辑界面

### 如何添加新的保护模式

1. 在 `ProtectionMode.kt` 中添加新的 enum 常量
2. 在 `FreezePolicy.kt` 中添加对应的策略字段
3. 在 `PolicyMatcher` 的优先级链中插入新的匹配步骤
4. 在 UI 层的模式选择器中添加新选项

### 调试技巧

- 在 `ModuleConfig.debugLog` 为 `true` 时输出详细日志到 Logcat，Tag 统一使用 `Athena`
- 待 `nativeFileInjection` 选项实现后，可通过直接修改原始 XML 文件的方式调试
- 推荐使用 [KernelSU](https://kernelsu.org) / [APatch](https://github.com/bmax121/APatch) 获取 root 权限后查看 `/data/oplus/os/bpm/sys_elsa_config_list.xml` 原始内容

### 注意事项

- **API 兼容性**: libxposed API v102 是 LSPosed 现代 API，与旧版 XposedBridge API 不兼容，所有 Hook 应使用 libxposed 的 `XposedModule` 接口
- **错误隔离**: 所有 Hook 点必须 try-catch，避免单个 Hook 失败导致模块整体异常或系统崩溃
- **进程分离**: UI 进程与 Hook 进程分离，配置通过 `XposedService` 跨进程共享，注意线程安全
- **ProGuard 规则**: `proguard-rules.pro` 已配置保留 `XposedModule` 子类的入口构造函数，新增 Hook 类无需额外配置
- **安全合规**: 本模块仅供学习研究用途，不得用于恶意破坏或绕过系统安全机制
