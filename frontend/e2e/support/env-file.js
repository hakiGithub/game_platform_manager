// 本地凭据装载：读取 e2e/.env.local（gitignored，凭据不入库），KEY=VALUE 逐行。
// 必须在 env.js 读取 process.env 之前执行（作为 env.js 的首个 import）。
// 已存在的真实环境变量优先，不被文件覆盖。
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ENV_LOCAL = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  ".env.local",
);
if (existsSync(ENV_LOCAL)) {
  for (const line of readFileSync(ENV_LOCAL, "utf8").split("\n")) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2];
  }
}
