/**
 * 页面: 部署向导 / 实例列表 / 实例详情
 * 用例: 实例生命周期全链路（l4d2 演练腿·前半）—— API 造主机 → 向导真实部署 l4d2(Docker)
 *       → 等待运行中 → 详情静态+动态 → 停止/启动/重启 → 卸载
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机（WSL）；
 *       网络可拉取 cm2network/left4dead2 镜像（数 GB，首启经 SteamCMD 更新，耗时属预期）
 * 通过标准（逐用例声明，超时按规格放宽）:
 *  - 部署: 向导五步走通（主机→游戏 l4d2+Docker→参数+端口检查通过+自动启动→环境校验通过→
 *    开始部署），任务创建提示出现，轮询实例至「运行中」（上限 25 分钟）
 *  - 详情: 标题/实例运行指标区/在线玩家面板渲染
 *  - 停止: 确认「确定停止」后轮询至「已停止」（上限 5 分钟）
 *  - 启动: 轮询回「运行中」（上限 5 分钟）
 *  - 重启: 确认「确定重启」后轮询回「运行中」（上限 5 分钟）
 *  - 卸载: 输入实例名确认卸载，列表不再出现（上限 5 分钟）
 * 注: 备份还原功能已临时下线（.scratch/backlog/issues/01），相关用例随之移除
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, login } from "../../support/api.js";
import { injectSession } from "../../support/session.js";
import { cleanupStaleE2EContainers } from "../../support/host-hygiene.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-deploy-host";
const INSTANCE_NAME = "e2e-l4d2-01";

test.describe.serial("实例生命周期（l4d2 Docker 演练腿）", () => {
  let api = null;
  let hostId = null;
  let instanceId = null;

  test.beforeAll(async () => {
    // 宿主卫生：清掉上一轮失败链遗留的 game-instance-* 容器，避免名字/端口占用
    cleanupStaleE2EContainers();
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    // 直连后端路径必须带 /api 前缀
    const created = await api.post("/api/hosts", {
      name: HOST_NAME,
      ip: e2eEnv.testHost.address,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      authType: "password",
      sshPassword: e2eEnv.testHost.auth,
    });
    hostId = created.body?.data?.id ?? null;
    if (!hostId) {
      throw new Error(`前置主机创建失败: ${JSON.stringify(created.body)}`);
    }
    // 触发一次真实连接测试：部署向导只列在线主机（status=1），API 直造的主机默认离线
    const tested = await api.post(`/api/hosts/${hostId}/test`);
    if (tested.body?.data?.connected === false) {
      throw new Error(
        `牺牲主机连接测试未通过: ${tested.body?.data?.message ?? ""}`,
      );
    }
  });

  test.afterAll(async () => {
    // 现场自清：残留实例与前置主机一并删除
    if (api && instanceId)
      await api.post(`/api/instances/${instanceId}/stop`).catch(() => {});
    if (api && hostId) await api.delete(`/api/hosts/${hostId}`).catch(() => {});
  });

  async function pollInstanceStatus(desc, timeoutMs) {
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
          );
          const records = res.body?.data?.records ?? [];
          return records.find((r) => r.instanceName === INSTANCE_NAME)
            ?.runStatusDesc;
        },
        { timeout: timeoutMs, intervals: [5_000, 10_000] },
      )
      .toBe(desc);
  }

  test("部署向导五步真实部署 l4d2 并等待运行中", async ({ page }) => {
    test.setTimeout(25 * 60_000);
    await injectAndGoto(page, "/services/instances/deploy");

    // 步骤1：选择主机
    await page.locator(".host-card", { hasText: HOST_NAME }).click();
    await page.getByRole("button", { name: "下一步" }).click();

    // 步骤2：搜索并选择游戏 l4d2，部署方式 Docker（el-radio-button；精确匹配避免选中 Docker Compose）
    await page.getByPlaceholder("搜索游戏名称或编码").fill("l4d2");
    await page.locator(".game-item", { hasText: "l4d2" }).first().click();
    // 选中游戏后详情区才渲染部署方式 radio（textContent 含图标留白，正则容忍空白）
    await expect(page.locator(".detail-header")).toContainText("求生之路2");
    await page
      .locator(".el-radio-button")
      .filter({ hasText: /^\s*Docker\s*$/ })
      .click();
    await page.getByRole("button", { name: "下一步" }).click();

    // 步骤3：配置参数
    await page.getByPlaceholder("请输入实例名称").fill(INSTANCE_NAME);
    await page.getByPlaceholder(/请输入部署路径/).fill("~/games/e2e-l4d2");
    // 牺牲主机 27015 被其上的真实 l4d2 服务占用——演练统一挪移到 27025 系端口
    await page.locator(".el-input-number input").first().fill("27025");
    await page.getByRole("button", { name: "检查端口" }).click();
    await expect(page.getByText("端口可用")).toBeVisible({ timeout: 15_000 });
    // 附加端口一并挪移，全部检查无占用后继续
    for (const [key, port] of Object.entries({
      query: 27026,
      rcon: 27025,
      steam: 27006,
    })) {
      await page
        .locator(".additional-port-row", { hasText: key })
        .locator(".el-input-number input")
        .fill(String(port));
    }
    await page.getByRole("button", { name: "检查所有附加端口" }).click();
    await expect(
      page.locator(".additional-port-row", { hasText: "端口被占用" }),
    ).toHaveCount(0, { timeout: 15_000 });
    // 自动启动：确保为开（容器部署后直接拉起）
    const autoSwitch = page
      .locator(".el-form-item", { hasText: "自动启动" })
      .locator(".el-switch");
    if (
      !(await autoSwitch.evaluate((el) => el.classList.contains("is-checked")))
    ) {
      await autoSwitch.click();
    }
    await page.getByRole("button", { name: "下一步" }).click();

    // 步骤4：环境校验——校验在点击「下一步」时执行，通过后自动进入确认部署步
    await page.getByRole("button", { name: "下一步" }).click();

    // 步骤5：确认部署
    await expect(page.locator(".el-descriptions")).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.locator(".el-descriptions")).toContainText(INSTANCE_NAME);
    await page.getByRole("button", { name: "开始部署" }).click();
    await expect(page.locator(".el-message--success")).toContainText(
      "部署任务已创建",
    );
    await expect(page).toHaveURL(/\/services\/instances\/list/, {
      timeout: 15_000,
    });

    // 部署进度经实例行状态呈现（安装中→运行中）；注意：部署任务不进任务中心——
    // /api/tasks 只收录插件任务类型（map-upload 等），经实测确认
    await pollInstanceStatus("运行中", 25 * 60_000);
    const list = await api.get(
      `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
    );
    instanceId = list.body?.data?.records?.[0]?.id ?? null;
    expect(instanceId).toBeTruthy();
  });

  test("实例详情静态与动态指标渲染", async ({ page }) => {
    test.setTimeout(60_000);
    expect(instanceId).toBeTruthy();
    await injectAndGoto(page, `/services/instances/detail/${instanceId}`);

    await expect(
      page.getByRole("heading", { name: INSTANCE_NAME }),
    ).toBeVisible();
    await expect(page.getByLabel("实例运行指标")).toBeVisible();
    await expect(page.getByText("ONLINE PLAYERS")).toBeVisible();
    await expect(page.getByLabel("实例运行上下文")).toBeVisible();
  });

  test("停止实例至已停止", async ({ page }) => {
    test.setTimeout(5 * 60_000);
    await injectAndGoto(page, `/services/instances/detail/${instanceId}`);
    await page.getByRole("button", { name: "停止", exact: true }).click();
    // 详情页确认框为 ElMessageBox 默认按钮（确定）
    await page
      .locator(".el-message-box")
      .getByRole("button", { name: "确定" })
      .click();
    await pollInstanceStatus("已停止", 5 * 60_000);
  });

  test("启动实例回运行中", async ({ page }) => {
    test.setTimeout(5 * 60_000);
    await injectAndGoto(page, `/services/instances/detail/${instanceId}`);
    await page.getByRole("button", { name: "启动", exact: true }).click();
    await pollInstanceStatus("运行中", 5 * 60_000);
  });

  test("重启实例回运行中", async ({ page }) => {
    test.setTimeout(5 * 60_000);
    await injectAndGoto(page, `/services/instances/detail/${instanceId}`);
    await page.getByRole("button", { name: "重启", exact: true }).click();
    await page
      .locator(".el-message-box")
      .getByRole("button", { name: "确定" })
      .click();
    await pollInstanceStatus("运行中", 5 * 60_000);
  });

  test("卸载实例完成清理", async ({ page }) => {
    test.setTimeout(5 * 60_000);
    // 卸载按钮仅对「已停止」实例显示——先经 API 停止
    await api.post(`/api/instances/${instanceId}/stop`);
    await pollInstanceStatus("已停止", 5 * 60_000);

    await injectAndGoto(page, "/services/instances/list");
    const row = page.locator(".el-table__row", { hasText: INSTANCE_NAME });
    await row.getByRole("button", { name: "卸载" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("卸载实例");
    await dialog.getByPlaceholder("请输入实例名称").fill(INSTANCE_NAME);
    await dialog.getByRole("button", { name: "确定卸载" }).click();
    await expect(page.locator(".el-message--success")).toContainText(
      "删除成功",
    );

    await expect(
      page.locator(".el-table__row", { hasText: INSTANCE_NAME }),
    ).toHaveCount(0, { timeout: 15_000 });
    instanceId = null;
  });
});

async function injectAndGoto(page, path) {
  await injectSession(page);
  await page.goto(path);
}
