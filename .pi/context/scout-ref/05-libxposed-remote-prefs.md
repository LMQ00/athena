# libxposed `getRemotePreferences` 跨进程同步机制调研

## Summary

LSPosed/libxposed 的 `getRemotePreferences` 基于 **Binder + AIDL 双向通信** 实现跨进程 SharedPreferences 共享。数据由 LSPosed Daemon 进程（服务端）统一存储，所有 Hook 进程（system_server、app 进程等）通过 `ILSPInjectedModuleService` 接口发起初始请求，并在服务端数据变更时通过 `IRemotePreferenceCallback` 接收推送更新。但模块端返回的 `SharedPreferences` 实例**只读**（`edit()` 抛 `UnsupportedOperationException`），写入需通过服务端其他机制完成。

---

## Key Findings

### 1. 架构：Binder 双向通信，服务端统一存储

- **来源**: LSPosed 源码 `LSPosedRemotePreferences.java` + `LSPosedContext.java`
- `getRemotePreferences(String group)` 返回 `LSPosedRemotePreferences` 实例，其构造函数通过 `ILSPInjectedModuleService.requestRemotePreferences(group, callback)` 发起跨进程 Binder 调用，向 LSPosed Daemon 请求数据和注册回调。
- 初始数据以 `Bundle` 返回，包含 `Serializable` 的 `Map<String, Object>`，被缓存到 `ConcurrentHashMap`。
- 服务端（LSPosed Daemon）是整个数据的主存储位置，所有 client 进程持有数据的一份本地快照。

```
┌─────────────────────────────────────┐
│         LSPosed Daemon (服务端)      │
│  ┌──────────────────────────────┐   │
│  │  Preferences Database/SQLite │   │  ← 数据主存储
│  └──────────────────────────────┘   │
│          ↕ Binder callback          │
│  ┌──────────────────────────────┐   │
│  │ IRemotePreferenceCallback     │   │  ← 向所有 client push 变更
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
          ↕ AIDL Binder
┌─────────────────────────────────────┐
│  Hook 进程 (system_server / app)    │
│  ┌──────────────────────────────┐   │
│  │ LSPosedRemotePreferences     │   │
│  │  ├─ mMap (ConcurrentHashMap) │   │  ← 本地缓存快照
│  │  ├─ onUpdate(Bundle) 回调   │   │  ← 服务端 push 时更新
│  │  └─ fire ChangeListener     │   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
```

- 文档参考：https://libxposed.github.io/api/io/github/libxposed/api/XposedInterface.html
- 源码：https://raw.githubusercontent.com/LSPosed/LSPosed/df74d83e/core/src/main/java/org/lsposed/lspd/impl/LSPosedRemotePreferences.java

### 2. Hook 进程写入后，UI 进程能否收到变化？

- **来源**: LSPosed 源码 `LSPosedRemotePreferences.java` (onUpdate 方法)
- **关键发现：`edit()` 抛 `UnsupportedOperationException("Read only implementation")`**。模块端的 `SharedPreferences` 实例**只读**，无法直接通过 `.edit().putXxx().apply()` 写入。
- 写入必须通过服务端（LSPosed Daemon）的其他接口完成（如 LSPosed Manager 或框架内部 API）。
- **当服务端数据变更时**，所有注册了 `IRemotePreferenceCallback` 的 client 进程**都会收到 `onUpdate(Bundle)` 调用**，更新本地 mMap 并触发 `OnSharedPreferenceChangeListener`。
- 结论：**如果系统进程通过服务端 API 写入**（非直接调用 `edit()`），UI 进程能收到变化推送。推送是**实时**的，通过 Binder 回调实现。

关键代码片段（`LSPosedRemotePreferences.java`）：

```java
IRemotePreferenceCallback callback = new IRemotePreferenceCallback.Stub() {
    @Override
    synchronized public void onUpdate(Bundle bundle) {
        Set<String> changes = new ArraySet<>();
        if (bundle.containsKey("delete")) {
            var deletes = (Set<String>) bundle.getSerializable("delete");
            changes.addAll(deletes);
            for (var key : deletes) { mMap.remove(key); }
        }
        if (bundle.containsKey("put")) {
            var puts = (Map<String, Object>) bundle.getSerializable("put");
            mMap.putAll(puts);
            changes.addAll(puts.keySet());
        }
        synchronized (mListeners) {
            for (var key : changes) {
                mListeners.forEach(listener ->
                    listener.onSharedPreferenceChanged(LSPosedRemotePreferences.this, key));
            }
        }
    }
};
```

### 3. Binder 同步是单向还是双向？

- **来源**: LSPosed 源码 `LSPosedContext.java` + `LSPosedRemotePreferences.java`
- **双向 Binder 通信**：
  1. **模块 → 服务端（同步 Binder call）**：`service.requestRemotePreferences(group, callback)` — 首次请求获取初始数据（同步）。
  2. **服务端 → 模块（异步 Binder callback）**：通过注册的 `IRemotePreferenceCallback.Stub`，服务端在数据变化时回调 `onUpdate(Bundle)` 推送增量变更（异步）。
- 设计来源说明（libxposed/service Issue #1）：
  > "To get the binder from another app, the only way is to use ContentProvider. ... For root frameworks, putting the binder to a static field of modules' classloader is a stable way."
  - 来源：https://github.com/libxposed/service/issues/1

- **对比原始 apsun/RemotePreferences 库**（第三方 ContentProvider 实现）：
  - 原始库通过 `ContentProvider` + `ContentObserver` 实现跨进程共享，支持写入。
  - libxposed 的实现不依赖 ContentProvider，直接使用 Binder，效率更高但**模块端只读**。
  - 来源：https://raw.githubusercontent.com/apsun/RemotePreferences/master/README.md

### 4. 已知的坑和限制

- **来源**: 源码阅读 + LSPosed Wiki + Brave Search 汇总

| 问题 | 详情 |
|------|------|
| **模块端只读** | `LSPosedRemotePreferences.edit()` 抛 `UnsupportedOperationException`，模块无法通过标准 `SharedPreferences.Editor` 写入。写入需通过服务端 API 间接实现。 |
| **数据大小限制** | 使用 `Bundle` + `Serializable` 传输，大体积数据（如序列化的大 Map）可能触发 Binder 事务缓冲区溢出（1MB 限制）。LSPosed Wiki 也标注 Remote Preferences 不支持 "Large Content"，大数据应使用 `Remote Files`。 |
| **键/值限制** | 键不可为 null 或空串。值可以为 null（原始库 v0.3+ 支持，libxposed 继承此行为）。 |
| **无跨群组原子操作** | 每个 `group` 的 SharedPreferences 是独立实例，跨 `group` 的读写不是原子的。 |
| **初始化时机** | 需确保在模块已加载（`onModuleLoaded` / `onPackageLoaded` 之后）才调用 `getRemotePreferences`，在构造函数中过早调用可能导致服务尚未就绪。 |
| **Hot Reload 行为** | API 102 支持 Hot Reload，但 RemotePreferences 的 callback 注册是否在 Hot Reload 后自动恢复，官方文档未明确说明，可能需要模块自行处理重注册。 |
| **迁移注意事项** | 从旧版 `XSharedPreferences` 迁移到 Remote Preferences 需注意：存储位置从 module 内部存储变更为 "LSPosed database"（`/data/adb/lspd/` 相关位置），旧数据不会自动迁移。 |
| **Android 11+ 兼容性** | 原始 `apsun/RemotePreferences` 库在 Android 11+ 因 App Visibility 变更失效（需 `<queries>` 声明）。libxposed 的 Binder 实现不受此影响，因为它不依赖 `ContentProvider` 查询。 |

### 5. LSPosed Wiki 中的 API 对比

- **来源**: LSPosed Wiki "Develop Xposed Modules Using Modern Xposed API"
- 对比表摘要：

| API 名称 | API 类型 | 支持版本 | 存储位置 | Change Listener | Large Content |
|----------|---------|---------|---------|----------------|---------------|
| New XSharedPreferences | Legacy(ext) | ❌ Since v2.1.0 | `/data/misc/<random>/prefs/<module>` | ❌ | ❌ |
| XSharedPreferences | Legacy | ✅ Since v2.0.0 | Module apps' internal storage | ❌ | ❌ |
| **Remote Preferences** | **Modern** | **✅ Since v1.9.0** | **LSPosed database** | **✅** | **❌** |
| Remote Files | Modern | ✅ Since v1.9.0 | `/data/adb/lspd/modules/<user>/<module>` | ❌ | ✅ |

- 文档：https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API

---

## Related Links

- `https://libxposed.github.io/api/io/github/libxposed/api/XposedInterface.html` — libxposed API 文档，包含 `getRemotePreferences` 定义
- `https://raw.githubusercontent.com/LSPosed/LSPosed/df74d83e/core/src/main/java/org/lsposed/lspd/impl/LSPosedRemotePreferences.java` — LSPosedRemotePreferences 完整实现（只读 + 双向 Binder 回调）
- `https://raw.githubusercontent.com/LSPosed/LSPosed/df74d83e/core/src/main/java/org/lsposed/lspd/impl/LSPosedContext.java` — LSPosedContext 中 `getRemotePreferences` 的创建逻辑
- `https://github.com/libxposed/service/issues/1` — libxposed service 原始设计提案（Binder 通信机制说明）
- `https://raw.githubusercontent.com/apsun/RemotePreferences/master/README.md` — 原始 RemotePreferences 库文档（ContentProvider 方案）
- `https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API` — LSPosed Wiki 现代 API 使用文档，含 API 对比表
- `https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences` — LSPosed Wiki 新版 XSharedPreferences 文档
- `https://deepwiki.com/LSPosed/LSPosed/7.1-api-reference` — LSPosed 源码 DeepWiki API 参考（含 Remote Preferences 概要说明）
