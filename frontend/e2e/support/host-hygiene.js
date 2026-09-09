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
      console.log(`[host-hygiene] 已清理残留容器: ${out.trim().split("\n").length} 个`);
      return true;
    }
    return false;
  } catch (err) {
    console.log(`[host-hygiene] 清理跳过/失败: ${err.message.split("\n")[0]}`);
    return false;
  }
}
