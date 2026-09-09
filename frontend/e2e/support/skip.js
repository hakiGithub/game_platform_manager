// 主机类用例统一入口：在依赖牺牲主机的用例文件顶部调用 skipWithoutTestHost()。
// 无凭据环境整文件跳过，报告注明 SKIP (no test host)；有凭据则全部正常执行。
import { test } from "@playwright/test";
import { e2eEnv } from "./env.js";

export function skipWithoutTestHost() {
  test.skip(!e2eEnv.hasTestHost(), "SKIP (no test host)");
}
