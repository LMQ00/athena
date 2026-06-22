# 主题 3: GitHub Actions API 公共访问

## 摘要

GitHub Actions REST API (v2026-03-10) 允许通过 `GET /repos/{owner}/{repo}/actions/runs` 等端点列出和查看 workflow run 信息。公共仓库上的只读操作**不需要认证**，但受限于来自同一 IP 60 req/h 的匿名速率限制。构建日志下载需要认证且有额外权限要求。

## 关键发现

### 1. `GET /repos/{owner}/{repo}/actions/runs` — 公共仓库无需 token
- **来源**: https://docs.github.com/en/rest/actions/workflow-runs#list-workflow-runs-for-a-repository
- 文档明确说明: **"Anyone with read access to the repository can use this endpoint."**
- 对于**公共仓库**，无需 token 即可访问（匿名请求）
- 对于**私有仓库**，需要 OAuth token 或 PAT 且具有 `repo` scope
- 该端点每次最多返回 **1000 条结果**（当使用 `actor`, `branch`, `event`, `status` 等过滤参数时）
- Path 参数: `owner` (string, required), `repo` (string, required)

### 2. 速率限制
- **来源**: https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- **匿名请求**: **60 次/小时**（基于来源 IP）
- **已认证请求 (PAT/OAuth)**: **5,000 次/小时**
- **GitHub Enterprise Cloud 用户/应用**: 最高 15,000 次/小时
- **Git LFS 请求**: 300 次/分钟（匿名）/ 3,000 次/分钟（已认证）
- **Search API 更严格**: 独立于主速率限制，有更低的限制
- 超过限制后 API 返回 `403` 或 `429`，响应头包含 `X-RateLimit-Remaining: 0`

### 3. 获取 build log 的限制
- **来源**: https://docs.github.com/en/rest/actions/workflow-runs#download-workflow-run-logs
- **`GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs`** — 下载 workflow run 日志
- 此端点**需要认证**（不能匿名访问），即使对公共仓库
- 需要 **"Actions" 仓库权限 (read)** — 使用 fine-grained PAT 时需要明确授予
- 使用 OAuth app 或 classic PAT 时需要 `repo` scope
- 注意：**日志归档为 zip 格式**，包含所有 job 的日志文件
- 也有按 attempt 下载的变体: `GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}/logs`

### 4. `workflow_dispatch` 触发条件
- **来源**: https://docs.github.com/en/rest/actions/workflows
- 通过 **`POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches`** 触发
- 需要 **"Actions" 仓库权限 (write)**（不能匿名触发）
- Body 参数: `ref` (string, required — 分支/tag 名) 和可选的 `inputs` (object)
- 触发后返回 `204 No Content`
- 工作流 YAML 中必须定义 `on: workflow_dispatch:` 才能接收此触发
- 对于 `workflow_dispatch` 的标准限制：**无额外频率限制**，但受限于主 API 速率限制

### 5. 其他关键端点
- `GET /repos/{owner}/{repo}/actions/runs/{run_id}` — 获取单次 run 详情（需读权限）
- `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs` — 获取 run 中所有 jobs
- `POST /repos/{owner}/{repo}/actions/runs/{run_id}/cancel` — 取消 run（需写权限）
- `POST /repos/{owner}/{repo}/actions/runs/{run_id}/rerun-failed-jobs` — 重试失败 job
- `DELETE /repos/{owner}/{repo}/actions/runs/{run_id}/logs` — 删除日志

### 6. 认证方式对比
| 认证方式 | 速率限制 | 公共仓库只读 | 日志下载 | workflow_dispatch |
|---------|---------|------------|---------|------------------|
| 无认证 | 60/h | ✅ | ❌ | ❌ |
| Classic PAT | 5,000/h | ✅ | ✅ (需repo scope) | ✅ (需repo scope) |
| Fine-grained PAT | 5,000/h | ✅ | ✅ (需Actions: read) | ✅ (需Actions: write) |
| GitHub App Token | 5k-15k/h | ✅ | ✅ | ✅ |
| OAuth App | 5,000/h | ✅ | ✅ (需repo scope) | ✅ (需repo scope) |

## 相关链接
- `https://docs.github.com/en/rest/actions/workflow-runs` — Workflow runs API 文档
- `https://docs.github.com/en/rest/actions/workflows` — Workflows API（含 dispatch）
- `https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api` — 速率限制文档
- `https://docs.github.com/en/rest/actions/artifacts` — Artifacts API
- `https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api` — 认证文档
