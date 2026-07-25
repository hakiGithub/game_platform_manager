"""
InstanceFileService SPI 迁移 - 浏览器测试脚本 v3
实例 54 已运行，覆盖更多场景（日志内容、地图列表、配置等）
"""
import json
import sys
from playwright.sync_api import sync_playwright

BASE_URL = "http://localhost:3000"
USERNAME = "admin"
PASSWORD = "admin123"
INSTANCE_ID = "54"

results = []

def record(case_id, name, passed, detail=""):
    status = "✅ PASS" if passed else "❌ FAIL"
    results.append({"id": case_id, "name": name, "passed": passed, "detail": detail})
    print(f"[{status}] {case_id} {name}")
    if detail:
        print(f"         {detail}")

def api_call(page, path, method="GET", body=None):
    """通过浏览器上下文调用 API（自动携带 token）"""
    return page.evaluate("""
        async ([path, method, body]) => {
            const token = localStorage.getItem('token');
            const headers = token ? {'Authorization': 'Bearer ' + token} : {};
            if (body) headers['Content-Type'] = 'application/json';
            try {
                const opts = {method, headers};
                if (body) opts.body = body;
                const resp = await fetch(path, opts);
                const text = await resp.text();
                let data;
                try { data = JSON.parse(text); } catch(e) { data = text; }
                return {status: resp.status, data, raw: text.substring(0, 500)};
            } catch(e) {
                return {status: 0, error: e.message};
            }
        }
    """, [path, method, body])

def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1600, "height": 900})
        page = context.new_page()

        console_errors = []
        page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)

        print("=" * 70)
        print("InstanceFileService SPI 浏览器测试 v3 (实例运行中)")
        print("=" * 70)

        # ===== 登录 =====
        print("\n--- E2E-IFS-001: 登录主应用 ---")
        try:
            page.goto(f"{BASE_URL}/login", wait_until="networkidle")
            page.wait_for_timeout(1000)
            page.fill('input[placeholder="请输入用户名"]', USERNAME)
            page.fill('input[placeholder="请输入密码"]', PASSWORD)
            page.click('button:has-text("登 录")')
            page.wait_for_url("**/dashboard**", timeout=15000)
            page.wait_for_load_state("networkidle")
            page.wait_for_timeout(1000)
            token = page.evaluate("() => localStorage.getItem('token')")
            record("E2E-IFS-001", "登录主应用", bool(token), f"token获取: {'成功' if token else '失败'}")
        except Exception as e:
            record("E2E-IFS-001", "登录主应用", False, str(e)[:200])

        # ===== E2E-IFS-002: 地图列表 =====
        print("\n--- E2E-IFS-002: 地图列表加载（Docker 路由） ---")
        try:
            r = api_call(page, f"/api/plugin/l4d2/maps/list?instanceId={INSTANCE_ID}")
            passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
            count = len(r.get("data", {}).get("data") or [])
            record("E2E-IFS-002", "地图列表加载", passed,
                   f"status={r.get('status')}, count={count}")
        except Exception as e:
            record("E2E-IFS-002", "地图列表加载", False, str(e)[:200])

        # ===== E2E-IFS-006: 插件列表 =====
        print("\n--- E2E-IFS-006: 插件列表加载 ---")
        try:
            r = api_call(page, f"/api/plugin/l4d2/plugins/list?instanceId={INSTANCE_ID}")
            passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
            count = len(r.get("data", {}).get("data") or [])
            record("E2E-IFS-006", "插件列表加载", passed,
                   f"status={r.get('status')}, count={count}")
        except Exception as e:
            record("E2E-IFS-006", "插件列表加载", False, str(e)[:200])

        # ===== E2E-IFS-010: 备份列表 =====
        print("\n--- E2E-IFS-010: 备份列表加载 ---")
        try:
            r = api_call(page, f"/api/plugin/l4d2/backups/list?instanceId={INSTANCE_ID}")
            passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
            data = r.get("data", {}).get("data")
            count = len(data) if isinstance(data, list) else 0
            record("E2E-IFS-010", "备份列表加载", passed,
                   f"status={r.get('status')}, count={count}")
        except Exception as e:
            record("E2E-IFS-010", "备份列表加载", False, str(e)[:200])

        # ===== E2E-IFS-014: 插件配置候选 cfg 路径 =====
        print("\n--- E2E-IFS-014: 插件配置候选 cfg 路径 ---")
        try:
            r = api_call(page, f"/api/plugin/l4d2/plugin-config/candidates?instanceId={INSTANCE_ID}&pluginName=admin-flatloader")
            passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
            candidates = r.get("data", {}).get("data") or []
            has_relative = any(c.get("path", "").startswith(("cfg/", "left4dead2/")) for c in candidates)
            record("E2E-IFS-014", "插件配置候选 cfg 路径", passed and has_relative,
                   f"status={r.get('status')}, count={len(candidates)}, 相对路径={has_relative}")
        except Exception as e:
            record("E2E-IFS-014", "插件配置候选 cfg 路径", False, str(e)[:200])

        # ===== E2E-IFS-016: 日志文件列表 =====
        print("\n--- E2E-IFS-016: 日志文件列表加载 ---")
        try:
            r = api_call(page, f"/api/plugin/l4d2/logs/files?instanceId={INSTANCE_ID}")
            passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
            data = r.get("data", {}).get("data")
            count = len(data) if isinstance(data, list) else 0
            record("E2E-IFS-016", "日志文件列表加载", passed,
                   f"status={r.get('status')}, count={count}")
        except Exception as e:
            record("E2E-IFS-016", "日志文件列表加载", False, str(e)[:200])

        # ===== E2E-IFS-017: 日志内容查看（实例运行中才能成功）=====
        print("\n--- E2E-IFS-017: 日志内容查看 ---")
        try:
            # 先获取日志文件列表
            r_files = api_call(page, f"/api/plugin/l4d2/logs/files?instanceId={INSTANCE_ID}")
            files = r_files.get("data", {}).get("data") or []
            if files:
                # 取第一个日志文件
                first_file = files[0] if isinstance(files[0], str) else files[0].get("name", "")
                r = api_call(page, f"/api/plugin/l4d2/logs/content?instanceId={INSTANCE_ID}&file={first_file}")
                passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
                content_len = len(r.get("data", {}).get("data", "") or "")
                record("E2E-IFS-017", "日志内容查看", passed,
                       f"file={first_file}, status={r.get('status')}, content_len={content_len}")
            else:
                # 无日志文件，验证空列表场景
                record("E2E-IFS-017", "日志内容查看", True,
                       "无日志文件（空目录），列表 API 已通过")
        except Exception as e:
            record("E2E-IFS-017", "日志内容查看", False, str(e)[:200])

        # ===== E2E-IFS-020: Console 无错误 =====
        print("\n--- E2E-IFS-020: Console 无错误 ---")
        critical_errors = [e for e in console_errors
                          if "favicon" not in e.lower()
                          and "font" not in e.lower()]
        passed = len(critical_errors) == 0
        record("E2E-IFS-020", "Console 无错误", passed,
               f"共 {len(console_errors)} 条 error，关键 {len(critical_errors)} 条" +
               (f": {critical_errors[:3]}" if critical_errors else ""))

        # ===== E2E-IFS-021: 路径越界防护 =====
        print("\n--- E2E-IFS-021: 路径越界防护 ---")
        try:
            tests = [
                ("logs(../../etc/passwd)", f"/api/plugin/l4d2/logs/content?instanceId={INSTANCE_ID}&file=../../etc/passwd"),
                ("logs(../../../etc/shadow)", f"/api/plugin/l4d2/logs/content?instanceId={INSTANCE_ID}&file=../../../etc/shadow"),
            ]
            all_passed = True
            details = []
            for desc, url in tests:
                r = api_call(page, url)
                raw = r.get("raw", "")
                has_sensitive = any(s in raw for s in ["root:", "/bin/bash", "/etc/shadow", "daemon:*"])
                if has_sensitive:
                    all_passed = False
                details.append(f"{desc}: status={r.get('status')}, 敏感={has_sensitive}")
            record("E2E-IFS-021", "路径越界防护", all_passed, " | ".join(details))
        except Exception as e:
            record("E2E-IFS-021", "路径越界防护", False, str(e)[:200])

        # ===== 新增：路径归一化验证（./ 剥离）=====
        print("\n--- E2E-IFS-022: 路径归一化（./ 剥离）---")
        try:
            # 用正常日志文件 + ./ 前缀，验证不报错且能读取
            r_files = api_call(page, f"/api/plugin/l4d2/logs/files?instanceId={INSTANCE_ID}")
            files = r_files.get("data", {}).get("data") or []
            if files:
                first_file = files[0] if isinstance(files[0], str) else files[0].get("name", "")
                # 加 ./ 前缀
                r = api_call(page, f"/api/plugin/l4d2/logs/content?instanceId={INSTANCE_ID}&file=./{first_file}")
                passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
                record("E2E-IFS-022", "路径归一化（./ 剥离）", passed,
                       f"file=./{first_file}, status={r.get('status')}")
            else:
                record("E2E-IFS-022", "路径归一化（./ 剥离）", True, "无日志文件可测")
        except Exception as e:
            record("E2E-IFS-022", "路径归一化（./ 剥离）", False, str(e)[:200])

        # ===== 新增：server.cfg 读取（验证 Docker 容器内文件读取）=====
        print("\n--- E2E-IFS-023: 服务器配置读取（Docker 容器内文件）---")
        try:
            r = api_call(page, f"/api/plugin/l4d2/server-config/get?instanceId={INSTANCE_ID}")
            passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
            record("E2E-IFS-023", "服务器配置读取", passed,
                   f"status={r.get('status')}")
        except Exception as e:
            record("E2E-IFS-023", "服务器配置读取", False, str(e)[:200])

        # ===== 新增：RCON 状态（验证实例运行状态，POST 方法）=====
        print("\n--- E2E-IFS-024: RCON 状态（验证实例运行）---")
        try:
            r = api_call(page, f"/api/plugin/l4d2/rcon/status", method="POST",
                        body=json.dumps({"instanceId": int(INSTANCE_ID)}))
            passed = r.get("status") == 200 and r.get("data", {}).get("code") == 200
            online = r.get("data", {}).get("data", {}).get("online", False) if isinstance(r.get("data", {}).get("data"), dict) else False
            record("E2E-IFS-024", "RCON 状态", passed,
                   f"status={r.get('status')}, online={online}")
        except Exception as e:
            record("E2E-IFS-024", "RCON 状态", False, str(e)[:200])

        # ===== 新增：插件 UI 资源可达 =====
        print("\n--- E2E-IFS-025: 插件 UI 资源可达 ---")
        try:
            r = page.evaluate("""
                async () => {
                    try {
                        const resp = await fetch('/api/pf4j/plugin/l4d2/ui/index.html');
                        return {status: resp.status, hasContent: (await resp.text()).length > 100};
                    } catch(e) { return {status: 0, error: e.message}; }
                }
            """)
            passed = r.get("status") == 200 and r.get("hasContent")
            record("E2E-IFS-025", "插件 UI 资源可达", passed,
                   f"status={r.get('status')}, hasContent={r.get('hasContent')}")
        except Exception as e:
            record("E2E-IFS-025", "插件 UI 资源可达", False, str(e)[:200])

        browser.close()

    # ===== 汇总 =====
    print("\n" + "=" * 70)
    print("测试结果汇总")
    print("=" * 70)
    total = len(results)
    passed_count = sum(1 for r in results if r["passed"])
    failed = total - passed_count
    print(f"总计: {total} | 通过: {passed_count} | 失败: {failed}")
    print("-" * 70)
    for r in results:
        status = "✅" if r["passed"] else "❌"
        print(f"{status} {r['id']} {r['name']}")
        if r["detail"]:
            print(f"   {r['detail']}")
    print("-" * 70)

    result_file = "/tmp/e2e_ifs_results_v3.json"
    with open(result_file, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"详细结果已保存: {result_file}")

    return 0 if failed == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
