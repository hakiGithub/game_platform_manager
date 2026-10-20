// 主机类用例统一入口：在依赖牺牲主机的用例文件顶部调用 skipWithoutTestHost()。
// 无凭据环境整文件跳过，报告注明 SKIP (no test host)；有凭据则全部正常执行。
import { test } from "@playwright/test";
import { e2eEnv } from "./env.js";

export function skipWithoutTestHost() {
  test.skip(!e2eEnv.hasTestHost(), "SKIP (no test host)");
}

// 真实 L4D2 实例用例统一入口：需要连接运行中服务器的 live 用例在文件顶部调用。
// 未显式提供实例 ID 时 SKIP，避免无主机环境因固定实例不存在而误报失败。
export function skipWithoutLiveL4d2Instance() {
  test.skip(
    !e2eEnv.hasLiveL4d2Instance(),
    "SKIP (set E2E_L4D2_LIVE_INSTANCE_ID to a live L4D2 instance)",
  );
}
