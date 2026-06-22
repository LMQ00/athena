# 主题 1: MT APK MCP 工具使用与示例

## 摘要

原指定仓库 `MegatronKing/MegatronApkTool` 返回 404（不存在），但搜索发现最相关的开源 APK MCP 服务器是 `zinja-coder/apktool-mcp-server`（479 stars），提供 13 个工具用于 APK 反编译、Smali 代码分析、资源操作等。此外也存在若干其他 Android 逆向 MCP 项目。

## 关键发现

### 1. 原指定 URL 不可用
- **来源**: https://github.com/MegatronKing/MegatronApkTool → **404 Not Found**
- 该仓库可能已被删除、改名或设置为私有。GitHub 用户 MegatronKing（Reqable 创始人）名下 71 个公开仓库中不包含此项目。

### 2. 最相关的替代项目: `zinja-coder/apktool-mcp-server`
- **来源**: https://github.com/zinja-coder/apktool-mcp-server
- 基于 Python/FastMCP，包装 apktool 命令行工具，提供 MCP 接口
- 当前定义的工具列表（13 个）:

| 工具名 | 功能 |
|--------|------|
| `decode_apk()` | 解码 APK 文件，提取资源和 smali |
| `build_apk()` | 从解码项目重建 APK |
| `get_manifest()` | 获取 AndroidManifest.xml 内容 |
| `get_apktool_yml()` | 获取 apktool.yml 信息 |
| `list_smali_directories()` | 列出所有 smali 目录 |
| `list_smali_files()` | 列出特定 smali 目录中的文件（可按包名前缀过滤） |
| `get_smali_file()` | 按类名获取 smali 文件内容 |
| `modify_smali_file()` | 修改 smali 文件内容 |
| `list_resources()` | 列出资源文件（可按资源类型过滤） |
| `get_resource_file()` | 获取特定资源文件内容 |
| `modify_resource_file()` | 修改资源文件内容 |
| `search_in_file()` | 在指定扩展名的文件中搜索模式 |
| `clean_project()` | 清理项目目录准备重新构建 |

### 3. 参数模式示例（基于源代码分析）
- **来源**: https://github.com/zinja-coder/apktool-mcp-server/blob/master/apktool_mcp_server.py
- 大多数工具接受 `project_name: str` 作为首个参数
- `list_smali_files(project_name: str, package_prefix: str = "")` — 支持可选的包名前缀过滤
- `get_smali_file(project_name: str, class_name: str)` — 通过完整类名获取文件
- `search_in_file(project_name: str, pattern: str, extensions: str = "smali,xml")` — 按正则搜索
- `list_resources(project_name: str, resource_type: str = "")` — 按类型过滤 (layout, drawable 等)
- **注意**: 工具名格式为 `snake_case`，与用户问题中提到的 `mt_apk_xxx` 命名风格类似但不同

### 4. 其他相关 Android RE MCP 项目
- `dPhoeniixx/dJEB_mcp_server` — 基于 JEB 反编译器的 MCP 服务器（22 stars）
- `pullkitsan/mobsf-mcp-server` — 基于 MobSF API 的 APK/IPA 分析 MCP 服务（20 stars）
- `1600822305/APK-Editor-MCP-Server` — APK 编辑 MCP 服务

### 5. 对 `mt_apk_*` 工具名的推断
- 用户提到的 `mt_apk_open`, `mt_apk_list`, `mt_apk_outline_class`, `mt_apk_read_text`, `mt_apk_read_zip_bytes`, `mt_apk_read_resource_values`, `mt_apk_search`, `mt_apk_continue` 这些工具名在 GitHub 上未找到公开仓库。
- "MT" 可能指 **MT Manager**（MT管理器，bin.mt.plus 开发的知名 Android APK 修改工具），它内置了 MCP Server 功能。
- 这些工具可能是 MT Manager 内部 MCP Server 暴露的接口，其文档可能仅在应用内或中文社区中流传。

## 相关链接
- `https://github.com/zinja-coder/apktool-mcp-server` — APKTool MCP Server（最佳替代）
- `https://github.com/dPhoeniixx/dJEB_mcp_server` — JEB MCP Server
- `https://github.com/pullkitsan/mobsf-mcp-server` — MobSF MCP Server
- `https://github.com/zinja-coder/jadx-ai-mcp` — JADX AI MCP（同一作者的另一项目）
