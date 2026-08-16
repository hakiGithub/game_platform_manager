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
    <div class="login-shell">
      <section class="login-visual" aria-label="平台能力介绍">
        <div class="visual-grid"></div>
        <div class="visual-content">
          <div class="visual-brand">
            <span class="brand-mark"><el-icon><Monitor /></el-icon></span>
            <span>GAME SERVER CONTROL</span>
          </div>
          <p class="visual-kicker">NIGHT OPS / CONTROL PLANE</p>
          <h1>把每一台游戏服务器，<span>变成可控的现场。</span></h1>
          <p class="visual-description">
            从主机纳管到实例运行状态，在一个清晰、实时的运维工作台里完成监控、部署与处置。
          </p>
          <div class="visual-signals">
            <div class="signal-item">
              <span class="signal-dot is-green"></span>
              <span>主机连接</span>
              <strong>稳定</strong>
            </div>
            <div class="signal-item">
              <span class="signal-dot is-blue"></span>
              <span>实例监控</span>
              <strong>实时</strong>
            </div>
          </div>
        </div>
        <div class="visual-footer">
          <span>OPS DESK</span>
          <span>SECURE ACCESS</span>
          <span>v1.0.0</span>
        </div>
      </section>

      <section class="login-box">
        <div class="login-header">
          <div class="logo-wrapper">
            <el-icon :size="32"><Monitor /></el-icon>
          </div>
          <p class="login-kicker">欢迎回到控制台</p>
          <h2 class="title">游戏服务器管理平台</h2>
          <p class="subtitle">Game Server Management Platform</p>
        </div>

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
              进入控制台
            </el-button>
          </el-form-item>
        </el-form>

        <div class="login-footer">
          <span>Authorized operators only</span>
          <span>{{ version }} © {{ currentYear }}</span>
        </div>
      </section>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.login-container {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  width: 100%;
  padding: 32px;
  overflow: hidden;
  background:
    radial-gradient(circle at 15% 20%, rgba(39, 181, 243, 0.14), transparent 30%),
    radial-gradient(circle at 90% 85%, rgba(26, 88, 119, 0.22), transparent 35%),
    var(--platform-bg);
}

.login-shell {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(360px, 0.92fr);
  width: min(1080px, 100%);
  min-height: 620px;
  overflow: hidden;
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  border-radius: 14px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.3);
}

.login-visual {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
  padding: 56px;
  overflow: hidden;
  background:
    linear-gradient(145deg, rgba(22, 50, 67, 0.92), rgba(8, 22, 32, 0.98)),
    var(--platform-surface-0);
  border-right: 1px solid var(--platform-line);
}

.visual-grid {
  position: absolute;
  inset: 0;
  opacity: 0.35;
  background-image:
    linear-gradient(rgba(77, 147, 180, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(77, 147, 180, 0.12) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: linear-gradient(to bottom right, black, transparent 68%);
}

.visual-content,
.visual-footer {
  position: relative;
  z-index: 1;
}

.visual-brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--platform-text-secondary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--platform-cyan);
  background: rgba(39, 181, 243, 0.12);
  border: 1px solid rgba(39, 181, 243, 0.35);
  border-radius: 7px;
}

.visual-kicker,
.login-kicker {
  margin: 58px 0 18px;
  color: var(--platform-cyan);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.visual-content h1 {
  max-width: 560px;
  margin: 0;
  color: var(--platform-text-primary);
  font-size: clamp(32px, 4vw, 52px);
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.04em;
}

.visual-content h1 span {
  display: block;
  color: var(--platform-cyan);
}

.visual-description {
  max-width: 480px;
  margin: 24px 0 0;
  color: var(--platform-text-secondary);
  font-size: 14px;
  line-height: 1.8;
}

.visual-signals {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  max-width: 440px;
  margin-top: 48px;
}

.signal-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 14px;
  color: var(--platform-text-secondary);
  font-size: 12px;
  background: rgba(16, 32, 45, 0.72);
  border: 1px solid var(--platform-line);
  border-radius: 8px;
}

.signal-item strong {
  color: var(--platform-text-primary);
  font-size: 12px;
}

.signal-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  box-shadow: 0 0 10px currentColor;
}

.signal-dot.is-green {
  color: var(--platform-green);
  background: var(--platform-green);
}

.signal-dot.is-blue {
  color: var(--platform-cyan);
  background: var(--platform-cyan);
}

.visual-footer {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: var(--platform-text-muted);
  font-size: 10px;
  letter-spacing: 0.12em;
}

.login-box {
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  padding: 56px;
  background: var(--platform-surface-1);
}

.login-header {
  margin-bottom: 38px;

  .logo-wrapper {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 56px;
    height: 56px;
    margin-bottom: 24px;
    color: var(--platform-cyan);
    background: rgba(39, 181, 243, 0.12);
    border: 1px solid rgba(39, 181, 243, 0.35);
    border-radius: 10px;
  }

  .login-kicker {
    margin: 0 0 10px;
    color: var(--platform-text-muted);
    font-size: 12px;
    letter-spacing: 0.08em;
  }

  .title {
    margin: 0 0 8px;
    color: var(--platform-text-primary);
    font-size: 25px;
    font-weight: var(--platform-font-weight-bold);
  }

  .subtitle {
    margin: 0;
    font-size: var(--platform-font-size-sm);
    color: var(--platform-text-secondary);
  }
}

.login-form {
  .el-form-item {
    margin-bottom: var(--platform-spacing-md);
  }

  .login-btn {
    width: 100%;
    height: 44px;
    font-size: var(--platform-font-size-base);
    font-weight: 600;
  }
}

.login-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
  padding-top: 18px;
  color: var(--platform-text-muted);
  font-size: var(--platform-font-size-xs);
  border-top: 1px solid var(--platform-line);
}

// 响应式适配
@media screen and (max-width: 760px) {
  .login-container {
    padding: 16px;
  }

  .login-shell {
    display: block;
    min-height: auto;
  }

  .login-visual {
    min-height: 300px;
    padding: 32px;
    border-right: 0;
    border-bottom: 1px solid var(--platform-line);
  }

  .visual-kicker {
    margin-top: 34px;
  }

  .visual-content h1 {
    font-size: 32px;
  }

  .visual-signals {
    margin-top: 28px;
  }

  .login-box {
    padding: 32px;
  }
}

// 1366x768 分辨率适配
@media screen and (min-width: 761px) and (max-width: 1366px) {
  .login-visual,
  .login-box {
    padding: 40px;
  }
}
</style>
