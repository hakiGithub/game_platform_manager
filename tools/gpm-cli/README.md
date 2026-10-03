# gpmcli — GPM 游戏平台管理 CLI

Python3 标准库单文件 CLI（零第三方依赖），用于管理 GPM 游戏平台（`game_platform_manager`）。

## 用法

```bash
python3 tools/gpm-cli/gpmcli.py [--json] [--verbose] <command> [args]
```

## 命令（T4 范围）

| 命令 | REST | 说明 |
|---|---|---|
| `ping` | `GET /system/health` | 认证 + 健康探测二合一（**需有效凭据**）；`--identity` 附带当前身份 |
| `whoami` | `GET /auth/info` | 当前身份（用户名/ID） |
| `system info` | `GET /system/info` | 平台版本/OS/JVM/内存 |

## 凭据（优先级：命令行 > 环境变量 > 配置文件）

1. `--token <tok>` / `--base-url <url>`
2. 环境变量 `GPM_TOKEN` / `GPM_BASE_URL`
3. `~/.config/gpm-cli/config.json`（仅含 `base_url`、`token` 两键；权限必须 `0600`，过宽会告警）

```bash
mkdir -p ~/.config/gpm-cli && chmod 700 ~/.config/gpm-cli
cat > ~/.config/gpm-cli/config.json <<'EOF'
{"base_url": "http://192.168.3.50:8081/api", "token": "gpm_xxx"}
EOF
chmod 600 ~/.config/gpm-cli/config.json
```

**严禁**将凭据写入仓库、日志、`--verbose` 输出或错误信息（CLI 侧对所有输出做 `gpm_*` 掩码）。

## 退出码

| 码 | 含义 | 触发示例 |
|---|---|---|
| 0 | 成功 | 业务成功 |
| 1 | 未分类错误 | 兜底 |
| 2 | 用法错误 | 参数缺失/非法、未知子命令、缺凭据 |
| 3 | 认证失败 | HTTP 401 / body code 401 / `TOKEN_EXPIRED`/`TOKEN_INVALID`/1002 |
| 4 | 权限不足 | HTTP 403 / body code 403 |
| 5 | 资源不存在 | HTTP 404 / body code 404、1101/1201/1401/… |
| 6 | 业务/状态冲突 | 1202/1203/1102/…、`success=false` |
| 7 | 网络/平台不可达 | 连接失败/超时/非 JSON 响应/5xx |
| 8 | `--wait` 超时 | （T6） |

## `--json` 契约

- 成功（stdout）：`{"ok":true,"data":<平台 data 原样>}`
- 失败（stderr，退出码非 0）：`{"ok":false,"error":{"kind":"auth|permission|not_found|business|network|timeout|usage","code":<平台业务码|null>,"http":<HTTP 状态|null>,"message":"..."}}`

## 错误判定顺序（C4）

先看 HTTP 状态码（401/403/404/405/5xx），再解析响应体 `code`——平台业务错误恒为 HTTP 200 + body `code`，**业务成败一律以 body.code 为准**（例：`DELETE /tokens/{未知id}` 返回 HTTP 200 + `code=404` → exit 5）。

## 测试

```bash
python3 -m unittest test_gpmcli -v   # 在 tools/gpm-cli/ 目录下执行；不发真实网络请求
```
