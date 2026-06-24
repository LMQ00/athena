# SwipeGuard 失效模式分析

> **用户报告**: "加入白名单的软件还是被删了"
> **分析范围**: Hook 安装失败、配置同步失败、未 Hook 的杀进程路径、XML 缓存问题
> **代码版本**: HEAD (t0-t10 优化后)
> **生成时间**: 2026-06-24

---

## 目录

1. [Failure Mode A: Hook 因 Class.forName 失败而静默降级](#a)
2. [Failure Mode B: 配置变更未从 UI 进程同步到 Hook 进程](#b)
3. [Failure Mode C: 杀进程走未 Hook 的路径](#c)
4. [Failure Mode D: 系统启动时缓存 XML 配置，热添加的白名单不生效](#d)
5. [综合排名](#ranking)
6. [各场景根因分析](#root-cause)
7. [修复建议](#fixes)

---

<a name="a"></a>
## (A) Hook 因 Class.forName 失败而静默降级

### 所有 Class.forName() 搜索位置

#### A1. `SwipeKillHooks.kt` — 路径 3 (最关键的 Athena 杀进程拦截点)

**文件**: `app/src/main/java/com/swipeguard/xposed/hook/SwipeKillHooks.kt:141-146`

```kotlin
val classCandidates = listOf(
    "com.oplus.athena.r3.c",
    "com.oplus.athena.r3.d",
    "oplus.athena.r3.c"          // 注意少一个 "com."
)
val clz = classCandidates.firstNotNullOfOrNull { name ->
    try {
        Class.forName(name, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    }
}
```

**风险**: 所有 3 个候选名都是 ProGuard 混淆名。Athena v6.0.1 中 `r3.c` 可能存在，但：
- ColorOS 不同版本/OTA 更新后混淆映射会变 → 3 个候选全错
- 类名中有 typo: `"oplus.athena.r3.c"` 漏了 `com.` 前缀，也匹配不了

**失败后的行为**: WARN 日志 + 整条路径跳过 (line 148-152)，SwipeKillHooks 仍报告"安装成功"，因为子方法有独立 try-catch。

```kotlin
// line 148-152
if (clz == null) {
    module.log(Log.WARN, tag,
        "Athena OplusWrapper class not found, skip forceStopPackageAndSaveActivity")
    return
}
```

#### A2. `SwipeKillHooks.kt` — 路径 4 (OplusActivityManager.forceStopPackage)

**文件**: `SwipeKillHooks.kt:213-214`

```kotlin
val clazz = Class.forName("android.app.OplusActivityManager", false, classLoader)
```

**风险**: 这是 ColorOS 框架类，通常存在。但如果：
- 非 ColorOS 设备 → ClassNotFoundException
- ColorOS 改名或移到其他包 → 失败
- ClassLoader 不对 (module 在 system_server 中可能拿到的 classLoader 不包含 framework 类)

**失败后的行为**: WARN 日志 + 跳过。路径 4 丢失。

#### A3. `AthenaKillHooks.kt` — 全部 4 个方法的候选类

**文件**: `AthenaKillHooks.kt:42-70`

```kotlin
val candidates = listOf(
    "com.oplus.athena.systemservice.OplusAthenaSystemService",
    "com.android.server.am.OplusAthenaAmManager",
    "oplus.app.AthenaServiceInternal"
)
for (clsName in candidates) {
    try {
        val clz = Class.forName(clsName, false, classLoader)
        // ... hook all matching methods ...
    } catch (_: ClassNotFoundException) {
    } catch (_: Throwable) {
    }
}
```

**风险**: 所有 3 个候选类都可能不存在 (取决于 ColorOS 版本和混淆程度)。**关键问题是 catch 是空的** — 没有 WARN 日志告知"类 X 未找到"。如果 3 个候选都失败，athenaKill/clearProcess 的所有 Hook 全部静默消失。

**失败后的行为**: **完全静默**。ModuleMain 中 `tryInstall("AthenaKillHooks")` 只捕获 install() 顶层异常，但由于 4 个 `hookMethod()` 各自内部消化异常，install() 返回成功。ModuleMain 日志会报告"3 hooks installed successfully"——但实际 0 个 Athena 拦截点生效。

#### A4. `OplusConfigHooks.kt` — OplusSettings.readConfig

**文件**: `OplusConfigHooks.kt:186-196`

```kotlin
val candidates = listOf(
    "android.provider.OplusSettings",
    "com.oplus.settings.OplusSettings",
    "com.android.internal.util.OplusSettings",
)
for (name in candidates) {
    try { return Class.forName(name, false, classLoader) }
    catch (_: Throwable) { }
}
```

**风险**: 中等。非 ColorOS 或类名变更时失败。但只影响 autostart 白名单注入，不影响核心杀进程拦截。

**失败后的行为**: WARN 日志 + 跳过 readConfig hook。

#### A5. `SwipeKillHooks.kt` — 路径 1/2 (AMS 标准方法)

**文件**: `SwipeKillHooks.kt:54,87`

```kotlin
val amsClass = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
```

**风险**: **极低**。这是 AOSP 核心类，在所有 Android 设备上存在。

### A 模式总结

| 位置 | 类名 | 风险等级 | 失败后果 | 失败是否可见 |
|------|------|---------|---------|------------|
| SwipeKillHooks 路径3 | `r3.c` 等混淆名 | **高** | 丢失 Athena 真实杀进程拦截 | WARN 日志，不体现为计数失败 |
| SwipeKillHooks 路径4 | `android.app.OplusActivityManager` | **中** | 丢失 Oplus AMS 拦截 | WARN 日志 |
| AthenaKillHooks 全部 | 3 个候选框架类 | **高** | 丢失 Athena Binder API 拦截 | **完全静默** |
| OplusConfigHooks | OplusSettings 3 候选 | **低** | 丢失自启动白名单注入 | WARN 日志 |
| SwipeKillHooks 路径1/2 | `ActivityManagerService` | **极低** | 丢失 AOSP 标准杀进程拦截 | WARN 日志 |

---

<a name="b"></a>
## (B) 配置变更未从 UI 进程同步到 Hook 进程

### 同步链路

```
UI 进程 (MainActivity)                     system_server (Hook 进程)
────────────────────                      ────────────────────────
SwipeGuardViewModel.addPackage(pkg)
  → LocalConfigRepository.save(cfg)
    → prefs.edit().putString("swipeguard_config_json", json).apply()
      ↓
      [libxposed RemotePreferences Binder 同步]
      ↓
                                      ModuleMain 的 OnSharedPreferenceChangeListener
                                        → configRepo.load() → config = ...
                                        → syncHooks()
                                          → OplusConfigHooks.updateConfig(config)
                                          → swipeKillHooks.syncConfig(configRepo)
                                          → athenaKillHooks.syncConfig(configRepo)
```

### 薄弱环节

#### B1. RemotePreferences Binder 连接存活性

**文件**: `ModuleMain.kt:78-79`

```kotlin
val prefs = getRemotePreferences(PREFS_NAME)
configRepo = RemoteConfigRepository(prefs)
```

`getRemotePreferences()` 返回的 `SharedPreferences` 底层通过 Binder 与 UI 进程的 `XposedService` 通信。如果：
- UI 进程被系统杀死 (用户划掉 SwipeGuard 后台)
- Binder 线程池耗尽
- `XposedService` 崩溃重启

→ Binder 连接断裂 → 后续所有 `putString().apply()` 到达不了 system_server
→ Hook 进程永远看不到新配置

**无重连机制**: 代码没有 `XposedService` 死亡监听或定期重连逻辑。

#### B2. 初始加载时序问题

**文件**: `ModuleMain.kt:76-78`

`configRepo.load()` 在 `onSystemServerStarting` 中调用一次，此时 UI 进程可能尚未写入任何配置（首次安装、冷启动后）。此时加载的是 DEFAULT。后续 UI 写入后依赖 Binder 同步 + listener 触发。

如果 listener 因 B1 中的问题未触发 → 永久使用 DEFAULT 配置。

#### B3. 竞态条件

**文件**: `ModuleMain.kt:89-91`

```kotlin
prefs.registerOnSharedPreferenceChangeListener { _, key ->
    if (key == IConfigRepository.KEY_CONFIG_JSON || key == null) {
        config = configRepo.load()   // ← 读 JSON → 写 config
        syncHooks()                  // ← 分发到各 Hook
    }
}
```

- Listener 在 Binder 线程池中回调
- 两次快速写入可能产生两个并发回调，都调用 `syncHooks()`（非幂等 → 重复日志和可能的内部状态异常）
- `configRepo.load()` 在 `RemoteConfigRepository` 中是 `@Synchronized` 的，但两次 `load()` 后分配到 `syncHooks()` 的时间不同 → 后一次可能覆盖前一次的正确状态

#### B4. Listener key 过滤问题

**文件**: `ModuleMain.kt:90`

```kotlin
if (key == IConfigRepository.KEY_CONFIG_JSON || key == null)
```

当 `key == null` 时也会触发 — 这通常是 `prefs.clear()` 或 `prefs.edit().remove(...)` 的回调。如果框架内部同步时发了 null key，可能触发不必要的重载。

#### B5. 所有 Hook 的 syncConfig 路径

**SwipeKillHooks.syncConfig**: `SwipeKillHooks.kt:27-30` — 读取 repo → 设置 `effectiveSet` 和 `enabled`

**AthenaKillHooks.syncConfig**: `AthenaKillHooks.kt:22-25` — 相同模式

**OplusConfigHooks.updateConfig**: `OplusConfigHooks.kt:109-113` — 更新 `currentConfig` 和 `currentEffectiveSet`

三者都只是更新内存变量，不会重新安装 Hook。SwipeKillHooks/AthenaKillHooks 在后续的 shouldProtect 判断中实时读取 `effectiveSet`。这是正确的（volatile 保证可见性）。

但 **OplusConfigHooks** 的更新仅影响 `currentConfig` 变量，不影响已注入的 `FileInputStream` 劫持缓冲（`streamStates`）。也就是说：如果 XML 已经在之前被读取并注入到 `streamStates`，后续的 `updateConfig()` 不会重新读取 XML、也不会更新 `streamStates` 中的增强缓冲。

**文件**: `OplusConfigHooks.kt:109-113`

```kotlin
fun updateConfig(config: SwipeGuardConfig) {
    currentConfig = config
    currentEffectiveSet = (config.systemDefaults - config.userRemovals) + config.userAdditions
}
```

注意：这里**没有**重新劫持 FileInputStream、**没有**更新 `streamStates` 中的缓冲数据。这意味着：
- 如果 XML 已经通过一次 FileInputStream 读取被劫持并写入了 `streamStates`
- 后续用户添加包名后 `updateConfig` 被调用
- 但系统不会重新读取 XML（已缓存）
- 所以新的包名从未进入过 `streamStates`

**除非系统触发第二次 FileInputStream 读取**，否则热添加的包名永远不会出现在增强 XML 中。

#### B6. 无降级回读机制

没有任何定期同步或"eTag"校验。如果一次 Binder 同步失败（丢包、事务太大被截断），system_server 永久停留在旧配置。

### B 模式总结

| 薄弱点 | 风险等级 | 描述 |
|--------|---------|------|
| B1 Binder 连接断裂 | **高** | UI 进程被杀后配置无法同步到 Hook 进程，永久使用旧配置 |
| B2 初始加载时序 | **中** | onSystemServerStarting 在 UI 写入前加载了 DEFAULT |
| B3 竞态 | **低** | 快速写入可能触发并发 syncHooks |
| B5 streamStates 不更新 | **高** | updateConfig 不更新已劫持的 XML 缓冲，热添加的包名不进增强 XML |
| B6 无回读 | **中** | 没有定期校验或重试机制 |

---

<a name="c"></a>
## (C) 杀进程走未 Hook 的路径

### 逆向报告 14 个候选 Hook 点覆盖情况

对照 `.pi/context/reverse-system-athena.md` §6:

| 编号 | 目标 | 方法 | 优先级 | 已 Hook? | 位置 |
|------|------|------|--------|---------|------|
| **HK01** | `r3.c` | `forceStopPackageAndSaveActivity` | ★★★★★ | ✅ 路径 3 | SwipeKillHooks.kt:140-180 |
| **HK02** | `x3.d` | `killProcess` | ★★★★★ | ❌ **未 Hook** | — |
| **HK03** | `x3.d` | `killProcessGroup` | ★★★★ | ❌ **未 Hook** | — |
| **HK04** | `x3.z0` | `handleBackgroundProcess` | ★★★★ | ❌ **未 Hook** | — |
| **HK05** | `x3.r1` | `killBackgroundUserProcessesWithAudioRecord` | ★★★ | ❌ **未 Hook** | — |
| **HK06** | `oplusguardelf.c` | `BTisInWhitelistNotInNotRestrict` | ★★★★★ | ❌ **未 Hook** | — |
| **HK07** | `g2.e$d` | `M`/`N`/`P` (whitePkg 匹配) | ★★★★ | ❌ **未 Hook** | — |
| **HK08** | `alwaysalive.d` | (mThirdSplashWhitePkgs 方法) | ★★★ | ❌ **未 Hook** | — |
| **HK09** | `athena.p0` | (background_protect_list) | ★★★★ | ❌ **未 Hook** | — |
| **HK10** | `g2.e` | `a`/`b`/`c` (配置加载初始化) | ★★★★★ | ❌ **未 Hook** | — |
| **HK11** | `g2.e$b` | `I`/`K`/`L` (XML 解析) | ★★★★ | ❌ **未 Hook** | — |
| **HK12** | `AthenaDynamicReceiver` | `onReceive` (动态更新) | ★★★ | ❌ **未 Hook** | — |
| **HK13** | `x3.l` | `a`/`b` (AthenaKillNotifier) | ★★★★ | ❌ **未 Hook** | — |
| **HK14** | `e4.b` | `onAthenaKilled` | ★★★ | ❌ **未 Hook** | — |
| — | `AMS` | `killBackgroundProcesses` | — | ✅ 路径 1 | SwipeKillHooks.kt:50-73 |
| — | `AMS` | `forceStopPackage` | — | ✅ 路径 2 | SwipeKillHooks.kt:82-107 |
| — | `OplusActivityManager` | `forceStopPackage` | — | ✅ 路径 4 | SwipeKillHooks.kt:210-238 |
| — | `OplusAthenaSystemService` | `athenaKill`/`athenaKill2`/`athenaKill3`/`clearProcess` | — | ✅ (条件性) | AthenaKillHooks.kt:23-70 |
| — | `FileInputStream` | ctors/read/mark/reset | — | ✅ | OplusConfigHooks.kt:220-370 |
| — | `OplusSettings` | `readConfig` | — | ✅ | OplusConfigHooks.kt:150-200 |

### 最关键的缺失路径

#### C1. `x3.d.killProcess` — 最终杀进程执行点 (HK02) ★★★★★

**逆向报告 §3** — 所有杀进程路径的最终汇合点:

```
r3.c.forceStopPackageAndSaveActivity(pkg, userId)
  → i3.h (OplusActivityManager 薄封装)
  → android.app.OplusActivityManager.forceStopPackage()
  → x3.d.killProcess()                          ← 最终执行
  → Process.killProcess()
```

**问题**: 如果系统在调用 `x3.d.killProcess()` 之前未经过 `r3.c`/`OplusActivityManager`/`AMS` 等已 Hook 方法，而是直接调用 `killProcess()`，我们无法拦截。

**可能发生此路径的场景**:
- `AthenaKillerManagerService` (`h1`) 在检查完自己的 ELSA 白名单后直接调用 `x3.d.killProcess()` (不通过 `r3.c`)
- Native 层 `libathena` 直接调用 `Process.killProcess(pid)` 绕过 Java 层 Hook
- OOM 调节器 / LMK (Low Memory Killer) 直接发送 `SIGKILL` 信号，完全绕过 Java 层

#### C2. `oplusguardelf.c.BTisInWhitelistNotInNotRestrict` — 白名单检查 (HK06) ★★★★★

**逆向报告 §4.5**: GuardElf 守护模块有自己的白名单检查代码。

**问题**: 如果我们能 Hook 这个白名单检查方法（让所有 `isInWhitelist` 都返回 true），就无需同时维护 XML 注入 + 3 个 kill 拦截点。目前完全未 Hook。

**为什么重要**: 如果 ELSA 白名单检查有多个入口（不只 `g2/e$d` 的 whitePkg map），我们只注入了其中一条。另一条路（GuardElf 检查）可能直接拒绝我们的包。

#### C3. `g2.e$d` — whitePkg 匹配解析 (HK07) ★★★★

**逆向报告 §7.1**: `h1` 通过 `g2/e$d` 查询 `whitePkg` map 来决定是否杀进程。

**问题**: 如果我们能直接注入这个 in-memory 的 `whitePkg` Map，就不需要 FileInputStream XML 注入。目前完全未 Hook。

**为什么重要**: XML 注入可被系统缓存规避（Failure D），但 `g2.e$d` 是每次查询都调用的运行时方法 — 直接 Hook 这里能 100% 覆盖。

#### C4. `x3.l` AthenaKillNotifier — 回调通知 (HK13) ★★★★

**逆向报告 §6.4**: kill 事件发生后通知所有已注册的 `IAthenaKillCallback`。

**问题**: 虽然 Hook 这里不能阻止杀进程，但可以用来**诊断** — 记录被杀的应用、触发者、原因，帮助开发者确认是哪个路径绕过了保护。

---

<a name="d"></a>
## (D) 系统启动时缓存 XML 配置，热添加的白名单不生效

### 问题本质

OplusConfigHooks 的核心机制是拦截 `FileInputStream` 读取 `/data/oplus/os/bpm/sys_elsa_config_list.xml`，劫持其字节流，注入 `whitePkg` 条目后返回增强版 XML。

### 系统行为

根据逆向报告 §7.1，Athena 的 `h1` (AthenaKillerManagerService) 和 `g2/e` (OFreezer 配置解析器) 的行为：

1. **启动时**: system_server 中 `g2/e.a()` (HK10) 被调用 → 解析 `sys_elsa_config_list.xml` → 构建 in-memory `Map<String, Integer>` (pkg→category)

2. **运行时**: 每次 kill 决策前，`h1` 查询这个 in-memory map → 若包名不在 map 中 → 继续杀进程

3. **无定期重读**: 逆向中没有发现系统周期性重新读取 `sys_elsa_config_list.xml` 的证据。唯一的重新读取可能是通过 `AthenaDynamicReceiver` (HK12) 接收广播触发。

### 对我们系统的影响

#### D1. 启动时注入 ✅ 有效

模块在 `onSystemServerStarting` 时安装 FileInputStream Hook。当系统第一次读取 `sys_elsa_config_list.xml` 时，钩子触发，注入增强 XML。此时系统解析的是修改后的 XML → in-memory map 包含所有 DEFAULT 白名单 + 插件自己添加的。

#### D2. 运行时添加 ❌ 无效

当用户通过 UI 添加包名后:
1. `updateConfig()` 被调用 — 更新 `currentConfig` 和 `currentEffectiveSet` ✅
2. `syncHooks()` → `SwipeKillHooks.syncConfig()` / `AthenaKillHooks.syncConfig()` — 更新它们的 `effectiveSet` ✅
3. 但是 `OplusConfigHooks` 的 `streamStates` (已劫持的 XML 缓冲) **没有被更新** ❌

**关键代码证据** — `OplusConfigHooks.kt:109-113`:
```kotlin
fun updateConfig(config: SwipeGuardConfig) {
    currentConfig = config
    currentEffectiveSet = (config.systemDefaults - config.userRemovals) + config.userAdditions
    // ← 没有更新 streamStates 中的任何内容
    // ← 没有重新读取或重新注入 XML
}
```

对比初始化时的 `hijackStream()` (`OplusConfigHooks.kt:290-310`):
```kotlin
private fun hijackStream(module: XposedModule, fis: FileInputStream) {
    val originalBytes = fis.readBytes()
    val originalXml = String(originalBytes, Charsets.UTF_8)
    val extractedDefaults = XmlPolicyBuilder.extractWhitePkgNames(originalXml)
    // ... 构建 enhancedXml ...
    synchronized(streamStates) {
        streamStates[fis] = StreamState(data = enhancedBytes)  // ← 写入缓冲
    }
}
```

`hijackStream()` 只在 `FileInputStream` 构造 Hook 的拦截器中调用 (`installElsaConfigFileInputStream` 内部的 `intercept`)。如果系统不重新构造 FileInputStream 来读那个文件（因为已缓存），`hijackStream()` 永不再执行。

#### D3. 综合后果

| 场景 | 启动时添加的包 | 运行时添加的包 |
|------|---------------|---------------|
| ELSA in-memory 白名单 | ✅ 被注入 | ❌ 不在 map 中 |
| SwipeKillHooks 拦截 | ✅ 拦截 | ✅ 拦截 (syncConfig 已更新) |
| AthenaKillHooks 拦截 | ✅ 拦截 | ✅ 拦截 (syncConfig 已更新) |
| **总保护效果** | ✅ 双重保护 (白名单 + kill 拦截) | ⚠️ 仅 kill 拦截，无白名单层保护 |

**用户报告的场景**: 启动后打开 UI，添加包 → **包只在 kill 拦截层受到保护**。如果杀进程从 ELSA 的 `h1` 直接调用 `x3.d.killProcess()`（不走已 Hook 的 r3.c/OplusActivityManager），则 kill 拦截也无效 → 应用被删。

---

<a name="ranking"></a>
## 综合排名

按导致「加入白名单的软件还是被删了」的可能性从高到低排序：

### 🥇 第 1 名: x3.d.killProcess 未 Hook (C1) + 混淆类名匹配失败 (A1/A3)
**置信度**: 85% | **影响面**: 所有用户，但 ColorOS 版本不同表现各异

**根因**: 
- `x3.d.killProcess` (HK02) 是最底层的 kill 执行点，所有杀进程路径都经过它，但完全未被 Hook
- `r3.c` (HK01) 和 AthenaKillHooks 的候选类名是 ProGuard 混淆名，随 APK 版本变化。如果用户在的 ColorOS/Athena 版本中这些类名不同，关键拦截点静默失效
- AthenaKillHooks 失败时**无日志**，用户/开发者完全不知

**快捷判断**: 查看日志中是否有 `"Athena OplusWrapper class not found"` 或 `"Hooked OplusAthenaSystemService.*"`。如果没有后者、有前者 → 此模式。

### 🥈 第 2 名: 运行时添加的包不在 in-memory ELSA 白名单中 (D2)
**置信度**: 80% | **影响面**: 所有在启动后添加白名单的用户

**根因**:
- `updateConfig()` 不更新 `streamStates` 中的劫持缓冲
- 系统启动后不再重新读取 `sys_elsa_config_list.xml`
- 新添加的包名只存在于 SwipeKillHooks/AthenaKillHooks 的 `effectiveSet` 中
- 如果 kill 从 `h1` → in-memory whitelist 检查 → 不通过 → 直接 `x3.d.killProcess()`，则 kill 拦截也无效（第 1 名的问题）

**快捷判断**: 重启设备后应该能保护（重启时 XML 注入含新包名）。如果重启后保护生效 → 此模式。如果重启后仍然不保护 → 第 1 名模式。

### 🥉 第 3 名: RemotePreferences Binder 同步断开 (B1/B6)
**置信度**: 60% | **影响面**: 经常划掉 SwipeGuard 后台的用户

**根因**:
- UI 进程被杀 → Binder 连接断 → Hook 进程看不到 UI 新写入的配置
- 无重连/重试机制

**快捷判断**: 打开 SwipeGuard UI 确认配置正确，然后检查 Hook 进程日志中 `effectiveSet` 大小是否和 UI 显示一致。如果不一致 → 此模式。

### 4. `oplusguardelf.c` 白名单绕过 (C2)
**置信度**: 50% | **影响面**: 使用了 GuardElf 独立白名单检查的 ColorOS 版本

**根因**:
- GuardElf 有自己的白名单检查（`BTisInWhitelistNotInNotRestrict`）
- ELSA 白名单注入不影响 GuardElf 决策
- 如果 GuardElf 决定杀进程，可能通过 `x3.d.killProcess()` 直接执行

### 5. `streamStates` 未在热更新时刷新 (B5)
**置信度**: 40% | **影响面**: 仅在系统重新读取 XML 时才触发（罕见）

**根因**:
- 如果系统因为某些原因（OTA 后重启、手动广播）重新读取了 `sys_elsa_config_list.xml`，`hijackStream()` 会用**旧配置**构建增强 XML
- `updateConfig()` 不更新 `streamStates`

### 6. OplusActivityManager.forceStopPackage 签名不匹配 (A2)
**置信度**: 30% | **影响面**: ColorOS 版本差异

**根因**:
- 当前使用模糊匹配 `m.name == "forceStopPackage" && m.parameterTypes[0] == String::class.java`
- 如果系统有多个重载但第一个参数不是 String（比如是 int userId），会被漏掉
- 如果方法名变了（OPlus 在 AOSP 上加的方法可能随 Android 版本改名）

### 7. 竞态条件导致 syncHooks 不一致 (B3)
**置信度**: 20% | **影响面**: 快速多次添加/移除包名时

### 8. ProGuard 导致 Hook 代码自身被裁剪
**置信度**: 10% | **影响面**: Release 构建

**原因**: `proguard-rules.pro` 保留了 `XposedModule` 子类，但 `SwipeKillHooks`/`AthenaKillHooks`/`OplusConfigHooks` 没有被显式保留。虽然它们被 `ModuleMain` 直接引用（Gradle 的 keep 规则会按引用链保留），但 R8 的全树收缩模式下可能出错。

---

<a name="root-cause"></a>
## 各场景根因分析

### 场景 1: 首次安装 → 打开 UI → 添加包 → 划卡 → 应用被杀

```
1. 安装完毕，重启
2. onSystemServerStarting → ModuleMain 安装 Hook
   ├── FileInputStream 劫持已安装 ✅
   ├── 系统读取 XML → hijackStream() 注入 DEFAULT 白名单 ✅
   ├── SwipeKillHooks 安装 (路径1/2/3/4) ← 路径3可能因混淆类名未匹配 ⚠️
   └── AthenaKillHooks 安装 ← 全静默失败可能 ⚠️
3. 用户打开 UI → SwipeGuardViewModel.init() → 加载 DEFAULT
4. 用户添加包 "com.example.app"
   → save() → SharedPreferences.apply() → Binder 同步
   → ModuleMain listener 触发 → syncHooks()
     → OplusConfigHooks.updateConfig() ← 不更新 streamStates ❌
     → SwipeKillHooks.syncConfig() → effectiveSet 包含新包 ✅
     → AthenaKillHooks.syncConfig() → effectiveSet 包含新包 ✅
5. 用户划卡 → SystemUI → IAthenaService.clearProcess()
   → h1 (AthenaKillerManagerService) 检查白名单:
     → 查询 in-memory whitePkg map → com.example.app 不在其中 ❌
     → 调用 r3.c.forceStopPackageAndSaveActivity("com.example.app")
       → 如果 r3.c 类找到了 → Hook 拦截 ✅ → 应用保活
       → 如果 r3.c 类未找到 → 继续执行 ⚠️
         → OplusActivityManager.forceStopPackage()
           → Hook 拦截 ✅ → 应用保活
           → 或直接 x3.d.killProcess() → 未 Hook ❌ → 应用被杀
```

**最可能的断点**: 第 2 步路径3失败 + 第 5 步走 `x3.d.killProcess()` 直接杀

### 场景 2: 重启后 → 划卡 → 应用仍被杀

```
1. 重启
2. onSystemServerStarting → 系统读取 XML → hijackStream() 注入
   ├── DEFAULT 白名单 ✅
   ├── 用户之前添加的包? → 如果用户上次关闭 UI 前保存了，config 已持久化
   │   → getRemotePreferences() 读的是 UI 进程的数据
   │   → 但 system_server 中的 RemotePreferences 在重启后重新连接 Binder
   │   → onSystemServerStarting 中 configRepo.load() 可能读到最新值 ✅
   └── 如果读了，updateConfig 在 install() 中被调用 ✅
       → 但 hijackStream() 使用的是 install() 传入的 config 参数，
         不是 configRepo.load() 的最新值 ⚠️
```

**关键问题**: `OplusConfigHooks.install()` 接收的是 `config` 参数 (ModuleMain 正在初始化的 `this.config`)。这个值在 `install()` 调用前通过 `configRepo.load()` 设置。但如果加载发生在 Binder 连接就绪前 → 读到 DEFAULT。而 `hijackStream()` 使用的是 install 时传入的 `config` → 用 DEFAULT 注入 → 用户之前的添加丢失。

**证据**: `ModuleMain.kt:66-68`
```kotlin
config = configRepo.load()
// OplusConfigHooks.install(this, config, classLoader, mutableListOf())
```

如果 `configRepo.load()` 在首次 Binder 同步前执行 → 读到 DEFAULT → 传给 OplusConfigHooks.install → hijackStream 使用 DEFAULT → 用户添加的包不在增强 XML 中。

---

<a name="fixes"></a>
## 修复建议（按优先级排序）

### Fix 1: Hook x3.d.killProcess (修复 C1) — 🔴 最高优先级

**文件**: `SwipeKillHooks.kt`

在 `install()` 中增加路径 5:

```kotlin
private fun hookKillProcess() {
    try {
        // x3.d 是混淆名，需要从逆向报告获知当前版本的正确类名
        // 候选: "com.oplus.athena.x3.d", "oplus.athena.x3.d"
        val candidates = listOf("com.oplus.athena.x3.d", "oplus.athena.x3.d")
        val clz = candidates.firstNotNullOfOrNull { name ->
            try { Class.forName(name, false, classLoader) } 
            catch (_: ClassNotFoundException) { null }
        } ?: run {
            module.log(Log.WARN, tag, "x3.d class not found, skip killProcess hook")
            return
        }
        val methods = clz.declaredMethods.filter { m ->
            m.name == "killProcess" && m.parameterCount >= 1
        }
        for (method in methods) {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg = findPkgFromKillProcess(chain)
                    if (shouldProtect(pkg)) {
                        module.log(Log.INFO, tag, "Blocked killProcess for $pkg")
                        return@intercept null
                    }
                    chain.proceed()
                }
        }
    } catch (t: Throwable) {
        module.log(Log.ERROR, tag, "killProcess hook failed", t)
    }
}
```

同时需要实现 `findPkgFromKillProcess` — 从 `killProcess` 的参数中提取包名（可能通过 `clearInfo` 字符串或 Bundle 参数）。

### Fix 2: 修复 streamStates 热更新 (修复 D2 + B5) — 🔴 高优先级

**文件**: `OplusConfigHooks.kt`

在 `updateConfig()` 中增加 streamStates 刷新:

```kotlin
fun updateConfig(config: SwipeGuardConfig) {
    currentConfig = config
    currentEffectiveSet = (config.systemDefaults - config.userRemovals) + config.userAdditions
    
    // ★ 新增: 刷新已劫持的 XML 缓冲
    synchronized(streamStates) {
        val iterator = streamStates.entries.iterator()
        while (iterator.hasNext()) {
            val (fis, state) = iterator.next()
            try {
                // 重新构建增强 XML (使用最新配置)
                val originalXml = String(state.data, Charsets.UTF_8)
                val enhancedXml = XmlPolicyBuilder.buildEnhancedXml(originalXml, config)
                val enhancedBytes = enhancedXml.toByteArray(Charsets.UTF_8)
                // 重置游标，允许重新读取
                state.data = enhancedBytes
                state.cursor = 0
                state.mark = -1
            } catch (t: Throwable) {
                iterator.remove()  // 刷新失败则移除劫持，回退到原始内容
            }
        }
    }
}
```

**注意**: `StreamState.data` 目前是 `val`（`private val data: ByteArray`），需要改为 `var`。

### Fix 3: AthenaKillHooks 增加失败日志 (修复 A3 静默失败) — 🟡 中优先级

**文件**: `AthenaKillHooks.kt`

```kotlin
for (clsName in candidates) {
    try {
        val clz = Class.forName(clsName, false, classLoader)
        // ... hook ...
        return
    } catch (_: ClassNotFoundException) {
        module.log(Log.WARN, tag, "Class $clsName not found, trying next")
    } catch (t: Throwable) {
        module.log(Log.WARN, tag, "Failed to hook $clsName: ${t.message}")
    }
}
// 所有候选都失败
module.log(Log.WARN, tag, "ALL candidates failed for $methodName — no Athena kill interception")
```

### Fix 4: 增加 `r3.c` 的更多候选名 (修复 A1) — 🟡 中优先级

**文件**: `SwipeKillHooks.kt:136-140`

从逆向报告可知 `r3.c` 是 Athena v6.0.1 的混淆名。建议增加更通用的搜索方案：

```kotlin
val classCandidates = listOf(
    "com.oplus.athena.r3.c",
    "com.oplus.athena.r3.d", 
    "oplus.athena.r3.c",
    "com.oplus.athena.r3.e",   // 增加备选
    "com.oplus.athena.r4.c",   // 增加备选
    // ★ 新增: 通过字符串搜索的兜底方案
)
// ★ 新增: 如果候选全部失败，尝试通过 dex 中 forceStopPackageAndSaveActivity 字符串搜索
if (clz == null) {
    clz = findClassByMethodString(classLoader, "forceStopPackageAndSaveActivity")
}
```

### Fix 5: `OplusActivityManager` 方法签名精确匹配 (修复 A2) — 🟢 低优先级

**文件**: `SwipeKillHooks.kt:214-218`

当前匹配：
```kotlin
val methods = clazz.declaredMethods.filter { m ->
    m.name == "forceStopPackage" &&
    m.parameterCount >= 1 &&
    m.parameterTypes[0] == String::class.java
}
```

改为同时匹配 `(String, int)` 和 `(String, int, Bundle)` 签名，并增加 fallback 查找：

```kotlin
val methods = clazz.declaredMethods.filter { m ->
    m.name == "forceStopPackage" && m.parameterCount >= 2 &&
    m.parameterTypes[0] == String::class.java
}
if (methods.isEmpty()) {
    // fallback: 任何第一个参数是 String 的 forceStopPackage
    methods = clazz.declaredMethods.filter { m ->
        m.name == "forceStopPackage" && m.parameterCount >= 1 &&
        m.parameterTypes[0] == String::class.java
    }
}
```

### Fix 6: 配置同步版本号与重试机制 (修复 B1/B6) — 🟢 低优先级

**文件**: `ModuleMain.kt`

增加定期一致性校验的轻量化方案（权衡性能）：

```kotlin
// 在 syncHooks() 中增加配置版本号追踪
private var lastConfigHash: Int = 0

private fun syncHooks() {
    val newCfg = configRepo.load()
    val newHash = newCfg.hashCode()
    if (newHash == lastConfigHash) return  // 无变化，跳过
    lastConfigHash = newHash
    
    config = newCfg
    OplusConfigHooks.updateConfig(newCfg)
    if (::swipeKillHooks.isInitialized) swipeKillHooks.syncConfig(configRepo)
    if (::athenaKillHooks.isInitialized) athenaKillHooks.syncConfig(configRepo)
}
```

### Fix 7: 为 release 构建保留 Hook 类 (修复 A8) — 🟢 低优先级

**文件**: `proguard-rules.pro`

```pro
# 保留所有 Hook 类（防止 R8 全树收缩误删）
-keep class com.swipeguard.xposed.hook.** { *; }
-keep class com.swipeguard.xposed.data.** { *; }
```

### Fix 8: Hook GuardElf 白名单检查 (修复 C2) — 🔵 远期

如果 1-7 后仍有问题，考虑 Hook `oplusguardelf.c` 的 `BTisInWhitelistNotInNotRestrict` 方法。这需要逆向当前设备的 GuardElf 类名（逆向报告已提供线索）。

---

## 总结

```
排名  故障模式                    置信度  修复难度  可诊断性
─────────────────────────────────────────────────────────
#1    x3.d.killProcess 未 Hook    85%    中        可 (IDE logcat)
#2    streamStates 不热更新       80%    低        可 (重启测)
#3    Binder 连接断裂            60%    高        中 (log 对比 UI)
#4    GuardElf 白名单绕过        50%    高        难
#5    OplusActivityManager 签名  30%    低        中
#6    竞态条件                   20%    极低      难
```

**最简快速修复组合**:
1. Fix 2 (streamStates 热更新) — 改动 ~15 行，解决最可能的问题
2. Fix 3 (AthenaKillHooks 加日志) — 改动 ~5 行，使故障可诊断
3. Fix 1 (Hook x3.d.killProcess) — 改动 ~40 行，补最关键的缺口

**验证方法**: 修改后部署到设备，在 LSPosed 日志中观察：
- "Hooked ... forceStopPackageAndSaveActivity" 是否出现
- "Blocked killProcess for com.example.app" 是否出现
- 重启后首次划卡是否保护成功 vs 热添加后的划卡是否保护成功
