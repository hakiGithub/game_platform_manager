/**
 * 页面: 容器资源 / 容器列表（ADR-0023 容器认领）
 * 用例: 未关联容器「识别为游戏实例」全链路 —— 入口按钮 / 对话框预填 / 认领提交 / 关联回显 / 记录级删除
 * 前置:
 *  - 平台前后端已运行（附着部署栈或受管栈均可）
 *  - 需要牺牲主机凭据（skipWithoutTestHost），且该主机 Docker 中存在名为 dstserver-lgsm 的未关联容器
 *    （WSL 牺牲主机常驻该容器；若不存在则整组 SKIP）
 * 通过标准（逐用例声明）:
 *  - 入口与预填: 选主机后 dstserver-lgsm 行出现「识别为游戏实例」按钮；对话框实例名预填=容器名、
 *    游戏按镜像名猜中 dst、部署方式解析为 docker
 *  - 认领链路: 提交成功提示出现；容器行「关联实例」列回显实例名；实例列表出现同名认领实例且 runStatus 与容器一致
 *  - 记录级删除: 认领实例删除后容器保留（仍在线），关联列回到「未关联」
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";
import { e2eEnv } from "../../support/env.js";
import { login } from "../../support/api.js";

const HOST_NAME = "wsl-253";
const CONTAINER_NAME = "dstserver-lgsm";
const GAME_CODE = "dst";

async function apiToken() {
  return login(e2eEnv.backendUrl, e2eEnv.adminUser, e2eEnv.adminPass);
}

/** 目标容器存在才执行（幂等：若已有关联实例先记录级清理） */
async function ensureFixtureReady() {
  let token;
  try {
    token = await apiToken();
  } catch {
    return false;
  }
  const base = e2eEnv.backendUrl;
  const hosts = await fetch(`${base}/api/hosts`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then((r) => r.json());
  const host = (hosts?.data?.records || hosts?.data || []).find(
    (h) => h.name === HOST_NAME,
  );
  if (!host) return false;

  const containers = await fetch(
    `${base}/api/docker/hosts/${host.id}/containers`,
    { headers: { Authorization: `Bearer ${token}` } },
  ).then((r) => r.json());
  const target = (containers?.data?.containers || []).find(
    (c) => c.containerName === CONTAINER_NAME,
  );
  if (!target) return false;

  if (target.isLinked && target.linkedInstanceId) {
    // 上次残留：记录级删除（不动容器）
    await fetch(
      `${base}/api/instances/${target.linkedInstanceId}?deleteContainer=false`,
      { method: "DELETE", headers: { Authorization: `Bearer ${token}` } },
    );
  }
  return true;
}

test.beforeAll(async () => {
  if (!(await ensureFixtureReady())) {
    test.skip(true, `牺牲主机缺少目标容器 ${CONTAINER_NAME}，跳过认领用例`);
  }
});

test("未关联容器可识别为游戏实例并回显关联", async ({ page }) => {
  await injectSession(page);
  await page.goto("/resources/containers/list");

  // 选择牺牲主机节点（页面会记忆上次选择：已选目标主机则跳过）
  const hostSelected = page
    .locator(".el-select")
    .filter({ hasText: HOST_NAME })
    .first();
  if (!(await hostSelected.isVisible().catch(() => false))) {
    await page.getByText("选择目标主机").click();
    await page.getByRole("option", { name: new RegExp(HOST_NAME) }).click();
  }

  // 目标行出现「识别为游戏实例」按钮（运行中容器行内唯一的 success 型按钮）
  const row = page.locator(".el-table__row").filter({
    has: page.getByText(CONTAINER_NAME, { exact: true }),
  });
  await expect(row).toBeVisible();
  await expect(row.locator(".el-button--success")).toHaveCount(1);

  // 打开认领对话框：实例名预填=容器名；镜像名不含游戏码 → 不预选（正确行为），手动选 dst
  await row.locator(".el-button--success").click();
  const dialog = page.locator(".el-dialog").filter({
    has: page.getByText("识别为游戏实例", { exact: true }),
  });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.locator("input").first(),
  ).toHaveValue(CONTAINER_NAME);
  await expect(dialog.getByText("已按镜像名自动预选，可修改")).toHaveCount(0);

  await dialog.locator(".el-select").first().click();
  await page.getByRole("option", { name: new RegExp(`\\(${GAME_CODE}\\)$`, "i") }).click();
  await expect(
    dialog.locator(".el-tag", { hasText: "docker" }).first(),
  ).toBeVisible();

  // 提交认领
  await dialog.getByRole("button", { name: "认领" }).click();
  await expect(page.getByText(/认领成功，已创建实例/)).toBeVisible({
    timeout: 15000,
  });

  // 关联回显：容器行「关联实例」列出现实例名链接
  await expect(row.locator(".el-link", { hasText: /.+/ })).toBeVisible({
    timeout: 15000,
  });
  // 已关联后行内不再出现认领按钮
  await expect(row.locator(".el-button--success")).toHaveCount(0);
});

test("认领实例记录级删除后容器保留且关联解除", async ({ page }) => {
  const token = await apiToken();
  const base = e2eEnv.backendUrl;

  await injectSession(page);
  await page.goto("/services/instances/list");

  // 实例列表出现认领实例行
  const row = page.locator(".el-table__row").filter({
    has: page.getByText(CONTAINER_NAME, { exact: true }),
  });
  await expect(row).toBeVisible();

  // API 记录级删除（deleteContainer=false 默认），容器保留
  const instances = await fetch(
    `${base}/api/instances?page=1&size=50`,
    { headers: { Authorization: `Bearer ${token}` } },
  ).then((r) => r.json());
  const inst = (instances?.data?.records || []).find(
    (r) => r.instanceName === CONTAINER_NAME,
  );
  expect(inst, "认领实例应存在于实例列表").toBeTruthy();
  expect(inst.runtimeMetadata?.adopted, "认领实例应带 adopted 标记").toBe(true);

  const del = await fetch(
    `${base}/api/instances/${inst.id}?deleteContainer=false`,
    { method: "DELETE", headers: { Authorization: `Bearer ${token}` } },
  ).then((r) => r.json());
  expect(del?.code).toBe(200);

  // 容器仍在运行（未被 docker rm），关联回到未关联
  const hosts = await fetch(`${base}/api/hosts`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then((r) => r.json());
  const host = (hosts?.data?.records || hosts?.data || []).find(
    (h) => h.name === HOST_NAME,
  );
  const containers = await fetch(
    `${base}/api/docker/hosts/${host.id}/containers`,
    { headers: { Authorization: `Bearer ${token}` } },
  ).then((r) => r.json());
  const target = (containers?.data?.containers || []).find(
    (c) => c.containerName === CONTAINER_NAME,
  );
  expect(target, "容器应保留").toBeTruthy();
  expect(target.status).toBe("running");
  expect(target.isLinked).toBeFalsy();
});
