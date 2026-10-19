// E2E runner：一条命令完成「起栈 → 跑用例 → 报告 → 清理」（规格：.scratch/ui-e2e-automation/spec.md）
//
// 用法：
//   npm run e2e                     # 受管模式（默认）：临时 SQLite 起后端+前端 → playwright → 清理
//   E2E_FAST=1 npm run e2e          # 受管模式跳过 mvn 编译（要求之前编译过一次，存在 core/target/cp.txt）
//   npm run e2e -- --attach         # 附着模式：不对 E2E_BASE_URL（缺省 http://localhost:3000）起栈，只跑用例
//
// 重复执行安全：受管模式启动前清掉上次残留的临时库；端口脚本自动避开被占用的前端端口。
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createApi, login } from "./support/api.js";
import { parseE2eArgs } from "./support/e2e-args.js";
import { e2eEnv } from "./support/env.js";

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)));
const FRONTEND_DIR = resolve(E2E_DIR, "..");
const PROJECT_DIR = resolve(FRONTEND_DIR, "..");
const TMP_DIR = join(E2E_DIR, ".tmp");
const FRONTEND_PORT_FILE = join(PROJECT_DIR, "logs", "frontend.port");
const BACKEND_PID_FILE = join(PROJECT_DIR, "backend", "logs", "game-platform-manager.pid");
const BACKEND_ARG_FILE_POINTER = join(
  PROJECT_DIR,
  "backend",
  "logs",
  "game-platform-jvm-args.path",
);
const FRONTEND_PID_FILE = join(PROJECT_DIR, "frontend", "logs", "frontend.pid");
const VITE_CACHE_DIR = join(FRONTEND_DIR, "node_modules", ".vite");

const {
  attach: ATTACH,
  round: ROUND,
  playwrightArgs: PLAYWRIGHT_ARGS,
} = parseE2eArgs(process.argv.slice(2));
let BASE_URL = e2eEnv.baseUrl;
const BACKEND_URL = e2eEnv.backendUrl;
let FRONTEND_PORT = new URL(BASE_URL).port || "80";
const BACKEND_PORT = new URL(BACKEND_URL).port || "80";

const log = (msg) => console.log(`[e2e-runner] ${msg}`);
const check = (name, ok, actual, remediation) => {
  console.log(`[CHECK][${ok ? "PASS" : "FAIL"}] ${name}: ${actual}`);
  if (!ok && remediation) {
    console.log(`  remediation: ${remediation}`);
  }
};

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, {
    cwd: PROJECT_DIR,
    stdio: "inherit",
    shell: false,
    maxBuffer: 64 * 1024 * 1024,
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
  try {
    let lastStatus = "no response";
    await pollUntil(
      async () => {
        const res = await fetch(url, { signal: AbortSignal.timeout(3000) });
        lastStatus = `HTTP ${res.status}`;
        return res.status > 0 ? null : "no response";
      },
      label,
      timeoutMs,
    );
    check(`${label} HTTP 就绪`, true, `url=${url}, status=${lastStatus}`);
  } catch (error) {
    check(
      `${label} HTTP 就绪`,
      false,
      `${url} -> ${error.message}`,
      `确认端口 ${FRONTEND_PORT} 有进程监听，并查看 logs/frontend.log`,
    );
    throw error;
  }
}

// 后端就绪标准 = 种子管理员真实登录成功。DatabaseInitializer 是 CommandLineRunner，
// 在 Tomcat 可服务之后才建核心表（sys_user 等）——端口能通 ≠ 可以开测。
async function waitForBackendLogin(backendUrl, timeoutMs = 240_000) {
  let actual = "尚未尝试";
  try {
    await pollUntil(
      async () => {
        const token = await login(
          backendUrl,
          e2eEnv.adminUser,
          e2eEnv.adminPass,
        );
        if (!token) return "登录响应无 token";

        // ApplicationReadyEvent 监听器会在端口可访问后扫描并写入游戏元数据。
        // 此时登录已成功，但 SQLite 仍可能持有写锁；以元数据可读作为业务就绪信号。
        const api = createApi(backendUrl, token);
        const games = await api.get("/api/games/list");
        const list = games.body?.data;
        actual = `login=ok, gamesHttp=${games.status}, games=${Array.isArray(list) ? list.length : "n/a"}`;
        if (games.status !== 200 || !Array.isArray(list) || list.length === 0) {
          return `游戏元数据尚未就绪（HTTP ${games.status}, count=${Array.isArray(list) ? list.length : "n/a"}）`;
        }
        return null;
      },
      "后端（登录验证）",
      timeoutMs,
    );
    check("后端登录与游戏元数据就绪", true, actual);
  } catch (error) {
    check(
      "后端登录与游戏元数据就绪",
      false,
      `${backendUrl} -> ${error.message} (${actual})`,
      `确认端口 ${BACKEND_PORT} 有后端进程、种子管理员凭据有效，并查看 logs/backend.log`,
    );
    throw error;
  }
}

function readPidFile(file) {
  if (!existsSync(file)) return null;
  const value = readFileSync(file, "utf8").trim();
  return /^\d+$/.test(value) ? value : null;
}

function processCommandLine(pid) {
  if (process.platform === "win32") {
    return (
      spawnSync(
        "pwsh",
        [
          "-NoProfile",
          "-NonInteractive",
          "-Command",
          `(Get-CimInstance Win32_Process -Filter 'ProcessId = ${pid}').CommandLine`,
        ],
        { encoding: "utf8" },
      ).stdout ?? ""
    );
  }
  return spawnSync("ps", ["-p", pid, "-o", "command="], { encoding: "utf8" }).stdout ?? "";
}

function backendArgFile() {
  if (!existsSync(BACKEND_ARG_FILE_POINTER)) return null;
  const value = readFileSync(BACKEND_ARG_FILE_POINTER, "utf8").trim();
  return value || null;
}

function commandUsesArgFile(commandLine, argFile) {
  if (!argFile) return false;
  const normalized = commandLine.replaceAll("/", "\\").toLowerCase();
  return normalized.includes(argFile.replaceAll("/", "\\").toLowerCase());
}

function listenerPids(port) {
  if (process.platform === "win32") {
    const out =
      spawnSync("netstat", ["-ano"], { encoding: "utf8" }).stdout ?? "";
    return out
      .split("\n")
      .map((line) => line.trim().split(/\s+/))
      .filter(
        (cols) =>
          cols.length >= 5 &&
          cols[0] === "TCP" &&
          cols[1].endsWith(`:${port}`) &&
          cols[3] === "LISTENING",
      )
      .map((cols) => cols[4]);
  }
  return (spawnSync("lsof", ["-ti", `:${port}`], { encoding: "utf8" }).stdout ?? "")
    .split("\n")
    .filter(Boolean);
}

function stopManagedProcess(port, expectedCommand, pidFile) {
  if (process.platform === "win32") {
    const candidatePids = new Set(listenerPids(port));
    const savedPid = readPidFile(pidFile);
    if (savedPid) candidatePids.add(savedPid);

    let stopped = false;
    for (const pid of candidatePids) {
      const cmd = processCommandLine(pid);
      // 后端用 java @argfile 启动时，进程命令行只有 java.exe 与 argfile 路径；
      // worktree 归属要从 argfile 内容判断，不能因为命令行不含项目路径就跳过。
      const backendArgFileOwned =
        port === BACKEND_PORT &&
        commandUsesArgFile(cmd, backendArgFile());
      const worktreeMatch =
        cmd.includes(PROJECT_DIR) || backendArgFileOwned;
      if (!worktreeMatch || !expectedCommand.test(cmd)) continue;
      log(`释放端口 ${port}（停止本项目 PID ${pid}）`);
      spawnSync("taskkill", ["/F", "/PID", pid], { stdio: "ignore" });
      stopped = true;
    }
    return stopped;
  } else {
    let stopped = false;
    for (const pid of listenerPids(port)) {
      const cmd = processCommandLine(pid);
      if (!cmd.includes(PROJECT_DIR) || !expectedCommand.test(cmd)) continue;
      spawnSync("kill", ["-9", pid], { stdio: "ignore" });
      stopped = true;
    }
    return stopped;
  }
}

function stopStack() {
  log(`清理：停止后端 ${BACKEND_PORT} 和前端 ${FRONTEND_PORT} 端口进程`);
  if (!stopManagedProcess(BACKEND_PORT, /java(?:\.exe)?/i, BACKEND_PID_FILE)) {
    log(`后端端口 ${BACKEND_PORT} 没有可清理的本项目进程`);
  }
  if (
    !stopManagedProcess(
      FRONTEND_PORT,
      /[\\/]frontend[\\/]+node_modules[\\/]+vite[\\/]+bin[\\/]+vite\.js/i,
      FRONTEND_PID_FILE,
    )
  ) {
    log(`前端端口 ${FRONTEND_PORT} 没有可清理的本项目进程`);
  }
  clearTmp();
}

function clearTmp() {
  rmSync(TMP_DIR, {
    recursive: true,
    force: true,
    maxRetries: 10,
    retryDelay: 1000,
  });
}

function readManagedFrontendPort() {
  if (!existsSync(FRONTEND_PORT_FILE)) return null;
  const port = readFileSync(FRONTEND_PORT_FILE, "utf8").trim();
  return /^\d+$/.test(port) ? port : null;
}

function optimizeViteDependencies() {
  const viteCli = join(FRONTEND_DIR, "node_modules", "vite", "bin", "vite.js");
  if (!existsSync(viteCli)) {
    throw new Error(`未找到 Vite CLI: ${viteCli}`);
  }
  log("预构建 Vite 依赖...");
  const r = run(process.execPath, [viteCli, "optimize", "--force"], {
    cwd: FRONTEND_DIR,
  });
  if (r.status !== 0) {
    throw new Error(`Vite 依赖预构建失败，退出码 ${r.status}`);
  }
}

function startStack() {
  // 受管模式自行选端口；不要杀 3000 上的 WSL 转发或其他应用。
  stopManagedProcess(BACKEND_PORT, /java(?:\.exe)?/i, BACKEND_PID_FILE);
  clearTmp();
  // Vite 依赖预构建缓存在依赖或浏览器状态变化后可能留下失效 chunk，
  // Wujie 跨路由加载时会表现为入口 HTML 已返回但 vendor chunk 永不响应。
  // 缓存可安全重建，受管 E2E 每次从干净版本开始。
  rmSync(VITE_CACHE_DIR, { recursive: true, force: true });
  log("已清理 Vite 依赖缓存");
  optimizeViteDependencies();
  mkdirSync(TMP_DIR, { recursive: true });
  const dbPath = join(TMP_DIR, `e2e-${Date.now()}.sqlite`).replaceAll(
    "\\",
    "/",
  );

  const skipCompile =
    process.env.E2E_FAST === "1" &&
    existsSync(join(PROJECT_DIR, "backend/core/target/cp.txt"));
  if (skipCompile) {
    log("E2E_FAST=1：跳过 mvn 编译");
  } else {
    log("编译后端（增量）...");
  }

  if (process.platform === "win32") {
    const backendArgs = [
      "-NoProfile",
      "-NonInteractive",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      join(PROJECT_DIR, "backend/scripts/rebuild-restart.ps1"),
      "-DbPath",
      dbPath,
    ];
    if (skipCompile) backendArgs.push("-SkipCompile");
    log(`起后端: pwsh ${backendArgs.join(" ")}`);
    const backend = run("pwsh", backendArgs);
    if (backend.status !== 0) {
      throw new Error(
        `后端启动脚本退出码 ${backend.status}，详见 backend/logs/application.log`,
      );
    }

    const frontendArgs = [
      "-NoProfile",
      "-NonInteractive",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      join(PROJECT_DIR, "frontend/scripts/rebuild-restart.ps1"),
      "-Port",
      "0",
    ];
    log(`起前端: pwsh ${frontendArgs.join(" ")}`);
    const frontend = run("pwsh", frontendArgs);
    if (frontend.status !== 0) {
      throw new Error(
        `前端启动脚本退出码 ${frontend.status}，详见 frontend/logs/frontend.log`,
      );
    }
  } else {
    const args = [
      "scripts/start-all.sh",
      "--db",
      dbPath,
      "--port",
      "0",
    ];
    if (skipCompile) args.push("--skip-compile");
    log(`起栈: bash ${args.join(" ")}`);
    const r = run("bash", args);
    if (r.status !== 0) {
      throw new Error(
        `start-all.sh 退出码 ${r.status}，详见 logs/backend.log / logs/frontend.log`,
      );
    }
  }

  const actualFrontendPort = readManagedFrontendPort();
  if (!actualFrontendPort) {
    throw new Error(
      "启动脚本未写入 logs/frontend.port，无法确定 E2E 前端地址",
    );
  }
  FRONTEND_PORT = actualFrontendPort;
  BASE_URL = `http://localhost:${actualFrontendPort}`;
  process.env.E2E_BASE_URL = BASE_URL;
  return dbPath;
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
  { name: "容器认领", paths: ["e2e/main-app/docker/adopt-container.spec.js"] },
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
    process.execPath,
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
  if (typeof output !== "string") {
    return { passed: 0, failed: 0, skipped: 0, didNotRun: 0 };
  }
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
    const groupOk =
      status === 0 && summary.failed === 0 && summary.didNotRun === 0;
    check(
      `分组[${group.name}]`,
      groupOk,
      `exit=${status}, passed=${summary.passed}, failed=${summary.failed}, skipped=${summary.skipped}, didNotRun=${summary.didNotRun}`,
      groupOk
        ? undefined
        : `查看 e2e/.artifacts 对应用例的 error-context.md、截图与 trace，并重跑该分组`,
    );
  }

  log("========== 整轮结果 ==========");
  log(
    `总数 ${tally.passed + tally.failed + tally.skipped} = 通过 ${tally.passed} + 失败 ${tally.failed} + 跳过 ${tally.skipped}` +
      (tally.didNotRun ? `（另有 ${tally.didNotRun} 项未执行）` : ""),
  );
  const verdict = tally.failed === 0 && tally.didNotRun === 0 ? "PASS" : "FAIL";
  check(
    "整轮通过判定（除显式 SKIP 外全部用例通过 = PASS）",
    verdict === "PASS",
    `passed=${tally.passed}, failed=${tally.failed}, skipped=${tally.skipped}, didNotRun=${tally.didNotRun}`,
    verdict === "PASS"
      ? undefined
      : "先按失败分组重跑定位；环境类问题查看上方 [CHECK][FAIL] 的实际值与 remediation",
  );
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
  const r = runPlaywright(PLAYWRIGHT_ARGS, {
    stdio: ["ignore", "pipe", "inherit"],
    encoding: "utf8",
  });
  if (r.stdout) process.stdout.write(r.stdout);
  const status = r.status ?? 1;
  const summary = parseSummary(r.stdout ?? "");
  const verdict =
    status === 0 && summary.failed === 0 && summary.didNotRun === 0
      ? "PASS"
      : "FAIL";
  check(
    "Playwright 用例通过判定（除显式 SKIP 外全部通过 = PASS）",
    verdict === "PASS",
    `exit=${status}, passed=${summary.passed}, failed=${summary.failed}, skipped=${summary.skipped}, didNotRun=${summary.didNotRun}`,
    verdict === "PASS"
      ? undefined
      : "查看 e2e/.artifacts 对应用例的 error-context.md、截图与 trace；环境类问题查看上方 [CHECK][FAIL] 的 remediation",
  );
  log(`HTML 报告: npm run e2e:report`);
  return verdict === "PASS" ? 0 : 1;
}

let exitCode = 1;
try {
  exitCode = await main();
} catch (err) {
  check(
    "E2E runner 执行",
    false,
    err.message,
    "查看 logs/backend.log、logs/frontend.log 与上方失败检查点；确认端口脚本已启动对应进程",
  );
} finally {
  if (!ATTACH) stopStack();
}
process.exit(exitCode);
