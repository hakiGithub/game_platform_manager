#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gpmcli — GPM 游戏平台管理 CLI（Python3 标准库，零第三方依赖）。

认证：Authorization: Bearer <token>，支持 `gpm_` API 令牌与登录 JWT。
凭据优先级：命令行 --token/--base-url > 环境变量 GPM_TOKEN/GPM_BASE_URL
          > ~/.config/gpm-cli/config.json（权限 0600）。

T4 范围命令：ping / whoami / system info（其余命令见 T5/T6）。

退出码：
  0 成功（含 dry-run）  1 未分类错误  2 用法错误
  3 认证失败  4 权限不足  5 资源不存在
  6 业务/状态冲突  7 网络/平台不可达（含非 JSON 响应、5xx）
  8 --wait 超时

--json 契约：
  成功（stdout）：{"ok":true,"data":<平台 data 原样>}
  失败（stderr）：{"ok":false,"error":{"kind":...,"code":...,"http":...,"message":...}}
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple

# =========================================================================
# 分节 1：常量与退出码
# =========================================================================

EXIT_OK = 0
EXIT_UNCATEGORIZED = 1
EXIT_USAGE = 2
EXIT_AUTH = 3
EXIT_PERMISSION = 4
EXIT_NOT_FOUND = 5
EXIT_BUSINESS = 6
EXIT_NETWORK = 7
EXIT_TIMEOUT = 8

DEFAULT_BASE_URL = "http://192.168.3.50:8081/api"
DEFAULT_HTTP_TIMEOUT = 30.0          # 只读默认；写命令 300s（T6）
CONFIG_PATH = "~/.config/gpm-cli/config.json"

# 业务码集合（PLUT-35 §4 + T2 设计 §4）
NOT_FOUND_CODES = {404, 1101, 1201, 1401, 1501, 1601, 1701, 1711}
BUSINESS_CODES = {400, 409, 1102, 1202, 1203, 1301, 1302, 1402, 1403}
AUTH_CODE_STRINGS = {"TOKEN_EXPIRED", "TOKEN_INVALID"}
AUTH_CODE_INTS = {401, 1002}  # 401 UNAUTHORIZED / 1002 USER_PASSWORD_ERROR

AUTH_HINT = "令牌无效或已过期，请更新 GPM_TOKEN"
PERMISSION_HINT = "当前令牌权限不足（read 令牌不可写；/tokens 需 ADMIN）"

# 分页样式（C2）：CLI 统一 --page/--size，出站按端点映射参数名
PAGINATION_STYLE = {
    "hosts": "current",
    "instances": "current",
    "games": "current",
    "tasks": "page",
    "schedules": "page",
}


# =========================================================================
# 分节 2：错误模型
# =========================================================================

@dataclass
class Outcome:
    """一次请求/判定的结果。kind="ok" 表示业务成功。"""
    kind: str                 # ok|auth|permission|not_found|business|network|timeout|usage|uncategorized
    exit_code: int
    code: Any = None          # 平台业务码（int/str）或 None
    http: Optional[int] = None
    message: str = ""
    data: Any = None


class GpmError(Exception):
    """统一错误；emit_error 是唯一出口。"""

    def __init__(self, kind: str, exit_code: int, code: Any = None,
                 http: Optional[int] = None, message: str = ""):
        super().__init__(message)
        self.kind = kind
        self.exit_code = exit_code
        self.code = code
        self.http = http
        self.message = message

    def to_outcome(self) -> Outcome:
        return Outcome(self.kind, self.exit_code, self.code, self.http, self.message)


def outcome_to_error(outcome: Outcome) -> GpmError:
    return GpmError(outcome.kind, outcome.exit_code, outcome.code,
                    outcome.http, outcome.message)


# =========================================================================
# 分节 3：凭据解析（CLI > env > config.json 0600）
# =========================================================================

@dataclass
class Config:
    base_url: str = DEFAULT_BASE_URL
    token: Optional[str] = None
    json_mode: bool = False
    verbose: bool = False
    no_color: bool = False
    http_timeout: float = DEFAULT_HTTP_TIMEOUT


def _warn(text: str, token: Optional[str]) -> None:
    print(redact(text, token), file=sys.stderr)


def _load_config_file(token_for_redact: Optional[str]) -> Dict[str, Any]:
    """读取 ~/.config/gpm-cli/config.json；仅允许 base_url/token 两键。"""
    path = os.path.expanduser(CONFIG_PATH)
    if not os.path.isfile(path):
        return {}
    try:
        if os.name != "nt":  # Windows 跳过权限检查
            mode = os.stat(path).st_mode & 0o777
            if mode & 0o077:
                _warn("警告: 配置文件 %s 权限过宽（%s），建议 chmod 600" % (path, oct(mode)), token_for_redact)
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            _warn("警告: 配置文件 %s 格式异常（非 JSON 对象），已忽略" % path, token_for_redact)
            return {}
        return {k: v for k, v in data.items() if k in ("base_url", "token")}
    except (OSError, ValueError) as e:
        _warn("警告: 配置文件 %s 读取失败，已忽略（%s）" % (path, e), token_for_redact)
        return {}


def resolve_config(args: argparse.Namespace) -> Config:
    file_cfg = _load_config_file(getattr(args, "token", None))

    base_url = (getattr(args, "base_url", None)
                or os.environ.get("GPM_BASE_URL")
                or file_cfg.get("base_url")
                or DEFAULT_BASE_URL)
    if not isinstance(base_url, str) or not base_url.startswith(("http://", "https://")):
        raise GpmError("usage", EXIT_USAGE,
                       message="--base-url 非法（须为 http(s) URL）：%s" % base_url)

    token = (getattr(args, "token", None)
             or os.environ.get("GPM_TOKEN")
             or file_cfg.get("token"))
    if not token:
        raise GpmError(
            "usage", EXIT_USAGE,
            message="缺少凭据：请通过 --token、环境变量 GPM_TOKEN 或 "
                    "~/.config/gpm-cli/config.json（权限 0600，仅含 base_url/token 两键）提供令牌")

    http_timeout = getattr(args, "http_timeout", None)
    if http_timeout is None:
        env_t = os.environ.get("GPM_HTTP_TIMEOUT")
        try:
            http_timeout = float(env_t) if env_t else DEFAULT_HTTP_TIMEOUT
        except ValueError:
            raise GpmError("usage", EXIT_USAGE,
                           message="GPM_HTTP_TIMEOUT 非法（须为数字秒数）：%s" % env_t)

    return Config(
        base_url=base_url.rstrip("/"),
        token=token,
        json_mode=bool(getattr(args, "json", False)),
        verbose=bool(getattr(args, "verbose", False)),
        no_color=bool(getattr(args, "no_color", False)),
        http_timeout=http_timeout,
    )


# =========================================================================
# 分节 4：脱敏（A7：所有输出通道绝不泄漏 token）
# =========================================================================

GPM_TOKEN_RE = re.compile(r"gpm_[A-Za-z0-9_\-]{8,}")


def redact(text: str, token: Optional[str] = None) -> str:
    if token and len(token) >= 8:  # 过短的“令牌”逐字替换会误伤正文，跳过
        text = text.replace(token, "gpm_****")
    return GPM_TOKEN_RE.sub("gpm_****", text)


# =========================================================================
# 分节 5：HTTP 传输（C4 双解析在 classify；本节只负责发请求）
# =========================================================================

Transport = Callable[[str, str, Dict[str, str], Optional[bytes], float], Tuple[int, str]]


def _urllib_transport(method: str, url: str, headers: Dict[str, str],
                      body: Optional[bytes], timeout: float) -> Tuple[int, str]:
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:  # 4xx/5xx 也要读 body 参与双解析
        try:
            text = e.read().decode("utf-8", "replace")
        except Exception:
            text = ""
        return e.code, text
    except (urllib.error.URLError, socket.timeout, TimeoutError,
            ConnectionError, OSError) as e:
        raise GpmError("network", EXIT_NETWORK,
                       message="连接失败: %s (%s)" % (redact(url), e))


def http_request(cfg: Config, method: str, path: str, *,
                 params: Optional[Dict[str, Any]] = None,
                 body: Optional[Dict[str, Any]] = None,
                 timeout: Optional[float] = None,
                 transport: Optional[Transport] = None) -> Outcome:
    url = cfg.base_url + path
    if params:
        clean = {k: v for k, v in params.items() if v is not None}
        if clean:
            url += "?" + urllib.parse.urlencode(clean)
    headers = {"Accept": "application/json"}
    if cfg.token:
        headers["Authorization"] = "Bearer " + cfg.token
    body_bytes = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        body_bytes = json.dumps(body, ensure_ascii=False).encode("utf-8")

    t0 = time.monotonic()
    if transport is None:
        status, text = _urllib_transport(method, url, headers, body_bytes,
                                         timeout if timeout is not None else cfg.http_timeout)
    else:
        status, text = transport(method, url, headers, body_bytes,
                                 timeout if timeout is not None else cfg.http_timeout)
    elapsed = time.monotonic() - t0

    if cfg.verbose:
        # 绝不打印请求头 / Authorization / token（A7）
        _warn("→ %s %s (%.2fs)" % (method, url, elapsed), cfg.token)
    return classify(status, text, url=url)


# =========================================================================
# 分节 6：错误分类（C4：先 HTTP 码、后 body.code，业务成败以 body.code 为准）
# =========================================================================

def classify(http_status: int, body_text: str, url: str = "") -> Outcome:
    try:
        body = json.loads(body_text)
    except (ValueError, TypeError):
        return Outcome("network", EXIT_NETWORK, http=http_status,
                       message="目标 %s 返回非 JSON 响应" % (url or "未知地址"))
    if not isinstance(body, dict):
        return Outcome("network", EXIT_NETWORK, http=http_status,
                       message="目标 %s 返回非 JSON 响应" % (url or "未知地址"))

    code = body.get("code")
    if isinstance(code, str) and code.isdigit():
        code = int(code)
    message = body.get("message") or ""
    data = body.get("data")
    success_false = isinstance(data, dict) and data.get("success") is False
    if success_false:
        # 启停等写接口：透传 {success,message}.message，不静默成功
        message = data.get("message") or message

    # 1) HTTP 层判定
    if http_status == 401:
        return Outcome("auth", EXIT_AUTH, code, http_status,
                       "%s（%s）" % (message, AUTH_HINT) if message else AUTH_HINT)
    if http_status == 403:
        return Outcome("permission", EXIT_PERMISSION, code, http_status,
                       "%s（%s）" % (message, PERMISSION_HINT) if message else PERMISSION_HINT)
    if http_status == 404:
        return Outcome("not_found", EXIT_NOT_FOUND, code, http_status,
                       message or "资源不存在")
    if http_status == 405:
        return Outcome("usage", EXIT_USAGE, code, http_status,
                       "HTTP 405：CLI 与平台接口不匹配（实现缺陷）")
    if http_status >= 500:
        return Outcome("network", EXIT_NETWORK, code, http_status,
                       "平台侧错误（HTTP %d）：%s" % (http_status, message))

    # 2) body.code 判定（平台业务错误恒为 HTTP 200，业务成败一律以 body.code 为准）
    if code == 200 and not success_false:
        return Outcome("ok", EXIT_OK, code, http_status, message, data=data)
    if code == 404 or code in NOT_FOUND_CODES:
        # 覆盖「HTTP 200 + body code=404」（如 DELETE /tokens/{未知id}）→ exit 5
        return Outcome("not_found", EXIT_NOT_FOUND, code, http_status,
                       message or "资源不存在")
    if code in AUTH_CODE_INTS or (isinstance(code, str) and code in AUTH_CODE_STRINGS):
        return Outcome("auth", EXIT_AUTH, code, http_status,
                       "%s（%s）" % (message, AUTH_HINT) if message else AUTH_HINT)
    if code == 403:
        return Outcome("permission", EXIT_PERMISSION, code, http_status,
                       "%s（%s）" % (message, PERMISSION_HINT) if message else PERMISSION_HINT)
    if code in BUSINESS_CODES or success_false:
        return Outcome("business", EXIT_BUSINESS, code, http_status,
                       message or "业务失败")
    if isinstance(code, int) and code >= 500:
        return Outcome("network", EXIT_NETWORK, code, http_status,
                       "平台侧错误（code=%s）：%s" % (code, message))
    return Outcome("uncategorized", EXIT_UNCATEGORIZED, code, http_status,
                   "未识别的响应（code=%s）：%s" % (code, message))


# =========================================================================
# 分节 7：分页归一化（C2）
# =========================================================================

def pagination_query(group: str, page: Optional[int], size: Optional[int]) -> Dict[str, int]:
    """CLI 统一 --page/--size → 出站参数名按端点分流。"""
    style = PAGINATION_STYLE.get(group, "current")
    out: Dict[str, int] = {}
    if page is not None:
        out[style] = page
    if size is not None:
        out["size"] = size
    return out


# =========================================================================
# 分节 8：命令注册表（T4：ping / whoami / system info；T5/T6 按票补齐）
# =========================================================================

@dataclass
class Param:
    flags: Tuple[str, ...]
    dest: str
    kind: str = "flag"          # flag|query|path|body
    default: Any = None
    action: str = "store"       # store|store_true
    query_name: str = ""        # 出站查询参数名（缺省同 dest）
    help: str = ""


@dataclass
class CommandSpec:
    cli: str                    # "system info"
    group: str                  # "system"（决定分页样式）
    action: Optional[str]       # None = 单词命令（ping/whoami）；否则两级子命令
    method: str
    path: str
    params: List[Param] = field(default_factory=list)
    write: bool = False
    handler: Optional[Callable] = None
    help: str = ""


def cmd_ping(cfg: Config, args: argparse.Namespace, spec: CommandSpec,
             transport: Optional[Transport]) -> Outcome:
    """ping = 认证 + 健康探测二合一（C3：需要有效凭据）。"""
    outcome = http_request(cfg, spec.method, spec.path, transport=transport)
    if outcome.kind != "ok":
        return outcome
    data = dict(outcome.data) if isinstance(outcome.data, dict) else {"data": outcome.data}
    if getattr(args, "identity", False):
        ident = http_request(cfg, "GET", "/auth/info", transport=transport)
        if ident.kind != "ok":
            return ident
        data["identity"] = ident.data
    return Outcome("ok", EXIT_OK, outcome.code, outcome.http, outcome.message, data=data)


COMMANDS: List[CommandSpec] = [
    CommandSpec(
        cli="ping", group="ping", action=None, method="GET", path="/system/health",
        params=[Param(flags=("--identity",), dest="identity", action="store_true",
                      help="追加 GET /auth/info 确认当前身份")],
        handler=cmd_ping,
        help="认证 + 健康探测二合一（需有效凭据；匿名/无效令牌 → 401，退出码 3）",
    ),
    CommandSpec(
        cli="whoami", group="whoami", action=None, method="GET", path="/auth/info",
        help="打印当前身份（用户名/ID）",
    ),
    CommandSpec(
        cli="system info", group="system", action="info", method="GET", path="/system/info",
        help="平台版本/OS/JVM/内存",
    ),
]


def lookup_spec(group: Optional[str], action: Optional[str]) -> CommandSpec:
    for spec in COMMANDS:
        if spec.group == group and spec.action == action:
            return spec
    raise GpmError("usage", EXIT_USAGE, message="未知命令：%s %s" % (group or "", action or ""))


# =========================================================================
# 分节 9：输出渲染（--json 契约：成功 stdout / 失败 stderr）
# =========================================================================

def _human_render(data: Any) -> str:
    if isinstance(data, dict):
        parts = []
        for k, v in data.items():
            if isinstance(v, (dict, list)):
                parts.append("%s=%s" % (k, json.dumps(v, ensure_ascii=False)))
            else:
                parts.append("%s=%s" % (k, v))
        return "  ".join(parts)
    return str(data)


def emit_success(outcome: Outcome, cfg: Config) -> int:
    if cfg.json_mode:
        print(json.dumps({"ok": True, "data": outcome.data},
                         ensure_ascii=False, sort_keys=False))
    else:
        print(_human_render(outcome.data))
    return EXIT_OK


def emit_error(err: GpmError, cfg: Config) -> int:
    message = redact(err.message, cfg.token)
    if cfg.json_mode:
        print(json.dumps({"ok": False, "error": {"kind": err.kind, "code": err.code,
                                                 "http": err.http, "message": message}},
                         ensure_ascii=False), file=sys.stderr)
    else:
        print("错误[%s] %s" % (err.kind, message), file=sys.stderr)
    return err.exit_code


# =========================================================================
# 分节 10：--wait 轮询引擎（T6 实现）
# =========================================================================

# =========================================================================
# 分节 11：dry-run（T6 实现；写命令缺 --yes 时不发请求）
# =========================================================================

# =========================================================================
# 分节 12：命令处理器（T4 命令多为注册表驱动默认执行）
# =========================================================================

def dispatch(cfg: Config, args: argparse.Namespace, spec: CommandSpec,
             transport: Optional[Transport]) -> Outcome:
    if spec.handler is not None:
        return spec.handler(cfg, args, spec, transport)
    params: Dict[str, Any] = {}
    for prm in spec.params:
        if prm.kind == "query":
            v = getattr(args, prm.dest, None)
            if v is not None:
                params[prm.query_name or prm.dest] = v
    return http_request(cfg, spec.method, spec.path, params=params, transport=transport)


# =========================================================================
# 分节 13：参数解析（argparse；用法错误 → exit 2）
# =========================================================================

class _GpmArgumentParser(argparse.ArgumentParser):
    def error(self, message: str):  # 用法错误统一走 GpmError（exit 2），不 SystemExit
        raise GpmError("usage", EXIT_USAGE, message=message)


def _add_global_options(p: argparse.ArgumentParser) -> None:
    """全局参数（default=SUPPRESS：未给时不写入 namespace，可放子命令前或后）。"""
    sup = argparse.SUPPRESS
    p.add_argument("--base-url", default=sup,
                   help="平台 API 根地址（含 /api 前缀）；env GPM_BASE_URL")
    p.add_argument("--token", default=sup, help="凭据（gpm_ 令牌或 JWT）；env GPM_TOKEN")
    p.add_argument("--json", action="store_true", default=sup,
                   help="机器可读输出（失败走 stderr）")
    p.add_argument("--no-color", action="store_true", default=sup, help="关闭 ANSI 颜色")
    p.add_argument("--verbose", "-v", action="store_true", default=sup,
                   help="打印请求方法/URL/耗时（绝不打印 token）")
    p.add_argument("--yes", action="store_true", default=sup,
                   help="写命令确认执行（缺省 dry-run，T6）")
    p.add_argument("--dry-run", dest="dry_run", action="store_true", default=sup,
                   help="显式 dry-run（写命令缺省行为，T6）")
    p.add_argument("--wait", action="store_true", default=sup,
                   help="写后轮询至终态（T6）")
    p.add_argument("--interval", type=float, default=sup, help="轮询间隔秒（默认 3，T6）")
    p.add_argument("--wait-timeout", dest="wait_timeout", type=float, default=sup,
                   help="轮询超时秒（实例 120 / 任务 600，T6）")
    p.add_argument("--http-timeout", dest="http_timeout", type=float, default=sup,
                   help="单次 HTTP 超时秒（只读默认 30；env GPM_HTTP_TIMEOUT）")
    p.add_argument("--page", type=int, default=sup, help="页码（默认 1，T5）")
    p.add_argument("--size", type=int, default=sup, help="页大小（默认按端点，T5）")


def build_parser() -> argparse.ArgumentParser:
    parser = _GpmArgumentParser(
        prog="gpmcli",
        description="gpmcli — GPM 游戏平台管理 CLI（凭据：--token / GPM_TOKEN / "
                    "~/.config/gpm-cli/config.json 0600）")
    _add_global_options(parser)
    sub = parser.add_subparsers(dest="group", metavar="<command>", required=True)

    def _add_params(sp: argparse.ArgumentParser, spec: CommandSpec) -> None:
        for prm in spec.params:
            if prm.action == "store_true":
                sp.add_argument(*prm.flags, dest=prm.dest, action="store_true",
                                default=argparse.SUPPRESS, help=prm.help)
            else:
                sp.add_argument(*prm.flags, dest=prm.dest, default=prm.default,
                                help=prm.help)

    for spec in COMMANDS:
        if spec.action is None:  # 单词命令：ping / whoami
            sp = sub.add_parser(spec.group, help=spec.help, description=spec.help)
            _add_global_options(sp)
            _add_params(sp, spec)
            sp.set_defaults(spec=spec)
        else:  # 两级子命令：system info
            gp = sub.add_parser(spec.group, help="%s 命令组" % spec.group)
            gsub = gp.add_subparsers(dest="action", metavar="<action>", required=True)
            for member in [s for s in COMMANDS if s.group == spec.group and s.action]:
                sp = gsub.add_parser(member.action, help=member.help,
                                     description=member.help)
                _add_global_options(sp)
                _add_params(sp, member)
                sp.set_defaults(spec=member)
    return parser


# =========================================================================
# 分节 14：入口
# =========================================================================

def main(argv: Optional[List[str]] = None, transport: Optional[Transport] = None) -> int:
    if argv is None:
        argv = sys.argv[1:]
    args = None
    cfg = None
    try:
        args = build_parser().parse_args(argv)
        cfg = resolve_config(args)
        spec: CommandSpec = args.spec
        outcome = dispatch(cfg, args, spec, transport)
        if outcome.kind != "ok":
            return emit_error(outcome_to_error(outcome), cfg)
        return emit_success(outcome, cfg)
    except GpmError as e:
        if cfg is None:
            cfg = Config(json_mode=bool(args and getattr(args, "json", False)))
        return emit_error(e, cfg)
    except Exception as e:  # 兜底：绝不打印堆栈，绝不泄漏 token
        if cfg is None:
            cfg = Config(json_mode=bool(args and getattr(args, "json", False)))
        return emit_error(GpmError("uncategorized", EXIT_UNCATEGORIZED,
                                   message="未分类错误: %s" % e), cfg)


if __name__ == "__main__":
    sys.exit(main())
