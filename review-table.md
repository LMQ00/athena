# Loop Review — Athena Xposed 模块

| ID | 文件位置 | 来源 | 问题描述 | 严重级别 | 修复建议 | 状态 |
|----|---------|------|---------|--------|---------|------|
| R-001 | `AppListItem.kt`（新增）+ `WhitelistScreen.kt` | 表格1 | 黑名单 Screen 引用了不存在的 `components.AppListItem` — `AppListItem` 在 `WhitelistScreen.kt:231` 声明为 `private`，跨文件无法 import | P0 | 提取到 `ui/components/AppListItem.kt` 声明为 `public` | review后判断已解决 |
| R-002 | `AddAppSheet.kt:80-83` + `BlacklistScreen.kt:157-170` | 表格1 | AddAppSheet 参数签名不匹配 — 函数体内引用了未声明的 `defaultMode`(line 110,178) 和 `showSystemApps`(line 108)，但形参未补 | P0 | 在 `AddAppSheet` 形参中补 `defaultMode: ProtectionMode = WHITELIST` 和 `showSystemApps: Boolean = false` | review后判断已解决 |
| R-003 | `BlacklistScreen.kt:172-180` | 表格1 | EditEntryDialog 参数名 `onConfirm` 应为 `onSave`，遗漏 `onDelete` | P0 | 改为 `onSave` + `onDelete` | review后判断已解决 |
| R-004 | `BlacklistScreen.kt:159-165, 245-252` | 表格1 | AppListItem 参数签名不匹配，BlacklistScreen 传入 `trailingContent/onLongClick` | P0 | 重构 AppListItem 为公共组件 | review后判断已解决 |
| R-005 | `ConfigViewModel.kt:99-109` | 表格1 | `update()` 读-改-写存在竞态，并发丢失 write | P0 | 用 `Mutex` 串行化 | review后判断已解决 |
| R-006 | `ConfigViewModel.kt:50-53` | 表格1 | `object` 单例用 `viewModelScope` 语义错误 | P0 | 改用应用级 `CoroutineScope` | review后判断已解决 |
| R-007 | `OplusConfigHooks.kt:180-194` | 表格1 | PROTECTIVE 模式吞异常 | P0 | 构造器走 `NEUTRAL` 模式 | review后判断已解决 |
| R-008 | `OplusConfigHooks.kt:248-355` | 表格1 | 漏掉 `available/skip/mark/reset` 等 Hook | P0 | 补全 6 个 hook | review后判断已解决 |
| R-009 | `OplusConfigHooks.kt:404-417` | 表格1 | `serveHijacked` TOCTOU 竞态 | P0 | 合并 synchronized 块 | review后判断已解决 |
| R-010 | `OplusConfigHooks.kt:118-147` | 表格1 | `readConfig` PROTECTIVE 吞 IOException | P0 | 改用 `NEUTRAL` 模式 | review后判断已解决 |
| R-011 | `PolicyMatcher.kt:130-150` | 表格1 | 自定义超时被错误全局化 | P0 | 移除全局合并，per-package 通过 `getFreezePolicy()` | review后判断已解决 |
| R-012 | `AthenaConfig.kt:48-53` | 表格1 | Json 实例不一致（compact vs pretty） | P1 | 统一为 compact | review后判断已解决 |
| R-013 | `AthenaConfig.kt:74-85` | 表格1 | `fromJson`/`toJson` 与 `JsonCodec` 行为不一致 | P1 | `@Deprecated` 引导迁移 | review后判断已解决 |
| R-014 | `OplusConfigHooks.kt:188-189` | 表格1 | 路径匹配逻辑冗余 | P1 | 直接 `path != ELSA_CONFIG_PATH` | review后判断已解决 |
| R-015 | `OplusConfigHooks.kt:36-58, 392-462` | 表格1 | WeakHashMap 锁与 map 假设 | P1 | 统一用 `synchronized(hijackedStreams)` | review后判断已解决 |
| R-016 | `SystemServiceHooks.kt:201-217` | 表格1 | 缓存字段非 volatile | P1 | 加 `@Volatile` | review后判断已解决 |
| R-017 | `LocalConfigRepository.kt:58-66` | 表格1 | @Synchronized 自我重入风险 | P1 | 监听器内独立解析 | review后判断已解决 |
| R-018 | `JsonCodec.kt:36-45` & `LocalConfigRepository.kt:35-50` | 表格1 | 解析失败静默回退到 DEFAULT → 数据丢失 | P1 | 异常时备份 JSON + Log.e | review后判断已解决 |
| R-019 | `XmlPolicyBuilder.kt:159-167` | 表格1 | XXE 防御静默失败 | P1 | 改用 `setAttribute` + `isExpandEntityReferences` | review后判断已解决 |
| R-020 | `XmlPolicyBuilder.kt:289-303` | 表格1 | `lastIndexOf("</")` 注入脆弱 | P1 | 改匹配 `</elsa_config>` | review后判断已解决 |
| R-021 | `EntrySet.kt:32-39` | 表格1 | `entries: MutableList` 公有字段绕过索引 | P1 | 改为 `private` 只读视图 | review后判断已解决 |
| R-022 | `AppEntry.kt:42-46, 67-70` + `PolicyMatcher.kt:104-108` | 表格1 | `customFreezeTimeoutMs = 0` 被误判为"未设置"，用户设"立即冻结"无效 | P1 | 全部改用 `Long?`（null=不覆盖），或在 AppEntry 中改用 `null` 字段 | review后判断已解决 |
| R-023 | `HomeScreen.kt:233-273` | 表格1 | 超时输入框无法清空 | P1 | 空输入不回写 | review后判断已解决 |
| R-024 | `WhitelistScreen.kt:107-110, 119-128` | 表格1 | `showSystemApps` 切换无标识提示 | P1 | 增加"含系统应用"徽标/提示 | review后判断已解决 |
| R-025 | `AddAppSheet.kt:174-180` | 表格1 | `onSelect` 时序问题 | P1 | 先读后赋值 | review后判断已解决 |
| R-026 | `WhitelistScreen.kt:188-201` | 表格1 | 编辑条目改 mode 后不迁移列表，条目进入死区 | P1 | 保存时检查 mode 一致性并迁移 | review后判断已解决 |
| R-027 | `ModuleMain.kt:102-116` | 表格1 | observeChanges 回调无 JSON 健全性校验 | P1 | 增加 runCatching 校验 | review后判断已解决 |
| R-028 | `ConfigViewModel.kt:55-91` | 表格1 | init 触发两次状态写入 | P1 | 加 `initialLoading` 标志 | review后判断已解决 |
| R-029 | `proguard-rules.pro:7-13` | 表格1 | XposedModule keep 规则不完整 | P1 | 扩展至所有回调方法 | review后判断已解决 |
| R-030 | `proguard-rules.pro:16-25` | 表格1 | 缺少 kotlinx.serialization keep 规则 | P1 | 添加 serializer 保留规则 | review后判断已解决 |
| R-031 | `build.gradle.kts/proguard-rules.pro` | 表格1 | 资源压缩误删反射资源 | P1 | 加 `-keep class **.R$*` | review后判断已解决 |
| R-032 | `WhitelistScreen.kt:70` | 表格2 | `@OptIn(ExperimentalFoundationApi::class)` 未 import `ExperimentalFoundationApi` | P0 | 删除残留 `@OptIn` 行或加 import | review后判断已解决 |
| R-033 | `WhitelistScreen.kt:211-212` | 表格2 | 孤儿 KDoc 块吞掉 `buildTabs` 文档 | P3 | 删除残留 `/**` 块 | 未解决 |
| R-034 | `HomeScreen.kt:205-211, 233-242` | 表格2 | DefaultFreezeTimeoutField 重复孤儿 KDoc + 文档与实现不符 | P3 | 删除重复 KDoc，对齐文档与实现 | 未解决 |
