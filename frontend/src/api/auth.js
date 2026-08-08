import request from "@/utils/request";

/**
 * 认证模块 API
 * 模块路径: /api/auth
 */

/**
 * 用户登录
 * @param {Object} data - 登录参数
 * @param {string} data.username - 用户名
 * @param {string} data.password - 密码
 * @returns {Promise<{token: string, tokenType: string}>}
 */
export function login(data) {
  return request({
    url: "/auth/login",
    method: "post",
    data,
  });
}

/**
 * 用户注册
 * @param {Object} data - 注册参数
 * @param {string} data.username - 用户名
 * @param {string} data.password - 密码
 * @param {string} data.confirmPassword - 确认密码
 * @param {string} [data.email] - 邮箱
 * @param {string} [data.phone] - 手机号
 * @returns {Promise<null>}
 */
export function register(data) {
  return request({
    url: "/auth/register",
    method: "post",
    data,
  });
}

/**
 * 用户登出
 * @returns {Promise<null>}
 */
export function logout() {
  return request({
    url: "/auth/logout",
    method: "post",
  });
}

/**
 * 获取当前用户信息
 * @returns {Promise<{username: string, nickname: string, email: string, avatar: string}>}
 */
export function getUserInfo() {
  return request({
    url: "/auth/info",
    method: "get",
  });
}

/**
 * 刷新Token
 * @returns {Promise<{token: string, tokenType: string}>}
 */
export function refreshToken() {
  return request({
    url: "/auth/refresh",
    method: "post",
  });
}

/**
 * 修改密码
 * @param {Object} data - 密码参数
 * @param {string} data.oldPassword - 原密码
 * @param {string} data.newPassword - 新密码
 * @param {string} data.confirmPassword - 确认新密码
 * @returns {Promise<null>}
 */
export function changePassword(data) {
  return request({
    url: "/auth/password",
    method: "put",
    data,
  });
}
