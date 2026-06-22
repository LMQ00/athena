# 逆向系统 Athena APK — 完整报告

> **生成时间**: 2026-06-23
> **APK**: `com.oplus.athena` v6.0.1 (versionCode 601)
> **WorkspaceId**: `25jitg9b`
> **反混淆度**: 深度 ProGuard 混淆（5229 classes，单 dex 4.8MB）

---

## 1. 架构总览

Athena 是 ColorOS 16 的核心后台进程管理系统，由 **三层架构** 组成：

```
┌──────────────────────────────────────────────────────────────┐
│  应用层 (client/)                                            │
│  AthenaService    ← 主 Service 入口，73 方法                    │
│  AthenaDynamicReceiver  ← 动态配置广播接收器                    │
│  子模块: appfrozen/, oplusguardelf/, db/, 各类 action/        │
├──────────────────────────────────────────────────────────────┤
│  系统服务层 (systemservice/)                                   │
│  h1  ← AthenaKillerManagerService（核心 kill 调度）            │
│  transact/RemoteService  ← 远程事务代理                       │
│  action/alwaysalive/AlwaysAliveManager                       │
│  utils/  ← OsenseKillActionMessenger 等                      │
├──────────────────────────────────────────────────────────────┤
│  策略解析层 (common/parser/)                                   │
│  g2/e  ← OFreezer3.0 配置解析器 (sys_elsa_config_list.xml)   │
│  athena/p0  ← background_protect_list + clear_whitelist     │
│  athena/k5  ← ProtectSelfWhiteList                          │
│  alwaysalive/d  ← always-alive whitelist                    │
│  guardelf/  ← GuardElf 通知白名单                            │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. API 接口层（AIDL 分析）

### 2.1 `IAthenaService` (com.oplus.app.IAthenaService)

核心 Binder 接口，所有外部调用入口：

| 方法 | 签名 | 说明 |
|------|------|------|
| `athenaKill` | `(int uid, int pid, String pkg, int level, int flag) → int` | 旧版 kill（Binder code 100） |
| `athenaFreeze` | `(int uid, int pid, String pkg, int level, int flag) → int` | 旧版 freeze（Binder code 101） |
| `athenaKill2` | `(... + int reason) → int` | 带 reason 的 kill（code 102） |
| `athenaKill3` | `(List<Bundle> killData) → int` | **新版批量 kill**（code 201） |
| `clearProcess` | `(Bundle bundle)` | 清理进程（code 223） |
| `getWhiteList` | `(int maxNum) → List<String>` | **获取白名单**（code 209） |
| `getActiveAppList` | `() → List<String>` | 活跃应用列表（code 208） |
| `isRecentlyUsedApp` | `(String pkgName) → boolean` | 是否最近使用（code 210） |
| `scheduleNoteNativeProcessKill` | `(Bundle, int reason, int subReason)` | Native 进程杀（code 248） |
| `registerAthenaKillInfo` | `(IAthenaKillCallback cb, String caller)` | 注册 kill 回调（code 211） |

### 2.2 `IAthenaKillerManager` (com.oplus.athena.interaction)

新架构 killer 管理接口（Binder code 0-16），提供：
- `getActiveAppList()`, `getWhiteList(int)`, `isRecentlyUsedApp(String)`
- `registerAthenaKillInfo(callback, caller)` / `unregister...`
- 两套回调系统：`IAthenaKillCallback`（com.oplus.app 包）和同名接口（com.oplus.athena.interaction 包）

### 2.3 `IAthenaKillCallback`

kill 事件回调：
- `onAppKilled(Bundle data)` — 应用被杀通知
- `onClearParamChanged()` — 清理参数变更
- `onAthenaCleanup(List<Bundle> clearData)` — 批量清理通知

---

## 3. 实际 Kill 调用链

```
系统触发（划卡/内存压力/thermal）
       │
       ▼
  r3.c.forceStopPackageAndSaveActivity()  ← 调用 OplusActivityManager
       │
       ▼
  i3.h  ← 薄封装 android.app.OplusActivityManager
       │  （Oplus AMS 扩展，覆盖 AOSP AMS）
       ▼
  x3.d.killProcess()                       ← "killProcess clearInfo="
       │  x3.d.killProcessGroup()           ← "killProcessGroup,pid:"
       ▼
  x3.z0  ← "handle background process", "KillAppFilterName"
       │  x3.r1  ← "killBackgroundUserProcessesWithAudioRecord with user"
       ▼
  Process.killProcess() / ActivityManagerService.killBackgroundProcesses()
```

---

## 4. 白名单体系（完整 Schema）

### 4.1 多层白名单架构

Athena 有 **5 层独立白名单**，按优先级从高到低：

| 层级 | 来源 | 解析类 | 配置文件 |
|------|------|--------|----------|
| **L1 - 系统保护** | 系统关键进程 | `athena/p0` | `background_protect_list`（硬编码） |
| **L2 - 自定义白名单** | 运营商定制 | `oplusguardelf/i` | `/etc/oplus_customize_whitelist.xml` |
| **L3 - Always-Alive** | 常驻应用 | `alwaysalive/d` | `sys_alwaysalive_config_list.xml` |
| **L4 - ELSA 白名单** | OFreezer 策略 | `g2/e$d` | `sys_elsa_config_list.xml` `<whitePkg>` |
| **L5 - GuardElf 通知** | 通知保护 | `oplusguardelf/o` | `notify_whitelist.xml` |

### 4.2 `sys_elsa_config_list.xml` `<whitePkg>` 语义

```xml
<!-- category 编码：三位数字，每位独立含义 -->
<!-- 100 = forcewhite (系统强制白名单，不可被覆盖) -->
<!-- 010 = oppo/oneplus 自有应用白名单 -->
<!-- 001 = 第三方白名单 -->
<whitePkg name="com.coloros.soundrecorder" category="100"/>
<whitePkg name="com.oplus.melody" category="010"/>
<whitePkg name="cn.xuexi.android" category="001"/>
```

**category 编码详解**：
- **`100`** — forcewhite：系统级强制白名单，即使杀进程也跳过这类应用（录音机、闹钟、TTS等）
- **`010`** — oplus white：Oppo/OnePlus 自有应用（屏幕录制、主题商店等）
- **`001`** — third white：第三方合作应用（滴滴司机端、美团商家、打印服务等）

### 4.3 `sys_athena_appfilter_list.xml` 过滤分值

使用 `<p k="pkg" v="score"/>` 格式，分值决定应用的"重要性"：
- `v >= 14337` — 系统核心进程（android、systemui、launcher）
- `v >= 8194` — 白名单保护应用（微信 16538、QQ 32922、支付宝 32914）
- `v == 512` — 测试/猴子应用
- `v == 8` — 最低优先级

### 4.4 其他白名单/保护列表

| 配置文件 | 保护类型 | 关键元素 |
|----------|----------|----------|
| `sys_guardelf_config_list.xml` | GuardElf 保护 | `<strong>` 强保护、`<notify>` 通知保护、`<killrestart>` 杀后重启 |
| `sys_appfrozen_config_list.xml` | 冻结豁免 | `<no_frozen>` 不冻结、`<frozen_dlg>` 仅对话框冻结 |
| `sys_alwaysalive_config_list.xml` | 常驻保活 | `<whitelist_packages>` 不杀白名单 |

### 4.5 代码级白名单检查

| 类 | 检查类型 | 发现的关键字符串 |
|----|----------|-----------------|
| `oplusguardelf/c` | GuardElf 白名单 | `BTisInWhitelistNotInNotRestrict`, `whitelist:` |
| `alwaysalive/d` | 常驻白名单 | `only_enable_whitelist_package`, `mThirdSplashWhitePkgs` |
| `AthenaKillerDumpManager` | dump 展示 | `bootWhiteList:`, `[dump background protect list]` |
| `DBProvider` | 数据库白名单 | `getAppFrozenWhiteList` |
| `athena/k5` | 自我保护白名单 | `ProtectSelfWhiteList` |

---

## 5. 关键配置文件结构

### 5.1 `sys_elsa_config_list.xml`（OFreezer3.0 主配置，85KB）

```xml
<filter-conf>
  <version>2026032300</version>
  <module-version>OFreezer3.0</module-version>          ← 当前版本 3.0

  <!-- 全局开关 -->
  <enableConfig hansEnable="true" gmsEnable="true" ... />

  <!-- LCD 状态策略 -->
  <lcdOffConfig ffTotal="6" ffInterval="10000" ... />
  <lcdOnConfig RToM="20000" MToF="10000" ... />

  <!-- 快速冻结 -->
  <ffConfig enable="true" enterTimeout="100" ... />
  <ffPkg type="white|black|skipAppSwitch" pkg="..." />

  <!-- ★ 白名单：category=100(forcewhite) | 010(oppo) | 001(third) -->
  <whitePkg name="pkg" category="100|010|001"/>

  <!-- ★ 系统黑名单：限制后台行为 -->
  <SysBlack name="pkg" version="minVer" scene="bitmask" mask="bitmask"/>
  <SysBlackApp inherit="lcdon|lcdoff|night" name="pkg">
    <broadcast excludeAction="..." />
    <net forceProxy="true"/>
  </SysBlackApp>

  <!-- ★ 冻后杀：冻结一段时间后自动 kill -->
  <killFrozenConfig pkg="cmb.pb"/>

  <!-- 包级行为配置 -->
  <pkgConfig category="bitmask" name="pkg1#pkg2..."/>

  <!-- ★ 防止唤醒/启动控制 -->
  <prevent scene="110" pkg="google play services" mask="1111111110"/>
</filter-conf>
```

**scene bitmask**（SysBlack + prevent）:
```
bit 0: extremeFg
bit 1: charging
bit 2: lcdon
bit 3: lcdoff
bit 4: night
bit 5: minSystem
bit 6: thermal_mid
bit 7: thermal_low
bit 8: fastFreeze
```

**mask bitmask**（组件禁用）:
```
bit 0: close_socket
bit 1: aysnbinder
bit 2: alarm
bit 3: sync
bit 4: job
bit 5: broadcast
bit 6: provider
bit 7: bindservice
bit 8: startservice
bit 9: activity
```

### 5.2 `sys_clear_config.xml`（内存清理配置，55KB）

- **异常内存监控**：`abnormal_memory_monitor` 配置段
- **force kill 阈值**：`fk_rss_bd`, `fk_pss`, `fk_gpu`, `fk_ion` (MB)
- **tip 阈值**：`tip_rss_bd`, `tip_pss` (MB) — 超过此值提示用户
- **分设备档次**：按 RAM 大小分 5 档 (2/3/4/6/8/12/16/24GB)
- **应用级自定义阈值**：`<appmem_config pkg="..." rss="..." pss="..."/>`
- **进程级自定义阈值**：`<procmem_config pkg="..." proc="..."/>`

**kill 触发条件**（以 com.taobao.taobao 为例）：
```
rss > 4000MB 且 pss > 2500MB → tip (提示)
rss > 5000MB 且 pss > 4000MB → force kill
```

### 5.3 `sys_athena_appfilter_list.xml`（应用过滤配置）

- `<p k="pkg" v="importance_score"/>` — 进程重要性分值
- `<p_diff k="pkg" v_12.0="score"/>` — 版本差异分值
- `<memory_monitor>` 内：
  - `skip_onekey_clear_system_process_to_kill_list` — 一键清理跳过列表
  - `skip_memory_guard_system_process_to_kill_list` — 内存守护跳过列表
- `<thermal_control_clear>` — 温控触发的清理策略

### 5.4 `sys_guardelf_config_list.xml`（GuardElf 守护配置）

```xml
<filter-conf>
  <strong>pkg</strong>      <!-- 强保护（杀后自动重启） -->
  <notify>pkg</notify>      <!-- 通知保护（不被冻结） -->
  <killrestart>pkg</killrestart>  <!-- 杀后重启（微信/QQ） -->
</filter-conf>
```

---

## 6. ★ Hook 点候选表

### 6.1 核心 Kill 拦截点

| 序号 | 类全名 | 方法 | 参数推测 | 触发时机 | 优先级 |
|------|--------|------|----------|----------|--------|
| HK01 | `r3.c` | `forceStopPackageAndSaveActivity` | `(String pkgName, int userId, ...)` | **每次系统决定杀进程** | ★★★★★ |
| HK02 | `x3.d` | `killProcess` | `(String clearInfo, int pid, ...)` | **实际 kill 执行前** | ★★★★★ |
| HK03 | `x3.d` | `killProcessGroup` | `(int uid, int pid, ...)` | cgroup kill 执行前 | ★★★★ |
| HK04 | `x3.z0` | `handleBackgroundProcess` | `(String pkg, int uid, ...)` | 后台进程处理入口 | ★★★★ |
| HK05 | `x3.r1` | `killBackgroundUserProcessesWithAudioRecord` | `(int userId, ...)` | 音频录制期间的杀进程 | ★★★ |

### 6.2 白名单检查拦截点

| 序号 | 类全名 | 方法 | 参数推测 | 触发时机 | 优先级 |
|------|--------|------|----------|----------|--------|
| HK06 | `oplusguardelf.c` | (包含 `BTisInWhitelistNotInNotRestrict` 的方法) | `(String pkg)` | **白名单检查时** | ★★★★★ |
| HK07 | `g2.e$d` | `M`/`N`/`P` (XML 解析后匹配) | `(String pkg)` | whitePkg 匹配时 | ★★★★ |
| HK08 | `alwaysalive.d` | (包含 `mThirdSplashWhitePkgs` 的方法) | `(String pkg)` | always-alive 检查 | ★★★ |
| HK09 | `athena.p0` | (读取 `background_protect_list` 的方法) | `(String pkg)` | 后台保护列表检查 | ★★★★ |

### 6.3 配置加载拦截点

| 序号 | 类全名 | 方法 | 参数推测 | 触发时机 | 优先级 |
|------|--------|------|----------|----------|--------|
| HK10 | `g2.e` | `a`/`b`/`c` (初始化) | `()` | **OFreezer 配置加载** | ★★★★★ |
| HK11 | `g2.e$b` | `I`/`K`/`L` (解析) | `(XmlPullParser)` | ELSA XML 解析中 | ★★★★ |
| HK12 | `AthenaDynamicReceiver` | `onReceive` | `(Context, Intent)` | 动态配置更新 | ★★★ |

### 6.4 回调/通知拦截点

| 序号 | 类全名 | 方法 | 参数推测 | 触发时机 | 优先级 |
|------|--------|------|----------|----------|--------|
| HK13 | `x3.l` | `a`/`b` (AthenaKillNotifier) | `(Bundle killData)` | **kill 后通知** | ★★★★ |
| HK14 | `e4.b` | `onAthenaKilled` | `(List<Bundle>)` | Kill 回调 | ★★★ |

---

## 7. ★ 关键发现

### 7.1 划卡杀进程的完整流程

```
用户划卡
  → SystemUI 或 Launcher 调用 IAthenaService.clearProcess(Bundle)
     Bundle 包含 {packageName, userId, clearType(划卡=?), ...}
  → AthenaService 路由到 systemservice/h1 (AthenaKillerManagerService)
  → h1 查 ELSA 白名单 (g2/e$d -> whitePkg)
  → h1 查 ProtectSelfWhiteList (athena/k5)
  → h1 查 appfilter_list 分值 (athena_appfilter_list.xml)
  → 如果不在白名单 且 分值低于阈值
     → r3.c.forceStopPackageAndSaveActivity(pkg)
       → i3.h → OplusActivityManager.forceStopPackage()
       → x3.d.killProcess()
  → 如果在白名单 或 分值高 → 仅 freeze (不 kill)
  → 回调 x3.l (AthenaKillNotifier) → 通知所有 IAthenaKillCallback
```

### 7.2 白名单如何生效

1. **ELSA 白名单**（`<whitePkg>`）在 `g2/e$d` 中被解析为 `Map<String, Integer>`（pkg → category）
2. **每次 kill 决策前**，`AthenaKillerManagerService` 调用白名单检查
3. **category=100**（forcewhite）的应用**绝对不会**被 kill，即使内存压力极高
4. **category=010/001** 的应用在普通场景不会被 kill，但在 extreme（如 thermal high、内存临界）可能被降级
5. **Always-Alive 白名单**（`sys_alwaysalive_config_list.xml`）：即使进程被系统杀死，Athena 也会自动重启
6. **GuardElf `<strong>` 列表**：杀后自动重启 + 通知不冻结
7. **自定义白名单**（`/etc/oplus_customize_whitelist.xml`）：运营商/OEM 定制级别的覆盖

### 7.3 kill vs freeze 决策矩阵

| 场景 | 白名单应用 | 普通应用 | 黑名单应用 |
|------|-----------|----------|-----------|
| 划卡清理 | **跳过**（不杀不冻） | **Kill** | **Kill** |
| 内存压力-low | 不冻 | **Freeze** | **Kill** |
| 内存压力-high | 不冻 | **Kill** | **Kill** |
| Thermal critical | **Freeze**（category=010/001） | **Kill** | **Kill** |
| LCD off 30min+ | 不冻 | **KillFrozen** | **Kill** |
| 夜间深度睡眠 | 不冻（category=100） | **KillFrozen** | **Kill** |

### 7.4 混淆映射表（推测）

| 混淆名 | 实际功能猜测 | 证据 |
|--------|-------------|------|
| `g2/e` | OFreezer3.0 主配置解析器 | 字符串 "OFreezer3.0", "sys_elsa_config_list" |
| `g2/e$b` | 配置子解析（freezeList/freezeLevel/freezeTime） | 字符串 "freezeLevel=", "freezeList" |
| `g2/e$d` | 白名单 XML 解析器 | 字符串 "whitePkg", "sys_elsa_config_list_abnormal" |
| `x3/d` | 进程 kill 执行器 | 字符串 "killProcess clearInfo=", "killProcessGroup" |
| `x3/z0` | 后台进程过滤器 | 字符串 "handle background process", "KillAppFilterName" |
| `x3/l` | Kill 事件通知器 | 字符串 "AthenaKillNotifier" |
| `x3/r1` | 音频录制期间的特殊 kill | 字符串 "killBackgroundUserProcessesWithAudioRecord" |
| `r3/c` | OplusActivityManager 封装 | 字符串 "forceStopPackageAndSaveActivity", "OplusActivityManager" |
| `i3/h` | OplusActivityManager 薄封装 | 字符串 "android.app.OplusActivityManager" |
| `h1` (systemservice) | AthenaKillerManagerService | 字符串 "AthenaKillerManagerService" |
| `athena/p0` | 后台保护白名单 | 字符串 "background_protect_list", "clear_whitelist" |
| `athena/k5` | 自我保护白名单 | 字符串 "ProtectSelfWhiteList" |

---

## 8. 对 t4/t5/t7 的建议

### 8.1 对 t4（Xposed 模块 Hook 开发）的建议

1. **首选 Hook 点：`r3.c.forceStopPackageAndSaveActivity`**
   - 这是决定杀进程的**最终执行点**
   - Hook 参数 `pkgName`，在白名单中则跳过原始调用
   - 风险：完全阻止可能导致系统内存管理失效

2. **次选 Hook 点：`x3.d.killProcess`**
   - 更底层的 kill 执行
   - 但可能遗漏 freeze 路径

3. **推荐的白名单注入点：`g2.e$d.M` 系列方法**
   - 在 XML 解析后、白名单位置
   - 向 `whitePkg` Map 注入自定义包名和 category=100

4. **回调 Hook：`x3.l` (AthenaKillNotifier)**
   - 可用于**监控**哪些应用被杀、何时被杀
   - 做日志记录而非拦截

### 8.2 对 t5（配置热更新）的建议

1. **热更新的目标文件**：
   - `/data/oplus/os/bpm/sys_elsa_config_list.xml`（主配置）
   - `/my_company/etc/oplus_customize_whitelist.xml`（自定义白名单）

2. **热更新方案**：
   - 修改 XML 后发送 `AthenaDynamicReceiver` 能接收的广播
   - 或调用 `IAthenaService.onParsedReady(parseType, xmlInfo, remote)` (Binder code 240)

3. **增量更新**：
   - 解析 `<whitePkg>` 元素后 append 新条目
   - 清理 force kill 阈值中的特定应用

### 8.3 对 t7（系统集成）的建议

1. **必须 root + Xposed/LSPosed**
2. **作用域**：`com.oplus.athena`（系统服务，在 `system_process` 或独立进程）
3. **ClassLoader**：需要通过 `XposedHelpers.findClass` 加载混淆类
4. **安全边界**：只修改白名单行为，不修改 kill 逻辑本身

---

## 9. 附录：完整 XML 配置清单

| 文件名 | 大小 | 用途 |
|--------|------|------|
| `sys_elsa_config_list.xml` | 85.5KB | **ELSA 调度主配置**（whitePkg, SysBlack, killFrozen, prevent, etc.） |
| `sys_clear_config.xml` | 55.5KB | **内存清理配置**（异常内存阈值、force kill 水线） |
| `sys_athena_config_list.xml` | 145.5KB | Athena 总配置（最大文件） |
| `sys_athena_appfilter_list.xml` | 15.8KB | **应用过滤分值**（进程重要性评分） |
| `sys_clear_appfilter_list.xml` | 12.1KB | 清理专用过滤 |
| `sys_guardelf_config_list.xml` | 6.3KB | **GuardElf 守护**（strong/notify/killrestart） |
| `sys_appfrozen_config_list.xml` | 5.4KB | **冻结策略**（no_frozen, auto_frozen_time） |
| `sys_athena_livelock_config_list.xml` | 5.6KB | 活锁检测 |
| `sys_alwaysalive_config_list.xml` | 3.1KB | **常驻保活**（whitelist_packages） |
| `sys_cpulimit_config.xml` | 8.7KB | CPU 限制 |
| `sys_system_config_list.xml` | 8.9KB | 系统级配置 |
| `sys_athena_hybrid_swap_config.xml` | 1.6KB | 混合交换 |
| `sys_athena_dcs_config.xml` | 1.5KB | DCS 配置 |
| `sys_athena_lock_app_config.xml` | 0.5KB | 锁定应用 |
| `sys_elsa_config_list_abnormal.xml` | 0.1KB | 异常配置覆盖 |

---

## 10. 附录：AIDL 接口完整清单

| AIDL 文件 | 包名 | 说明 |
|-----------|------|------|
| `IAthenaService.aidl` | com.oplus.app | **主服务接口**（athenaKill, athenaFreeze, clearProcess, getWhiteList） |
| `IAthenaKillerManager.aidl` | com.oplus.athena.interaction | **新架构 kill 管理器**（getWhiteList, registerKillInfo） |
| `IAthenaKillCallback.aidl` | com.oplus.app | kill 回调（onAppKilled, onClearParamChanged） |
| `IAthenaKillCallback.aidl` | com.oplus.athena.interaction | kill 回调（同名，互动包） |
| `IAthenaSystemService.aidl` | com.oplus.app | 系统服务生命周期（onBootPhase） |
| `IAthenaCallback.aidl` | com.oplus.app.athena | 通用回调（1695 字节） |
| `IEmService.aidl` | com.oplus.athena.interaction | 内存管理服务 |
| `IStateService.aidl` | com.oplus.athena.interaction | 状态管理服务 |
| `IStateCallback.aidl` | com.oplus.app + com.oplus.athena.interaction | 状态回调 |
| `IPinnerService.aidl` | com.oplus.app | Pinner 服务（内存驻留） |
| `ITerminateObserver.aidl` | com.oplus.app | 进程终止观察者 |
| `IRemoteGuardElfInterface.aidl` | com.oplus.athena.policy.battery | GuardElf 远程接口 |
| `OKillerArgs.aidl` | com.oplus.athena.interaction | Kill 参数 parcelable |

