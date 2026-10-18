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

// 业务成功的判据是信封 code，不是 HTTP 状态码。
// GlobalExceptionHandler 对 BusinessException 返回 HTTP 200 + Result.fail(...)，
// 只看 res.ok / status < 400 会把「拒绝」当成「成功」——MERC-24 的 AC-20 就栽在这上面：
// retry-deploy 的「只有异常状态的实例可以重试部署」照样过断言，用例随后读到的是上一次
// create 的日志。凡是「这次写操作必须成功」的地方都用本函数判，不看 status。
export function assertOk(res, label) {
  const code = res?.body?.code;
  if (res?.status >= 400 || (code !== 200 && code !== 0)) {
    const body = JSON.stringify(res?.body ?? null).slice(0, 300);
    throw new Error(
      `${label ?? "接口"}未返回业务成功: HTTP ${res?.status} / body=${body}`,
    );
  }
  return res;
}

export function createApi(backendUrl, token) {
  async function request(method, path, jsonBody, attempt = 1) {
    try {
      const res = await fetch(`${backendUrl}${path}`, {
        method,
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
