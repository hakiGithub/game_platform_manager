// 宿主卫生（仅托管环境）：清理上一次失败链遗留的 E2E 部署容器。
// 平台 Docker 适配器以 game-instance-<自增ID> 命名容器，临时库每轮重建后
// 自增 ID 从 1 开始，会与上一轮残留容器同名冲突（端口/名字占用导致部署失败）。
// 只删 game-instance-* —— 精确匹配 E2E 产物，绝不触碰宿主机上的真实容器。
// 远程牺牲主机不做此操作（无 SSH 通道，见规格接缝约定）。
import { execFileSync } from "node:child_process";
import { e2eEnv } from "./env.js";

export function cleanupStaleE2EContainers() {
  if (!e2eEnv.hasTestHost()) return false;
  const addr = e2eEnv.testHost.address;
  if (addr !== "127.0.0.1" && addr !== "localhost") return false;
  try {
    const out = execFileSync(
      "wsl.exe",
      [
        "-e",
        "bash",
        "-c",
        "docker ps -aq --filter name=game-instance- | xargs -r docker rm -f",
      ],
      { encoding: "utf8", timeout: 30_000, stdio: ["ignore", "pipe", "pipe"] },
    );
    if (out.trim()) {
      console.log(
        `[host-hygiene] 已清理残留容器: ${out.trim().split("\n").length} 个`,
      );
      return true;
    }
    return false;
  } catch (err) {
    console.log(`[host-hygiene] 清理跳过/失败: ${err.message.split("\n")[0]}`);
    return false;
  }
}

function isLocalHost() {
  const addr = e2eEnv.testHost.address;
  return e2eEnv.hasTestHost() && (addr === "127.0.0.1" || addr === "localhost");
}

function wslDocker(args, timeoutMs = 30_000) {
  return execFileSync("wsl.exe", ["-e", "bash", "-c", args], {
    encoding: "utf8",
    timeout: timeoutMs,
    stdio: ["ignore", "pipe", "pipe"],
  });
}

// 等待 LinuxGSM 容器首启自动安装完成（/app/<shortname> 脚本就绪）。
// 仅本地托管环境可用。容器内访问 GitHub 下载 serverlist.csv 不通时（国内网络
// 典型问题，LinuxGsmDockerAdapter 注释亦有预言），初始化永不完成——先探测
// 连通性，不通则快速返回 false，由用例显式 SKIP 并注明环境阻塞。
export function waitForLinuxGsmReady(
  containerName,
  shortname,
  timeoutMs = 30 * 60_000,
) {
  if (!isLocalHost()) return false;
  // 连通性预检：容器内 curl GitHub（8 秒超时，3 次尝试）
  let githubReachable = false;
  for (let i = 0; i < 3; i++) {
    try {
      const ok = wslDocker(
        `docker exec ${containerName} curl -m 8 -sI https://raw.githubusercontent.com -o /dev/null -w '%{http_code}'`,
        20_000,
      ).trim();
      if (ok.startsWith("2") || ok.startsWith("3")) {
        githubReachable = true;
        break;
      }
    } catch {
      /* curl 失败继续尝试 */
    }
  }
  if (!githubReachable) {
    console.log(
      "[host-hygiene] 容器内访问 GitHub 不通，LinuxGSM 初始化无法完成（环境阻塞）",
    );
    return false;
  }
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const out = wslDocker(
        `docker exec ${containerName} ls /app/${shortname} 2>/dev/null | wc -l`,
        20_000,
      );
      if (out.trim() !== "0") return true;
    } catch {
      /* 容器可能尚在重建，继续等 */
    }
    try {
      const state = wslDocker(
        `docker inspect ${containerName} | grep -c '"Running": true'`,
        15_000,
      ).trim();
      if (state === "0") return false;
    } catch {
      /* 容器不存在等情况继续等 */
    }
    execFileSync("wsl.exe", ["-e", "bash", "-c", "sleep 20"], {
      timeout: 30_000,
      stdio: "ignore",
    });
  }
  return false;
}
