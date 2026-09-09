// 平台 REST 断言客户端：效果断言统一走平台自身 API（携带 JWT），
// 而不是让测试进程直连 SSH —— 这是规格定下的断言通道（见 .scratch/ui-e2e-automation/spec.md）。
export async function login(backendUrl, username, password) {
  const res = await fetch(`${backendUrl}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    throw new Error(`平台登录失败: HTTP ${res.status}`);
  }
  const body = await res.json();
  return body.data?.token ?? body.data?.accessToken;
}

export function createApi(backendUrl, token) {
  async function request(method, path, jsonBody) {
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
  }
  return {
    get: (path) => request("GET", path),
    post: (path, jsonBody) => request("POST", path, jsonBody),
    put: (path, jsonBody) => request("PUT", path, jsonBody),
    delete: (path) => request("DELETE", path),
  };
}
