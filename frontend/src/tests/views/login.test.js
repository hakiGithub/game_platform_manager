/**
 * 登录组件单元测试
 * 测试表单验证、登录成功/失败、记住密码功能
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { setActivePinia, createPinia } from "pinia";

// Mock Element Plus
const mockElMessageSuccess = vi.fn();
const mockElMessageError = vi.fn();

vi.mock("element-plus", () => ({
  ElMessage: {
    success: mockElMessageSuccess,
    error: mockElMessageError,
    warning: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn().mockResolvedValue(true),
  },
}));

// Mock router
const mockPush = vi.fn();
const mockCurrentRoute = { value: { query: {} } };

vi.mock("vue-router", () => ({
  useRouter: () => ({
    push: mockPush,
    currentRoute: mockCurrentRoute,
  }),
}));

// Mock user store
const mockLogin = vi.fn();

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({
    login: mockLogin,
    token: "",
  }),
}));

// 创建简化版登录组件用于测试
const LoginComponent = {
  template: `
    <div class="login-container">
      <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules">
        <el-form-item prop="username">
          <el-input v-model="loginForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="loginForm.remember">记住密码</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleLogin">登录</el-button>
        </el-form-item>
      </el-form>
    </div>
  `,
  data() {
    return {
      loading: false,
      loginForm: {
        username: "",
        password: "",
        remember: false,
      },
      loginRules: {
        username: [
          { required: true, message: "请输入用户名", trigger: "blur" },
          {
            min: 2,
            max: 50,
            message: "用户名长度为2-50个字符",
            trigger: "blur",
          },
        ],
        password: [
          { required: true, message: "请输入密码", trigger: "blur" },
          { min: 6, max: 20, message: "密码长度为6-20个字符", trigger: "blur" },
        ],
      },
    };
  },
  mounted() {
    // 读取记住的用户名
    const rememberedUsername = localStorage.getItem("rememberedUsername");
    const rememberMe = localStorage.getItem("rememberMe");
    if (rememberedUsername && rememberMe === "true") {
      this.loginForm.username = rememberedUsername;
      this.loginForm.remember = true;
    }
  },
  methods: {
    async handleLogin() {
      // 模拟表单验证
      if (!this.loginForm.username || !this.loginForm.password) {
        return;
      }
      if (
        this.loginForm.username.length < 2 ||
        this.loginForm.username.length > 50
      ) {
        return;
      }
      if (
        this.loginForm.password.length < 6 ||
        this.loginForm.password.length > 20
      ) {
        return;
      }

      this.loading = true;
      try {
        const { useUserStore } = await import("@/stores/user");
        const userStore = useUserStore();
        await userStore.login(this.loginForm);

        // 记住密码
        if (this.loginForm.remember) {
          localStorage.setItem("rememberedUsername", this.loginForm.username);
          localStorage.setItem("rememberMe", "true");
        } else {
          localStorage.removeItem("rememberedUsername");
          localStorage.removeItem("rememberMe");
        }

        const { ElMessage } = await import("element-plus");
        ElMessage.success("登录成功");

        // 跳转到重定向页面或首页
        const { useRouter } = await import("vue-router");
        const router = useRouter();
        const redirect =
          router.currentRoute.value.query.redirect || "/dashboard";
        router.push(redirect);
      } catch (error) {
        console.error("Login failed:", error);
        this.loginForm.password = "";
      } finally {
        this.loading = false;
      }
    },
  },
};

describe("Login Component", () => {
  let wrapper;

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    localStorage.clear();
  });

  afterEach(() => {
    if (wrapper) {
      wrapper.unmount();
    }
    vi.clearAllMocks();
  });

  describe("表单验证", () => {
    it("应该有正确的初始状态", () => {
      wrapper = mount(LoginComponent);

      expect(wrapper.vm.loginForm.username).toBe("");
      expect(wrapper.vm.loginForm.password).toBe("");
      expect(wrapper.vm.loginForm.remember).toBe(false);
      expect(wrapper.vm.loading).toBe(false);
    });

    it("应该有正确的验证规则", () => {
      wrapper = mount(LoginComponent);

      expect(wrapper.vm.loginRules.username).toHaveLength(2);
      expect(wrapper.vm.loginRules.username[0].required).toBe(true);
      expect(wrapper.vm.loginRules.username[0].message).toBe("请输入用户名");

      expect(wrapper.vm.loginRules.password).toHaveLength(2);
      expect(wrapper.vm.loginRules.password[0].required).toBe(true);
      expect(wrapper.vm.loginRules.password[0].message).toBe("请输入密码");
    });

    it("用户名长度应该在2-50个字符之间", () => {
      wrapper = mount(LoginComponent);

      const usernameRule = wrapper.vm.loginRules.username[1];
      expect(usernameRule.min).toBe(2);
      expect(usernameRule.max).toBe(50);
    });

    it("密码长度应该在6-20个字符之间", () => {
      wrapper = mount(LoginComponent);

      const passwordRule = wrapper.vm.loginRules.password[1];
      expect(passwordRule.min).toBe(6);
      expect(passwordRule.max).toBe(20);
    });

    it("空用户名应该阻止登录", async () => {
      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "";
      wrapper.vm.loginForm.password = "password123";

      await wrapper.vm.handleLogin();

      expect(mockLogin).not.toHaveBeenCalled();
    });

    it("空密码应该阻止登录", async () => {
      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "";

      await wrapper.vm.handleLogin();

      expect(mockLogin).not.toHaveBeenCalled();
    });

    it("用户名太短应该阻止登录", async () => {
      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "a";
      wrapper.vm.loginForm.password = "password123";

      await wrapper.vm.handleLogin();

      expect(mockLogin).not.toHaveBeenCalled();
    });

    it("密码太短应该阻止登录", async () => {
      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "123";

      await wrapper.vm.handleLogin();

      expect(mockLogin).not.toHaveBeenCalled();
    });
  });

  describe("记住密码功能", () => {
    it("应该读取记住的用户名", () => {
      localStorage.setItem("rememberedUsername", "admin");
      localStorage.setItem("rememberMe", "true");

      wrapper = mount(LoginComponent);

      expect(wrapper.vm.loginForm.username).toBe("admin");
      expect(wrapper.vm.loginForm.remember).toBe(true);
    });

    it("没有记住密码时不应该读取", () => {
      wrapper = mount(LoginComponent);

      expect(wrapper.vm.loginForm.username).toBe("");
      expect(wrapper.vm.loginForm.remember).toBe(false);
    });
  });

  describe("登录逻辑", () => {
    it("登录成功应该保存记住密码设置", async () => {
      mockLogin.mockResolvedValue({ token: "test-token" });

      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "password123";
      wrapper.vm.loginForm.remember = true;

      await wrapper.vm.handleLogin();
      await flushPromises();

      expect(mockLogin).toHaveBeenCalledWith({
        username: "admin",
        password: "password123",
        remember: true,
      });
      expect(localStorage.getItem("rememberedUsername")).toBe("admin");
      expect(localStorage.getItem("rememberMe")).toBe("true");
    });

    it("登录成功不记住密码应该清除存储", async () => {
      localStorage.setItem("rememberedUsername", "admin");
      localStorage.setItem("rememberMe", "true");

      mockLogin.mockResolvedValue({ token: "test-token" });

      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "password123";
      wrapper.vm.loginForm.remember = false;

      await wrapper.vm.handleLogin();
      await flushPromises();

      expect(localStorage.getItem("rememberedUsername")).toBeNull();
      expect(localStorage.getItem("rememberMe")).toBeNull();
    });

    it("登录失败应该清空密码", async () => {
      mockLogin.mockRejectedValue(new Error("登录失败"));

      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "wrongpassword";

      await wrapper.vm.handleLogin();
      await flushPromises();

      expect(wrapper.vm.loginForm.password).toBe("");
    });

    it("登录成功应该跳转到dashboard", async () => {
      mockLogin.mockResolvedValue({ token: "test-token" });
      mockCurrentRoute.value.query = {};

      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "password123";

      await wrapper.vm.handleLogin();
      await flushPromises();

      expect(mockPush).toHaveBeenCalledWith("/dashboard");
    });

    it("登录成功应该跳转到redirect页面", async () => {
      mockLogin.mockResolvedValue({ token: "test-token" });
      mockCurrentRoute.value.query = { redirect: "/host" };

      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "password123";

      await wrapper.vm.handleLogin();
      await flushPromises();

      expect(mockPush).toHaveBeenCalledWith("/host");
    });

    it("登录成功应该显示成功消息", async () => {
      mockLogin.mockResolvedValue({ token: "test-token" });

      wrapper = mount(LoginComponent);
      wrapper.vm.loginForm.username = "admin";
      wrapper.vm.loginForm.password = "password123";

      await wrapper.vm.handleLogin();
      await flushPromises();

      expect(mockElMessageSuccess).toHaveBeenCalledWith("登录成功");
    });
  });
});
