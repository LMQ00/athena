# DAG 计划：逆向系统 Athena → 优化 SwipeGuard Hook 实现

> **核心目标**：通过本地 MT APK MCP 逆向 **系统 Athena APK**（ColorOS 16 自带的后台进程管理服务），搞清楚其白名单机制与 `killBackgroundProcesses` 路径，然后据此**优化 SwipeGuard 三个 Hook 的拦截逻辑**，使 ColorOS 16 划卡不再杀白名单应用的后台。
>
> **冻结路径已放弃**：用户已用第三方墓碑模块接管 OplusFreeze/PKMS 冻结，所以 OomAdjuster 辅助保活（`SystemServiceHooks.kt`）可移除，本计划不再包含冻结相关代码改动。
>
> **完成标准**：CI `assembleDebug` 通过 + `t3` 逆向报告完整记录系统 Athena 关键类/方法 + `SwipeKillHooks`/`OplusConfigHooks`/`ModuleMain` 按逆向结果优化 + `SystemServiceHooks.kt` 评估后处理。

---

## 0. 前置上下文（已完成）

### 0.1 项目现状
- 仓库：`github.com/LMQ00/athena`（main 分支，HEAD = f78dcf2）
- 最近一次 CI：#22 = **success**（2026-06-21）
- 历史有 4 次连续 compile 失败（#17, #18, #19, #21）已通过 f78dcf2 修复
- 工作区脏文件：仅 `.pi/context/plan.md` 与 `plan.md` 被删（已 stash 在 git 索引中可恢复）
- 用户在主目录有 GitHub token → 5,000 req/h，可触发 workflow_dispatch、下载日志、下载产物

### 0.2 工具能力探测结果
| 能力 | 状态 | 备注 |
|------|------|------|
| 本地 MCP `http://127.0.0.1:8787/mcp` | ✅ 在线 | **已默认指向系统 Athena APK**（从 ColorOS 系统提取），8 个只读工具（open/list/outline_class/read_text/read_zip_bytes/read_resource_values/search/continue） |
| GitHub Token | ✅ 已有 | 5,000 req/h 认证速率；可触发 `workflow_dispatch`、下载日志、下载 artifacts |
| 本地 JDK | ❌ 无 | `which java` 失败；构建只能靠 GitHub Actions |
| 本地 Android SDK | ❌ 无 | `ANDROID_HOME` 空 |
| `gradle-wrapper.jar` | ✅ 存在 | Gradle 8.13，AGP 8.13.2 |
| libxposed 102.0.0 | ✅ 在 libs.versions.toml | 编译期 `compileOnly` |
| 本地逆向工具（apktool/jadx） | ❌ 无 | 只能通过 MCP |
| 第三方墓碑模块 | ✅ 用户已安装 | 接管 OplusFreeze/PKMS 冻结，无需 SwipeGuard 处理冻结路径 |

### 0.3 已完成的 scout 调研
| 文件 | 关键内容 |
|------|---------|
| `.pi/context/scout-ref/01-mt-apk-mcp-usage.md` | MT APK MCP 工具调用格式与示例 |
| `.pi/context/scout-ref/02-libxposed-102-api.md` | libxposed 102 API 关键差异（HookBuilder、Chain、PROTECTIVE、replaceHook） |
| `.pi/context/scout-ref/03-github-actions-api.md` | GitHub Actions REST API 公共/认证访问（**有 token 可用 5,000/h**） |
| `.pi/context/scout-ref/04-coloros-athena-arch.md` | ColorOS 16 / Athena 后台冻结机制、`sys_elsa_config_list.xml` 路径、OShin 参考 |

### 0.4 关键假设（已根据用户澄清更新）
1. **MCP 逆向对象是 ColorOS 系统 Athena APK**（用户从真机/系统提取），不是本项目自身 APK，也不是 ColorOS 通用 `framework.jar`。系统 Athena APK 已在 MCP server 中默认配置好，可直接调用。
2. **冻结由第三方墓碑模块接管**：`SystemServiceHooks.kt` 的 OomAdjuster 辅助保活路径在用户场景下**冗余**，本计划**只评估/移除**，不重写。
3. **构建必须借助 GitHub Actions**：本机无 JDK；用户有 GitHub token，可触发 `workflow_dispatch`、下载 logs/artifacts。
4. **优化不引入破坏性 API 变更**：项目目前编译通过，目标是改进 Hook 拦截准确性而非推倒重来。

---

## 1. 任务 DAG 总览

```
                              ┌────────────────────────────┐
                              │ t0 [scout ✅done]          │
                              │ 网络调研 (01-04)           │
                              └─────────────┬──────────────┘
                                            │
              ┌───────────────────┬─────────┴────────┬──────────────────────┐
              ▼                   ▼                  ▼                      ▼
     ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐  ┌─────────────────┐
     │ t1 [worker]     │ │ t2 [worker]     │ │ t3 [worker]     │  │ t6 [worker]     │
     │ (可选) 触发 CI  │ │ MCP smoke test  │ │ ★ 逆向系统      │  │ 增强 workflow   │
     │ 构建自身 Swipe  │ │                 │ │   Athena APK    │  │ + 产物缓存      │
     │ Guard APK       │ │                 │ │   （核心）      │  │                 │
     └─────────────────┘ └────────┬────────┘ └────────┬────────┘  └────────┬────────┘
                                 │                   │                    │
                                 │                   ▼                    │
                                 │         ┌──────────────────────────────┴──┐
                                 │         │  t3 产出：逆向报告               │
                                 │         │  - killBackgroundProcesses 路径 │
                                 │         │  - 白名单机制（xml 字段含义）   │
                                 │         │  - 候选类名验证 / 新发现类      │
                                 │         └──────────┬────────────────────┘
                                 │                    │
                                 │     ┌──────────────┼──────────────────┬──────────────┐
                                 │     ▼              ▼                  ▼              ▼
                                 │ ┌────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐
                                 │ │t4[impl]│  │ t5 [impl]   │  │ t7 [impl]  │  │ t8 [impl]  │
                                 │ │SwipeKi │  │ OplusConfig │  │ 评估/移除  │  │ ModuleMain │
                                 │ │ llHooks│  │  Hooks     │  │  SystemSrv │  │ 收尾       │
                                 │ │ 优化   │  │  XML 注入   │  │  Hooks     │  │            │
                                 │ └───┬────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
                                 │     │              │                │              │
                                 │     └──────────────┴────────────────┴──────────────┘
                                 │                                    │
                                 │                                    ▼
                                 │                          ┌─────────────────┐
                                 │                          │ t9 [worker]     │
                                 │                          │ AGENTS.md 同步  │
                                 │                          └────────┬────────┘
                                 │                                   │
                                 │                                   ▼
                                 │                          ┌─────────────────┐
                                 │                          │ t10 [worker]    │
                                 │                          │ 验证：CI + 烟测 │
                                 │                          │ 回归            │
                                 │                          └─────────────────┘
                                 │
                                 └──────（t2 失败则阻断 t3，仅此一处依赖）
```

---

## 2. 任务清单

> **代理类型图例**：
> - **scout**：网络/协议/外部资料调研
> - **worker**：多步骤复杂任务（MCP 多轮调用、CI 编排、文档同步、验证）
> - **implementer**：单文件/单类代码修改
>
> 用户特别说明：**不用 coder**，全部由 implementer/worker 承担。

### 任务 t0 — 网络调研（已完成 ✅）

| 字段 | 内容 |
|------|------|
| 代理 | scout |
| 状态 | ✅ DONE |
| 输出 | `.pi/context/scout-ref/{01,02,03,04}-*.md` + `README.md` |
| 设计意图 | 为后续 MCP 逆向、libxposed 编码、CI 监控建立事实基线 |
| 参考 | `01-mt-apk-mcp-usage.md`（MCP 工具）、`02-libxposed-102-api.md`（API 102 行为）、`03-github-actions-api.md`（5,000/h 认证）、`04-coloros-athena-arch.md`（Athena 系统背景） |

---

### 任务 t1 — （可选）触发 CI 构建自身 SwipeGuard APK

| 字段 | 内容 |
|------|------|
| 代理 | worker |
| 依赖 | — |
| 涉及文件 | `.pi/cache/swipeguard-debug.apk`（新建缓存） |
| 状态 | **可选 / 按需执行**：默认跳过；只有当逆向系统 Athena 后需要**对照验证** SwipeGuard 自身代码（如 proguard 影响、模块结构）时才执行 |
| 设计意图 | 备份：万一后续想用 MCP 验 SwipeGuard 自身 APK 形态（类未被混淆、xposed 元数据完整），先拿一个 debug APK |
| 执行要点 | 1) 用本地 GitHub token 调 `POST /repos/LMQ00/athena/actions/workflows/build.yml/dispatches` 触发 `workflow_dispatch`；2) `GET /repos/LMQ00/athena/actions/runs?status=in_progress` 轮询直到 `conclusion=success`；3) `GET /repos/LMQ00/athena/actions/runs/{id}/artifacts` 拿 artifact id → `GET .../zip` 下载（**有 token 不限速**）；4) 解压到 `.pi/cache/swipeguard-debug.apk` |
| 风险 | (a) token 失效 → 回退用 `git commit --allow-empty && git push` 触发 push 事件；(b) CI 失败 → 记录失败原因，**不影响 t3**（t3 独立逆向系统 Athena） |
| 参考 | `03-github-actions-api.md` §5-§6 |

---

### 任务 t2 — 本地 MCP 烟雾测试

| 字段 | 内容 |
|------|------|
| 代理 | worker |
| 状态 | ✅ DONE |
| 实际结果 | MCP `v0.1.0` 在线，workspaceId=`25jitg9b`，默认 APK = `com.oplus.athena` v6.0.1 |
| 依赖 | — |
| 涉及文件 | `.pi/context/mcp-smoke-test.json`（已创建） |
| 设计意图 | 验证 MCP 端点 + 工具 schema + 当前默认指向的 APK 是**系统 Athena**（不是 SwipeGuard 自身），建立调用模板（params/headers/session-id） |
| 执行要点 | 1) `curl -X POST http://127.0.0.1:8787/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"initialize",...}'` 取 `protocolVersion` + 工具白名单；2) `tools/list` 拿完整 schema；3) `mt_apk_open` 不带参数（确认默认 APK 路径），记录 `result.path` 用于 t3；4) `mt_apk_list` 顶层 1-2 层验证 APK 是 `*athena*` 相关的系统 APK 而非本项目 APK；5) 把所有响应保存到 `.pi/context/mcp-smoke-test.json`；6) **关键**：MCP 协议 v2025-06-18 强制 `Accept: application/json, text/event-stream` 双 Accept 头 |
| 风险 | MCP 端点不在线（curl connection refused）→ **阻断 t3**；其他任务不受影响 |
| 参考 | `01-mt-apk-mcp-usage.md` §3、§5 |

---

### 任务 t3 — ★ 逆向系统 Athena APK（核心）

| 字段 | 内容 |
|------|------|
| 代理 | worker |
| 状态 | ✅ DONE |
| 实际结果 | 发现 14 个 Hook 点候选、完整 kill 7 步调用链、白名单 5 层独立体系 + category 编码 |
| 依赖 | t2 |
| 涉及文件 | `.pi/context/reverse-system-athena.md`（已创建） |
| 设计意图 | **本计划核心**：搞清楚 ColorOS 16 系统 Athena 内部如何决定「划卡杀哪个进程」「白名单怎么生效」，把发现落地为后续 t4/t5/t7 的优化指令 |
| 执行要点 | 1) 用 t2 确认的默认 APK 路径 → `mt_apk_open`；2) `mt_apk_list` 看 classes.dex + AndroidManifest.xml + res/xml 全貌，**确认包名是 `com.oplus.athena.*` 或 `oplus.app.*`**（不是 `com.swipeguard.xposed`）；3) `mt_apk_search` 关键词：`killBackgroundProcesses`、`athenaKill`、`whitePkg`、`filter-conf`、`isInWhiteList`、`getProtectPids` 等；4) `mt_apk_outline_class` 抽这些关键类的所有方法签名：Athena 入口 service 类（候选：`OplusAthenaSystemService` / `OplusAthenaAmManager` / `AthenaServiceInternal`）、策略解析类（读 `sys_elsa_config_list.xml` 的 parser）、白名单匹配工具类（包含包名 → 是否在白名单的方法）；5) `mt_apk_read_text` 读 `res/xml/` 下所有 XML 文件，记录 `<whitePkg>` 属性语义（`category="001"` 实际含义）；6) 关键产物「**Hook 点候选表**」：`{类全名, 方法名, 参数类型, 触发时机}` 列表；7) 关键产物「**白名单文件结构**」：`<filter-conf>` 完整 schema + category 取值 + 是否支持嵌套规则；8) 输出结构化报告：`✅/❌ 表格 + 关键发现 + Hook 优化建议（喂给 t4/t5/t7）+ AthenaKillHooks 候选类名校验结论` |
| 风险 | (a) MCP 端点挂 → 整任务阻塞；(b) 系统 Athena APK 已被 ProGuard 深度混淆 → 方法名无意义，备选方案：根据 dex 字符串常量（`killBackgroundProcesses` 等）反查调用点；(c) 类名与 `04-coloros-athena-arch.md` 推测不一致 → 以本次实际结果为准，更新文档 |
| 参考 | `01-mt-apk-mcp-usage.md` 工具表；`04-coloros-athena-arch.md` §1、§2 |

---

### 任务 t4 — SwipeKillHooks 优化（基于 t3 逆向结果）

| 字段 | 内容 |
|------|------|
| 代理 | implementer |
| 状态 | ✅ DONE |
| 实际结果 | 新增 `hookForceStopPackageAndSaveActivity()` 第三 Hook 路径，拦截 Athena 实际 kill 执行点 |
| 依赖 | t3 |
| 涉及文件 | `app/src/main/java/com/swipeguard/xposed/hook/SwipeKillHooks.kt`（修改） |
| 设计意图 | 当前 SwipeKillHooks 只 hook `killBackgroundProcesses` + `forceStopPackage` 两方法；按 t3 发现的真实 `killBackgroundProcesses` 路径（包括变体重载、参数签名差异）补全 hook 点；按 t3 发现的实际调用时机决定是否加 `chain.proceed()` 之后的二次校验 |
| 执行要点（具体以 t3 输出为准） | 1) **核实方法签名**：用 t3 的真实类+方法签名替换当前的 `firstOrNull { m -> m.name == ... && m.parameterCount >= 1 && ... }` 模糊匹配，改为按完整参数类型精确匹配 `Class.forName("...").getDeclaredMethod("killBackgroundProcesses", String::class.java, Int::class.javaPrimitiveType, ...)`；2) **补充遗漏 hook 点**：若 t3 发现 `athenaKill*` 系列方法也走 AMS 调用入口但被 SwipeKillHooks 当前实现漏掉，**把 t3 发现的精确方法签名前置**到 `hookKillBackgroundProcesses()` 内部；3) **去除 AthenaKillHooks 重复**：若 t3 表明 `OplusAthenaSystemService.athenaKill` 是 ColorOS 在 AMS 内部的「调用入口」、最终仍转回 `killBackgroundProcesses`，则**保留两路 hook 但加上 INFO 日志说明**「双路保险，都指向同一目标」；若 t3 表明两路**互不重叠**（athenaKill 不经过 AMS），则保留 AthenaKillHooks 原状；4) **保持 PROTECTIVE 模式 + null return 语义不变**（保护包名时 `return@intercept null`）；5) **配置可见性**：保持 `private var config` + `syncConfig(repo)` 接口（与 t5 的热更新对齐） |
| 风险 | (a) 反射方法签名猜错 → 抛 `NoSuchMethodException`；当前已有 try-catch + WARN 日志，不影响 system_server；(b) 与 AthenaKillHooks 职责重叠 → 决定保留/移除见要点 3 |
| 参考 | `02-libxposed-102-api.md` §3-§5；`04-coloros-athena-arch.md` §1 |

---

### 任务 t5 — OplusConfigHooks：XML 注入策略优化

| 字段 | 内容 |
|------|------|
| 代理 | implementer |
| 状态 | ✅ DONE |
| 实际结果 | 真 XML parser + 去重 + 可配置 category + 3 WeakHashMap 合并为 StreamState |
| 依赖 | t3 |
| 涉及文件 | `app/src/main/java/com/swipeguard/xposed/hook/OplusConfigHooks.kt`、`XmlPolicyBuilder.kt`、`SwipeGuardConfig.kt`（修改） |
| 设计意图 | 当前 `XmlPolicyBuilder` 用 `lastIndexOf("</filter-conf>")` 字符串切片，且只生成 `<whitePkg name="..." category="001"/>` 单一格式；按 t3 发现的 `<whitePkg>` 真实属性 schema（`category` 取值、是否有 `uid`、`reason` 等）修正 XML 格式；按 t3 发现的真实读取路径（确认仍是 `FileInputStream` 拦截 + OplusSettings 拦截两路）决定是否需要加更多入口 |
| 执行要点（具体以 t3 输出为准） | 1) **修正 XML 格式**：用 t3 发现的真实属性替换 `category="001"` 硬编码；若发现 `category` 有多个有效值（如 `001`/`002`/`003` 分别对应不同优先级），在 `SwipeGuardConfig` 加 `whitelistCategory: String = "001"`，让用户可选；2) **改用真 XML parser**：引入 `javax.xml.parsers.DocumentBuilderFactory`（Android 内置无新依赖），避免字符串切片丢系统原条目；解析失败时 fallback 到当前字符串方案 + WARN 日志；3) **白名单去重**：t3 若发现系统中已有同名 `<whitePkg>` 条目，新代码需检测并**跳过重复追加**（避免运行后 XML 越来越大）；4) **3 个 WeakHashMap 合并**：将 `hijackedStreams` + `streamCursor` + `streamMark` 合并为 `Map<FileInputStream, StreamState>` 减少 3 次 synchronized 查询（详见旧 t8，但**只在 t3 验证劫持场景存在 mark/reset 需求时**才实施，否则保留 3 map 避免过度重构）；5) **OplusSettings hook 维持原样**：当前 `OplusSettings.readConfig` 拦截 autostart 白名单已 OK；只有 t3 发现 ColorOS 实际用别的 API 读此文件时才改 |
| 风险 | (a) `javax.xml` 在 system_server 早期不可用 → fallback 到 `android.util.Xml`；(b) XML 格式改坏 → fallback 路径必须保留原字符串方案；(c) 去重逻辑写错导致白名单元数据被反复追加 → 加单元测试 |
| 参考 | `04-coloros-athena-arch.md` §2（XML 结构）；`02-libxposed-102-api.md` §4 |

---

### 任务 t6 — 增强 `.github/workflows/build.yml` + 产物缓存

| 字段 | 内容 |
|------|------|
| 代理 | worker |
| 状态 | ✅ DONE |
| 实际结果 | 已添加 concurrency、Gradle cache、7 天 retention、APK summary、dispatch audit |
| 依赖 | — |
| 涉及文件 | `.github/workflows/build.yml`（修改） |
| 设计意图 | 利用现有 GitHub token 完善 CI 能力：让 `assembleDebug` 产物稳定可下载、暴露更多调试信息、加快迭代 |
| 执行要点 | 1) 启用 Gradle 缓存：`actions/setup-java@v4` + `actions/cache@v4`（缓存 `~/.gradle/caches` 和 `~/.gradle/wrapper`）；2) artifact 保留期改 7 天（默认 90 太长）；3) 增加 `summary` step 输出 APK 路径、size、SHA256；4) 增加 `concurrency` 字段：同分支取消旧 run；5) 触发 `workflow_dispatch` 时输出调用者信息便于审计；6) **不**改 trigger 条件（保持 push/PR 触发） |
| 风险 | 无破坏性；lint 暂不引入（避免暴露大量历史问题） |
| 参考 | `03-github-actions-api.md` §5 |

---

### 任务 t7 — 评估并移除 `SystemServiceHooks.kt`

| 字段 | 内容 |
|------|------|
| 代理 | implementer |
| 状态 | ✅ DONE |
| 实际结果 | 整文件删除 + ModuleMain 清理 + 4 处 AGENTS.md 标记待处理 |
| 依赖 | t3（间接） |
| 涉及文件 | `app/src/main/java/com/swipeguard/xposed/hook/SystemServiceHooks.kt`（**已删除**）、`ModuleMain.kt`（清理） |
| 设计意图 | 用户的第三方墓碑模块已接管 OplusFreeze/PKMS 冻结，SwipeGuard 的 `SystemServiceHooks.applyOomAdjLocked` 辅助保活在用户场景下**完全冗余**；保留它会与第三方墓碑产生不必要的 LMK 行为冲突，且反射 `ProcessRecord` 字段是 AOSP 内部 API 易随系统升级失效 |
| 执行要点 | 1) **删除** `app/src/main/java/com/swipeguard/xposed/hook/SystemServiceHooks.kt`；2) **从 `ModuleMain.kt` 移除**：`systemServiceHooks` 字段、`install` 调用、`syncHooks` 中对应行；3) **不删除** `OomAdjuster.applyOomAdjLocked` 的相关调研文档（保留在 `.pi/context/scout-ref/` 备查）；4) 提交信息：明确「移除冻结相关辅助保活，由用户已安装的第三方墓碑模块接管」；5) **如果未来用户取消第三方墓碑**，代码可通过 git history 一键回滚 |
| 风险 | (a) 误删导致编译失败 → 同步改 `ModuleMain.kt` 即修复；(b) 用户期望保留兜底 → 提交前在 plan.md / AGENTS.md 明确说明「已评估决定移除」 |
| 参考 | `04-coloros-athena-arch.md` §1（freezer vs kill 两条路径） |

---

### 任务 t8 — ModuleMain 收尾：确保 3 Hook 配合正确

| 字段 | 内容 |
|------|------|
| 代理 | implementer |
| 状态 | ✅ DONE |
| 实际结果 | tryInstall 辅助函数 + install 顺序注释 + syncHooks 接 updateConfig + OplusConfigHooks currentConfig 字段 |
| 依赖 | t4, t5, t7 |
| 涉及文件 | `app/src/main/java/com/swipeguard/xposed/hook/ModuleMain.kt`、`OplusConfigHooks.kt`（修改） |
| 设计意图 | t4/t5/t7 完成后，ModuleMain 的 install 顺序、`syncHooks` 调用、错误日志格式需要统一收尾，确保 3 个 Hook（`OplusConfigHooks` + `SwipeKillHooks` + 移除后的 `AthenaKillHooks` 视情况）配合无误 |
| 执行要点 | 1) **install 顺序**：保持当前 `OplusConfigHooks` → `SwipeKillHooks` → `AthenaKillHooks`（OplusConfig 先注入白名单，SwipeKill 再拦截 kill 调用，形成「配置→拦截」闭环）；2) **抽公共函数**：新增 `private inline fun tryInstall(name: String, block: () -> Unit)`，统一错误格式（「`$name install failed`」）；3) **`syncHooks` 同步**：保持 `OplusConfigHooks` 为 `object` 不通过 syncConfig 刷新的设计（t5 引入的 `updateConfig(config)` 由 ModuleMain 显式调用），**注意 t4/t5 若引入热更新机制需在此处接线**；4) **AthenaKillHooks 处理**：若 t4 决定保留 AthenaKillHooks（与 SwipeKillHooks 并行），则保留现有 install + syncConfig 流程；若 t4 决定移除（t3 证明冗余），则同步删除 `athenaKillHooks` 字段及 install/syncHooks 中的对应行（**此决策属 t4 范畴，t8 跟随执行**）；5) **顶层 try-catch 兜底保留** |
| 风险 | (a) 内联函数 + 闭包捕获 `this/param` 影响 IDE 高亮 → 不影响编译；(b) install 顺序错乱导致白名单未生效前已拦截 kill → 写注释说明顺序 |
| 参考 | `02-libxposed-102-api.md` §2（HookBuilder 风格） |

---

### 任务 t9 — AGENTS.md 同步

| 字段 | 内容 |
|------|------|
| 代理 | worker |
| 状态 | ✅ DONE |
| 实际结果 | 6 类同步：文件树/架构图/Hook 表/配置同步/MCP 逆向章节/冻结说明 + whitelistCategory 补全 |
| 依赖 | t4, t5, t7, t8 |
| 涉及文件 | `AGENTS.md`（修改） |
| 设计意图 | 文档与代码现状脱节：AGENTS.md 列 3 个 Hook（含 `SystemServiceHooks`），实际 t7 后只剩 2-3 个（`SwipeKillHooks` + `OplusConfigHooks` + 可选 `AthenaKillHooks`）；AGENTS.md 提 `assets/module.prop`（实际在 `resources/META-INF/xposed/`） |
| 执行要点 | 1) 修正 `META-INF/xposed` 路径描述（实际在 `app/src/main/resources/META-INF/xposed/`，不是 `assets/`）；2) **Hook 列表更新**：移除 `SystemServiceHooks` 章节；保留/更新 `AthenaKillHooks` 描述（按 t8 决策）；3) 修正「核心机制」摘要：「3 个 Xposed Hook」改为「N 个 Xposed Hook」并说明各路径分工；4) 新增「## MCP 集成与 CI」章节引用 `.pi/context/` 路径与 t3 逆向结论；5) 新增「## 冻结路径说明」章节：**说明 SwipeGuard 不处理冻结，由用户第三方墓碑模块接管**（与 t7 对应）；6) 删除过时的 `WhitelistScreen` 引用（已删） |
| 风险 | 无 |
| 参考 | t4/t5/t7/t8 的输出 |

---

### 任务 t10 — 验证：CI + 烟测回归

| 字段 | 内容 |
|------|------|
| 代理 | worker |
| 状态 | ✅ DONE |
| 实际结果 | CI #27976887217 success + MCP 在线 + verify-report.md 已输出 |
| 依赖 | t6, t7, t8, t9 |
| 涉及文件 | `.pi/context/verify-report.md`（已创建） |
| 设计意图 | 闭环验证：所有改动已落 → CI 通过 → MCP 烟测确认未破坏现有 Hook 结构 → 输出最终状态 |
| 执行要点 | 1) `git add -A && git commit -m 'optimize: t3-t9 逆向系统 Athena 后的 Hook 优化'` 触发 CI；2) 用 GitHub token 调 `GET /repos/LMQ00/athena/actions/runs?per_page=1` 轮询最新 run 直到 `conclusion=success`（**有 token 不限速**）；3) 失败则 `GET /repos/LMQ00/athena/actions/runs/{id}/logs` 拿 zip 日志（**有 token 可下载**），定位失败任务回滚；4) 成功则**重跑 t2 烟测**确认 MCP 端点仍正常（防止修改期间 MCP 挂了）；5) 输出 `verify-report.md`：`{"build": "success", "ci_run_id": N, "hook_files": [...], "system_service_hooks_removed": true, "reverse_report": "reverse-system-athena.md", "files_modified": [...]}` |
| 风险 | CI 失败需要迭代修复 → 留出最多 3 轮迭代预算 |
| 参考 | `03-github-actions-api.md`、t2 烟测输出 |

---

## 3. 关键路径（Critical Path）

```
t0 → t2 → t3 → {t4, t5} → t8 → t9 → t10
```

- **t0 → t2 → t3 是阻塞链**：必须先确认 MCP 在线才能逆向系统 Athena
- **t3 是分叉点**：t4/t5/t7 三个 implementer 任务均依赖 t3 的逆向结论
- **t8 收尾**：t4/t5/t7 都完成后才能改 ModuleMain
- **t9/t10 收尾**：文档 + 验证

**总并行机会**：
- t1（可选）/t2/t6 完全独立
- t4 / t5 / t7 三个 implementer 任务**严格并行**（互不修改同一文件）
- t8 等待 t4/t5/t7 完成后开始
- t9 等待 t4-t8
- t10 最后串行

---

## 4. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| MCP 端点 `127.0.0.1:8787` 不在线 | 低 | 阻断 t2/t3 | t1/t6 之前先 ping；不在线则跳过 MCP，t3 改用静态分析（`04-coloros-athena-arch.md` 已知信息） |
| MCP 默认指向的 APK 不是系统 Athena | 低 | t3 阻断 | t2 烟测时显式验证 `mt_apk_open` 后的 `result.path`/`result.package` 字段；若不对则尝试 `mt_apk_open` 显式传 ColorOS 系统 APK 路径 |
| 系统 Athena APK 深度混淆 | 中 | t3 难读 | 备选：根据 dex 字符串常量（`killBackgroundProcesses`/`athenaKill` 等）反查调用点；用 `mt_apk_read_text` 读 resources 目录的 XML 仍然可读 |
| GitHub token 失效 | 低 | t1/t6/t10 阻断 | token 从 `~/.config/gh/hosts.yml` 或环境变量读取时增加错误处理；fallback 改用 `git push` 触发 CI |
| 用户场景不需要 `AthenaKillHooks`（与 `SwipeKillHooks` 重复） | 中 | t8 决策复杂度 | 由 t3 实际结果决定，t4 实现时给出明确判断，t8 跟随执行 |
| `SystemServiceHooks` 移除后用户反悔 | 低 | 需要回滚 | git history 一键恢复；AGENTS.md 明确说明移除原因 |
| XML parser 在 system_server 早期不可用 | 低 | t5 失败 | fallback 用 `android.util.Xml`；最差保留字符串方案 + 警告 |
| 第三方墓碑模块升级后行为变化 | 低 | SwipeGuard 不受影响 | 第三方墓碑与 SwipeGuard 职责正交（一个管冻结、一个管 kill），不依赖 |

---

## 5. 选择依据（为什么用 DAG 而非 Simple）

| 因素 | 评估 |
|------|------|
| 文件数 | 10+ 个文件改动（5-6 个 Kotlin + 1 个 yaml + 3-4 个新建 markdown） |
| 并行机会 | t1/t2/t6 完全独立；t4/t5/t7 三个 implementer 任务严格并行；t8/t9/t10 串行收尾 |
| 步骤数 | 11 个任务（vs 旧版 19 个）→ 减少 42% |
| 不确定性 | 需验证 MCP 默认 APK 身份、t3 逆向发现的方法签名、t4 对 AthenaKillHooks 保留/移除的决策 |
| **决策** | **DAG**（worker 编排 + implementer 并行） |

---

## 6. 假设清单（执行前需确认）

1. **MCP 默认指向系统 Athena APK**：t2 烟测时确认 `result.package` 包含 `oplus` 或 `athena` 字样
2. **GitHub token 在主目录有备份**：从 `~/.config/gh/hosts.yml` 或 `~/.netrc` 读取；或由用户临时提供
3. **接受 release 验证延后**：本轮只验证 debug 通过
4. **`SystemServiceHooks` 移除不可逆决策**：AGENTS.md 明确说明，用户可随时 git revert
5. **AthenaKillHooks 保留/移除由 t3 决定**：执行 t4 时根据 `athenaKill` 真实路径是「独立调用」还是「转发到 AMS」做最终判断
6. **不引入新依赖**：`javax.xml.parsers` / `android.util.Xml` 是 Android 内置，无需改 `libs.versions.toml`

---

## 7. 成功标准

- ✅ 全部 11 个任务状态 DONE（t0 已是 DONE，t1 默认跳过）
- ✅ GitHub Actions 最新 run `conclusion=success`（有 token 可下载日志确认）
- ✅ `.pi/context/reverse-system-athena.md` 完整记录：系统 Athena 关键类/方法签名 + 白名单 XML 真实 schema + Hook 点候选表
- ✅ `SwipeKillHooks` 按 t3 结果补全 hook 点（精确方法签名替代模糊匹配）
- ✅ `OplusConfigHooks` 的 XML 格式与系统实际一致（`category` 取值正确 + 不丢原条目 + 去重）
- ✅ `SystemServiceHooks.kt` 已删除，`ModuleMain` 已清理
- ✅ `AGENTS.md` 与代码现状一致（路径修正 + Hook 列表更新 + 冻结路径说明）
- ✅ `verify-report.md` 输出最终状态摘要
