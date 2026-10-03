#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gpmcli 单测（stdlib unittest，零第三方依赖；不发真实网络请求）。

测试切入点（T2 设计 §1.4）：
- 纯函数直测：classify / pagination_query / resolve_config / redact
- 注入 Transport 回放：401 / 200+code=404 / 非 JSON / 5xx 等
- 端到端：main([...], transport=fake) 断言退出码与 stdout/stderr JSON 结构
"""

import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import gpmcli  # noqa: E402


def envelope(code=200, message="操作成功", data=None):
    return json.dumps({"code": code, "message": message, "data": data,
                       "timestamp": 1700000000000})


def fake_transport(status, body_text, capture=None):
    def t(method, url, headers, body, timeout):
        if capture is not None:
            capture.append({"method": method, "url": url, "headers": headers,
                            "body": body, "timeout": timeout})
        return status, body_text
    return t


ENV_KEYS = ("GPM_TOKEN", "GPM_BASE_URL", "GPM_HTTP_TIMEOUT")


def run_main(argv, transport=None, env=None, home=None):
    """隔离环境跑 main，返回 (exit_code, stdout_text, stderr_text)。"""
    out, err = io.StringIO(), io.StringIO()
    saved = {k: os.environ.get(k) for k in ENV_KEYS}
    saved_home = os.environ.get("HOME")
    try:
        for k in ENV_KEYS:
            os.environ.pop(k, None)
        for k, v in (env or {}).items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        if home is not None:
            os.environ["HOME"] = home
            os.environ["USERPROFILE"] = home
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gpmcli.main(list(argv), transport=transport)
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        if saved_home is None:
            os.environ.pop("HOME", None)
        else:
            os.environ["HOME"] = saved_home
    return code, out.getvalue(), err.getvalue()


class TempHomeTestCase(unittest.TestCase):
    """每个用例一个干净的假 HOME（无 config.json）。"""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.home = self._tmp.name
        # 隔离 GPM_* 环境，保证用例互不泄漏
        self._saved_env = {k: os.environ.get(k) for k in ENV_KEYS}
        self._saved_home = os.environ.get("HOME")
        for k in ENV_KEYS:
            os.environ.pop(k, None)
        os.environ["HOME"] = self.home
        os.environ["USERPROFILE"] = self.home

    def tearDown(self):
        for k, v in self._saved_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        if self._saved_home is None:
            os.environ.pop("HOME", None)
        else:
            os.environ["HOME"] = self._saved_home
        self._tmp.cleanup()

    def write_config(self, content, mode=0o600):
        cfg_dir = os.path.join(self.home, ".config", "gpm-cli")
        os.makedirs(cfg_dir, exist_ok=True)
        path = os.path.join(cfg_dir, "config.json")
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        os.chmod(path, mode)
        return path


class ClassifyTest(unittest.TestCase):
    """classify 全表：C4 双解析 + body.code=404 → exit 5。"""

    def test_http_401_auth_exit3(self):
        o = gpmcli.classify(401, envelope(401, "未授权,请先登录"))
        self.assertEqual((o.kind, o.exit_code), ("auth", 3))
        self.assertIn("GPM_TOKEN", o.message)

    def test_http_403_permission_exit4(self):
        o = gpmcli.classify(403, envelope(403, "FORBIDDEN"))
        self.assertEqual((o.kind, o.exit_code), ("permission", 4))

    def test_http_404_not_found_exit5(self):
        o = gpmcli.classify(404, envelope(404, "nope"))
        self.assertEqual((o.kind, o.exit_code), ("not_found", 5))

    def test_http_405_usage_exit2(self):
        o = gpmcli.classify(405, envelope(405))
        self.assertEqual((o.kind, o.exit_code), ("usage", 2))

    def test_http_5xx_network_exit7(self):
        for status in (500, 502, 503):
            o = gpmcli.classify(status, envelope(status, "boom"))
            self.assertEqual((o.kind, o.exit_code), ("network", 7), "status=%d" % status)

    def test_ok_200(self):
        o = gpmcli.classify(200, envelope(200, data={"status": "UP"}))
        self.assertEqual((o.kind, o.exit_code), ("ok", 0))
        self.assertEqual(o.data, {"status": "UP"})

    def test_body_code_404_with_http_200_exit5(self):
        """关键用例：HTTP 200 + body code=404（DELETE /tokens/{未知id}）→ exit 5。"""
        o = gpmcli.classify(200, envelope(404, "API 令牌不存在: 123"))
        self.assertEqual((o.kind, o.exit_code), ("not_found", 5))
        self.assertEqual(o.code, 404)

    def test_business_not_found_codes(self):
        for c in (1101, 1201, 1401, 1501, 1601, 1701, 1711):
            o = gpmcli.classify(200, envelope(c, "x"))
            self.assertEqual((o.kind, o.exit_code), ("not_found", 5), "code=%d" % c)

    def test_business_codes_exit6(self):
        for c in (1202, 1203, 1102, 1301, 1302, 1402, 1403):
            o = gpmcli.classify(200, envelope(c, "x"))
            self.assertEqual((o.kind, o.exit_code), ("business", 6), "code=%d" % c)

    def test_http_400_with_code_400_exit6(self):
        o = gpmcli.classify(400, envelope(400, "参数非法"))
        self.assertEqual((o.kind, o.exit_code), ("business", 6))

    def test_http_409_exit6(self):
        o = gpmcli.classify(409, envelope(409, "冲突"))
        self.assertEqual((o.kind, o.exit_code), ("business", 6))

    def test_data_success_false_is_business(self):
        o = gpmcli.classify(200, envelope(200, data={"success": False, "message": "已在运行"}))
        self.assertEqual((o.kind, o.exit_code), ("business", 6))
        self.assertIn("已在运行", o.message)

    def test_body_code_500_is_network(self):
        o = gpmcli.classify(200, envelope(500, "内部错误"))
        self.assertEqual((o.kind, o.exit_code), ("network", 7))

    def test_auth_code_strings(self):
        for c in ("TOKEN_EXPIRED", "TOKEN_INVALID"):
            o = gpmcli.classify(200, envelope(c, "令牌无效"))
            self.assertEqual((o.kind, o.exit_code), ("auth", 3), "code=%s" % c)
        o = gpmcli.classify(200, envelope(1002, "用户名或密码错误"))
        self.assertEqual((o.kind, o.exit_code), ("auth", 3))

    def test_non_json_response(self):
        o = gpmcli.classify(200, "<html><body>502 Bad Gateway</body></html>", url="http://x/api/system/health")
        self.assertEqual((o.kind, o.exit_code), ("network", 7))
        self.assertIn("非 JSON", o.message)
        self.assertIn("http://x/api/system/health", o.message)

    def test_non_dict_json(self):
        o = gpmcli.classify(200, "[1,2,3]")
        self.assertEqual((o.kind, o.exit_code), ("network", 7))

    def test_unknown_code_uncategorized(self):
        o = gpmcli.classify(200, envelope("WEIRD_CODE", "?"))
        self.assertEqual((o.kind, o.exit_code), ("uncategorized", 1))

    def test_numeric_string_code_normalized(self):
        o = gpmcli.classify(200, envelope("1201", "实例不存在"))
        self.assertEqual((o.kind, o.exit_code), ("not_found", 5))


class PaginationTest(unittest.TestCase):
    """C2 分流：hosts/instances/games→current；tasks/schedules→page。"""

    def test_style_mapping(self):
        self.assertEqual(gpmcli.pagination_query("hosts", 2, 10), {"current": 2, "size": 10})
        self.assertEqual(gpmcli.pagination_query("instances", 2, None), {"current": 2})
        self.assertEqual(gpmcli.pagination_query("games", 1, 10), {"current": 1, "size": 10})
        self.assertEqual(gpmcli.pagination_query("tasks", 3, 20), {"page": 3, "size": 20})
        self.assertEqual(gpmcli.pagination_query("schedules", 3, 20), {"page": 3, "size": 20})

    def test_unknown_group_defaults_current(self):
        self.assertEqual(gpmcli.pagination_query("unknown", 1, 5), {"current": 1, "size": 5})


class RedactTest(unittest.TestCase):
    def test_gpm_pattern_masked(self):
        self.assertEqual(gpmcli.redact("Bearer gpm_ab12cd34ef56gh78"),
                         "Bearer gpm_****")

    def test_explicit_token_masked(self):
        self.assertEqual(gpmcli.redact("token=sekret123", "sekret123"), "token=gpm_****")

    def test_short_gpm_not_masked(self):
        # 前缀本身（<8 位）不算令牌
        self.assertEqual(gpmcli.redact("gpm_short"), "gpm_short")

    def test_degenerate_short_token_not_replaced(self):
        # 过短 token 不做逐字替换（避免把 URL/正文中的同名字符误伤）
        self.assertEqual(gpmcli.redact("http://nonexistent-host/api", "x"),
                         "http://nonexistent-host/api")


class ResolveConfigTest(TempHomeTestCase):
    def test_priority_cli_over_env_over_file(self):
        self.write_config(json.dumps({"base_url": "http://file:1/api", "token": "cfg_tok"}))
        with mock.patch.dict(os.environ, {"GPM_TOKEN": "env_tok", "GPM_BASE_URL": "http://env:2/api"}):
            cfg = gpmcli.resolve_config(self._args(["--token", "cli_tok"]))
            self.assertEqual(cfg.token, "cli_tok")
            self.assertEqual(cfg.base_url, "http://env:2/api")  # CLI 未给 base_url，env 胜于文件
            cfg = gpmcli.resolve_config(self._args())
            self.assertEqual(cfg.token, "env_tok")
            self.assertEqual(cfg.base_url, "http://env:2/api")
        cfg = gpmcli.resolve_config(self._args())
        self.assertEqual(cfg.token, "cfg_tok")
        self.assertEqual(cfg.base_url, "http://file:1/api")

    def test_missing_token_exit2(self):
        with self.assertRaises(gpmcli.GpmError) as cm:
            gpmcli.resolve_config(self._args())
        self.assertEqual(cm.exception.exit_code, 2)
        self.assertIn("GPM_TOKEN", cm.exception.message)
        self.assertIn("config.json", cm.exception.message)

    def test_invalid_base_url_exit2(self):
        with self.assertRaises(gpmcli.GpmError) as cm:
            gpmcli.resolve_config(self._args(["--base-url", "ftp://x", "--token", "t"]))
        self.assertEqual(cm.exception.exit_code, 2)

    def test_env_http_timeout(self):
        with mock.patch.dict(os.environ, {"GPM_HTTP_TIMEOUT": "5"}):
            cfg = gpmcli.resolve_config(self._args(["--token", "t"]))
            self.assertEqual(cfg.http_timeout, 5.0)

    def test_wide_permission_warns_but_works(self):
        self.write_config(json.dumps({"token": "cfg_tok"}), mode=0o644)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            cfg = gpmcli.resolve_config(self._args())
        self.assertEqual(cfg.token, "cfg_tok")
        self.assertIn("权限过宽", err.getvalue())

    def test_missing_file_no_warning(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            gpmcli.resolve_config(self._args(["--token", "t"]))
        self.assertNotIn("警告", err.getvalue())

    def _args(self, extra=None):
        return gpmcli.build_parser().parse_args(["ping"] + list(extra or []))


class EndToEndTest(TempHomeTestCase):
    """注入 Transport 的端到端：A1/A6/A7。"""

    TOKEN = "gpm_e2e_secret_token_123"

    def _argv(self, *rest):
        return ["--token", self.TOKEN] + list(rest)

    def test_ping_success_json(self):
        cap = []
        t = fake_transport(200, envelope(200, data={"status": "UP", "version": "1.0.0"}), cap)
        code, out, err = run_main(self._argv("--json", "ping"), transport=t)
        self.assertEqual(code, 0)
        payload = json.loads(out)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["data"]["status"], "UP")
        self.assertEqual(payload["data"]["version"], "1.0.0")
        self.assertEqual(cap[0]["method"], "GET")
        self.assertTrue(cap[0]["url"].endswith("/system/health"))
        self.assertEqual(cap[0]["headers"]["Authorization"], "Bearer " + self.TOKEN)

    def test_ping_success_human(self):
        t = fake_transport(200, envelope(200, data={"status": "UP", "version": "1.0.0"}))
        code, out, err = run_main(self._argv("ping"), transport=t)
        self.assertEqual(code, 0)
        self.assertIn("status=UP", out)
        self.assertIn("version=1.0.0", out)

    def test_ping_identity_flag_makes_two_requests(self):
        cap = []
        responses = [
            (200, envelope(200, data={"status": "UP", "version": "1.0.0"})),
            (200, envelope(200, data={"id": 1, "username": "admin"})),
        ]

        def t(method, url, headers, body, timeout):
            cap.append(url)
            return responses[len(cap) - 1]

        code, out, err = run_main(self._argv("--json", "ping", "--identity"), transport=t)
        self.assertEqual(code, 0)
        self.assertEqual(len(cap), 2)
        self.assertTrue(cap[0].endswith("/system/health"))
        self.assertTrue(cap[1].endswith("/auth/info"))
        payload = json.loads(out)
        self.assertEqual(payload["data"]["identity"]["username"], "admin")

    def test_invalid_token_exit3_and_json_error_structure(self):
        t = fake_transport(401, envelope(401, "未授权,请先登录"))
        code, out, err = run_main(self._argv("--json", "ping"), transport=t)
        self.assertEqual(code, 3)
        self.assertEqual(out, "")  # 失败输出只走 stderr
        payload = json.loads(err)
        self.assertFalse(payload["ok"])
        e = payload["error"]
        self.assertEqual(e["kind"], "auth")
        self.assertEqual(e["http"], 401)
        self.assertIn("GPM_TOKEN", e["message"])

    def test_non_json_response_exit7(self):
        t = fake_transport(200, "<html>nginx 502</html>")
        code, out, err = run_main(self._argv("--json", "ping"), transport=t)
        self.assertEqual(code, 7)
        payload = json.loads(err)
        self.assertEqual(payload["error"]["kind"], "network")
        self.assertIn("非 JSON", payload["error"]["message"])

    def test_5xx_exit7(self):
        t = fake_transport(503, envelope(503, "维护中"))
        code, out, err = run_main(self._argv("--json", "whoami"), transport=t)
        self.assertEqual(code, 7)

    def test_missing_credentials_exit2(self):
        code, out, err = run_main(["ping"], transport=fake_transport(200, envelope()))
        self.assertEqual(code, 2)
        self.assertIn("缺少凭据", err)

    def test_unknown_command_exit2(self):
        code, out, err = run_main(self._argv("bogus", "cmd"))
        self.assertEqual(code, 2)

    def test_system_info_two_level_command(self):
        t = fake_transport(200, envelope(200, data={"version": "1.0.0", "os": "Linux"}))
        code, out, err = run_main(self._argv("--json", "system", "info"), transport=t)
        self.assertEqual(code, 0)
        payload = json.loads(out)
        self.assertEqual(payload["data"]["version"], "1.0.0")
        self.assertEqual(payload["data"]["os"], "Linux")

    def test_token_never_leaks_anywhere(self):
        """A7：--verbose 与错误信息绝不包含 token 明文。"""
        t = fake_transport(401, envelope(401, "未授权,请先登录"))
        code, out, err = run_main(
            self._argv("--json", "--verbose", "ping"), transport=t)
        self.assertEqual(code, 3)
        all_output = out + err
        self.assertNotIn(self.TOKEN, all_output)
        self.assertIn("→ GET", err)  # verbose 行存在

    def test_env_token_missing_config_file(self):
        t = fake_transport(200, envelope(200, data={"status": "UP"}))
        code, out, err = run_main(["--json", "ping"], transport=t,
                                  env={"GPM_TOKEN": "gpm_envtok_12345678"})
        self.assertEqual(code, 0)
        payload = json.loads(out)
        self.assertTrue(payload["ok"])

    def test_config_file_token_used(self):
        self.write_config(json.dumps({"base_url": "http://192.168.3.50:8081/api",
                                      "token": "gpm_filetok_12345678"}))
        cap = []
        t = fake_transport(200, envelope(200, data={"status": "UP"}), cap)
        code, out, err = run_main(["--json", "ping"], transport=t, home=self.home)
        self.assertEqual(code, 0)
        self.assertEqual(cap[0]["headers"]["Authorization"], "Bearer gpm_filetok_12345678")


class PagedListTest(TempHomeTestCase):
    """T5 分页列表：--page/--size 按 C2 分流（current/page），带端点默认值。"""

    TOKEN = "gpm_e2e_secret_token_123"

    def _argv(self, *rest):
        return ["--token", self.TOKEN] + list(rest)

    def _paged(self, *rest):
        """跑一个 paged 命令并返回 (exit_code, payload, captured_url)。"""
        cap = []
        t = fake_transport(200, envelope(200, data={"current": 1, "size": 10,
                                                    "total": 0, "pages": 0,
                                                    "records": []}), cap)
        code, out, err = run_main(self._argv("--json", *rest), transport=t)
        payload = json.loads(out) if out else None
        return code, payload, cap[0]["url"] if cap else ""

    def test_hosts_list_maps_current(self):
        code, payload, url = self._paged("hosts", "list", "--page", "2", "--size", "5")
        self.assertEqual(code, 0)
        self.assertTrue(payload["ok"])
        self.assertIn("/hosts?", url)
        self.assertIn("current=2", url)
        self.assertIn("size=5", url)
        self.assertNotIn("page=2", url)

    def test_hosts_list_defaults(self):
        code, _, url = self._paged("hosts", "list")
        self.assertEqual(code, 0)
        self.assertIn("current=1", url)
        self.assertIn("size=10", url)

    def test_hosts_list_keyword_and_order(self):
        code, _, url = self._paged("hosts", "list", "--keyword", "web",
                                   "--order-by", "create_time", "--order", "asc")
        self.assertEqual(code, 0)
        self.assertIn("keyword=web", url)
        self.assertIn("orderBy=create_time", url)
        self.assertIn("order=asc", url)

    def test_tasks_list_maps_page(self):
        code, _, url = self._paged("tasks", "list", "--page", "3", "--size", "20")
        self.assertEqual(code, 0)
        self.assertIn("/tasks?", url)
        self.assertIn("page=3", url)
        self.assertIn("size=20", url)
        self.assertNotIn("current=", url)

    def test_tasks_list_defaults_and_filters(self):
        code, _, url = self._paged("tasks", "list", "--status", "RUNNING",
                                   "--task-type", "deploy", "--keyword", "l4d2",
                                   "--start-time", "2026-10-01T00:00:00",
                                   "--end-time", "2026-10-02T00:00:00")
        self.assertEqual(code, 0)
        self.assertIn("page=1", url)
        self.assertIn("size=20", url)
        self.assertIn("status=RUNNING", url)
        self.assertIn("taskType=deploy", url)
        self.assertIn("keyword=l4d2", url)
        self.assertIn("startTime=2026-10-01T00%3A00%3A00", url)
        self.assertIn("endTime=2026-10-02T00%3A00%3A00", url)

    def test_instances_list_game_code(self):
        code, _, url = self._paged("instances", "list", "--game-code", "l4d2",
                                   "--keyword", " infected")
        self.assertEqual(code, 0)
        self.assertIn("gameCode=l4d2", url)
        self.assertIn("keyword=+infected", url)

    def test_games_list_uses_current_style(self):
        code, _, url = self._paged("games", "list", "--keyword", "l4d2", "--page", "1")
        self.assertEqual(code, 0)
        self.assertIn("/games/list?", url)
        self.assertIn("keyword=l4d2", url)
        self.assertIn("current=1", url)

    def test_schedules_list_maps_page_and_filters(self):
        code, _, url = self._paged("schedules", "list", "--source", "SCHEDULE",
                                   "--handler-key", "l4d2-crawler", "--enabled", "true")
        self.assertEqual(code, 0)
        self.assertIn("page=1", url)
        self.assertIn("size=20", url)
        self.assertIn("source=SCHEDULE", url)
        self.assertIn("handlerKey=l4d2-crawler", url)
        self.assertIn("enabled=true", url)


if __name__ == "__main__":
    unittest.main()
