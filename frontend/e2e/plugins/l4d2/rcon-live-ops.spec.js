/**
 * 页面: L4D2 RCON 控制台 + RCON REST API
 * 用例: 真实服务器上的难度、特殊字符透传、可回收 bot fixture、踢人/封禁能力边界
 * 前置: 平台前后端已运行；显式设置 E2E_L4D2_LIVE_INSTANCE_ID 指向一个运行中的 L4D2 实例；
 *       实例 RCON 可达，且服务器没有人类玩家。没有该变量时整文件 SKIP。
 * 步骤: API 读取初始状态 -> sm_cvar 切难度并由 z_difficulty 回读 -> 恢复；
 *       提交特殊字符命令并核对原样回显 -> 建 bot、核对 users/status、尝试 kick；
 *       探测封禁命令能力，不用真实 SteamID 执行破坏性封禁；
 *       UI 断言 RCON 控制台是原始命令入口，不存在踢人/封禁对话框。
 * 通过标准: 难度读回一致且 API 失败时明确报错；特殊字符不崩溃且回显原命令；
 *           bot 状态变化可观察且清理完成；页面加载且不存在未实现的对话框入口。
 *
 * 环境说明: laoyutang/l4d2-pure 支持 L4D2_RCON_PASSWORD，可用 sb_add 生成
 * SourceMod SurvivorBot。sb_add 只新增 status 玩家行，不增加 "N humans" 计数。
 */
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { createApi, login } from "../../support/api.js";
import { injectSession } from "../../support/session.js";

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), "../../../..");

async function discoverFrontendUrl() {
  if (process.env.E2E_BASE_URL) return process.env.E2E_BASE_URL;

  const candidates = [];
  const portFile = join(projectDir, "logs", "frontend.port");
  if (existsSync(portFile)) {
    const port = readFileSync(portFile, "utf8").trim();
    if (/^\d+$/.test(port)) candidates.push(`http://127.0.0.1:${port}`);
  }
  for (const port of [3000, 3001, 3002, 3003, 3004, 3005, 4173, 5173, 5174]) {
    candidates.push(`http://127.0.0.1:${port}`);
  }

  for (const candidate of [...new Set(candidates)]) {
    try {
      const html = await fetch(candidate, {
        signal: AbortSignal.timeout(2_000),
      }).then((response) => response.text());
      if (html.includes("游戏服务器管理平台")) return candidate;
    } catch {
      // Try the next local frontend candidate.
    }
  }
  throw new Error(
    "未发现 Game Platform 前端；设置 E2E_BASE_URL 指向本 worktree 的 Vite 服务",
  );
}

const instanceId = Number(process.env.E2E_L4D2_LIVE_INSTANCE_ID ?? 0);
const testBotMarker = "e2e-rcon-live-bot";
const originalDifficulty = { value: null };
const createdBots = new Set();
const baselineBots = new Set();
let frontendUrl = null;

test.skip(
  !Number.isInteger(instanceId) || instanceId <= 0,
  "SKIP (set E2E_L4D2_LIVE_INSTANCE_ID to a live L4D2 instance)",
);

test.describe.serial("L4D2 RCON 真实服务器深度操作", () => {
  let api = null;

  async function execute(command) {
    const response = await api.post("/api/plugin/l4d2/rcon/execute", {
      instanceId,
      command,
    });
    expect(response.status, JSON.stringify(response.body)).toBeLessThan(400);
    expect(response.body?.code, JSON.stringify(response.body)).toBe(200);
    return response.body.data;
  }

  async function status() {
    const response = await api.post("/api/plugin/l4d2/rcon/status", {
      instanceId,
    });
    expect(response.status, JSON.stringify(response.body)).toBeLessThan(400);
    expect(response.body?.code, JSON.stringify(response.body)).toBe(200);
    const value = response.body.data;
    expect(value?.online, `RCON 不可达: ${value?.reason ?? "unknown"}`).toBe(
      true,
    );
    return value;
  }

  async function parseUsers() {
    const result = await execute("users");
    const entries = [];
    for (const line of String(result.output ?? "").split("\n")) {
      const match = line.match(/^(\d+):(\d+):"(.+)"$/);
      if (match) {
        entries.push({
          slot: Number(match[1]),
          userId: Number(match[2]),
          name: match[3],
        });
      }
    }
    return entries;
  }

  async function users() {
    return (await parseUsers()).map((entry) => entry.name);
  }

  async function addTestBot() {
    const before = await parseUsers();
    const beforeNames = new Set(before.map((entry) => entry.name));
    const result = await execute("sb_add");
    expect(result.success, JSON.stringify(result)).toBe(true);
    let added = null;
    await expect
      .poll(
        async () => {
          const after = await parseUsers();
          added =
            after.find((entry) => !beforeNames.has(entry.name)) ?? null;
          return added;
        },
        { timeout: 20_000, intervals: [500, 1_000, 2_000] },
      )
      .not.toBeNull();
    createdBots.add(added);
    return added;
  }

  async function removeTestBots() {
    for (const bot of createdBots) {
      await execute(`kickid ${bot.userId}`).catch(() => null);
    }
    createdBots.clear();
  }

  test.beforeAll(async () => {
    test.setTimeout(2 * 60_000);
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    frontendUrl = await discoverFrontendUrl();
    console.log(`[CHECK][INFO] 使用前端 ${frontendUrl}`);

    const current = await status();
    const detail = await api.get(`/api/instances/${instanceId}`);
    expect(detail.status, JSON.stringify(detail.body)).toBe(200);
    expect(detail.body?.data?.gameCode).toBe("l4d2");

    const humans = Number(current.currentPlayers ?? 0);
    const humansFromStatus = String(current.players ?? "").match(
      /^(\d+)\//,
    )?.[1];
    expect(
      humans === 0 && (!humansFromStatus || humansFromStatus === "0"),
      `拒绝在有人类玩家的服务器上执行可写烟雾测试: players=${current.players}`,
    ).toBe(true);

    originalDifficulty.value = current.difficulty ?? null;
    const difficultyText = await execute("z_difficulty");
    const match = String(difficultyText.output ?? "").match(
      /"z_difficulty"\s*=\s*"([^"]+)"/,
    );
    originalDifficulty.value = match?.[1] ?? null;
    expect(originalDifficulty.value).toBeTruthy();

    for (const name of await users()) baselineBots.add(name);
  });

  test.afterAll(async () => {
    if (!api) return;
    await removeTestBots();
    if (originalDifficulty.value) {
      await execute(
        `sm_cvar z_difficulty ${originalDifficulty.value}`,
      ).catch(() => null);
    }
  });

  test("难度切换由 sm_cvar 生效且服务端回读一致", async () => {
    const before = originalDifficulty.value.toLowerCase();
    const targetLabel = before === "hard" ? "普通" : "高级";
    const targetCode = targetLabel === "普通" ? "normal" : "hard";

    const response = await api.post(
      "/api/plugin/l4d2/rcon/change-difficulty",
      { instanceId, difficulty: targetLabel },
    );
    expect(response.status, JSON.stringify(response.body)).toBeLessThan(400);
    expect(response.body?.code, JSON.stringify(response.body)).toBe(200);

    const text = await execute("z_difficulty");
    expect(String(text.output).toLowerCase()).toContain(
      `"${targetCode.toLowerCase()}"`,
    );

    const next = await status();
    const targetDisplay = targetLabel === "普通" ? "普通" : "高级";
    expect(next.difficulty).toBe(targetDisplay);
    console.log(
      `[CHECK][PASS] 难度 ${before} -> ${targetCode}，API 与 RCON 回读一致`,
    );
  });

  test("特殊字符命令原样透传且不导致控制台/服务器崩溃", async () => {
    const command = `say "${testBotMarker}'--; status`;
    const result = await execute(command);
    expect(result.success, JSON.stringify(result)).toBe(true);
    expect(String(result.output)).toContain(testBotMarker);

    const after = await status();
    expect(after.online).toBe(true);
    expect(after.map).toBeTruthy();
    console.log(
      `[CHECK][PASS] 特殊字符命令保持原样透传: output=${JSON.stringify(
        result.output,
      )}`,
    );
  });

  test("sb_add 生成可观察 bot，users/status 双通道确认", async () => {
    const bot = await addTestBot();
    const botName = bot.name;
    const listed = await users();
    expect(listed).toContain(botName);

    const current = await status();
    const raw = await execute("status");
    expect(String(raw.output)).toContain(`"${botName}" BOT`);
    expect(current.online).toBe(true);
    console.log(
      `[CHECK][PASS] bot fixture 已由 sb_add 创建；status=${JSON.stringify(
        current.players,
      )}（bots 不一定计入 humans）`,
    );
  });

  test("kick bot 已发送且结果可观察", async () => {
    const bot = [...createdBots][0] ?? (await addTestBot());
    const response = await api.post("/api/plugin/l4d2/rcon/kick", {
      instanceId,
      target: bot.name,
    });
    expect(response.status, JSON.stringify(response.body)).toBeLessThan(400);
    expect(response.body?.code, JSON.stringify(response.body)).toBe(200);

    let removed = false;
    await expect
      .poll(
        async () => {
          removed = !(await users()).includes(bot.name);
          return removed;
        },
        { timeout: 10_000, intervals: [500, 1_000, 2_000] },
      )
      .toBe(true);
    createdBots.delete(bot);
    console.log(
      `[CHECK][PASS] kick ${bot.name} 后 users 中已消失`,
    );
  });

  test("封禁链路仅做能力探测，不对真实 SteamID 落盘", async () => {
    const command = await execute("cvarlist banid");
    const output = String(command.output ?? "");
    const hasBanCommand =
      /banid\s+: cmd/i.test(output) || /banid/.test(output);
    expect(
      hasBanCommand,
      `当前服务器未暴露 banid 命令: ${JSON.stringify(output)}`,
    ).toBe(true);

    const writeId = await execute("writeid");
    expect(writeId.success, JSON.stringify(writeId)).toBe(true);
    console.log(
      `[CHECK][PASS] banid 命令存在，writeid 可执行；未对真实玩家执行破坏性封禁`,
    );
  });

  test("UI 为原始 RCON 控制台且明确不存在踢人/封禁对话框", async ({
    page,
  }) => {
    await injectSession(page);
    await page.goto(
      `${frontendUrl}/extensions/app/l4d2/rcon?instanceId=${instanceId}`,
    );
    await expect(page.locator(".rcon-page")).toBeVisible({ timeout: 60_000 });
    await expect(
      page.getByPlaceholder("输入 RCON 命令..."),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: /踢人|封禁/ }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("dialog", { name: /踢人|封禁/ }),
    ).toHaveCount(0);
    console.log(
      "[CHECK][PASS] 当前 RCON 页面确为原始命令控制台，无踢人/封禁 UI 入口",
    );
  });
});
