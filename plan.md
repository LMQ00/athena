# Plan: ColorOS 16 后台管理 Xposed 模块

## 目标
从零创建一个 ColorOS 16 后台进程管理 Xposed 模块项目，通过 Hook 系统服务实现白名单/黑名单保护机制，配套设置 UI 与完整构建配置。

## 任务

### 调研阶段
- [x] t1: 调研 ColorOS 16 后台进程管理机制 — scout ← 依赖: -
- [x] t2: 调研 Xposed/LSPosed 在 Android 14/15 上的开发规范与依赖版本 — scout ← 依赖: -

### 项目初始化
- [x] t3: 初始化 Gradle 项目结构（settings.gradle / build.gradle / app 模块） — implementer ← 依赖: t2
- [x] t4: 编写 AndroidManifest.xml（Xposed 模块声明、宿主权限） — implementer ← 依赖: t3
- [x] t5: 编写 gradle.properties 与 libs.versions.toml（Xposed API 依赖、Android SDK 34/35） — implementer ← 依赖: t3

### 数据层
- [x] t6: 设计白名单/黑名单数据模型与持久化方案（SharedPreferences / XSharedPreferences） — planner ← 依赖: t1
- [x] t11: 实现白名单/黑名单匹配引擎（包名匹配 + 进程名匹配 + 正则支持） — coder ← 依赖: t6
- [x] t12: 实现 XSharedPreferences 桥接层（模块进程读取宿主配置） — coder ← 依赖: t6

### Hook 层
- [x] t7: 实现主入口 Hook 类（XposedModule 入口 + OplusConfigHooks + SystemServiceHooks） — coder ← 依赖: t1, t2, t4
- [x] t8: Hook ActivityManagerService 的 kill / killBackgroundProcesses 方法 — coder ← 依赖: t1, t7（⏭️ 已跳过：t1 调研确认 ColorOS 用冻结而非杀死，此路径非主要）
- [x] t9: Hook ProcessList / OomAdjuster 的 oomAdj / kill 判定方法 — coder ← 依赖: t1, t7（已在 t7 的 SystemServiceHooks 中实现）
- [x] t10: Hook ColorOS 自定义类（OplusSettings.readConfig + FileInputStream 策略注入） — coder ← 依赖: t1, t7（已在 t7 的 OplusConfigHooks 中实现）

### UI 层
- [x] t13: 设计设置 Activity 方案（Material Design / Jetpack Compose 选型） — planner ← 依赖: t6
- [x] t14: 实现设置主界面（HomeScreen + DebugScreen + 基础设施） — coder ← 依赖: t13
- [x] t15: 实现白名单编辑界面（WhitelistScreen + AddAppSheet + EditEntryDialog + ModeChip） — coder ← 依赖: t13, t14
- [x] t16: 实现黑名单编辑界面（应用列表 + 强制管控策略） — coder ← 依赖: t13, t14

### 文档
- [x] t17: 编写模块使用说明与构建说明（README.md） — implementer ← 依赖: t7
- [x] t18: 编写 AGENTS.md 项目上下文文档 — implementer ← 依赖: t3

### 构建与审查
- [x] t19: 本地构建 APK 验证（项目完整性检查 + 构建就绪报告） — implementer ← 依赖: t5, t7, t8, t9, t10, t12
- [x] t20: 代码审查（Hook 安全性、配置兼容性、UI 健壮性） — reviewer ← 依赖: t7, t8, t9, t10, t11, t12, t14, t15, t16
  （Critical 问题: 6 | Warning: 19 | Suggestion: 15）

## 并行机会
| 并行组 | 任务 | 说明 |
|--------|------|------|
| P1 | t1, t2 | 调研阶段，独立进行 |
| P2 | t4, t5 | 依赖 t3 完成后并行 |
| P3 | t8, t9, t10 | 三个 Hook 实现彼此独立，均依赖 t7 |
| P4 | t11, t12 | 依赖 t6 完成后并行 |
| P5 | t15, t16 | 依赖 t14 完成后并行 |
| P6 | t17, t18 | 文档编写，彼此独立 |

## 关键路径
```
t1 → t7 → t8 → t11 → t19 → t20
```
（调研 → 入口 hook → AMS 拦截 → 匹配引擎 → 构建验证 → 审查）

## 风险
| 风险 | 应对 |
|------|------|
| ColorOS 16 内部类签名因 OTA 变更 | t7 统一封装 hook 工具类，try-catch + Class.forName 回退 |
| Xposed vs LSPosed API 差异 | t2 明确目标框架，固定 compileOnly 依赖版本 |
| 进程间配置同步（XSharedPreferences 限制） | t4 Manifest 正确声明 ro.xposed 标识，按需备选 ContentProvider |
| 白名单/黑名单冲突 | t6 规定白名单优先级高于黑名单 |
| 过度保护导致系统异常 | t11 匹配引擎支持 UID/PID/进程组细粒度控制 |
| 本地构建缺 Android SDK | t19 前确认 SDK 路径，输出产物说明 |
| 合规法律风险 | README 明确仅供学习研究用途 |
