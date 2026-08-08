<script setup>
import { ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const userStore = useUserStore();

const loading = ref(false);
const loginFormRef = ref(null);

const loginForm = reactive({
  username: "",
  password: "",
  remember: false,
});

const loginRules = {
  username: [
    { required: true, message: "请输入用户名", trigger: "blur" },
    { min: 2, max: 50, message: "用户名长度为2-50个字符", trigger: "blur" },
  ],
  password: [
    { required: true, message: "请输入密码", trigger: "blur" },
    { min: 6, max: 20, message: "密码长度为6-20个字符", trigger: "blur" },
  ],
};

// 版本信息
const version = ref("V1.0.0");
const currentYear = new Date().getFullYear();

// 登录
async function handleLogin() {
  if (!loginFormRef.value) return;

  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true;
      try {
        await userStore.login(loginForm);

        // 记住密码
        if (loginForm.remember) {
          localStorage.setItem("rememberedUsername", loginForm.username);
          localStorage.setItem("rememberMe", "true");
        } else {
          localStorage.removeItem("rememberedUsername");
          localStorage.removeItem("rememberMe");
        }

        ElMessage.success("登录成功");

        // 跳转到重定向页面或首页
        const redirect =
          router.currentRoute.value.query.redirect || "/dashboard";
        router.push(redirect);
      } catch (error) {
        console.error("Login failed:", error);
        // 清空密码
        loginForm.password = "";
      } finally {
        loading.value = false;
      }
    }
  });
}

// 初始化
onMounted(() => {
  // 读取记住的用户名
  const rememberedUsername = localStorage.getItem("rememberedUsername");
  const rememberMe = localStorage.getItem("rememberMe");
  if (rememberedUsername && rememberMe === "true") {
    loginForm.username = rememberedUsername;
    loginForm.remember = true;
  }
});
</script>

<template>
  <div class="login-container">
    <div class="login-box">
      <!-- Logo 和标题 -->
      <div class="login-header">
        <div class="logo-wrapper">
          <el-icon :size="48" color="#409EFF"><Monitor /></el-icon>
        </div>
        <h1 class="title">游戏服务器管理平台</h1>
        <p class="subtitle">Game Server Management Platform</p>
      </div>

      <!-- 登录表单 -->
      <el-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        class="login-form"
        size="large"
      >
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            prefix-icon="User"
            clearable
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="loginForm.remember">记住密码</el-checkbox>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            class="login-btn"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 底部信息 -->
      <div class="login-footer">
        <p>{{ version }} © {{ currentYear }}</p>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.login-container {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 100vh;
  background: var(--el-bg-color-page);
}

.login-box {
  width: 400px;
  padding: 40px;
  background-color: var(--el-bg-color);
  border-radius: var(--platform-card-radius);
  box-shadow: var(--platform-card-shadow);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;

  .logo-wrapper {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 80px;
    height: 80px;
    margin-bottom: 16px;
    background: linear-gradient(135deg, #ecf5ff 0%, #d9ecff 100%);
    border-radius: 50%;
  }

  .title {
    margin: 0 0 8px;
    font-size: var(--platform-font-size-xl);
    font-weight: var(--platform-font-weight-bold);
    color: var(--el-text-color-primary);
  }

  .subtitle {
    margin: 0;
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-secondary);
  }
}

.login-form {
  .el-form-item {
    margin-bottom: var(--platform-spacing-md);
  }

  .login-btn {
    width: 100%;
    height: 40px;
    font-size: var(--platform-font-size-base);
  }
}

.login-footer {
  text-align: center;
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--el-border-color-lighter);

  p {
    margin: 0;
    font-size: var(--platform-font-size-xs);
    color: var(--el-text-color-secondary);
  }
}

// 响应式适配
@media screen and (max-width: 480px) {
  .login-box {
    width: calc(100% - 32px);
    max-width: 400px;
    padding: 24px;
  }
}

// 1366x768 分辨率适配
@media screen and (max-width: 1366px) {
  .login-box {
    padding: 32px;
  }
}
</style>
