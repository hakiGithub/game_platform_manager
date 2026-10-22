// 平台 REST 断言客户端：效果断言统一走平台自身 API（携带 JWT），
// 而不是让测试进程直连 SSH —— 这是规格定下的断言通道（见 .scratch/ui-e2e-automation/spec.md）。
export async function login(backendUrl, username, password, attempt = 1) {
  try {
    const res = await fetch(`${backendUrl}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    if (!res.ok) {
      throw new Error(`平台登录失败: HTTP ${res.status}`);
    }
    const body = await res.json().catch(() => null);
    const token = body?.data?.token ?? body?.data?.accessToken;
    if (!body || !(body.code === 200 || body.code === 0) || !token) {
      throw new Error(
        `平台登录响应异常: ${JSON.stringify(body).slice(0, 200)}`,
      );
    }
    return token;
  } catch (error) {
    // 瞬时网络/后端初始化抖动重试 2 次（整轮演练中前一分组刚结束时的窗口期）
    if (attempt < 3) {
      await new Promise((r) => setTimeout(r, 3000 * attempt));
      return login(backendUrl, username, password, attempt + 1);
    }
    throw error;
  }
}

/**
 * 单次请求超时：长链路（插件聚合总览会逐个连接源探活）可能长时间不返回，
 * 无超时会让 Playwright 用例整体挂到 testTimeout 才失败，产物里看不出是哪一步。
 * 默认 120s（实例删除要下发 compose down，可能接近一分钟），可用 E2E_API_TIMEOUT_MS 覆盖。
 */
const REQUEST_TIMEOUT_MS = Number(process.env.E2E_API_TIMEOUT_MS ?? 120_000);

export function createApi(backendUrl, token) {
  async function request(method, path, jsonBody, attempt = 1) {
    try {
      const res = await fetch(`${backendUrl}${path}`, {
        method,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        headers: {
          Authorization: `Bearer ${token}`,
          ...(jsonBody !== undefined
            ? { "Content-Type": "application/json" }
            : {}),
        },
        ...(jsonBody !== undefined ? { body: JSON.stringify(jsonBody) } : {}),
      });
      const body = await res.json().catch(() => null);
      return { status: res.status, body };
    } catch (error) {
      if (error?.name === "TimeoutError") {
        throw new Error(
          `[CHECK][FAIL] ${method} ${path} 超过 ${REQUEST_TIMEOUT_MS}ms 未返回（后端阻塞或连接源探活卡死）; remediation: 查后端日志与实例/数据库连接状态`,
        );
      }
      // 长时间演练中宿主机高负载可能造成回环网络瞬时抖动，网络错误重试 2 次
      if (attempt < 3) {
        await new Promise((r) => setTimeout(r, 3000 * attempt));
        return request(method, path, jsonBody, attempt + 1);
      }
      throw error;
    }
  }
  return {
    get: (path) => request("GET", path),
    post: (path, jsonBody) => request("POST", path, jsonBody),
    put: (path, jsonBody) => request("PUT", path, jsonBody),
    delete: (path) => request("DELETE", path),
  };
}

export async function findHostsByName(api, name) {
  const res = await api.get("/api/hosts");
  const data = res.body?.data;
  const list = Array.isArray(data) ? data : (data?.records ?? data?.list ?? []);
  return list.filter((host) => host.name === name);
}

export async function removeHostsByName(api, name) {
  for (const host of await findHostsByName(api, name)) {
    await api.delete(`/api/hosts/${host.id}`).catch(() => {});
  }
  const remaining = await findHostsByName(api, name);
  if (remaining.length > 0) {
    throw new Error(
      `测试前置清理失败：主机 ${name} 仍存在（${remaining.map((h) => h.id).join(", ")}）`,
    );
  }
}

/**
 * 按 IP 复用牺牲主机记录；不存在时创建并返回 created 标记。
 *
 * 平台对 host_info.ip_address 有全局唯一约束。持久开发库或整轮编排中，
 * 同一个 WSL IP 可能已经由其他分组纳管；用例不应因为“该 IP 已存在”失败，
 * 也不应删除别的分组正在使用的主机记录。
 */
export async function ensureHostByIp(api, {
  name,
  ip,
  sshPort,
  sshUsername,
  sshPassword,
}) {
  const res = await api.get("/api/hosts");
  const data = res.body?.data;
  const list = Array.isArray(data) ? data : (data?.records ?? data?.list ?? []);
  const existing = list.find((host) => host.ip === ip);
  if (existing) {
    return { hostId: existing.id, host: existing, created: false };
  }

  const created = await api.post("/api/hosts", {
    name,
    ip,
    sshPort,
    sshUsername,
    authType: "password",
    sshPassword,
  });
  const hostId = created.body?.data?.id ?? null;
  if (!hostId) {
    throw new Error(
      `[CHECK][FAIL] 主机创建失败: HTTP ${created.status} ${JSON.stringify(
        created.body,
      ).slice(0, 300)}; remediation: 确认平台后端可用，且 ${ip} 未被其他记录占用`,
    );
  }
  return { hostId, host: created.body?.data, created: true };
}

export async function removeHostsByPrefix(api, prefix) {
  const res = await api.get("/api/hosts");
  const data = res.body?.data;
  const list = Array.isArray(data) ? data : (data?.records ?? data?.list ?? []);
  for (const host of list.filter((item) => item.name?.startsWith(prefix))) {
    await api.delete(`/api/hosts/${host.id}`).catch(() => {});
  }
  const remainingRes = await api.get("/api/hosts");
  const remainingData = remainingRes.body?.data;
  const remainingList = Array.isArray(remainingData)
    ? remainingData
    : (remainingData?.records ?? remainingData?.list ?? []);
  const remaining = remainingList.filter((host) =>
    host.name?.startsWith(prefix),
  );
  if (remaining.length > 0) {
    throw new Error(
      `测试前置清理失败：名称前缀 ${prefix} 的主机仍存在（${remaining
        .map((h) => `${h.name}:${h.id}`)
        .join(", ")}）`,
    );
  }
}

export async function removeGamesByCode(api, gameCode) {
  const findGames = async () => {
    const res = await api.get(
      `/api/games?keyword=${encodeURIComponent(gameCode)}&size=100`,
    );
    return (res.body?.data?.records ?? []).filter(
      (game) => game.gameCode === gameCode,
    );
  };

  for (const game of await findGames()) {
    await api.delete(`/api/games/${game.id}`).catch(() => {});
  }
  const remaining = await findGames();
  if (remaining.length > 0) {
    throw new Error(
      `测试前置清理失败：游戏 ${gameCode} 仍存在（${remaining
        .map((g) => g.id)
        .join(", ")}）`,
    );
  }
}
