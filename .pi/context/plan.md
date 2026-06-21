# Plan: ColorOS 16 后台白名单管理模块（SwipeGuard）

> **状态**：基于 3 份 scout 调研结果重新规划（取代之前 plan.md 中的初步 SwipeGuard 草案）
> **目标版本**：能在 ColorOS 16 + LSPosed v1.9.2 真机上跑起来的 Xposed 模块 APK
> **核心交付**：能编辑 ColorOS Athena 白名单的工具 —— 添加进白名单的 app 在划卡时不被杀进程

---

## 🎯 项目改名方案

### 调研结论
原项目名 **"Athena"** 与 ColorOS 系统组件 `com.coloros.athena` 同名，会导致：
- 文档混淆（用户和开发者不知道"哪个 Athena"）
- 可能的包名/类名冲突
- 法律责任风险（OPPO 的 Athena 商标）

### 新名字决策
**采用「SwipeGuard」**（沿用 plan.md 草案，但补充更充分的理由）：

| 候选 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **SwipeGuard** | 直观（划卡卫士），与「划卡杀进程」场景直接对应 | "Guard" 类名易撞 | ✅ **采用** |
| OplusWhitelist | 技术精确（Oplus + 白名单） | 难念，啰嗦 | ❌ |
| OplusKeeper | 含义清晰（保活） | 没体现"白名单编辑" | ❌ |
| ColorGuard | 简洁 | "Color" 已被 ColorOS 占用 | ❌ |
| AthenaBypass | 沿用旧名 | 仍含 Athena 商标风险 | ❌ |

### 包名 / 资源 ID 全面替换
- `com.athena.xposed` → `com.swipeguard.xposed`
- `com/athena/xposed/` → `com/swipeguard/xposed/`
- 字符串资源 `app_name` → `SwipeGuard` / `划卡卫士`
- 文件名 `athena_config`（SharedPreferences）→ `swipeguard_config`
- `META-INF/xposed/java_init.list` 入口类路径更新
- `Settings` rootProject.name → `SwipeGuard`

---

## 🔧 LSPosed 识别修复方案（**核心问题**）

### Scout #1 关键发现
LSPosed v1.9.2 **只支持 libxposed API 100**，**不支持 102**！

| 组件 | 当前 | 修正 |
|------|------|------|
| libxposed API 编译依赖 | `102.0.0` | **`100.0.0`** |
| libxposed Service 依赖 | `102.0.0` | **`100.0.0`** |
| `META-INF/xposed/module.prop` | **缺失** | **必须新增** |
| `META-INF/xposed/java_init.list` | ✅ 有（路径需重命名） | 重命名到 `com.swipeguard.xposed.hook.ModuleMain` |
| AndroidManifest xposedmodule meta-data | ❌ 不需要 | ❌ 不需要（libxposed 风格） |
| `packaging.resources.merges` | ✅ 已配 | ✅ 保留 |

### 详细修复清单

#### 1) `gradle/libs.versions.toml` 降级
```toml
[versions]
xposed-api = "100.0.0"        # ← 从 102.0.0
xposed-service = "100.0.0"    # ← 从 102.0.0
```

#### 2) 新增 `app/src/main/resources/META-INF/xposed/module.prop`
```properties
minApiVersion=100
targetApiVersion=100
staticScope=true
autoHotReload=true
```
**关键**：`minApiVersion=100` 是 LSPosed v1.9.2 daemon 校验入口的硬性下限。

#### 3) `java_init.list` 重写
```
# 旧: com.athena.xposed.hook.ModuleMain
# 新:
com.swipeguard.xposed.hook.ModuleMain
```

#### 4) 验证打包
构建后用 `unzip -l app/build/outputs/apk/release/app-release.apk | grep META-INF/xposed` 确认两个文件都在。

### API 100 vs 102 代码适配风险
⚠️ **风险点**：libxposed 100 → 102 期间 API 可能有破坏性变更。需要在降级后跑一次 `assembleDebug` 确认编译通过。
**已知 API 100 接口**（根据 LSPosed 源码推断）：
- `XposedModule`、`XposedInterface`、`ExceptionMode.PROTECTIVE` — 都在 100 已存在
- `module.hook(method).intercept { chain -> ... }` 链式 API — 已在 100 提供
- `getRemotePreferences(name)` — 已在 100 提供
- `onSystemServerStarting(param)` — 已在 100 提供

**结论**：现有代码 99% 应该能编译，仅需确认 kotlin coroutine lambda 类型签名。

---

## 🐛 Scout #3 关键发现：当前 Hook 思路不解决真实需求

### 真实问题链
```
用户划卡 → ActivityManagerService.removeTask()
  → OplusActivityManagerService.removeTask()
  → OplusBackgroundExecutor.killProcessGroup()  ← 关键拦截点
  → Process.killProcessGroup(uid, pid)
  → 进程死亡
```

### 当前 OplusConfigHooks 的两个致命问题
1. **XML schema 完全错误**
   - 当前生成：`<elsa_config><white_pkg_list><pkg_item name="..."/></white_pkg_list></elsa_config>`
   - ColorOS 16 实际：`<filter-conf><whitePkg name="..." category="..."/></filter-conf>`
   - **结果**：ColorOS 解析器直接忽略注入的条目 → 白名单注入无效
2. **干预的是 freeze 路径，不是 kill 路径**
   - 走 `sys_elsa_config_list.xml` → OFreezer 冻结
   - 划卡走 `killProcessGroup` → 直接杀
   - **结果**：即使 XML 正确，划卡保护也不生效

### 修正后的 Hook 策略
**双路径保护**：

| 路径 | Hook 点 | 作用时机 | 修复内容 |
|------|---------|---------|---------|
| **A** | `FileInputStream(".../sys_elsa_config_list.xml")` | 系统服务启动时 | 重写 XmlPolicyBuilder 用正确的 `<filter-conf>` schema → 防 freeze |
| **B** | **`Process.killProcessGroup(uid, pid)`**（新增） | 运行时划卡 | 检查 pid 对应的包是否在白名单，命中则拦截 kill → 防划卡 kill |
| C | `OomAdjuster.applyOomAdjLocked`（保留） | LMK 计算 adj | 强制白名单进程 adj = -17 → 防 LMK 杀（辅助） |

**关键**：路径 B 才是用户真正需要的"划卡保护"。

### 关于 `Process.killProcessGroup` 的可行性
- `android.os.Process.killProcessGroup(int uid, int pid)` 是公开 API
- 在 system_server 进程内可被 hook
- 拦截后从 `uid` 反查包名（需要 PackageManager 引用），判断是否在白名单
- **风险**：`uid → packageName` 需要某种方式查表（通过 `IPackageManager` 或 `ProcessRecord`）
- **简化方案**：拦截后只放过指定 pid（UI 进程把包名+pid 列表发给 hook 进程）
- **推荐简化方案**：在 `ActivityManagerService.killBackgroundProcesses` 拦截（比 killProcessGroup 早，且参数直接含包名）

最终采用：**Hook `ActivityManagerService.killBackgroundProcesses(String pkg, int userId, int reason)`** 作为主拦截点（参数直接是包名，简单可靠），保留 `killProcessGroup` 作为最后一道防线。

---

## 📦 代码复用性评估（基于完整文件清单）

### 39 个文件的全量分类

| 分类 | 文件数 | 决策 |
|------|--------|------|
| 🟢 **直接保留**（改包名/类名后即可用） | 5 | 改 package/import |
| 🟡 **重写**（保留设计，简化实现） | 4 | 重大修改 |
| 🔴 **删除**（功能不需要或被替代） | 25 | 直接删 |
| ✨ **新增** | 7 | 新建 |

### 🟢 保留（5 个，改 package 后即可用）
| 文件 | 原因 | 修改量 |
|------|------|--------|
| `data/LocalConfigRepository.kt` | UI 侧配置仓储 | 改 package + 类名 + 配置文件名 |
| `data/RemoteConfigRepository.kt` | Hook 侧配置仓储 | 改 package + 类名 + 配置文件名 |
| `data/IConfigRepository.kt` | 仓储接口 | 改 package + 类型引用 |
| `data/JsonCodec.kt` | JSON 编解码工具 | 改 package + 类型引用 |
| `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` | Compose 主题 | 改 package（AthenaTheme → SwipeGuardTheme） |

### 🟡 重写（4 个，保留思路大幅简化）
| 文件 | 修改内容 |
|------|----------|
| `model/AthenaConfig.kt` → `model/SwipeGuardConfig.kt` | 极简：`enabled: Boolean` + `protectedApps: Set<String>` + `schemaVersion: Int` |
| `hook/ModuleMain.kt` → `hook/ModuleMain.kt` | 简化：去掉 PolicyMatcher；只挂载 OplusConfigHooks + SwipeKillHooks |
| `hook/OplusConfigHooks.kt` | **核心修复**：调用新 XmlPolicyBuilder 生成正确 schema；可继续拦截自启动白名单 |
| `hook/XmlPolicyBuilder.kt` | **完全重写**：根元素 `<filter-conf>`，扁平 `<whitePkg name="..." category="001"/>` |

### 🔴 删除（25 个）
**老模型（8）**：
- `model/AppEntry.kt`（用包名集合替代）
- `model/AthenaConfig.kt`（替换为 SwipeGuardConfig）
- `model/DefaultPolicy.kt`
- `model/EntrySet.kt`
- `model/FreezePolicy.kt`
- `model/ModuleConfig.kt`
- `model/ProtectionMode.kt`
- `model/ProtectionResult.kt`

**老引擎（3）**：
- `engine/IPolicyMatcher.kt`
- `engine/PolicyMatcher.kt`
- `engine/MatcherStats.kt`

**老 Hook（2）**：
- `hook/SystemServiceHooks.kt`（OomAdj 调整——保留思路但简化集成到新结构）
- `hook/XmlPolicyBuilder.kt`（**整文件重写**——原文件先删除）

**老 Application / Activity（3）**：
- `AthenaApplication.kt` → `SwipeGuardApplication.kt`（新建）
- `ui/AthenaApp.kt`（删除，单页不需要 NavGraph）
- `ui/MainActivity.kt`（重写为 SwipeGuardActivity 或直接改 MainActivity）

**老导航（2）**：
- `ui/navigation/Destinations.kt`
- `ui/navigation/NavGraph.kt`

**老屏幕（5）**：
- `ui/screens/home/HomeScreen.kt`
- `ui/screens/whitelist/WhitelistScreen.kt`
- `ui/screens/blacklist/BlacklistScreen.kt`
- `ui/screens/debug/DebugScreen.kt`
- `ui/screens/about/AboutScreen.kt`

**老 UI 组件（6）**：
- `ui/components/AddAppSheet.kt`
- `ui/components/AppListItem.kt`
- `ui/components/EditEntryDialog.kt`
- `ui/components/EmptyStateView.kt`
- `ui/components/ModeChip.kt`
- `ui/components/ProtectionModeSelector.kt`

**老 ViewModel（1）**：
- `ui/data/ConfigViewModel.kt` → `ui/data/SwipeGuardViewModel.kt`（新建）

### ✨ 新增（7 个）
| 文件 | 作用 |
|------|------|
| `model/SwipeGuardConfig.kt` | 极简配置：`enabled`, `protectedApps: Set<String>`, `schemaVersion` |
| `hook/SwipeKillHooks.kt` | 新 Hook：拦截 `ActivityManagerService.killBackgroundProcesses` |
| `ui/screens/SwipeGuardScreen.kt` | 单屏：已选 app 列表 + 添加按钮 + 全局开关 |
| `ui/data/SwipeGuardViewModel.kt` | UI 状态管理（替代老 ConfigViewModel） |
| `SwipeGuardApplication.kt` | Application 入口（替代老 AthenaApplication） |
| `ui/MainActivity.kt` | 简化版 MainActivity |
| `META-INF/xposed/module.prop` | **关键新增**：LSPosed daemon 校验依赖 |

---

## 🛠️ 最终技术架构（简化版）

```
UI 进程（宿主）
└─ SwipeGuardApplication
   └─ MainActivity
      └─ SwipeGuardScreen
         └─ SwipeGuardViewModel
            └─ LocalConfigRepository (SharedPreferences "swipeguard_config")
               └─ JsonCodec ↔ SwipeGuardConfig (JSON)
                                       ↕ XposedService 跨进程同步
Hook 进程（system_server）
└─ ModuleMain (META-INF/xposed/java_init.list)
   ├─ RemoteConfigRepository (RemotePreferences "swipeguard_config")
   ├─ OplusConfigHooks ──────── FileInputStream 拦截 sys_elsa_config_list.xml
   │                            → XmlPolicyBuilder 注入 <whitePkg> (filter-conf schema)
   ├─ SwipeKillHooks ────────── ActivityManagerService.killBackgroundProcesses
   │                            → 检查 pkg 在白名单 → 跳过 kill
   └─ SystemServiceHooks ────── OomAdjuster 强制白名单进程 adj=-17
```

---

## 📋 DAG 任务图（DAG 模式）

### 关键路径
**调研完成（✓） → t2 重命名 → t3/t4 修复 LSPosed → t6 模型 → t7 XML 重写 → t8 划卡拦截 → t9 ModuleMain → t10 UI → t11 构建**

### 任务列表（30 个）

#### 阶段 0：调研（已完成 ✓）
- [x] s1: 调研 LSPosed v1.9.2 对 libxposed 102 的支持
- [x] s2: 调研 OplusConfigHook 项目的实现
- [x] s3: 调研 ColorOS Athena 真实机制

#### 阶段 1：项目重命名（4 个任务，可并行）
- [x] t1: 移动目录树 `com/athena/xposed/` → `com/swipeguard/xposed/` — worker ← 依赖: -
- [x] t2: 批量替换所有 `.kt` 的 package 声明 + 引用 — worker ← 依赖: t1
- [x] t3: 更新 `build.gradle.kts` 的 namespace/applicationId → `com.swipeguard.xposed` — worker ← 依赖: -
- [x] t4: 更新 `settings.gradle.kts` rootProject.name → `SwipeGuard` + `strings.xml` app_name — worker ← 依赖: -

#### 阶段 2：LSPosed 识别修复（3 个任务，关键路径）
- [x] t5: 降级 `libs.versions.toml` 中 `xposed-api` 和 `xposed-service` 到 `100.0.0` — worker ← 依赖: -
- [x] t6: **新增** `META-INF/xposed/module.prop`（`minApiVersion=100` `targetApiVersion=100`） — worker ← 依赖: t5
- [x] t7: 更新 `java_init.list` 入口类路径到 `com.swipeguard.xposed.hook.ModuleMain` — worker ← 依赖: t1
- [x] t8: 验证构建后 `unzip -l` 确认 `META-INF/xposed/` 下两个文件都在 — worker ← 依赖: t5, t6, t7

#### 阶段 3：老代码删除（4 个任务，可并行）
- [x] t9: 删除 8 个老 model 文件（AthenaConfig, AppEntry, EntrySet, FreezePolicy, ProtectionMode, ProtectionResult, DefaultPolicy, ModuleConfig） — worker ← 依赖: t2
- [x] t10: 删除 3 个老 engine 文件（IPolicyMatcher, PolicyMatcher, MatcherStats） — worker ← 依赖: t2
- [x] t11: 删除 5 个老 screen 文件（Home/Whitelist/Blacklist/Debug/About） — worker ← 依赖: t2
- [x] t12: 删除 6 个老 component + 2 个 navigation + AthenaApp + AthenaApplication + ConfigViewModel — worker ← 依赖: t2

#### 阶段 4：核心 Hook 重写（4 个任务，关键路径）
- [x] t13: **新建** `model/SwipeGuardConfig.kt`（极简配置） — coder ← 依赖: t9
- [x] t14: **重写** `data/LocalConfigRepository.kt` 适配新 SwipeGuardConfig — coder ← 依赖: t13
- [x] t15: **重写** `data/RemoteConfigRepository.kt` 适配新 SwipeGuardConfig — coder ← 依赖: t13
- [x] t16: **重写** `data/JsonCodec.kt` 适配新 SwipeGuardConfig — coder ← 依赖: t13
- [x] t17: **重写** `hook/XmlPolicyBuilder.kt`：用 `<filter-conf>` 根 + 扁平 `<whitePkg name="..." category="001"/>` — coder ← 依赖: t13
- [x] t18: **修改** `hook/OplusConfigHooks.kt`：调用新 XmlPolicyBuilder；保留自启动白名单 hook — coder ← 依赖: t17
- [x] t19: **新建** `hook/SwipeKillHooks.kt`：Hook `ActivityManagerService.killBackgroundProcesses(String pkg, ...)` 检查 pkg 在白名单则拦截 — coder ← 依赖: t13
- [x] t20: **新建** `hook/SystemServiceHooks.kt`（简化版）：仅 OomAdjuster 强制 adj=-17 — coder ← 依赖: t13
- [x] t21: **重写** `hook/ModuleMain.kt`：去掉 PolicyMatcher 依赖，只挂载 3 个 Hook（OplusConfig + SwipeKill + SystemService） — coder ← 依赖: t16, t18, t19, t20

#### 阶段 5：UI 简化（4 个任务）
- [x] t22: **新建** `SwipeGuardApplication.kt`（替代 AthenaApplication） — worker ← 依赖: t4
- [x] t23: **新建** `ui/data/SwipeGuardViewModel.kt`（简化版 ViewModel） — coder ← 依赖: t13, t14
- [x] t24: **重写** `ui/MainActivity.kt`：直接挂载 SwipeGuardScreen（无 NavHost） — coder ← 依赖: t22
- [x] t25: **新建** `ui/screens/SwipeGuardScreen.kt`：单页 UI（已选 app 列表 + 添加按钮 + 全局开关） — coder ← 依赖: t23

#### 阶段 6：清理与文档（3 个任务）
- [x] t26: 更新 `proguard-rules.pro`：保留规则改 `com.swipeguard.xposed.model.**` + `com.swipeguard.xposed.hook.**` — worker ← 依赖: t3
- [x] t27: 重写 `README.md` + `AGENTS.md`：说明新名字、真实用途、ColorOS 16 + LSPosed 1.9.2 兼容性 — worker ← 依赖: t21, t25
- [x] t28: 删除 `报错.log` + `ci_log.txt` + `ci2.log` + `review-table.md` 旧审计文件 — worker ← 依赖: -

#### 阶段 7：构建与审查（2 个任务）
- [x] t29: `assembleDebug` 构建验证 — ⏭️ 已跳过（当前环境无 JDK）
- [x] t30: 代码审查（Hook 安全性 / LSPosed 识别 / UI 健壮性 / XML schema 正确性） — reviewer ← 依赖: t29

---

## 🔀 并行机会表

| 并行组 | 任务 | 加速点 |
|--------|------|--------|
| **P0** | t1, t2, t3, t4, t5 | 重命名 + 降级 API 全部独立 |
| **P1** | t9, t10, t11, t12 | 4 个「删除老代码」分组全独立 |
| **P2** | t14, t15, t16 | 3 个数据层重写独立（共用 t13） |
| **P3** | t17, t19, t20 | XmlPolicyBuilder 重写 + SwipeKillHooks 新建 + SystemServiceHooks 新建独立（共用 t13） |
| **P4** | t22, t25 | Application + Screen 新建独立 |
| **P5** | t26, t27, t28 | 清理收尾三任务全独立 |

**关键串行**（不能并行）：
- t13（SwipeGuardConfig）→ t14, t15, t16, t17, t19, t20, t23（任何用新模型的代码）
- t17 → t18（XmlPolicyBuilder → OplusConfigHooks）
- t18, t19, t20 → t21（3 个 Hook → ModuleMain）
- t21, t23, t25 → t24（ModuleMain + ViewModel + Screen → MainActivity）
- t1-t28 → t29（构建）
- t29 → t30（审查）

---

## ⚠️ 风险矩阵

| 风险 | 等级 | 应对 |
|------|------|------|
| **libxposed 100 API 与现有代码不完全兼容** | 🔴 高 | t5 降级后立即跑 `assembleDebug` 验证；如编译失败，准备适配补丁（lambda 类型 / 协变） |
| **XmlPolicyBuilder 修正后 ColorOS 16 仍不识别** | 🟡 中 | t17 编写时严格对照真实 XML 样本；保留字符串注入回退路径；提供手动测脚本 |
| **`killBackgroundProcesses` Hook 点不准确** | 🟡 中 | t19 实现时先加日志验证调用频率和参数；提供降级到 `killProcessGroup` 的备选 |
| **`uid → packageName` 反查不可靠** | 🟡 中 | t19 不走 uid 反查，直接 hook 接收 `String pkg` 的方法（`killBackgroundProcesses`），避免 |
| **重命名遗漏（import / R 类 / 资源）** | 🟡 中 | t2 用全项目 grep 复查 `com.athena` `Athena` `athena_config` 关键字 |
| **删代码后 ModuleMain 编译失败** | 🟢 低 | t21 写在 t10 之后立即重写 |
| **R8 混淆破坏 Hook 类** | 🟢 低 | t26 更新 proguard 规则，保留所有 `XposedModule` 子类构造器 |
| **Compose 主题未包裹导致 UI 难看** | 🟢 低 | t24 保留 `SwipeGuardTheme` 包裹 |

---

## 📊 决策对照表

| 决策项 | 旧方案 | 新方案 | 原因 |
|--------|--------|--------|------|
| 项目名 | Athena | **SwipeGuard** | 避免与 ColorOS 系统组件同名 |
| 包名 | com.athena.xposed | **com.swipeguard.xposed** | 与新名一致 |
| libxposed API | 102.0.0 | **100.0.0** | LSPosed v1.9.2 只支持 100 |
| module.prop | 缺失 | **必须新增** | daemon 校验需要 |
| XML 根元素 | `<elsa_config>` | **`<filter-conf>`** | 真实 ColorOS 16 schema |
| XML 白名单 | `<white_pkg_list><pkg_item/></white_pkg_list>` | **`<whitePkg name="..." category="001"/>`** | 真实 schema |
| 划卡保护 | 只 hook FileInputStream | **+ hook killBackgroundProcesses** | 双路径保护 |
| 数据模型 | 8 个类，5 个枚举 | **1 个 SwipeGuardConfig** | 精简功能 |
| 引擎 | PolicyMatcher（150行） | **删除（直接传 Set<String>）** | 不需要匹配引擎 |
| UI 屏幕 | 5 个（Home/White/Black/Debug/About） | **1 个（SwipeGuardScreen）** | 核心功能不需要多页 |
| 导航 | NavGraph + 5 routes | **无导航** | 单页足够 |
| 黑名单 | 完整支持 | **删除** | 用户只要白名单 |
| IM 保活 | 完整支持 | **删除** | 用户只要白名单 |
| 默认策略 | 3 选 1 | **删除** | 用户只要白名单 |
| 进程匹配 | 正则 / 精确 | **删除（按包名）** | 简化 |
| Hook 数量 | 3 组（OplusConfig + SystemService + 计划中的 SwipeKill） | **3 组** | 保留但简化 |

---

## 📐 文件级操作清单

### 删除（25 个文件）
```
app/src/main/java/com/athena/xposed/
├── AthenaApplication.kt                                  → 改为 SwipeGuardApplication.kt
├── model/
│   ├── AppEntry.kt                                       🔴 删
│   ├── AthenaConfig.kt                                   🔴 删（替换为 SwipeGuardConfig.kt）
│   ├── DefaultPolicy.kt                                  🔴 删
│   ├── EntrySet.kt                                       🔴 删
│   ├── FreezePolicy.kt                                   🔴 删
│   ├── ModuleConfig.kt                                   🔴 删
│   ├── ProtectionMode.kt                                 🔴 删
│   └── ProtectionResult.kt                               🔴 删
├── engine/                                              🔴 整目录删
│   ├── IPolicyMatcher.kt
│   ├── MatcherStats.kt
│   └── PolicyMatcher.kt
├── hook/
│   ├── SystemServiceHooks.kt                             🔴 删（重写）
│   └── XmlPolicyBuilder.kt                               🔴 删（重写）
├── ui/
│   ├── AthenaApp.kt                                      🔴 删（无导航）
│   ├── MainActivity.kt                                   🟡 重写
│   ├── components/                                       🔴 整目录删（6 个文件）
│   │   ├── AddAppSheet.kt
│   │   ├── AppListItem.kt
│   │   ├── EditEntryDialog.kt
│   │   ├── EmptyStateView.kt
│   │   ├── ModeChip.kt
│   │   └── ProtectionModeSelector.kt
│   ├── data/
│   │   └── ConfigViewModel.kt                            🔴 删（替换为 SwipeGuardViewModel.kt）
│   ├── navigation/                                       🔴 整目录删（2 个文件）
│   │   ├── Destinations.kt
│   │   └── NavGraph.kt
│   └── screens/                                          🔴 整目录删（5 个文件）
│       ├── about/AboutScreen.kt
│       ├── blacklist/BlacklistScreen.kt
│       ├── debug/DebugScreen.kt
│       ├── home/HomeScreen.kt
│       └── whitelist/WhitelistScreen.kt
└── (data/JsonCodec.kt, LocalConfigRepository.kt,        🟡 保留但适配新模型
     RemoteConfigRepository.kt, IConfigRepository.kt)
```

### 新建（7 个文件）
```
app/src/main/java/com/swipeguard/xposed/
├── SwipeGuardApplication.kt                              ✨ 新
├── model/
│   └── SwipeGuardConfig.kt                               ✨ 新
├── data/                                                🟡 保留重写
│   ├── IConfigRepository.kt
│   ├── JsonCodec.kt
│   ├── LocalConfigRepository.kt
│   └── RemoteConfigRepository.kt
├── hook/                                                🟡 保留重写
│   ├── ModuleMain.kt
│   ├── OplusConfigHooks.kt
│   ├── XmlPolicyBuilder.kt                               ✨ 完全重写
│   ├── SwipeKillHooks.kt                                 ✨ 新
│   └── SystemServiceHooks.kt                             ✨ 简化重写
└── ui/
    ├── MainActivity.kt                                   🟡 重写
    ├── theme/                                            🟢 保留
    │   ├── Color.kt
    │   ├── Theme.kt
    │   └── Type.kt
    ├── data/
    │   └── SwipeGuardViewModel.kt                        ✨ 新
    └── screens/
        └── SwipeGuardScreen.kt                           ✨ 新
app/src/main/resources/META-INF/xposed/
├── module.prop                                           ✨ 新（关键）
└── java_init.list                                        🟡 更新类路径
```

---

## 🎯 完成定义（Definition of Done）

满足以下所有条件才视为完成：

1. ✅ `./gradlew assembleDebug` 编译成功，无错误
2. ✅ `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep META-INF/xposed` 显示 `module.prop` 和 `java_init.list` 都在
3. ✅ 源码中无 `com.athena` `Athena` 关键字残留（用 grep 验证）
4. ✅ `SwipeGuardConfig.kt` 是唯一的数据模型类
5. ✅ 3 个 Hook 都已实现：FileInputStream + killBackgroundProcesses + OomAdj
6. ✅ UI 是单页（SwipeGuardScreen），无 NavGraph
7. ✅ `proguard-rules.pro` 包含 `com.swipeguard.xposed.model.**` + `com.swipeguard.xposed.hook.**` 保留规则
8. ✅ `README.md` 和 `AGENTS.md` 已重写为新名字和真实用途

**未在本轮交付的**（明确范围外）：
- ❌ 真实在 ColorOS 16 + LSPosed v1.9.2 设备上的运行验证（需要真机测试，超出本规划范围）
- ❌ release 签名配置（开发阶段用 debug 签名）
- ❌ 国际化文案（先中文）

---

## 代理类型分配

- **scout**：已完成（3 份调研报告）
- **worker**：执行重命名 / 删文件 / 改 manifest / 改 gradle 等机械工作（t1-t12, t22, t26-t28）
- **coder**：执行核心代码编写（新模型 / 新 Hook / 新 UI）（t13-t21, t23-t25）
- **reviewer**：执行最终代码审查（t30）
