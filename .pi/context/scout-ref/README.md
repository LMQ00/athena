# Scout 调研结果目录

本目录保存为 athena 项目 MCP 逆向 / 优化 / CI 调试任务预先做的网上调研。

## 文件清单

| 文件 | 主题 | 调研时间 |
|------|------|----------|
| `01-mt-apk-mcp-usage.md` | MT APK MCP 工具调用格式与示例 | 2026-06-23 |
| `02-libxposed-102-api.md` | libxposed 102 API 关键差异 | 2026-06-23 |
| `03-github-actions-api.md` | GitHub Actions REST API 公共访问 | 2026-06-23 |
| `04-coloros-athena-arch.md` | ColorOS 16 / Athena 后台冻结机制 | 2026-06-23 |

## 使用方式

由 scout 代理通过 fetch 拉取真实网页内容并整理，**不修改项目源码**。
被 DAG 计划中各任务引用作为前置调研输入。
