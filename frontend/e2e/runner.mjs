// E2E runner：一条命令完成「起栈 → 跑用例 → 报告 → 清理」（规格：.scratch/ui-e2e-automation/spec.md）
//
// 用法：
//   npm run e2e                     # 受管模式（默认）：临时 SQLite 起后端+前端 → playwright → 清理
//   E2E_FAST=1 npm run e2e          # 受管模式跳过 mvn 编译（要求之前编译过一次，存在 core/target/cp.txt）
//   npm run e2e -- --attach         # 附着模式：不对 E2E_BASE_URL（缺省 http://localhost:3000）起栈，只跑用例
//
// 重复执行安全：受管模式启动前清掉上次残留的临时库；start-all.sh 自身会先释放 8080/3000 端口。
import { spawn, spawnSync } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { login } from "./support/api.js";
import { e2eEnv } from "./support/env.js";

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)));
const FRONTEND_DIR = resolve(E2E_DIR, "..");
const PROJECT_DIR = resolve(FRONTEND_DIR, "..");
const TMP_DIR = join(E2E_DIR, ".tmp");

const ATTACH = process.argv.includes("--attach");
const ROUND = process.argv.includes("--round");
const BASE_URL = e2eEnv.baseUrl;
const BACKEND_URL = e2eEnv.backendUrl;
const FRONTEND_PORT = new URL(BASE_URL).port || "80";
const BACKEND_PORT = new URL(BACKEND_URL).port || "80";

const log = (msg) => console.log(`[e2e-runner] ${msg}`);

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, {
    cwd: PROJECT_DIR,
    stdio: "inherit",
    shell: false,
    ...opts,
  });
  if (r.error) {
    log(`spawn ${cmd} 失败: ${r.error.message}`);
  }
  return r;
}

// 通用轮询：check() 返回 null = 就绪，返回字符串 = 未就绪原因，抛异常 = 瞬时失败继续等
async function pollUntil(check, label, timeoutMs = 240_000) {
  const deadline = Date.now() + timeoutMs;
  let lastErr = "never tried";
  while (Date.now() < deadline) {
    try {
      lastErr = await check();
      if (lastErr === null) {
        log(`${label} 已就绪`);
        return;
      }
    } catch (e) {
      lastErr = e.message;
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(
    `${label} 在 ${timeoutMs / 1000}s 内未就绪（最后状态: ${lastErr}）`,
  );
}

async function waitForHttp(url, label, timeoutMs = 240_000) {
  await pollUntil(
    async () => {
      const res = await fetch(url, { signal: AbortSignal.timeout(3000) });
      return res.status > 0 ? null : "no response";
    },
    label,
    timeoutMs,
  );
}

// 后端就绪标准 = 种子管理员真实登录成功。DatabaseInitializer 是 CommandLineRunner，
// 在 Tomcat 可服务之后才建核心表（sys_user 等）——端口能通 ≠ 可以开测。
async function waitForBackendLogin(backendUrl, timeoutMs = 240_000) {
  await pollUntil(
    async () => {
      const token = await login(backendUrl, e2eEnv.adminUser, e2eEnv.adminPass);
      return token ? null : "登录响应无 token";
    },
    "后端（登录验证）",
    timeoutMs,
  );
}

// 端口占用清理（与 start-all.sh 的 kill_port 同思路：netstat 找 LISTENING PID 再 taskkill）
function killPort(port) {
  if (process.platform === "win32") {
    const out =
      spawnSync("netstat", ["-ano"], { encoding: "utf8" }).stdout ?? "";
    const pids = new Set();
    for (const line of out.split("\n")) {
      const cols = line.trim().split(/\s+/);
      // TCP  0.0.0.0:8080  0.0.0.0:0  LISTENING  <pid>
      if (
        cols.length >= 5 &&
        cols[0] === "TCP" &&
        cols[1].endsWith(`:${port}`) &&
        cols[3] === "LISTENING"
      ) {
        pids.add(cols[4]);
      }
    }
    for (const pid of pids) {
      log(`释放端口 ${port}（杀 PID ${pid}）`);
      spawnSync("taskkill", ["/F", "/PID", pid], { stdio: "ignore" });
    }
  } else {
    const out =
      spawnSync("lsof", ["-ti", `:${port}`], { encoding: "utf8" }).stdout ?? "";
    for (const pid of out.split("\n").filter(Boolean)) {
      spawnSync("kill", ["-9", pid.trim()], { stdio: "ignore" });
    }
  }
}

function clearTmp() {
  rmSync(TMP_DIR, {
    recursive: true,
    force: true,
    maxRetries: 10,
    retryDelay: 1000,
  });
}

function startStack() {
  // 先释放端口再删临时库：上次残留的后端可能还锁着 sqlite 文件
  killPort(BACKEND_PORT);
  killPort(FRONTEND_PORT);
  clearTmp();
  const dbPath = join(TMP_DIR, `e2e-${Date.now()}.sqlite`).replaceAll(
    "\\",
    "/",
  );

  const args = ["scripts/start-all.sh", "--db", dbPath, "--skip-plugins"];
  if (
    process.env.E2E_FAST === "1" &&
    existsSync(join(PROJECT_DIR, "backend/core/target/cp.txt"))
  ) {
    log("E2E_FAST=1：跳过 mvn 编译");
    args.push("--skip-compile");
  } else {
    log("编译后端（增量）...");
  }
  log(`起栈: bash ${args.join(" ")}`);
  const r = run("bash", args);
  if (r.status !== 0) {
    throw new Error(
      `start-all.sh 退出码 ${r.status}，详见 logs/backend.log / logs/frontend.log`,
    );
  }
  return dbPath;
}

function stopStack() {
  log("清理：停止 8080/3000 端口进程");
  killPort(BACKEND_PORT);
  killPort(FRONTEND_PORT);
  clearTmp();
}

// 整轮分组：保序执行（无主机用例先行，部署腿最后——插件"零实例"用例依赖未部署状态）
const ROUND_GROUPS = [
  {
    name: "冒烟与导航",
    paths: ["e2e/infra", "e2e/main-app/auth", "e2e/main-app/workspace"],
  },
  {
    name: "无主机页面组",
    paths: [
      "e2e/main-app/games",
      "e2e/main-app/system",
      "e2e/main-app/tasks",
      "e2e/main-app/plugins",
    ],
  },
  { name: "主机纳管", paths: ["e2e/main-app/host"] },
  {
    name: "实例生命周期 l4d2",
    paths: ["e2e/main-app/instance/lifecycle.spec.js"],
  },
  { name: "插件子应用 l4d2", paths: ["e2e/plugins/l4d2"] },
  {
    name: "dst/sdtd 演练腿",
    paths: ["e2e/main-app/instance/drill-legs.spec.js"],
  },
];

function runPlaywright(args, opts = {}) {
  return run(
    "node",
    ["node_modules/@playwright/test/cli.js", "test", ...args],
    {
      cwd: FRONTEND_DIR,
      env: {
        ...process.env,
        E2E_BASE_URL: BASE_URL,
        E2E_BACKEND_URL: BACKEND_URL,
      },
      ...opts,
    },
  );
}

function parseSummary(output) {
  const pick = (re) => {
    const m = output.match(re);
    return m ? Number(m[1]) : 0;
  };
  return {
    passed: pick(/(\d+)\s+passed/),
    failed: pick(/(\d+)\s+failed/),
    skipped: pick(/(\d+)\s+skipped/),
    didNotRun: pick(/(\d+)\s+did not run/),
    interrupted:
      /interrupted|Timed out waiting for/.test(output) &&
      !/failed/.test(output),
  };
}

async function runRound() {
  const deadline =
    Date.now() + Number(process.env.E2E_ROUND_TIMEOUT ?? 150) * 60_000;
  const tally = { passed: 0, failed: 0, skipped: 0, didNotRun: 0 };
  for (const group of ROUND_GROUPS) {
    if (Date.now() > deadline) {
      log(`整轮看门狗超时，跳过剩余分组：${group.name}`);
      tally.didNotRun += 1; // 以分组数计的未执行标记
      continue;
    }
    log(`—— 分组[${group.name}]开始 ——`);
    // 管道捕获以便聚合；分组结束后回显完整输出保持可见性
    const r = runPlaywright([...group.paths, "--reporter=list"], {
      stdio: ["ignore", "pipe", "inherit"],
      encoding: "utf8",
    });
    if (r.stdout) process.stdout.write(r.stdout);
    const status = r.status ?? 1;
    const summary = parseSummary(r.stdout ?? "");
    tally.passed += summary.passed;
    tally.failed += summary.failed;
    tally.skipped += summary.skipped;
    tally.didNotRun += summary.didNotRun;
    log(
      `分组[${group.name}]结束：退出码 ${status}，+${summary.passed} 通过 / +${summary.failed} 失败 / +${summary.skipped} 跳过`,
    );
  }

  log("========== 整轮结果 ==========");
  log(
    `总数 ${tally.passed + tally.failed + tally.skipped} = 通过 ${tally.passed} + 失败 ${tally.failed} + 跳过 ${tally.skipped}` +
      (tally.didNotRun ? `（另有 ${tally.didNotRun} 项未执行）` : ""),
  );
  const verdict = tally.failed === 0 && tally.didNotRun === 0 ? "PASS" : "FAIL";
  log(`整轮通过判定（除显式 SKIP 外全部用例通过 = PASS）: ${verdict}`);
  log(`HTML 报告: npm run e2e:report`);
  return verdict === "PASS" ? 0 : 1;
}

async function main() {
  if (ATTACH) {
    log(`附着模式：直接对 ${BASE_URL} 跑用例（不起栈、不清理）`);
    await waitForBackendLogin(BACKEND_URL);
    await waitForHttp(`${BASE_URL}/`, "前端");
  } else {
    startStack();
    await waitForBackendLogin(BACKEND_URL);
    await waitForHttp(`${BASE_URL}/`, "前端");
  }

  if (ROUND) {
    return runRound();
  }

  log("执行 Playwright 用例...");
  // Windows 下 npx 是 .cmd，spawn 不带 shell 会静默失败 —— 直接用 node 调 CLI 入口
  const r = runPlaywright([]);
  log(`用例执行结束，退出码 ${r.status}（报告: npm run e2e:report）`);
  return r.status ?? 1;
}

let exitCode = 1;
try {
  exitCode = await main();
} catch (err) {
  log(`失败: ${err.message}`);
} finally {
  if (!ATTACH) stopStack();
}
process.exit(exitCode);
