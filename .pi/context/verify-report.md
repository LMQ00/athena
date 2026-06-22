# 验证报告 — 执行完成

> **生成时间**: 2026-06-23
> **提交**: a9c5c402 (main)
> **CI Run**: #27976887217

## 验证结果

| 检查项 | 状态 | 备注 |
|--------|------|------|
| GitHub Actions build | ✅ **success** | assembleDebug 通过 |
| MCP 端点可达 | ✅ 在线 | MT APK MCP v0.1.0, protocol 2025-06-18 |
| git push | ✅ 成功 | a9c5c40 → origin/main |

## 修改文件清单

| 文件 | 改动 | 说明 |
|------|------|------|
| `SwipeKillHooks.kt` | 修改 | 新增 `forceStopPackageAndSaveActivity` Hook 路径（三层防御） |
| `OplusConfigHooks.kt` | 修改 | 3 WeakHashMap 合并为 StreamState、新增 `currentConfig` + `updateConfig` |
| `XmlPolicyBuilder.kt` | 重写 | 真 XML parser + 字符串 fallback + 白名单去重 + 可配置 category |
| `SwipeGuardConfig.kt` | 修改 | 新增 `whitelistCategory: String = "100"` |
| `ModuleMain.kt` | 修改 | tryInstall 辅助函数、install 顺序注释、syncHooks 接线 |
| `SystemServiceHooks.kt` | **删除** | 冻结已由第三方墓碑接管 |
| `AGENTS.md` | 重写 | 6 类同步 + MCP 逆向章节 + 冻结说明 |
| `.github/workflows/build.yml` | 修改 | concurrency + Gradle 缓存 + 7天保留 + APK summary + dispatch audit |
| `.pi/context/reverse-system-athena.md` | **新建** | 完整逆向报告（440行） |
| `.pi/context/mcp-smoke-test.json` | **新建** | MCP 烟测记录 |
| `.pi/context/scout-ref/` | **新建** | 4 份调研文档 |
| `plan.md` | **删除** | 原旧版计划文件 |

## 逆向核心发现

1. **真实 kill 路径**: `r3.c.forceStopPackageAndSaveActivity` → `OplusActivityManager.forceStopPackage()` → `x3.d.killProcess()` — **不走 AMS 标准路径**
2. **白名单体系**: 5 层独立白名单（L1-L5），`<whitePkg category="100|010|001">`
3. **category 编码**: 100=forcewhite（强制不杀）、010=oplus自有、001=第三方
4. **14 个候选 Hook 点**: 覆盖 kill 拦截、白名单检查、配置加载、回调通知

## Hook 架构（最终）

```
┌──────────────────────────────────────┐
│ OplusConfigHooks (object)            │
│  ├─ FileInputStream 劫持 XML 读取     │
│  ├─ OplusSettings.readConfig 拦截     │
│  └─ XmlPolicyBuilder (真XML parser)   │
├──────────────────────────────────────┤
│ SwipeKillHooks (object)              │
│  ├─ killBackgroundProcesses (AMS兜底) │
│  ├─ forceStopPackage (AMS兜底)       │
│  └─ forceStopPackageAndSaveActivity  │
│     (★ 核心: 拦截 Athena 实际 kill)   │
├──────────────────────────────────────┤
│ AthenaKillHooks (instance)           │
│  ├─ IAthenaService.athenaKill        │
│  └─ IAthenaService.clearProcess      │
└──────────────────────────────────────┘
```
