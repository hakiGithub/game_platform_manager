/**
 * 页面: 无（基础设施自检）
 * 用例: 牺牲主机凭据契约两态验证（哑用例）
 * 前置: 不依赖任何页面与主机连通性；凭据来自 E2E_TEST_HOST_* 环境契约
 * 步骤: 有凭据时校验契约字段形态（地址非空、端口为数字、用户名与凭据非空）
 * 通过标准: 有凭据 → 断言全过；无凭据 → 本文件整组 SKIP 并注明 SKIP (no test host)
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../support/env.js";
import { skipWithoutTestHost } from "../support/skip.js";

skipWithoutTestHost();

test("牺牲主机凭据契约自检", async () => {
  expect(e2eEnv.testHost.address).toBeTruthy();
  expect(Number(e2eEnv.testHost.port)).not.toBeNaN();
  expect(e2eEnv.testHost.username).toBeTruthy();
  expect(e2eEnv.testHost.auth).toBeTruthy();
});
