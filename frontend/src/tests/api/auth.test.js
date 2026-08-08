/**
 * auth.js API 单元测试
 * 测试登录、登出、用户信息、密码修改等接口
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// Mock request 模块
vi.mock("@/utils/request", () => ({
  default: vi.fn(),
}));

describe("auth API", () => {
  let request;
  let authApi;

  beforeEach(async () => {
    vi.clearAllMocks();
    request = (await import("@/utils/request")).default;
    authApi = await import("@/api/auth");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("login - 用户登录", () => {
    it("应该使用正确的参数调用登录接口", async () => {
      const loginData = {
        username: "admin",
        password: "password123",
      };
      const mockResponse = {
        token: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
        tokenType: "Bearer",
      };
      request.mockResolvedValue(mockResponse);

      const result = await authApi.login(loginData);

      expect(request).toHaveBeenCalledWith({
        url: "/auth/login",
        method: "post",
        data: loginData,
      });
      expect(result).toEqual(mockResponse);
    });

    it("应该处理登录失败", async () => {
      const loginData = {
        username: "admin",
        password: "wrong_password",
      };
      request.mockRejectedValue(new Error("用户名或密码错误"));

      await expect(authApi.login(loginData)).rejects.toThrow(
        "用户名或密码错误",
      );
    });

    it("应该处理空用户名或密码", async () => {
      const loginData = {
        username: "",
        password: "",
      };
      request.mockRejectedValue(new Error("用户名和密码不能为空"));

      await expect(authApi.login(loginData)).rejects.toThrow(
        "用户名和密码不能为空",
      );
    });
  });

  describe("register - 用户注册", () => {
    it("应该使用正确的参数调用注册接口", async () => {
      const registerData = {
        username: "newuser",
        password: "password123",
        confirmPassword: "password123",
        email: "newuser@example.com",
        phone: "13800138000",
      };
      request.mockResolvedValue(null);

      const result = await authApi.register(registerData);

      expect(request).toHaveBeenCalledWith({
        url: "/auth/register",
        method: "post",
        data: registerData,
      });
      expect(result).toBeNull();
    });

    it("应该处理注册失败 - 用户名已存在", async () => {
      const registerData = {
        username: "existinguser",
        password: "password123",
        confirmPassword: "password123",
      };
      request.mockRejectedValue(new Error("用户名已存在"));

      await expect(authApi.register(registerData)).rejects.toThrow(
        "用户名已存在",
      );
    });

    it("应该处理注册失败 - 密码不匹配", async () => {
      const registerData = {
        username: "newuser",
        password: "password123",
        confirmPassword: "differentpassword",
      };
      request.mockRejectedValue(new Error("两次输入的密码不一致"));

      await expect(authApi.register(registerData)).rejects.toThrow(
        "两次输入的密码不一致",
      );
    });
  });

  describe("logout - 用户登出", () => {
    it("应该正确调用登出接口", async () => {
      request.mockResolvedValue(null);

      const result = await authApi.logout();

      expect(request).toHaveBeenCalledWith({
        url: "/auth/logout",
        method: "post",
      });
      expect(result).toBeNull();
    });

    it("应该处理登出失败", async () => {
      request.mockRejectedValue(new Error("网络错误"));

      await expect(authApi.logout()).rejects.toThrow("网络错误");
    });
  });

  describe("getUserInfo - 获取用户信息", () => {
    it("应该正确获取用户信息", async () => {
      const mockUserInfo = {
        username: "admin",
        nickname: "管理员",
        email: "admin@example.com",
        avatar: "https://example.com/avatar.png",
        roles: ["admin"],
        permissions: ["user:read", "user:write"],
      };
      request.mockResolvedValue(mockUserInfo);

      const result = await authApi.getUserInfo();

      expect(request).toHaveBeenCalledWith({
        url: "/auth/info",
        method: "get",
      });
      expect(result).toEqual(mockUserInfo);
    });

    it("应该处理未授权错误", async () => {
      request.mockRejectedValue(new Error("未授权，请重新登录"));

      await expect(authApi.getUserInfo()).rejects.toThrow("未授权，请重新登录");
    });

    it("应该处理返回部分用户信息", async () => {
      const mockUserInfo = {
        username: "admin",
        // 缺少其他字段
      };
      request.mockResolvedValue(mockUserInfo);

      const result = await authApi.getUserInfo();

      expect(result.username).toBe("admin");
      expect(result.nickname).toBeUndefined();
    });
  });

  describe("refreshToken - 刷新Token", () => {
    it("应该正确刷新Token", async () => {
      const mockResponse = {
        token: "new_token_123",
        tokenType: "Bearer",
      };
      request.mockResolvedValue(mockResponse);

      const result = await authApi.refreshToken();

      expect(request).toHaveBeenCalledWith({
        url: "/auth/refresh",
        method: "post",
      });
      expect(result).toEqual(mockResponse);
    });

    it("应该处理刷新Token失败", async () => {
      request.mockRejectedValue(new Error("Token已过期"));

      await expect(authApi.refreshToken()).rejects.toThrow("Token已过期");
    });
  });

  describe("changePassword - 修改密码", () => {
    it("应该使用正确的参数调用修改密码接口", async () => {
      const passwordData = {
        oldPassword: "oldpassword123",
        newPassword: "newpassword123",
        confirmPassword: "newpassword123",
      };
      request.mockResolvedValue(null);

      const result = await authApi.changePassword(passwordData);

      expect(request).toHaveBeenCalledWith({
        url: "/auth/password",
        method: "put",
        data: passwordData,
      });
      expect(result).toBeNull();
    });

    it("应该处理原密码错误", async () => {
      const passwordData = {
        oldPassword: "wrongpassword",
        newPassword: "newpassword123",
        confirmPassword: "newpassword123",
      };
      request.mockRejectedValue(new Error("原密码错误"));

      await expect(authApi.changePassword(passwordData)).rejects.toThrow(
        "原密码错误",
      );
    });

    it("应该处理新密码与确认密码不一致", async () => {
      const passwordData = {
        oldPassword: "oldpassword123",
        newPassword: "newpassword123",
        confirmPassword: "differentpassword",
      };
      request.mockRejectedValue(new Error("两次输入的密码不一致"));

      await expect(authApi.changePassword(passwordData)).rejects.toThrow(
        "两次输入的密码不一致",
      );
    });

    it("应该处理新密码长度不足", async () => {
      const passwordData = {
        oldPassword: "oldpassword123",
        newPassword: "123",
        confirmPassword: "123",
      };
      request.mockRejectedValue(new Error("密码长度不能少于6位"));

      await expect(authApi.changePassword(passwordData)).rejects.toThrow(
        "密码长度不能少于6位",
      );
    });
  });
});
