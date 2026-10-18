// 宿主卫生与 LinuxGSM 联网就绪检查。
// 托管环境使用 WSL 作为牺牲主机；所有检查都输出可读的 [CHECK][PASS|FAIL]，
// 失败时附带实际值、可能原因与修复建议，避免“GitHub 不通 → 静默 SKIP”。
import { execFileSync } from "node:child_process";
import { e2eEnv } from "./env.js";

function record(checks, name, ok, actual, options = {}) {
  const check = {
    name,
    ok: Boolean(ok),
    actual: actual == null ? "(empty)" : String(actual),
    details: options.details,
    hint: options.hint,
    remediation: options.remediation,
  };
  checks.push(check);
  console.log(`[CHECK][${check.ok ? "PASS" : "FAIL"}] ${name}: ${check.actual}`);
  if (!check.ok) {
    if (check.details) console.log(`  details: ${check.details}`);
    if (check.hint) console.log(`  hint: ${check.hint}`);
    if (check.remediation) console.log(`  remediation: ${check.remediation}`);
  }
  return check;
}

function result(checks, extra = {}) {
  const failures = checks.filter((check) => !check.ok);
  return {
    ok: failures.length === 0 && !extra.skipped,
    checks,
    failures,
    reason: extra.reason ?? failures.map((c) => `${c.name}: ${c.actual}`).join("; "),
    ...extra,
  };
}

function execWsl(args, timeoutMs = 30_000) {
  try {
    return {
      ok: true,
      stdout: execFileSync("wsl.exe", args, {
        encoding: "utf8",
        timeout: timeoutMs,
        stdio: ["ignore", "pipe", "pipe"],
      }).trim(),
      stderr: "",
    };
  } catch (error) {
    return {
      ok: false,
      stdout: String(error.stdout ?? "").trim(),
      stderr: String(error.stderr ?? error.message ?? "").trim(),
    };
  }
}

export function execWslBash(command, timeoutMs = 30_000) {
  return execWsl(["-e", "bash", "-lc", command], timeoutMs);
}

function wslBash(command, timeoutMs = 30_000) {
  return execWslBash(command, timeoutMs);
}

export function execWslDocker(args, timeoutMs = 30_000) {
  return execWsl(["-e", "docker", ...args], timeoutMs);
}

function wslDocker(args, timeoutMs = 30_000) {
  return execWslDocker(args, timeoutMs);
}

function isLoopbackAddress(address) {
  return address === "127.0.0.1" || address === "localhost";
}

/**
 * 本地牺牲主机可能是 127.0.0.1，也可能是 WSL eth0 LAN IP。
 * 先识别 loopback，再与当前 WSL 地址比较；比较阶段不调用 getWslLanIp()，
 * 避免形成 isLocalHost -> getWslLanIp -> isLocalHost 的循环。
 */
function isLocalHost(address = e2eEnv.testHost.address) {
  if (!e2eEnv.hasTestHost()) return false;
  if (isLoopbackAddress(address)) return true;
  if (!/^\d+\.\d+\.\d+\.\d+$/.test(String(address ?? ""))) return false;
  const out = wslBash("hostname -I | awk '{print $1}'");
  return out.ok && out.stdout === address;
}

function safeName(value, label) {
  if (!/^[A-Za-z0-9_.-]+$/.test(String(value ?? ""))) {
    throw new Error(`${label} 含不安全字符，拒绝拼入 shell: ${value}`);
  }
  return String(value);
}

export function cleanupStaleE2EContainers() {
  if (!isLocalHost()) return false;
  const cleaned = wslBash(
    "docker ps -aq --filter name=game-instance- | xargs -r docker rm -f",
  );
  if (!cleaned.ok) {
    console.log(
      `[host-hygiene] 清理跳过/失败: ${cleaned.stderr || cleaned.stdout || "unknown error"}`,
    );
    return false;
  }
  if (cleaned.stdout) {
    console.log(
      `[host-hygiene] 已清理残留容器: ${cleaned.stdout.split("\n").filter(Boolean).length} 个`,
    );
    return true;
  }
  return false;
}

export function getWslLanIp() {
  const out = wslBash("hostname -I | awk '{print $1}'");
  return out.ok && /^\d+\.\d+\.\d+\.\d+$/.test(out.stdout) ? out.stdout : null;
}

/**
 * 检查宿主机 Steam302 / hosts 映射状态。
 */
export function inspectSteam302HostState(options = {}) {
  const address = options.address ?? e2eEnv.testHost.address;
  if (!isLocalHost(address)) {
    return result([], { skipped: true, reason: "非本地 WSL 牺牲主机" });
  }
  const checks = [];
  const expectLan = Boolean(options.expectLan);
  const expectedLanIp = options.expectedLanIp ?? null;

  const running = wslDocker([
    "inspect",
    "--format",
    "{{.State.Running}}",
    "steam302",
  ]);
  record(
    checks,
    "Steam302 容器运行",
    running.ok && running.stdout === "true",
    running.ok ? `running=${running.stdout}` : running.stderr || "docker inspect 失败",
    {
      hint: "Steam302 未运行或容器名不是 steam302",
      remediation:
        "先在主机详情启动 Steam302；如未安装，先执行 Steam302 安装任务",
    },
  );

  const hosts = wslBash("grep '#S302' /etc/hosts || true");
  const lines = hosts.stdout ? hosts.stdout.split("\n").filter(Boolean) : [];
  const lanLines = lines.filter((line) => line.includes("#S302-LAN"));
  const parsedLan = lanLines
    .map((line) => line.trim().split(/\s+/)[0])
    .filter((ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip));
  const lanIp = parsedLan[0] ?? null;

  record(
    checks,
    "宿主机 hosts 含 #S302 标记",
    lines.length > 0,
    `entries=${lines.length}, lan=${lanLines.length}`,
    {
      details: lines.slice(0, 3).join(" | "),
      hint: "平台没有写入 Steam302 hosts 条目，或容器未运行",
      remediation:
        "启动 Steam302 后在主机详情刷新状态；平台会对 RUNNING 且 hosts 为空的状态自愈",
    },
  );
  const localLines = lines.filter((line) => !line.includes("#S302-LAN"));
  const parsedLocal = localLines
    .map((line) => line.trim().split(/\s+/)[0])
    .filter((ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip));
  const localIp = parsedLocal[0] ?? null;
  const validTarget = expectLan ? Boolean(lanIp) : Boolean(lanIp || localIp);
  const targetMatchesWsl =
    !expectLan || !expectedLanIp || lanIp === expectedLanIp;
  record(
    checks,
    expectLan
      ? `宿主机 hosts 的 #S302-LAN 指向 ${expectedLanIp ?? "有效 WSL IP"}`
      : "宿主机 hosts 的 #S302 目标 IP 有效",
    validTarget && targetMatchesWsl,
    lanIp
      ? `targetIp=${lanIp}, mode=LAN`
      : localIp
        ? `targetIp=${localIp}, mode=local`
        : `lanLines=${lanLines.length}, localLines=${localLines.length}`,
    {
      hint: expectLan
        ? targetMatchesWsl
          ? "容器共享加速未开启，或平台写入的 LAN IP 无法解析"
          : `期望 WSL LAN IP=${expectedLanIp}，实际 hosts 目标=${lanIp}`
        : "默认本机模式或容器共享模式均未写入有效目标 IP",
      remediation: expectLan
        ? "开启 Steam302「容器共享加速」，确认主机记录 IP 是 WSL eth0 IP 而不是 127.0.0.1"
        : "确认 Steam302 正在运行且 hosts 劫持条目已写入；若需验证 bridge 容器共享，再开启容器共享加速",
    },
  );
  return result(checks, {
    lanIp,
    localIp,
    mode: lanIp ? "lan" : localIp ? "local" : "unknown",
    hostLines: lines,
  });
}

function inspectMounts(containerName, checks) {
  const inspect = wslDocker([
    "inspect",
    "--format",
    "{{json .Mounts}}",
    containerName,
  ]);
  if (!inspect.ok) {
    record(checks, "容器 Mounts 可读取", false, inspect.stderr, {
      hint: `容器 ${containerName} 不存在或 Docker 不可用`,
      remediation: "确认实例容器已创建且状态为 running",
    });
    return { mounts: [] };
  }

  let mounts = [];
  try {
    mounts = JSON.parse(inspect.stdout);
  } catch {
    record(checks, "容器 Mounts JSON 可解析", false, inspect.stdout.slice(0, 240), {
      hint: "docker inspect 返回了非 JSON 内容",
      remediation: "检查 docker inspect 命令输出是否被 shell 包装污染",
    });
    return { mounts: [] };
  }

  record(checks, "容器 Mounts 可读取", true, `count=${mounts.length}`);
  const hostsMount = mounts.find((m) => m.Destination === "/etc/hosts");
  record(
    checks,
    "容器挂载宿主机 /etc/hosts",
    Boolean(hostsMount),
    hostsMount ? `source=${hostsMount.Source}` : "missing",
    {
      hint: "LinuxGSM 部署时未启用 mountHostCerts=true",
      remediation:
        "部署时打开“挂载宿主机证书与 hosts”，或确认 LinuxGsmDockerAdapter 注入了 /etc/hosts:ro",
    },
  );

  const caMount = mounts.find(
    (m) => m.Destination === "/etc/ssl/certs/ca-certificates.crt",
  );
  record(
    checks,
    "容器挂载宿主机 CA bundle",
    Boolean(caMount),
    caMount ? `source=${caMount.Source}` : "missing",
    {
      hint: "LinuxGSM 容器未挂载宿主机 /etc/ssl/certs/ca-certificates.crt",
      remediation:
        "确认 LinuxGsmDockerAdapter.injectRequiredCaCerts 生效后重新部署实例",
    },
  );
  return { mounts, hostsMount, caMount };
}

function inspectContainerHosts(containerName, checks) {
  const out = wslDocker(
    ["exec", containerName, "sh", "-lc", "grep '#S302' /etc/hosts || true"],
    20_000,
  );
  const lines = out.stdout ? out.stdout.split("\n").filter(Boolean) : [];
  record(
    checks,
    "容器内 /etc/hosts 可见 #S302 映射",
    out.ok && lines.length > 0,
    out.ok ? `entries=${lines.length}` : out.stderr || "docker exec 失败",
    {
      details: lines.slice(0, 3).join(" | "),
      hint: "容器未挂载宿主机 /etc/hosts，或宿主机没有 #S302 条目",
      remediation: "确认 mountHostCerts=true，且 Steam302 正在运行并已写入 hosts",
    },
  );
  return { lines };
}

function inspectDns(containerName, checks, options = {}) {
  const out = wslDocker(
    [
      "exec",
      containerName,
      "sh",
      "-lc",
      "getent hosts raw.githubusercontent.com || true",
    ],
    20_000,
  );
  const lines = out.stdout ? out.stdout.split("\n").filter(Boolean) : [];
  const ip = lines[0]?.trim().split(/\s+/)[0] ?? "";
  const loopback = ip === "127.0.0.1" || ip === "::1";
  const allowLoopback = Boolean(options.allowLoopback);
  const loopbackOk = allowLoopback ? true : !loopback;
  record(
    checks,
    allowLoopback
      ? "容器内 raw.githubusercontent.com DNS 可解析"
      : "容器内 raw.githubusercontent.com DNS 可解析且非 loopback",
    out.ok && lines.length > 0 && loopbackOk,
    out.ok ? lines[0] || "no result" : out.stderr || "getent 失败",
    {
      hint: allowLoopback
        ? "DNS 未命中 Steam302 hosts"
        : "DNS 未命中 Steam302 hosts，或错误映射到容器自身 127.0.0.1",
      remediation: allowLoopback
        ? "确认 Steam302 正在运行且部署启用 mountHostCerts=true"
        : "确认宿主机 #S302-LAN 指向 WSL LAN IP，且部署启用 mountHostCerts=true",
    },
  );
  return { lines, ip };
}

function inspectGithubHttps(containerName, checks, attempts = 3) {
  let last = "curl 未执行";
  for (let attempt = 1; attempt <= attempts; attempt++) {
    const out = wslDocker(
      [
        "exec",
        containerName,
        "sh",
        "-lc",
        "curl -m 8 -sS -I -o /dev/null -w '%{http_code}' https://raw.githubusercontent.com",
      ],
      20_000,
    );
    const code = out.stdout.trim().split(/\s+/).at(-1) ?? "";
    last = `http=${code || "none"}${out.stderr ? `, stderr=${out.stderr}` : ""}`;
    if (out.ok && /^[23]\d\d$/.test(code)) {
      record(checks, "容器内 GitHub HTTPS", true, `attempt=${attempt}, http=${code}`);
      return { ok: true, httpCode: code };
    }
  }
  record(checks, "容器内 GitHub HTTPS", false, last, {
    hint: "Steam302 DNS 映射、hosts 挂载或 CA bundle 任一未生效",
    remediation:
      "检查 Steam302 是否 RUNNING、#S302-LAN 是否指向 WSL IP、mountHostCerts 是否开启，并查看 curl stderr",
  });
  return { ok: false, httpCode: null, output: last };
}

function inspectShortname(containerName, shortname, checks) {
  const out = wslDocker(
    [
      "exec",
      containerName,
      "sh",
      "-lc",
      `test -e /app/${shortname} && echo ready || echo missing`,
    ],
    20_000,
  );
  const ready = out.ok && out.stdout === "ready";
  record(
    checks,
    `LinuxGSM /app/${shortname} 已生成`,
    ready,
    out.stdout || out.stderr || `exit=${out.ok ? 0 : 1}`,
    {
      hint: "LinuxGSM entrypoint 尚未完成，或 GitHub/SteamCMD 下载失败",
      remediation:
        "确认容器内 GitHub HTTPS 已通过；首次初始化可能需要数分钟，随后重新运行用例",
    },
  );
  return ready;
}

/**
 * 对运行中的 LinuxGSM 容器执行联网/挂载预检。
 */
export function inspectLinuxGsmEnvironment(containerName, shortname, options = {}) {
  safeName(containerName, "containerName");
  safeName(shortname, "shortname");
  const checks = [];
  const address = options.address ?? e2eEnv.testHost.address;
  if (!isLocalHost(address)) {
    return result(checks, {
      skipped: true,
      reason: "非本地 WSL 牺牲主机，无法执行容器文件/网络检查",
    });
  }

  const state = wslDocker([
    "inspect",
    "--format",
    "{{.State.Running}}",
    containerName,
  ]);
  record(
    checks,
    `容器 ${containerName} 运行`,
    state.ok && state.stdout === "true",
    state.ok ? `running=${state.stdout}` : state.stderr || "docker inspect 失败",
    {
      hint: "实例容器不存在、已停止或名称与部署配置不一致",
      remediation:
        "先等待部署完成；确认 CONTAINER_NAME 与 docker ps 中的名称一致",
    },
  );
  if (!(state.ok && state.stdout === "true")) {
    return result(checks, { containerReady: false });
  }

  const mounts = inspectMounts(containerName, checks);
  const hosts = inspectContainerHosts(containerName, checks);
  const dns = inspectDns(containerName, checks, {
    allowLoopback: Boolean(options.allowLoopback),
  });
  const github = inspectGithubHttps(containerName, checks);
  return result(checks, { containerReady: true, mounts, hosts, dns, github });
}

/**
 * 等待 LinuxGSM 容器首启自动安装完成。
 * 前置校验失败时立即返回，调用方应显式 SKIP/失败并打印 remediation。
 */
export function waitForLinuxGsmReady(
  containerName,
  shortname,
  timeoutMs = 30 * 60_000,
  options = {},
) {
  safeName(containerName, "containerName");
  safeName(shortname, "shortname");
  const address = options.address ?? e2eEnv.testHost.address;
  if (!isLocalHost(address)) {
    return result([], {
      skipped: true,
      reason: "非本地 WSL 牺牲主机，无法轮询容器内初始化状态",
    });
  }

  const preflight = inspectLinuxGsmEnvironment(containerName, shortname, options);
  if (!preflight.ok) return preflight;

  const deadline = Date.now() + timeoutMs;
  let last = "missing";
  while (Date.now() < deadline) {
    const checks = [];
    if (inspectShortname(containerName, shortname, checks)) {
      return result([...preflight.checks, ...checks], {
        containerReady: true,
        waitedMs: timeoutMs - (deadline - Date.now()),
      });
    }
    last = checks.at(-1)?.actual ?? last;
    if (Date.now() >= deadline) break;
    wslBash("sleep 20", 30_000);
  }

  return result(
    [
      ...preflight.checks,
      {
        name: `LinuxGSM /app/${shortname} 就绪超时`,
        ok: false,
        actual: `last=${last}, timeoutMs=${timeoutMs}`,
        hint: "初始化超过等待上限",
        remediation:
          "查看 docker logs --tail 200；若 GitHub 可达但 SteamCMD 下载慢，提高用例超时或预拉游戏数据",
      },
    ],
    { containerReady: true, timeout: true },
  );
}
