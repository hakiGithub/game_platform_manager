/**
 * 页面: dnf_tw（DNF 台服）插件子应用
 *       仪表盘 / 连接管理 / 账号管理 / 角色管理 / 邮件发放 /
 *       在线监控 / 活动管理 / 拍卖行 / 字典管理 / 日志查询
 * 用例: 插件用例包范式的第四个样板，覆盖全部 10 个菜单
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       llnut/dnf:debian13-qf1031-latest 可拉取或已缓存
 * 通过标准（逐用例声明）:
 *  - 进入工作区: 直跳 dashboard，Wujie 容器与主侧边栏 10 个菜单可见
 *  - 仪表盘: 总览接口 200，实例源出现在表格，详情区给出统计或明确连接错误
 *  - 连接管理: 页面契约可见；连接档案经 UI 新增 -> 编辑 -> 删除完整闭环
 *  - 账号/角色/邮件/在线: 页面关键控件可见，游戏库接口 200 或有明确错误提示
 *  - 活动/拍卖行/字典: 页面主区域与接口结果可见，不允许白屏或静默吞错
 *  - 日志查询: 两个 Tab 可切换，插件自有日志接口均返回 200
 *
 * 环境约束:
 *  - dnf_tw 只能通过 docker-compose 部署，且 compose 中只有 MYSQL_PORT 可变量；
 *    因此固定使用 13306，部署前必须确认其余固定端口没有残留容器占用。
 *  - 上游 compose 的 mem_limit=1g 实测会把两个游戏分片压到反复 SIGKILL；
 *    1.5g 也只够跑一小段（实测 1 分钟后 df_game_r 仍被 cgroup OOM 杀掉，
 *    随后聚合接口因逐源探活而长时间阻塞）。本包用 resources.memoryLimit=2.5
 *    覆盖：该容器实测常驻 1.2~1.5 GiB，2.5 只是上限，WSL 3.8 GiB 下可承受。
 *    运行期间仍应避免再起其它重容器。
 *  - 游戏库页面允许“有数据 / 明确空态 / 明确错误提示”；后端 500 不会被静默
 *    当成成功，必须在页面上看到可诊断的错误提示并写 [CHECK][WARN]。
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

skipWithoutTestHost();

const HOST_NAME = "e2e-dnf-tw-plugin-host";
const INSTANCE_NAME = "e2e-dnf-tw-plugin";
const PROFILE_PREFIX = "e2e-dnf-tw-profile";
const MYSQL_PORT = 13306;
const DB_PASSWORD = "88888888";
const PAGE_TIMEOUT = 60_000;
// 上游 compose 自带 mem_limit=1g，两个分片会被反复 SIGKILL；2.5g 为实测可稳定运行的上限值
const DNF_MEMORY_LIMIT_G = Number(process.env.E2E_DNF_MEMORY_LIMIT_G ?? 2.5);

test.describe.serial("dnf_tw 插件子应用", () => {
  let api = null;
  let instanceId = null;
  let hostId = null;
  let createdHost = false;
  let profileId = null;

  const sourceBase = () => `/api/plugin/dnf_tw/instances/${instanceId}`;

  function isApiSuccess(response) {
    const code = response?.body?.code;
    return (
      response?.status === 200 &&
      (code === 200 || code === 0 || code === undefined)
    );
  }

  function responseActual(response) {
    return `HTTP ${response?.status}, body=${JSON.stringify(
      response?.body,
    ).slice(0, 600)}`;
  }

  function assertApiSuccess(response, label) {
    if (isApiSuccess(response)) {
      console.log(`[CHECK][PASS] ${label} API 200`);
      return;
    }
    throw new Error(
      `[CHECK][FAIL] ${label} API 不可用: ${responseActual(
        response,
      )}; remediation: 查看 e2e/.artifacts 的 error-context.md、容器日志与 dnf_tw 插件后端日志`,
    );
  }

  /**
   * 游戏库页面在 1.5 GiB 限制下仍可能遭遇上游分片重启。
   * 200 是首选；非 200 时必须由页面显示可理解的错误提示，不能静默放过。
   */
  async function assertApiOrExplicitPageError(page, response, label) {
    if (isApiSuccess(response)) {
      console.log(`[CHECK][PASS] ${label} API 200`);
      return true;
    }

    console.log(
      `[CHECK][WARN] ${label} API 不可用: ${responseActual(
        response,
      )}; 等待页面明确错误提示`,
    );
    await page.reload();
    await expect(
      page
        .locator(".el-message")
        .filter({ hasText: /失败|错误|超时|异常|无法|连接/ })
        .first(),
      `[CHECK][FAIL] ${label} 接口失败但页面没有明确错误提示`,
    ).toBeVisible({ timeout: 30_000 });
    return false;
  }

  async function injectSession(page) {
    const { injectSession: inject } = await import("../../support/session.js");
    await inject(page);
  }

  async function enterPluginWorkspace(page) {
    await page.goto("/extensions/plugins/list");
    // 扩展台卡片标题是插件展示名（「DNF 台服」），不是 gameCode；
    // 用 pluginId 定位才不会被改名或本地化影响。
    const card = page.locator(".plugin-card", { hasText: "plugin-dnf-tw" });
    await expect(card).toBeVisible({ timeout: 30_000 });
    await card.getByRole("button", { name: "进入工作区" }).click();

    const dashboardUrl = new RegExp(
      `/extensions/app/dnf_tw/dashboard\\?instanceId=${instanceId}`,
    );
    const dialog = page.locator(".plugin-instance-dialog");
    const outcome = await Promise.race([
      dialog
        .waitFor({ state: "visible", timeout: 30_000 })
        .then(() => "dialog")
        .catch((error) => {
          if (error.name === "TimeoutError") return "timeout";
          throw error;
        }),
      page
        .waitForURL(dashboardUrl, { timeout: 30_000 })
        .then(() => "dashboard")
        .catch((error) => {
          if (error.name === "TimeoutError") return "timeout";
          throw error;
        }),
    ]);

    if (outcome === "dialog") {
      const row = dialog
        .locator(".el-table__row")
        .filter({ hasText: INSTANCE_NAME });
      await row.first().getByRole("button", { name: "进入管理" }).click();
    } else if (outcome === "timeout") {
      throw new Error(
        `[CHECK][FAIL] 进入 dnf_tw 工作区超时: 未出现实例选择弹窗，也未导航到 dashboard; actual=${page.url()}; remediation: 检查 /api/instances?gameCode=dnf_tw 与插件扩展台日志`,
      );
    }

    await expect(page).toHaveURL(dashboardUrl, { timeout: 30_000 });
    await expect(page.locator(".plugin-tab")).toBeVisible({ timeout: 30_000 });
  }

  async function gotoPluginPage(page, path, rootSelector = ".page") {
    const url = `/extensions/app/dnf_tw/${path}?instanceId=${instanceId}`;
    await page.goto(url);
    try {
      await expect(page.locator(rootSelector).first()).toBeVisible({
        timeout: PAGE_TIMEOUT,
      });
    } catch (error) {
      const actual = {
        url: page.url(),
        body: (
          await page
            .locator("body")
            .innerText()
            .catch(() => "")
        )
          .replaceAll(/\s+/g, " ")
          .slice(0, 500),
      };
      await page.reload();
      try {
        await expect(page.locator(rootSelector).first()).toBeVisible({
          timeout: PAGE_TIMEOUT,
        });
      } catch (reloadError) {
        throw new Error(
          `[CHECK][FAIL] dnf_tw 子应用页面 ${path} 未渲染根节点 ${rootSelector}: ${reloadError.message}; actual=${JSON.stringify(
            actual,
          )}; remediation: 查看 e2e/.artifacts 的 error-context.md、trace 与 /api/pf4j/plugin/dnf_tw/ui/ 响应`,
          { cause: error },
        );
      }
    }
  }

  async function getConnections() {
    return api.get("/api/plugin/dnf_tw/connections");
  }

  async function removeProfilesByPrefix() {
    const list = await getConnections();
    for (const item of list.body?.data ?? []) {
      if (item.kind === "profile" && item.name?.startsWith(PROFILE_PREFIX)) {
        await api
          .delete(`/api/plugin/dnf_tw/connections/profiles/${item.profileId}`)
          .catch(() => {});
      }
    }
  }

  test.beforeAll(async () => {
    test.setTimeout(20 * 60_000);

    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);

    await removeHostsByName(api, HOST_NAME);
    const ensured = await ensureHostByIp(api, {
      name: HOST_NAME,
      ip: e2eEnv.testHost.address,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      sshPassword: e2eEnv.testHost.auth,
    });
    hostId = ensured.hostId;
    createdHost = ensured.created;
    const tested = await api.post(`/api/hosts/${hostId}/test`);
    expect(tested.body?.data?.connected).not.toBe(false);

    await removeProfilesByPrefix();
    const stale = await api.get(
      `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=50`,
    );
    for (const inst of stale.body?.data?.records ?? []) {
      const deleted = await api.delete(`/api/instances/${inst.id}`);
      expect(
        isApiSuccess(deleted),
        `[CHECK][FAIL] 清理同名 dnf_tw 实例失败: ${responseActual(deleted)}`,
      ).toBe(true);
    }

    const games = await api.get("/api/games/list");
    const dnf = (games.body?.data ?? []).find((g) => g.gameCode === "dnf_tw");
    expect(dnf).toBeTruthy();

    const deployed = await api.post("/api/instances", {
      instanceName: INSTANCE_NAME,
      gameId: dnf.id,
      hostId,
      deployType: "docker-compose",
      installPath: `~/games/${INSTANCE_NAME}`,
      portConfig: {
        game: 7001,
        mysql: MYSQL_PORT,
      },
      configInfo: {
        MYSQL_PORT: String(MYSQL_PORT),
        DNF_DB_ROOT_PASSWORD: DB_PASSWORD,
        PUBLIC_IP: e2eEnv.testHost.address,
        resources: {
          memoryLimit: DNF_MEMORY_LIMIT_G,
          cpuLimit: 2,
        },
      },
    });
    instanceId = deployed.body?.data?.id ?? null;
    expect(
      instanceId,
      `[CHECK][FAIL] dnf_tw 实例创建失败: ${responseActual(deployed)}`,
    ).toBeTruthy();

    let lastStatus = "尚未查询";
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
          );
          const record = res.body?.data?.records?.find(
            (item) => item.instanceName === INSTANCE_NAME,
          );
          lastStatus = record?.runStatusDesc ?? "实例不存在";
          return lastStatus;
        },
        { timeout: 10 * 60_000, intervals: [3_000, 5_000, 10_000] },
      )
      .toBe("运行中");
    console.log(
      `[CHECK][PASS] dnf_tw 实例部署到运行中（最后状态: ${lastStatus}, mysql=${MYSQL_PORT}）`,
    );

    await expect
      .poll(
        async () => {
          const res = await api.get("/api/plugin/dnf_tw/dashboard/overview");
          const item = (res.body?.data ?? []).find(
            (row) =>
              row.kind === "instance" &&
              Number(row.instanceId) === Number(instanceId),
          );
          return Boolean(item?.connected);
        },
        { timeout: 3 * 60_000, intervals: [3_000, 5_000] },
      )
      .toBe(true);
    console.log("[CHECK][PASS] dnf_tw 实例连接源 connected=true");
  });

  test.afterAll(async () => {
    if (!api) return;
    // 删除实例要下发 compose down + 清理远端目录，远慢于默认 30s 钩子超时
    test.setTimeout(5 * 60_000);
    await removeProfilesByPrefix().catch(() => {});
    if (profileId) {
      await api
        .delete(`/api/plugin/dnf_tw/connections/profiles/${profileId}`)
        .catch(() => {});
    }
    if (instanceId) {
      await api.delete(`/api/instances/${instanceId}`).catch(() => {});
    }
    if (hostId && createdHost) {
      await api.delete(`/api/hosts/${hostId}`).catch(() => {});
    }
  });

  test("进入工作区直跳 Wujie 容器且 10 个菜单可见", async ({ page }) => {
    test.setTimeout(3 * 60_000);
    await injectSession(page);
    await enterPluginWorkspace(page);
    await expect(page.locator(".page").first()).toBeVisible({
      timeout: 30_000,
    });

    const sidebar = page.locator(".el-menu");
    await expect(
      sidebar.getByRole("menuitem", { name: "DNF 台服" }).first(),
    ).toBeVisible({ timeout: 30_000 });
    for (const menu of [
      "仪表盘",
      "连接管理",
      "账号管理",
      "角色管理",
      "邮件发放",
      "在线监控",
      "活动管理",
      "拍卖行",
      "字典管理",
      "日志查询",
    ]) {
      await expect(
        sidebar.getByRole("menuitem", { name: menu }).first(),
      ).toBeVisible();
    }
    console.log(
      "[CHECK][PASS] dnf_tw 工作区直跳成功：Wujie 容器与 10 个侧边栏菜单均可见",
    );
  });

  test("仪表盘总览、实例源与详情区", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "dashboard");
    const root = page.locator(".page").first();

    await expect(
      root.getByText("全部连接源总览", { exact: true }),
    ).toBeVisible();
    await expect(
      root.getByRole("button", { name: "刷新", exact: true }),
    ).toBeVisible();
    assertApiSuccess(
      await api.get("/api/plugin/dnf_tw/dashboard/overview"),
      "仪表盘总览",
    );

    const row = root
      .locator(".el-table__row")
      .filter({ hasText: INSTANCE_NAME });
    await expect(row.first()).toBeVisible({ timeout: 30_000 });
    await expect(root).toContainText(/账号总数|该连接源数据库连接失败/);
    await expect(root).toContainText(/当年按月充值|暂无连接源/);
    console.log(
      "[CHECK][PASS] 仪表盘展示实例源、统计卡/连接告警与充值图表区域",
    );
  });

  test("连接管理页面与连接档案 UI CRUD 闭环", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "connections");
    const root = page.locator(".page").first();
    const unique = Date.now();
    const originalName = `${PROFILE_PREFIX}-${unique}`;
    const renamed = `${originalName}-edited`;

    await expect(root.getByText("连接源总览", { exact: true })).toBeVisible();
    await expect(
      root.getByText("连接档案（手动配置）", { exact: true }),
    ).toBeVisible();
    // 页面有两张表：「连接源总览」与「连接档案（手动配置）」，同名档案会同时出现在两处。
    // 编辑/删除按钮只在档案表里，行定位必须限定到该卡片，否则会命中总览行。
    const profileCard = root
      .locator(".el-card")
      .filter({ hasText: "连接档案（手动配置）" });
    assertApiSuccess(await getConnections(), "连接源与连接档案清单");

    const createResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        response.url().includes("/api/plugin/dnf_tw/connections/profiles"),
      { timeout: 30_000 },
    );
    await root.getByRole("button", { name: "新增档案" }).click();
    let dialog = page.getByRole("dialog").filter({ hasText: "新增档案" });
    await expect(dialog).toBeVisible();
    await dialog.getByPlaceholder("如：台服一区").fill(originalName);
    await dialog.locator(".el-radio-button", { hasText: "直连" }).click();
    await dialog.getByPlaceholder("127.0.0.1").fill("172.17.0.1");
    await dialog.getByRole("spinbutton").first().fill(String(MYSQL_PORT));
    await dialog.getByPlaceholder("root").fill("root");
    await dialog.locator('input[type="password"]').fill(DB_PASSWORD);
    await dialog.getByRole("button", { name: "保存", exact: true }).click();

    const created = await createResponse;
    const createdBody = await created.json().catch(() => null);
    expect(
      created.ok(),
      `[CHECK][FAIL] 新增连接档案失败: HTTP ${created.status()} ${JSON.stringify(
        createdBody,
      ).slice(0, 600)}`,
    ).toBe(true);
    profileId = createdBody?.data?.id ?? null;
    expect(profileId).toBeTruthy();
    await expect(root).toContainText(originalName, { timeout: 30_000 });

    const updateResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "PUT" &&
        response
          .url()
          .includes(`/api/plugin/dnf_tw/connections/profiles/${profileId}`),
      { timeout: 30_000 },
    );
    const createdRow = profileCard
      .locator(".el-table__row")
      .filter({ hasText: originalName })
      .first();
    await createdRow.getByRole("button", { name: "编辑" }).click();
    dialog = page.getByRole("dialog").filter({ hasText: "编辑档案" });
    await expect(dialog).toBeVisible();
    await dialog.getByPlaceholder("如：台服一区").fill(renamed);
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    const updated = await updateResponse;
    const updatedBody = await updated.json().catch(() => null);
    expect(
      updated.ok(),
      `[CHECK][FAIL] 编辑连接档案失败: HTTP ${updated.status()} ${JSON.stringify(
        updatedBody,
      ).slice(0, 600)}`,
    ).toBe(true);
    await expect(root).toContainText(renamed, { timeout: 30_000 });

    const deleteResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "DELETE" &&
        response
          .url()
          .includes(`/api/plugin/dnf_tw/connections/profiles/${profileId}`),
      { timeout: 30_000 },
    );
    const editedRow = profileCard
      .locator(".el-table__row")
      .filter({ hasText: renamed })
      .first();
    await editedRow.getByRole("button", { name: "删除" }).click();
    const confirm = page.locator(".el-message-box");
    await expect(confirm).toBeVisible();
    await confirm.getByRole("button", { name: /确定|OK/ }).click();
    const deleted = await deleteResponse;
    const deletedBody = await deleted.json().catch(() => null);
    expect(
      deleted.ok(),
      `[CHECK][FAIL] 删除连接档案失败: HTTP ${deleted.status()} ${JSON.stringify(
        deletedBody,
      ).slice(0, 600)}`,
    ).toBe(true);
    await expect(root).not.toContainText(renamed, { timeout: 30_000 });

    await expect
      .poll(
        async () => {
          const list = await getConnections();
          return (list.body?.data ?? []).some(
            (item) => item.profileId === profileId,
          );
        },
        { timeout: 30_000 },
      )
      .toBe(false);
    profileId = null;
    console.log("[CHECK][PASS] 连接档案经 UI 新增、编辑、删除并完成平台侧回读");
  });

  test("账号管理页面、查询入口与账号接口", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "accounts");
    const root = page.locator(".page").first();

    await expect(root.getByText("账号管理", { exact: true })).toBeVisible();
    await expect(root.getByPlaceholder("UID 前缀")).toBeVisible();
    await expect(
      root.getByRole("button", { name: "查询", exact: true }),
    ).toBeVisible();
    await expect(root.getByRole("button", { name: "创建账号" })).toBeVisible();
    await assertApiOrExplicitPageError(
      page,
      await api.get(`${sourceBase()}/accounts?page=1&size=20`),
      "账号列表",
    );
    await expect(root.locator(".el-table").first()).toBeVisible();
    await expect(root.getByText("UID", { exact: true }).first()).toBeVisible();
  });

  test("角色管理页面、UID/名称查询入口与角色接口", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "roles");
    const root = page.locator(".page").first();

    await expect(root.getByText("角色管理", { exact: true })).toBeVisible();
    await expect(root.getByPlaceholder("UID")).toBeVisible();
    await expect(root.getByPlaceholder("角色名称")).toBeVisible();
    await expect(root.getByRole("button", { name: "按UID查询" })).toBeVisible();
    await expect(
      root.getByRole("button", { name: "按名称搜索" }),
    ).toBeVisible();
    await assertApiOrExplicitPageError(
      page,
      await api.get(`${sourceBase()}/roles?name=e2e&page=1&size=20`),
      "角色搜索",
    );
    await expect(root.locator(".el-table").first()).toBeVisible();
  });

  test("邮件发放页面、物品与角色控件", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "email");
    const root = page.locator(".page").first();

    await expect(root.getByText("GM 邮件发放", { exact: true })).toBeVisible();
    await expect(root.getByPlaceholder("UID")).toBeVisible();
    await expect(root.getByText("收件角色", { exact: true })).toBeVisible();
    await expect(root.getByText("物品", { exact: true })).toBeVisible();
    await expect(root.getByText("金币", { exact: true })).toBeVisible();
    await expect(root.getByText("装备等级", { exact: true })).toBeVisible();
    await expect(root.getByText("异界属性", { exact: true })).toBeVisible();
    await expect(root.getByText("是否封装", { exact: true })).toBeVisible();
    await expect(root.getByRole("button", { name: "发送邮件" })).toBeVisible();
    await assertApiOrExplicitPageError(
      page,
      await api.get(`${sourceBase()}/gold?page=1&size=20`),
      "邮件物品字典",
    );
  });

  test("在线监控页面、筛选条件与在线接口", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "online");
    const root = page.locator(".page").first();

    await expect(root.getByText("在线玩家监控", { exact: true })).toBeVisible();
    await expect(root.getByPlaceholder("账号名")).toBeVisible();
    await expect(root.getByPlaceholder("角色名")).toBeVisible();
    await expect(root.getByText("仅在线", { exact: true })).toBeVisible();
    await expect(
      root.getByRole("button", { name: "查询", exact: true }),
    ).toBeVisible();
    await assertApiOrExplicitPageError(
      page,
      await api.get(`${sourceBase()}/online?page=1&size=20&onlyOnline=true`),
      "在线玩家列表",
    );
    await expect(root.locator(".el-table").first()).toBeVisible();
  });

  test("活动管理两个 Tab、活动列表与泡点计划", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "activities");
    const root = page.locator(".page").first();

    await expect(root.getByRole("tab", { name: "活动管理" })).toBeVisible();
    await expect(
      root.getByRole("tab", { name: "泡点发放（定时计划）" }),
    ).toBeVisible();
    await expect(root.getByText("可添加的活动", { exact: true })).toBeVisible();
    await expect(root.getByText("已添加的活动", { exact: true })).toBeVisible();
    const activities = await api.get(`${sourceBase()}/activities`);
    const activityLogs = await api.get(
      `${sourceBase()}/activities/logs?page=1&size=20`,
    );
    if (!isApiSuccess(activities) || !isApiSuccess(activityLogs)) {
      await assertApiOrExplicitPageError(
        page,
        isApiSuccess(activities) ? activityLogs : activities,
        "活动列表",
      );
    } else {
      console.log("[CHECK][PASS] 活动列表与已添加活动 API 200");
    }

    await root.getByRole("tab", { name: "泡点发放（定时计划）" }).click();
    await expect(root).toContainText("创建泡点计划");
    await expect(root).toContainText("已有泡点计划（全部连接源）");
  });

  test("拍卖行页面、统计卡、白名单与执行入口", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "auction");
    const root = page.locator(".page").first();

    await expect(root.getByText("在架总数", { exact: true })).toBeVisible();
    await expect(root.getByText("系统在架", { exact: true })).toBeVisible();
    await expect(root.getByText("待发退款邮件", { exact: true })).toBeVisible();
    await expect(
      root.getByText("白名单（系统补货 + 扫货定价）", { exact: true }),
    ).toBeVisible();
    await expect(root.getByRole("button", { name: "添加商品" })).toBeVisible();
    await expect(
      root.getByRole("button", { name: "立即执行一次" }),
    ).toBeVisible();

    const state = await api.get(`${sourceBase()}/auction/state`);
    const whitelist = await api.get(
      `${sourceBase()}/auction/whitelist/page?page=1&size=20`,
    );
    if (!isApiSuccess(state) || !isApiSuccess(whitelist)) {
      await assertApiOrExplicitPageError(
        page,
        isApiSuccess(state) ? whitelist : state,
        "拍卖行状态",
      );
    } else {
      console.log("[CHECK][PASS] 拍卖行状态与白名单 API 200");
    }
  });

  test("字典管理状态、五类字典与物品检索入口", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "pvf");
    const root = page.locator(".page").first();

    await expect(
      root.getByText("字典状态与导入（dnf_gm_tool 库）", { exact: true }),
    ).toBeVisible();
    for (const label of ["地下城", "道具", "装备", "职业", "任务"]) {
      await expect(
        root.getByText(label, { exact: true }).first(),
      ).toBeVisible();
    }
    await expect(
      root.getByRole("button", { name: "从实例拉取并导入" }),
    ).toBeVisible();
    await expect(root.getByPlaceholder("名称或 ItemID")).toBeVisible();

    const status = await api.get(`${sourceBase()}/pvf/status`);
    const items = await api.get(
      `${sourceBase()}/pvf/items/page?kind=stackable&page=1&size=20`,
    );
    if (!isApiSuccess(status) || !isApiSuccess(items)) {
      await assertApiOrExplicitPageError(
        page,
        isApiSuccess(status) ? items : status,
        "PVF 字典",
      );
    } else {
      console.log("[CHECK][PASS] PVF 字典状态与物品分页 API 200");
    }
  });

  test("日志查询两个 Tab 与插件自有日志接口", async ({ page }) => {
    test.setTimeout(120_000);
    await injectSession(page);
    await gotoPluginPage(page, "logs");
    const root = page.locator(".page").first();

    await expect(root.getByText("日志查询", { exact: true })).toBeVisible();
    await expect(
      root.locator(".el-radio-button", { hasText: "充值日志" }),
    ).toBeVisible();
    await expect(
      root.locator(".el-radio-button", { hasText: "操作日志" }),
    ).toBeVisible();
    await expect(root.getByPlaceholder("UID")).toBeVisible();
    assertApiSuccess(
      await api.get("/api/plugin/dnf_tw/logs/recharge?page=1&size=20"),
      "充值日志",
    );

    await root.locator(".el-radio-button", { hasText: "操作日志" }).click();
    await expect(root.getByPlaceholder("操作")).toBeVisible();
    await expect(root.getByText("操作人", { exact: true })).toBeVisible();
    await expect(root.getByText("结果", { exact: true })).toBeVisible();
    assertApiSuccess(
      await api.get("/api/plugin/dnf_tw/logs/operate?page=1&size=20"),
      "操作日志",
    );
    console.log(
      "[CHECK][PASS] 日志查询两个 Tab 可切换，充值/操作日志 API 均 200",
    );
  });
});
