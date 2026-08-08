import { defineStore } from "pinia";
import { ref, computed } from "vue";
import {
  login as loginApi,
  logout as logoutApi,
  getUserInfo,
  refreshToken as refreshTokenApi,
  changePassword as changePasswordApi,
} from "@/api/auth";
import router from "@/router";
import { ElMessage } from "element-plus";

export const useUserStore = defineStore("user", () => {
  // ========== 状态 ==========
  const token = ref(localStorage.getItem("token") || "");
  const tokenType = ref(localStorage.getItem("tokenType") || "Bearer");
  const userInfo = ref(null);
  const permissions = ref([]);

  // ========== 计算属性 ==========
  const isLoggedIn = computed(() => !!token.value);
  const username = computed(() => userInfo.value?.username || "");
  const nickname = computed(
    () => userInfo.value?.nickname || userInfo.value?.username || "",
  );
  const avatar = computed(() => userInfo.value?.avatar || "");
  const email = computed(() => userInfo.value?.email || "");

  // ========== Actions ==========

  /**
   * 用户登录
   * @param {Object} loginForm - 登录表单
   * @param {string} loginForm.username - 用户名
   * @param {string} loginForm.password - 密码
   * @returns {Promise<{token: string, tokenType: string}>}
   */
  async function login(loginForm) {
    try {
      const data = await loginApi(loginForm);

      // 保存token
      token.value = data.token;
      tokenType.value = data.tokenType || "Bearer";
      localStorage.setItem("token", data.token);
      localStorage.setItem("tokenType", data.tokenType || "Bearer");

      // 获取用户信息
      await fetchUserInfo();

      return data;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 用户登出
   */
  async function logout() {
    try {
      await logoutApi();
    } catch (error) {
      console.error("Logout error:", error);
    } finally {
      // 清除状态
      clearAuth();

      // 跳转登录页
      router.push("/login");
    }
  }

  /**
   * 获取用户信息
   * @returns {Promise<Object>}
   */
  async function fetchUserInfo() {
    try {
      const data = await getUserInfo();
      userInfo.value = data;
      return data;
    } catch (error) {
      // 获取用户信息失败，清除token
      clearAuth();
      throw error;
    }
  }

  /**
   * 刷新Token
   * @returns {Promise<{token: string, tokenType: string}>}
   */
  async function refreshToken() {
    try {
      const data = await refreshTokenApi();

      // 更新token
      token.value = data.token;
      tokenType.value = data.tokenType || "Bearer";
      localStorage.setItem("token", data.token);
      localStorage.setItem("tokenType", data.tokenType || "Bearer");

      return data;
    } catch (error) {
      // 刷新失败，清除认证信息
      clearAuth();
      throw error;
    }
  }

  /**
   * 修改密码
   * @param {Object} data - 密码数据
   * @param {string} data.oldPassword - 原密码
   * @param {string} data.newPassword - 新密码
   * @param {string} data.confirmPassword - 确认新密码
   * @returns {Promise<null>}
   */
  async function changePassword(data) {
    try {
      await changePasswordApi(data);
      ElMessage.success("密码修改成功，请重新登录");

      // 清除认证信息，跳转登录页
      clearAuth();
      router.push("/login");

      return null;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 检查权限
   * @param {string} permission - 权限标识
   * @returns {boolean}
   */
  function hasPermission(permission) {
    if (!permissions.value || permissions.value.length === 0) {
      return true; // 暂时没有权限控制，默认返回true
    }
    return permissions.value.includes(permission);
  }

  /**
   * 设置Token
   * @param {string} newToken - 新token
   * @param {string} [newTokenType='Bearer'] - token类型
   */
  function setToken(newToken, newTokenType = "Bearer") {
    token.value = newToken;
    tokenType.value = newTokenType;
    localStorage.setItem("token", newToken);
    localStorage.setItem("tokenType", newTokenType);
  }

  /**
   * 清除认证信息
   */
  function clearAuth() {
    token.value = "";
    tokenType.value = "Bearer";
    userInfo.value = null;
    permissions.value = [];
    localStorage.removeItem("token");
    localStorage.removeItem("tokenType");
  }

  /**
   * 初始化用户状态（页面刷新时调用）
   */
  async function initUserState() {
    if (token.value) {
      try {
        await fetchUserInfo();
      } catch (error) {
        console.error("Failed to fetch user info:", error);
      }
    }
  }

  return {
    // 状态
    token,
    tokenType,
    userInfo,
    permissions,

    // 计算属性
    isLoggedIn,
    username,
    nickname,
    avatar,
    email,

    // Actions
    login,
    logout,
    fetchUserInfo,
    refreshToken,
    changePassword,
    hasPermission,
    setToken,
    clearAuth,
    initUserState,
  };
});
