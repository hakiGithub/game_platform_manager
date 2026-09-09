// 主机类用例的统一入口：无牺牲主机凭据的环境里，用 hostTest 写的用例整组跳过，
// 报告中注明 SKIP (no test host)，其余用例照常执行。
import { test } from "@playwright/test";
import { e2eEnv } from "./env.js";

export const hostTest = e2eEnv.hasTestHost() ? test : test.skip;
