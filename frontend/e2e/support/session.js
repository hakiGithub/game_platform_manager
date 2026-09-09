// UI 登录态注入：经平台 API 换取真实 JWT 后写入 localStorage。
// 键名与 src/stores/user.js 对齐（token / tokenType），供需要登录态的用例免走 UI 登录流程。
import { login } from "./api.js";
import { e2eEnv } from "./env.js";

export async function injectSession(page) {
  const token = await login(
    e2eEnv.backendUrl,
    e2eEnv.adminUser,
    e2eEnv.adminPass,
  );
  if (!token) {
    throw new Error("平台 API 登录未返回 token，无法注入 UI 登录态");
  }
  await page.addInitScript(
    ({ token, tokenType }) => {
      localStorage.setItem("token", token);
      localStorage.setItem("tokenType", tokenType);
    },
    { token, tokenType: "Bearer" },
  );
  return token;
}
