// E2E 环境契约：所有外部依赖（地址、凭据）只从环境变量读取，禁止写死进用例。
// 缺省值仅覆盖"本地受管模式 + 初始种子账号"这一种情况。
// .env.local（gitignored）在第一个 import 中先行装载，真实环境变量优先。
import "./env-file.js";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT_DIR = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const FRONTEND_PORT_FILE = join(PROJECT_DIR, "logs", "frontend.port");

function readManagedFrontendUrl() {
  if (!existsSync(FRONTEND_PORT_FILE)) return undefined;
  const port = readFileSync(FRONTEND_PORT_FILE, "utf8").trim();
  return /^\d+$/.test(port) ? `http://localhost:${port}` : undefined;
}

export const e2eEnv = {
  baseUrl:
    process.env.E2E_BASE_URL ??
    readManagedFrontendUrl() ??
    "http://localhost:3000",
  backendUrl: process.env.E2E_BACKEND_URL ?? "http://localhost:8080",

  // 种子管理员（backend db/data-*.sql 初始化）
  adminUser: process.env.E2E_ADMIN_USER ?? "admin",
  adminPass: process.env.E2E_ADMIN_PASS ?? "admin123",

  // 并行 worker 数（用例共享一个后端，默认串行）
  workers: Number(process.env.E2E_WORKERS ?? 1),

  // 牺牲主机（真实主机全链路用例专用）。任一缺失 ⇒ 主机类用例整组 SKIP。
  testHost: {
    address: process.env.E2E_TEST_HOST_ADDRESS ?? "",
    port: process.env.E2E_TEST_HOST_PORT ?? "22",
    username: process.env.E2E_TEST_HOST_USERNAME ?? "",
    auth: process.env.E2E_TEST_HOST_AUTH ?? "",
  },

  hasTestHost() {
    return Boolean(
      this.testHost.address && this.testHost.username && this.testHost.auth,
    );
  },
};
