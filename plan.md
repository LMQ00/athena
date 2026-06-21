# Plan: ColorOS 16 划卡保护自定义模块（SwipeGuard）

## 目标
将现有「Athena / OFreezer 冻结策略注入」项目，重构为「SwipeGuard / ColorOS 16 划卡保护解除」Xposed 模块：解决包名冲突、补全 LSPosed 识别元数据、按真实需求（划卡杀死）重新设计数据模型与 Hook 路径。

> **新模块名**：SwipeGuard | **新包名**：`com.swipeguard.xposed` | **显示名**：划卡卫士

## 任务

### 阶段 0：调研与决策
- [ ] t1: 调研 ColorOS 16 划卡保护机制（RecentTask 杀进程路径） — scout ← 依赖: -
- [ ] t2: 决策新模块名（SwipeGuard / swipedefender / protectlist 三选一） — scout ← 依赖: -

### 阶段 1：重命名
- [ ] t3: 移动整棵目录树 `com/athena/xposed/` → `com/swipeguard/xposed/` — worker ← 依赖: t2
- [ ] t4: 批量替换所有 `.kt` 的 package/import 引用 — worker ← 依赖: t3
- [ ] t5: 更新 `namespace`/`applicationId` → `com.swipeguard.xposed` — worker ← 依赖: t2
- [ ] t6: 更新 `settings.gradle.kts` rootProject.name — worker ← 依赖: t2
- [ ] t7: 更新 `strings.xml` + 应用名/描述文案 — worker ← 依赖: t2
- [ ] t8: 重命名 Application 类 + 更新 xml:name — worker ← 依赖: t3, t4
- [ ] t9: 更新 `java_init.list` 入口类路径 — worker ← 依赖: t5, t8

### 阶段 2：LSPosed 识别修复（可与阶段 1 并行）
- [ ] t10: 新增 `assets/module.prop`（LSPosed 识别核心） — worker ← 依赖: t2
- [ ] t11: 添加 `xposedmodule`/`xposeddescription`/`xposedminversion`/`xposedscope` meta-data — worker ← 依赖: t5, t9

### 阶段 3：数据模型重新设计
- [ ] t12: 新建 `SwipeGuardConfig`（极简：`enabled` + `killableApps: Set<String>`） — worker ← 依赖: t3
- [ ] t13: 简化 JsonCodec / Repository 层 — worker ← 依赖: t12
- [ ] t14: 删除旧模型（AppEntry, AthenaConfig, EntrySet, FreezePolicy 等 8 个文件） — worker ← 依赖: t3
- [ ] t15: 删除旧引擎（IPolicyMatcher, PolicyMatcher, MatcherStats） — worker ← 依赖: t3

### 阶段 4：新 Hook 实现
- [ ] t16: 删除旧 Hook（OplusConfigHooks, SystemServiceHooks, XmlPolicyBuilder） — worker ← 依赖: t3
- [ ] t17: 新建 `SwipeKillHooks`（Hook removeTask/killProcessGroup） — worker ← 依赖: t1, t12, t13
- [ ] t18: 重写 `ModuleMain`（只挂载 SwipeKillHooks） — worker ← 依赖: t13, t17

### 阶段 5：UI 简化
- [ ] t19: 删除旧 UI 页面（7 个文件）+ NavGraph + AppListItem 等组件（5 个文件） — worker ← 依赖: t3
- [ ] t20: 新建 `SwipeGuardScreen`（单屏：应用列表 + 允许划卡开关） — worker ← 依赖: t12
- [ ] t21: 重写 `MainActivity`（移除 NavHost，直接挂载 SwipeGuardScreen） — worker ← 依赖: t20
- [ ] t22: 简化 `ConfigViewModel`（只保留 toggleKillable + 全局开关） — worker ← 依赖: t12, t13

### 阶段 6：清理与收尾
- [ ] t23: 更新 proguard-rules / 主题文件 — worker ← 依赖: t3
- [ ] t24: 更新文档（README.md / AGENTS.md） — worker ← 依赖: 全部
- [ ] t25: 删除旧审计日志（报错.log, ci_*.log, review-table.md） — worker ← 依赖: -

### 阶段 7：构建与审查
- [ ] t26: `assembleDebug` 构建验证 — worker ← 依赖: t1-t25
- [ ] t27: 代码审查（Hook 安全性 / LSPosed 识别 / UI 健壮性） — reviewer ← 依赖: t26

## 并行机会
| 并行组 | 任务 | 说明 |
|--------|------|------|
| P0 | t1, t2 | 调研 + 命名决策，独立 |
| P1 | t10, t11 | LSPosed 元数据与重命名并行 |
| P2 | t14, t15, t16 | 三个「删除旧代码」独立 |
| P3 | t20 (UI), t17 (Hook) | 新 UI 和新 Hook 互相独立 |
| P4 | t23, t24, t25 | 清理收尾，彼此独立 |

## 关键路径
t1 → t17 → t18 → t26 → t27（调研 → 新 Hook → ModuleMain → 构建 → 审查）

## 风险
| 风险 | 应对 |
|------|------|
| ColorOS 划卡保护可能不是 removeTask，而是自定 Oplus 类 | t1 先确认方法；t17 写多个候选 Hook 点 + 反射回退 |
| 重命名容易遗漏（KDoc 引用、proguard 规则） | t4 用全项目 grep 复查 |
| 删除旧代码后在 ModuleMain 中编译报错 | t18 必须在 t16 之后立即重写 |
| 简化 UI 后丢失 Theme 包裹导致界面无样式 | t21 保留 SwipeGuardTheme 包裹 |
