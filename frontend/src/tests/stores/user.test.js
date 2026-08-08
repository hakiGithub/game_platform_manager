/**
 * user.js Store 单元测试
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useUserStore } from "@/stores/user";

// Mock API
vi.mock("@/api/auth", () => ({
  login: vi.fn(),
  logout: vi.fn(),
  getUserInfo: vi.fn(),
  refreshToken: vi.fn(),
  changePassword: vi.fn(),
}));

// Mock router
vi.mock("@/router", () => ({
  default: {
    push: vi.fn(),
  },
}));

// Mock element-plus
vi.mock("element-plus", () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

describe("user store", () => {
  let userStore;
  let authApi;

  beforeEach(async () => {
    setActivePinia(createPinia());
    userStore = useUserStore();
    authApi = await import("@/api/auth");
    vi.clearAllMocks();
    localStorage.clear();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("初始状态", () => {
    it("应该有正确的初始状态", () => {
      expect(userStore.token).toBe("");
      expect(userStore.tokenType).toBe("Bearer");
      expect(userStore.userInfo).toBeNull();
      expect(userStore.permissions).toEqual([]);
    });

    it("应该从 localStorage 恢复 token", () => {
      localStorage.setItem("token", "saved-token");
      localStorage.setItem("tokenType", "Bearer");

      // 重新创建 store
      setActivePinia(createPinia());
      const newStore = useUserStore();

      expect(newStore.token).toBe("saved-token");
      expect(newStore.tokenType).toBe("Bearer");
    });
  });

  describe("计算属性", () => {
    it("isLoggedIn 应该返回正确的登录状态", () => {
      expect(userStore.isLoggedIn).toBe(false);

      userStore.token = "test-token";

      expect(userStore.isLoggedIn).toBe(true);
    });

    it("username 应该返回用户名", () => {
      expect(userStore.username).toBe("");

      userStore.userInfo = { username: "admin" };

      expect(userStore.username).toBe("admin");
    });

    it("nickname 应该返回昵称或用户名", () => {
      userStore.userInfo = { username: "admin" };
      expect(userStore.nickname).toBe("admin");

      userStore.userInfo = { username: "admin", nickname: "管理员" };
      expect(userStore.nickname).toBe("管理员");
    });

    it("avatar 应该返回头像", () => {
      expect(userStore.avatar).toBe("");

      userStore.userInfo = { avatar: "https://example.com/avatar.png" };

      expect(userStore.avatar).toBe("https://example.com/avatar.png");
    });

    it("email 应该返回邮箱", () => {
      expect(userStore.email).toBe("");

      userStore.userInfo = { email: "admin@example.com" };

      expect(userStore.email).toBe("admin@example.com");
    });
  });

  describe("Actions", () => {
    describe("login", () => {
      it("应该成功登录", async () => {
        authApi.login.mockResolvedValue({
          token: "test-token",
          tokenType: "Bearer",
        });

        authApi.getUserInfo.mockResolvedValue({
          username: "admin",
          nickname: "管理员",
          email: "admin@example.com",
        });

        const result = await userStore.login({
          username: "admin",
          password: "password123",
        });

        expect(result.token).toBe("test-token");
        expect(userStore.token).toBe("test-token");
        expect(userStore.userInfo.username).toBe("admin");
        expect(localStorage.getItem("token")).toBe("test-token");
      });

      it("应该处理登录失败", async () => {
        const error = new Error("用户名或密码错误");
        authApi.login.mockRejectedValue(error);

        await expect(
          userStore.login({
            username: "admin",
            password: "wrong",
          }),
        ).rejects.toThrow("用户名或密码错误");
      });
    });

    describe("logout", () => {
      it("应该成功登出", async () => {
        const router = (await import("@/router")).default;

        userStore.token = "test-token";
        userStore.userInfo = { username: "admin" };

        authApi.logout.mockResolvedValue(null);

        await userStore.logout();

        expect(userStore.token).toBe("");
        expect(userStore.userInfo).toBeNull();
        expect(router.push).toHaveBeenCalledWith("/login");
      });

      it("即使 API 失败也应该清除状态", async () => {
        const router = (await import("@/router")).default;

        userStore.token = "test-token";

        authApi.logout.mockRejectedValue(new Error("Network error"));

        await userStore.logout();

        expect(userStore.token).toBe("");
        expect(router.push).toHaveBeenCalledWith("/login");
      });
    });

    describe("fetchUserInfo", () => {
      it("应该获取用户信息", async () => {
        authApi.getUserInfo.mockResolvedValue({
          username: "admin",
          nickname: "管理员",
          email: "admin@example.com",
        });

        const result = await userStore.fetchUserInfo();

        expect(result.username).toBe("admin");
        expect(userStore.userInfo.username).toBe("admin");
      });

      it("获取失败时应该清除认证信息", async () => {
        authApi.getUserInfo.mockRejectedValue(new Error("Unauthorized"));

        userStore.token = "test-token";

        await expect(userStore.fetchUserInfo()).rejects.toThrow("Unauthorized");

        expect(userStore.token).toBe("");
      });
    });

    describe("refreshToken", () => {
      it("应该刷新 token", async () => {
        authApi.refreshToken.mockResolvedValue({
          token: "new-token",
          tokenType: "Bearer",
        });

        const result = await userStore.refreshToken();

        expect(result.token).toBe("new-token");
        expect(userStore.token).toBe("new-token");
        expect(localStorage.getItem("token")).toBe("new-token");
      });

      it("刷新失败时应该清除认证信息", async () => {
        authApi.refreshToken.mockRejectedValue(new Error("Token expired"));

        userStore.token = "old-token";

        await expect(userStore.refreshToken()).rejects.toThrow("Token expired");

        expect(userStore.token).toBe("");
      });
    });

    describe("changePassword", () => {
      it("应该成功修改密码", async () => {
        const router = (await import("@/router")).default;
        const { ElMessage } = await import("element-plus");

        authApi.changePassword.mockResolvedValue(null);

        userStore.token = "test-token";

        await userStore.changePassword({
          oldPassword: "old",
          newPassword: "new",
          confirmPassword: "new",
        });

        expect(ElMessage.success).toHaveBeenCalledWith(
          "密码修改成功，请重新登录",
        );
        expect(userStore.token).toBe("");
        expect(router.push).toHaveBeenCalledWith("/login");
      });

      it("应该处理修改密码失败", async () => {
        authApi.changePassword.mockRejectedValue(new Error("原密码错误"));

        await expect(
          userStore.changePassword({
            oldPassword: "wrong",
            newPassword: "new",
            confirmPassword: "new",
          }),
        ).rejects.toThrow("原密码错误");
      });
    });

    describe("hasPermission", () => {
      it("没有权限列表时应该返回 true", () => {
        expect(userStore.hasPermission("any")).toBe(true);
      });

      it("应该检查权限", () => {
        userStore.permissions = ["user:read", "user:write"];

        expect(userStore.hasPermission("user:read")).toBe(true);
        expect(userStore.hasPermission("user:delete")).toBe(false);
      });
    });

    describe("setToken", () => {
      it("应该设置 token", () => {
        userStore.setToken("new-token", "Bearer");

        expect(userStore.token).toBe("new-token");
        expect(userStore.tokenType).toBe("Bearer");
        expect(localStorage.getItem("token")).toBe("new-token");
      });

      it("应该使用默认 token 类型", () => {
        userStore.setToken("new-token");

        expect(userStore.tokenType).toBe("Bearer");
      });
    });

    describe("clearAuth", () => {
      it("应该清除所有认证信息", () => {
        userStore.token = "test-token";
        userStore.tokenType = "Bearer";
        userStore.userInfo = { username: "admin" };
        userStore.permissions = ["user:read"];

        userStore.clearAuth();

        expect(userStore.token).toBe("");
        expect(userStore.tokenType).toBe("Bearer");
        expect(userStore.userInfo).toBeNull();
        expect(userStore.permissions).toEqual([]);
        expect(localStorage.getItem("token")).toBeNull();
      });
    });

    describe("initUserState", () => {
      it("有 token 时应该获取用户信息", async () => {
        authApi.getUserInfo.mockResolvedValue({
          username: "admin",
        });

        userStore.token = "test-token";

        await userStore.initUserState();

        expect(userStore.userInfo.username).toBe("admin");
      });

      it("没有 token 时不应获取用户信息", async () => {
        userStore.token = "";

        await userStore.initUserState();

        expect(authApi.getUserInfo).not.toHaveBeenCalled();
      });

      it("获取用户信息失败时不应抛出错误", async () => {
        authApi.getUserInfo.mockRejectedValue(new Error("Network error"));

        userStore.token = "test-token";

        // 不应抛出错误
        await expect(userStore.initUserState()).resolves.toBeUndefined();
      });
    });
  });
});
