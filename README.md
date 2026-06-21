# Athena — ColorOS 16 后台管理 Xposed 模块

## 概述

ColorOS 16 的 OFreezer 3.0 后台管理系统存在两个典型问题：

- **误杀后台**：系统为了省电，会激进地将用户常用的应用（IM、音乐、网盘等）冻结，导致通知延迟、断连或功能异常。
- **漏杀流氓**：部分应用通过互相唤醒、绑定系统服务等手段逃避冻结，持续占用内存和电量。

**Athena** 是一款基于 [libxposed 现代 API](https://github.com/libxposed/libxposed) 的 Xposed 模块，通过拦截 ColorOS OFreezer 的策略读取路径，将用户自定义的白名单/黑名单注入到系统冻结决策中，实现精确的后台进程管控。

### 工作原理

Athena 的核心机制参考了 [OplusConfigHook](https://github.com/kyaryunha/OplusConfigHook) 项目，具体流程如下：

1. ColorOS 的 `system_server` 进程在启动冻结策略时，通过 `FileInputStream` 读取 `/data/oplus/os/bpm/sys_elsa_config_list.xml`（OFreezer 的配置文件）。
2. Athena 在 `FileInputStream` 的构造器中 Hook 目标路径，拦截文件内容。
3. 拦截后，由 `XmlPolicyBuilder` 解析原始 XML，根据用户配置的 `FreezePolicy` 注入白名单包名（`white_pkg_list`）、强制冻结包名（`ff_pkg_list`）和 IM 保活包名（`im_pkg_list`），并返回修改后的 XML 字节流。
4. 此外，Athena 还可 Hook `/data/oplus/os/bpm/startup/autostart_white_list.txt`（自启动白名单），追加用户白名单条目，防止被 OFreezer 的「自动管理」机制干扰。

对于更高阶的白名单保护，Athena 还提供了 `SystemServiceHooks`，通过调节 OomScoreAdj 等方式辅助保活，降低被 LMK（Low Memory Killer）优先回收的风险。

## 功能

| 功能 | 说明 |
|------|------|
| **白名单保护** | 将指定应用加入 `white_pkg_list`，防止被 OFreezer 冻结，保持后台运行 |
| **IM 保活** | 最高优先级保护，适用于即时通讯类应用；支持自定义心跳超时 |
| **黑名单强制管控** | 将流氓应用加入 `ff_pkg_list`，强制快速冻结，阻止后台偷跑 |
| **自定义冻结超时** | 为单个应用或全局设定冻结超时时长（毫秒），灵活控制冻结策略 |
| **默认策略配置** | 三种模式可选：`跟随系统` / `全部排除` / `全部冻结`，决定未匹配条目的处理方式 |
| **全局开关** | 一键启用/禁用所有策略，方便对比效果或临时恢复系统默认 |
| **日志诊断** | 内置调试日志开关，可在调试页查看当前策略的匹配统计信息 |

### 匹配优先级

```
IM_KEEPALIVE（IM 保活） > WHITELIST（白名单） > BLACKLIST（黑名单） > DefaultPolicy（默认策略）
```

## 环境要求

- **设备**：已解锁 Bootloader 的 ColorOS 16 手机（如 OnePlus、OPPO、realme 机型）
- **Root 方案**：Magisk / KernelSU / APatch
- **Xposed 框架**：**LSPosed v1.9.2**（推荐，需要 libxposed API v102 支持）
- **Android 版本**：Android 14 / 15（ColorOS 16 对应版本）

> 注意：旧版 XposedBridge API 不兼容，必须使用支持 libxposed 现代 API 的框架。

## 安装方法

### 快速安装（使用预编译 APK）

1. 从 [Releases](https://github.com/YOUR_USERNAME/athena/releases) 下载最新版 APK。
2. 安装 APK 到设备。
3. 打开 **LSPosed** 应用，在模块列表中找到 **Athena**，启用模块。
4. **作用域配置**：
   - **必须勾选**：系统框架（`system` / `System Server`）
   - **按需勾选**：需要被白名单/黑名单管控的第三方应用（用于 per-app 进程内 Hook，白名单保护/黑名单强制管控生效于 system_server，勾选第三方应用仅用于未来扩展的进程内 Hook）
5. **重启设备**，或使用 LSPosed 的「软重启」功能。

### 启用状态确认

重启后，通过以下方式确认模块正在工作：

1. 打开 Athena 应用，确保**全局开关**处于开启状态。
2. 在**调试页**查看「当前策略」中是否包含了您的配置条目。
3. 使用 `logcat` 过滤 `Athena` 标签查看模块日志：

```bash
adb logcat -s Athena
```

## 使用说明

### 首页

| 控件 | 说明 |
|------|------|
| 全局开关 | 一键启用/禁用全部策略。关闭时模块进入透传模式，完全跟随系统原始策略，不影响正常使用 |
| 默认策略 | 下拉选择未匹配条目的处理方式：`跟随系统` / `全部排除` / `全部冻结` |
| 默认冻结超时 | 设置全局默认的冻结超时时间（毫秒），影响黑名单中被强制冻结的应用 |
| 默认 IM 心跳超时 | 设置 IM 保活应用的默认心跳间隔（毫秒） |
| 快捷导航 | 白名单 / 黑名单 / 调试页入口 |

### 白名单管理

白名单中的应用将**不会被 OFreezer 冻结**，适合需要常驻后台的应用。

- **添加应用**：点击右下角 FAB → 从已安装应用列表中选择 → 确认
- **选择保护模式**：每个条目可设置为 `WHITELIST`（普通保护）或 `IM_KEEPALIVE`（IM 保活，最高优先级）
- **进程选择**：可指定仅保护特定进程（如 `com.tencent.mm:push`），默认保护全部进程
- **删除**：左滑或点击删除图标移出白名单

#### IM 保活模式

IM 保活模式优先级最高，适用于微信、QQ、Telegram 等即时通讯应用。开启后，Athena 会将对应包名注入 `im_pkg_list`，同时支持自定义心跳超时，确保消息推送及时到达。

### 黑名单管理

黑名单中的应用将被**强制快速冻结**，适合乱唤醒、后台频繁偷跑资源的应用。

- **添加应用**：点击右下角 FAB → 从已安装应用列表中选择
- **自定义冻结超时**（可选）：可为单个条目指定更短的冻结超时（如 15 秒），实现更激进的管控
- **删除**：左滑或点击删除图标移出黑名单

### 调试页

- **当前策略预览**：实时显示合并后的 `FreezePolicy`，包含 whitePkg、ffPkg、imPkg 集合及超时参数
- **匹配统计**：白名单数量、黑名单数量、IM 保活数量、自定义配置数量、缓存有效性签名
- **日志开关**：启用后，所有匹配判定和策略注入都会输出到 Logcat，便于排查问题
- **手动刷新**：强制从 UI 进程重新同步配置到 Hook 进程

## 从源码构建

```bash
# 克隆仓库
git clone https://github.com/YOUR_USERNAME/athena.git
cd athena

# Debug 构建
./gradlew assembleDebug

# Release 构建（启用 R8 混淆）
./gradlew assembleRelease
```

构建产物位于：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

### 开发环境

| 工具 | 版本 |
|------|------|
| Android Studio | Koala / Ladybug 或更高 |
| JDK | 17+ |
| Android SDK | 35 (compileSdk / targetSdk) |
| Gradle | 8.9 (由 wrapper 自动管理) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| libxposed API | 102.0.0 |
| Compose BOM | 2024.10.01 |

## 技术栈

| 类别 | 技术 | 用途 |
|------|------|------|
| **Xposed 框架** | [libxposed API](https://github.com/libxposed/libxposed) v102 | 现代 Xposed 模块接口，支持 system_server Hook 和跨进程配置共享 |
| **Hook 注入** | `FileInputStream` 拦截 + OplusSettings 相关方法 | 注入自定义冻结策略至 OFreezer 3.0 |
| **UI 框架** | Jetpack Compose + Material 3 | 现代化声明式 UI，支持动态主题 |
| **导航** | Navigation Compose | 页面路由（首页 / 白名单 / 黑名单 / 调试 / 关于） |
| **数据序列化** | [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | `AthenaConfig` 等数据模型的 JSON 序列化/反序列化 |
| **持久化** | `SharedPreferences` + JSON | UI 进程写入配置，Hook 进程通过 `XposedService` 跨进程读取 |
| **状态管理** | ViewModel + StateFlow | UI 层响应式数据流 |
| **构建** | Gradle + Version Catalog | `libs.versions.toml` 集中管理依赖版本 |

### 项目架构

项目采用四层架构，自底向上分别为：

```
UI 层（Jetpack Compose）
    ↕ 读写
数据持久化层（LocalConfigRepository / RemoteConfigRepository）
    ↕ 跨进程同步（XposedService）
策略匹配引擎层（PolicyMatcher）
    ↕ 查询
Hook 注入层（OplusConfigHooks / SystemServiceHooks）
    ↓
ColorOS OFreezer 3.0（系统服务）
```

### 核心 Hook 路径

1. **OplusConfigHooks**：拦截 `FileInputStream.<init>(File)`，当路径为 `/data/oplus/os/bpm/sys_elsa_config_list.xml` 时，用 `XmlPolicyBuilder` 注入自定义策略。
2. **XmlPolicyBuilder**：解析原始 XML，按 `FreezePolicy` 注入 whitePkg / ffPkg / imPkg 条目（支持 DOM 和字符串回退两种路径，确保兼容性）。
3. **SystemServiceHooks**（可选增强）：调节 OomScoreAdj/ProcessList，辅助保活。

### 数据流

```
┌──────────────────────────────────────────────┐
│              UI 进程（宿主）                    │
│  HomeScreen → ConfigViewModel                  │
│       → LocalConfigRepository                  │
│       → SharedPreferences (JSON)              │
└───────────────────────┬──────────────────────┘
                        │ 跨进程读取（XposedService）
                        ▼
┌──────────────────────────────────────────────┐
│          Hook 进程（system_server）             │
│  RemoteConfigRepository → PolicyMatcher       │
│       → OplusConfigHooks（拦截 FileInputStream） │
│       → XmlPolicyBuilder（注入修改后的 XML）      │
└──────────────────────────────────────────────┘
```

## 许可与免责

### 许可

```
Copyright (C) 2025 Athena Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### 免责声明

- **仅供学习研究使用**：本模块旨在帮助用户理解和研究 ColorOS 后台管理机制，不得用于商业用途或违反用户协议的行为。
- **遵守当地法律法规**：用户应确保在使用本模块时遵守所在国家或地区的法律法规。Root 和 Xposed 模块的使用可能违反设备制造商的保修条款。
- **使用风险自负**：本模块修改系统运行时的行为，可能导致系统不稳定、应用异常或意外耗电。作者不对因使用本模块造成的任何数据丢失、设备损坏或其他损失承担责任。
- **备份建议**：在安装或更新模块前，建议备份重要数据。首次使用时，建议先在非主力设备上验证稳定性。

---

**Athena** — 让 ColorOS 的后台管理，听你的。
