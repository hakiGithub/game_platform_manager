/**
 * request.js 单元测试
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import axios from "axios";

// Mock axios
vi.mock("axios", () => {
  const mockAxios = {
    create: vi.fn(() => mockAxios),
    interceptors: {
      request: {
        use: vi.fn(),
      },
      response: {
        use: vi.fn(),
      },
    },
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  };
  return {
    default: mockAxios,
  };
});

// Mock element-plus
vi.mock("element-plus", () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn().mockResolvedValue(true),
  },
}));

// Mock router
vi.mock("@/router", () => ({
  default: {
    push: vi.fn(),
  },
}));

// Mock user store
vi.mock("@/stores/user", () => ({
  useUserStore: vi.fn(() => ({
    token: "test-token",
    logout: vi.fn(),
  })),
}));

describe("request.js", () => {
  let mockAxios;
  let requestInterceptors;
  let responseInterceptors;

  beforeEach(async () => {
    vi.clearAllMocks();

    // 重新导入以获取新的实例
    mockAxios = (await import("axios")).default;

    // 获取拦截器
    requestInterceptors = mockAxios.interceptors.request.use.mock.calls;
    responseInterceptors = mockAxios.interceptors.response.use.mock.calls;
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("axios 实例创建", () => {
    it("应该使用正确的配置创建 axios 实例", async () => {
      // 导入 request 会触发 axios.create
      await import("@/utils/request");

      expect(mockAxios.create).toHaveBeenCalled();
      const createConfig = mockAxios.create.mock.calls[0][0];

      expect(createConfig.timeout).toBe(30000);
      expect(createConfig.headers["Content-Type"]).toBe(
        "application/json;charset=UTF-8",
      );
    });
  });

  describe("请求拦截器", () => {
    it("应该在请求头中添加 token", async () => {
      await import("@/utils/request");

      const requestInterceptor = requestInterceptors[0][0];
      const config = {
        headers: {},
      };

      const result = requestInterceptor(config);

      expect(result.headers["Authorization"]).toBe("Bearer test-token");
    });

    it("应该处理请求错误", async () => {
      await import("@/utils/request");

      const requestErrorHandler = requestInterceptors[0][1];
      const error = new Error("Request error");

      await expect(requestErrorHandler(error)).rejects.toThrow("Request error");
    });
  });

  describe("响应拦截器", () => {
    it("应该正确处理成功响应 (code: 0)", async () => {
      await import("@/utils/request");

      const responseInterceptor = responseInterceptors[0][0];
      const response = {
        data: {
          code: 0,
          data: { id: 1, name: "test" },
          message: "success",
        },
      };

      const result = await responseInterceptor(response);

      expect(result).toEqual({ id: 1, name: "test" });
    });

    it("应该正确处理成功响应 (code: 200)", async () => {
      await import("@/utils/request");

      const responseInterceptor = responseInterceptors[0][0];
      const response = {
        data: {
          code: 200,
          data: { id: 2, name: "test2" },
          message: "success",
        },
      };

      const result = await responseInterceptor(response);

      expect(result).toEqual({ id: 2, name: "test2" });
    });

    it("应该处理文件下载响应", async () => {
      await import("@/utils/request");

      const responseInterceptor = responseInterceptors[0][0];
      const response = {
        config: {
          responseType: "blob",
        },
        data: new Blob(["test"]),
      };

      const result = await responseInterceptor(response);

      expect(result).toBe(response);
    });

    it("应该处理业务错误", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseInterceptor = responseInterceptors[0][0];
      const response = {
        data: {
          code: 400,
          message: "业务错误",
        },
      };

      await expect(responseInterceptor(response)).rejects.toThrow("业务错误");
      expect(ElMessage.error).toHaveBeenCalledWith("业务错误");
    });

    it("应该处理默认错误消息", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseInterceptor = responseInterceptors[0][0];
      const response = {
        data: {
          code: 500,
        },
      };

      await expect(responseInterceptor(response)).rejects.toThrow("请求失败");
      expect(ElMessage.error).toHaveBeenCalledWith("请求失败");
    });
  });

  describe("响应错误处理", () => {
    it("应该处理 401 错误", async () => {
      const { ElMessageBox } = await import("element-plus");
      const router = (await import("@/router")).default;
      const { useUserStore } = await import("@/stores/user");

      await import("@/utils/request");

      const responseErrorHandler = responseInterceptors[0][1];
      const error = {
        response: {
          status: 401,
          data: {},
        },
      };

      await responseErrorHandler(error);

      expect(ElMessageBox.confirm).toHaveBeenCalled();
    });

    it("应该处理 403 错误", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseErrorHandler = responseInterceptors[0][1];
      const error = {
        response: {
          status: 403,
          data: {},
        },
      };

      await expect(responseErrorHandler(error)).rejects.toBeDefined();
      expect(ElMessage.error).toHaveBeenCalledWith("没有权限访问该资源");
    });

    it("应该处理 404 错误", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseErrorHandler = responseInterceptors[0][1];
      const error = {
        response: {
          status: 404,
          data: {},
        },
      };

      await expect(responseErrorHandler(error)).rejects.toBeDefined();
      expect(ElMessage.error).toHaveBeenCalledWith("请求的资源不存在");
    });

    it("应该处理 500 错误", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseErrorHandler = responseInterceptors[0][1];
      const error = {
        response: {
          status: 500,
          data: {
            message: "服务器内部错误",
          },
        },
      };

      await expect(responseErrorHandler(error)).rejects.toBeDefined();
      expect(ElMessage.error).toHaveBeenCalledWith("服务器内部错误");
    });

    it("应该处理超时错误", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseErrorHandler = responseInterceptors[0][1];
      const error = {
        message: "timeout of 30000ms exceeded",
      };

      await expect(responseErrorHandler(error)).rejects.toBeDefined();
      expect(ElMessage.error).toHaveBeenCalledWith("请求超时，请检查网络连接");
    });

    it("应该处理网络错误", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseErrorHandler = responseInterceptors[0][1];
      const error = {
        message: "Network Error",
      };

      await expect(responseErrorHandler(error)).rejects.toBeDefined();
      expect(ElMessage.error).toHaveBeenCalledWith("网络错误，请检查网络连接");
    });

    it("应该处理未知错误", async () => {
      const { ElMessage } = await import("element-plus");
      await import("@/utils/request");

      const responseErrorHandler = responseInterceptors[0][1];
      const error = {
        message: "Unknown error",
      };

      await expect(responseErrorHandler(error)).rejects.toBeDefined();
      expect(ElMessage.error).toHaveBeenCalledWith("Unknown error");
    });
  });
});
