/**
 * 页面: 容器资源 / 容器列表（ADR-0023 容器认领）
 * 用例: 未关联容器「识别为游戏实例」全链路 —— 入口按钮 / 对话框预填 / 认领提交 / 关联回显 / 记录级删除
 * 前置:
 *  - 平台前后端已运行（附着部署栈或受管栈均可）
 *  - 需要牺牲主机凭据（skipWithoutTestHost），本用例动态创建 WSL 主机与 alpine fixture
 * 通过标准（逐用例声明）:
 *  - 入口与预填: fixture 行出现「识别为游戏实例」按钮；对话框实例名预填=容器名、
 *    镜像名不含游戏码时显示「未自动预选」；手动选 l4d2 后部署方式解析为 docker
 *  - 认领链路: 提交成功提示出现；容器行「关联实例」列回显实例名；实例列表出现同名认领实例
 *  - 记录级删除: 认领实例删除后容器保留（仍在线），关联列回到「未关联」
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import {
  createApi,
  ensureHostByIp,
  login,
  removeHostsByName,
} from "../../support/api.js";
import {
  execWslDocker,
  getWslLanIp,
  inspectSteam302HostState,
} from "../../support/host-hygiene.js";
import { injectSession } from "../../support/session.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-adopt-host";
const CONTAINER_NAME = "e2e-adopt-fixture";
const GAME_CODE = "l4d2";

test.describe.serial("容器认领", () => {
  let api = null;
  let hostId = null;
  let adoptedInstanceId = null;
  let hostName = null;
  let createdHost = false;

  test.beforeAll(async () => {
    test.setTimeout(3 * 60_000);
    const lanIp = getWslLanIp();
    if (!lanIp) {
      throw new Error(
        "[CHECK][FAIL] 无法获取 WSL eth0 IP; remediation: 确认 wsl.exe 可执行并能访问默认发行版",
      );
    }
    const steam302 = inspectSteam302HostState({ address: lanIp });
    console.log(
      `[CHECK][INFO] 容器认领 fixture 前置: Steam302=${steam302.ok ? "PASS" : "FAIL"}, mode=${steam302.mode ?? "unknown"}`,
    );

    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    await removeHostsByName(api, HOST_NAME);

    const ensured = await ensureHostByIp(api, {
      name: HOST_NAME,
      ip: lanIp,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      sshPassword: e2eEnv.testHost.auth,
    });
    hostId = ensured.hostId;
    hostName = ensured.host?.name ?? HOST_NAME;
    createdHost = ensured.created;
    console.log(
      `[CHECK][${createdHost ? "PASS" : "INFO"}] 认领主机${createdHost ? "已创建" : "复用既有记录"}: id=${hostId}, name=${hostName}, ip=${lanIp}`,
    );
    const tested = await api.post(`/api/hosts/${hostId}/test`);
    if (tested.body?.data?.connected === false) {
      throw new Error(
        `[CHECK][FAIL] 认领主机 SSH 连接失败: ${tested.body?.data?.message ?? "unknown"}; remediation: 确认 sshd 监听 ${lanIp}:${e2eEnv.testHost.port} 且凭据有效`,
      );
    }

    execWslDocker(["rm", "-f", CONTAINER_NAME]);
    const run = execWslDocker([
      "run",
      "-d",
      "--name",
      CONTAINER_NAME,
      "alpine:3.20",
      "sleep",
      "3600",
    ]);
    if (!run.ok) {
      throw new Error(
        `[CHECK][FAIL] 创建认领 fixture 失败: ${run.stderr || run.stdout}; remediation: 确认 WSL Docker 可用且 alpine:3.20 镜像已拉取`,
      );
    }
    console.log(`[CHECK][PASS] 认领 fixture 容器运行: ${CONTAINER_NAME}`);
  });

  test.afterAll(async () => {
    if (api && adoptedInstanceId) {
      await api
        .delete(`/api/instances/${adoptedInstanceId}?deleteContainer=false`)
        .catch(() => {});
    }
    const removed = execWslDocker(["rm", "-f", CONTAINER_NAME]);
    if (!removed.ok) {
      console.log(
        `[CHECK][FAIL] 清理认领 fixture 失败: ${removed.stderr || removed.stdout}; remediation: 手动执行 docker rm -f ${CONTAINER_NAME}`,
      );
    }
    if (api && createdHost) {
      await removeHostsByName(api, HOST_NAME).catch(() => {});
    }
  });

  test("未关联容器可识别为游戏实例并回显关联", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await page.goto("/resources/containers/list");

    // 选择本轮使用的主机；页面可能记住其他主机，先明确切换。
    const hostSelect = page.locator(".host-selector .el-select").first();
    await hostSelect.click();
    await page
      .getByRole("option", { name: new RegExp(`^${hostName}`) })
      .click();

    const row = page.locator(".el-table__row").filter({
      has: page.getByText(CONTAINER_NAME, { exact: true }),
    });
    await expect(row).toBeVisible({ timeout: 30_000 });
    await expect(row.locator(".el-button--success")).toHaveCount(1);

    await row.locator(".el-button--success").click();
    const dialog = page.locator(".el-dialog").filter({
      has: page.getByText("识别为游戏实例", { exact: true }),
    });
    await expect(dialog).toBeVisible();
    await expect(dialog.locator("input").first()).toHaveValue(CONTAINER_NAME);
    // fixture 镜像为 alpine，不含游戏码，不应出现自动预选提示。
    await expect(dialog.getByText("已按镜像名自动预选，可修改")).toHaveCount(0);

    await dialog.locator(".el-select").first().click();
    await page
      .getByRole("option", { name: new RegExp(`\\(${GAME_CODE}\\)$`, "i") })
      .click();
    await expect(
      dialog.locator(".el-tag", { hasText: "docker" }).first(),
    ).toBeVisible();

    await dialog.getByRole("button", { name: "认领" }).click();
    await expect(page.getByText(/认领成功，已创建实例/)).toBeVisible({
      timeout: 30_000,
    });

    await expect(row.locator(".el-link", { hasText: /.+/ })).toBeVisible({
      timeout: 30_000,
    });
    await expect(row.locator(".el-button--success")).toHaveCount(0);

    const instances = await api.get(
      `/api/instances?keyword=${encodeURIComponent(CONTAINER_NAME)}&size=10`,
    );
    const inst = (instances.body?.data?.records ?? []).find(
      (record) => record.instanceName === CONTAINER_NAME,
    );
    expect(inst, "认领实例应已创建").toBeTruthy();
    adoptedInstanceId = inst.id;
  });

  test("认领实例记录级删除后容器保留且关联解除", async ({ page }) => {
    test.setTimeout(60_000);
    expect(adoptedInstanceId).toBeTruthy();

    await injectSession(page);
    await page.goto("/services/instances/list");
    const row = page.locator(".el-table__row").filter({
      has: page.getByText(CONTAINER_NAME, { exact: true }),
    });
    await expect(row).toBeVisible({ timeout: 30_000 });

    const detail = await api.get(`/api/instances/${adoptedInstanceId}`);
    expect(detail.body?.data?.runtimeMetadata?.adopted).toBe(true);

    const del = await api.delete(
      `/api/instances/${adoptedInstanceId}?deleteContainer=false`,
    );
    expect(del.status).toBeLessThan(400);
    adoptedInstanceId = null;

    await expect
      .poll(
        async () => {
          const containers = await api.get(
            `/api/docker/hosts/${hostId}/containers`,
          );
          const target = (containers.body?.data?.containers ?? []).find(
            (container) => container.containerName === CONTAINER_NAME,
          );
          return {
            exists: Boolean(target),
            status: target?.status ?? "MISSING",
            linked: Boolean(target?.isLinked),
          };
        },
        { timeout: 30_000, intervals: [2_000, 4_000] },
      )
      .toEqual({ exists: true, status: "running", linked: false });

    await page.goto("/resources/containers/list");
    const hostSelect = page.locator(".host-selector .el-select").first();
    await hostSelect.click();
    await page
      .getByRole("option", { name: new RegExp(`^${hostName}`) })
      .click();
    const containerRow = page
      .locator(".el-table__row")
      .filter({ has: page.getByText(CONTAINER_NAME, { exact: true }) });
    await expect(containerRow).toContainText("未关联", { timeout: 30_000 });
  });
});
