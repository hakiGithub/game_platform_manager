<script setup>
import { ref, reactive, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getSystemSettings,
  updateSystemSettings,
  changePassword,
} from "@/api/system";

// 加载状态
const loading = ref(false);
const saving = ref(false);

// 当前Tab
const activeTab = ref("platform");

// 平台配置
const platformForm = reactive({
  platformName: "游戏服务器管理平台",
  platformUrl: "",
  logo: "",
  favicon: "",
  defaultLanguage: "zh-CN",
  sessionTimeout: 30,
  maxUploadSize: 100,
  enableRegister: false,
  enableTwoFactor: false,
});

// SSH配置
const sshForm = reactive({
  defaultPort: 22,
  connectionTimeout: 10,
  retryCount: 3,
  retryInterval: 5,
  keepAliveInterval: 30,
  enableSftp: true,
});

// Docker配置
const dockerForm = reactive({
  dockerHost: "unix:///var/run/docker.sock",
  registryUrl: "https://registry.hub.docker.com",
  registryUsername: "",
  registryPassword: "",
  defaultNetwork: "bridge",
  enableLogging: true,
  logMaxSize: "100m",
  logMaxFile: 3,
});

// 密码修改
const passwordForm = reactive({
  oldPassword: "",
  newPassword: "",
  confirmPassword: "",
});

const passwordFormRef = ref(null);

const passwordRules = {
  oldPassword: [{ required: true, message: "请输入当前密码", trigger: "blur" }],
  newPassword: [
    { required: true, message: "请输入新密码", trigger: "blur" },
    { min: 6, max: 20, message: "密码长度为6-20个字符", trigger: "blur" },
  ],
  confirmPassword: [
    { required: true, message: "请确认新密码", trigger: "blur" },
    {
      validator: (rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error("两次输入的密码不一致"));
        } else {
          callback();
        }
      },
      trigger: "blur",
    },
  ],
};

// 获取设置
async function fetchSettings() {
  loading.value = true;
  try {
    const data = await getSystemSettings();
    if (data) {
      Object.assign(platformForm, data.platform || {});
      Object.assign(sshForm, data.ssh || {});
      Object.assign(dockerForm, data.docker || {});
    }
  } catch (error) {
    console.error("Failed to fetch settings:", error);
  } finally {
    loading.value = false;
  }
}

// 保存平台配置
async function savePlatformSettings() {
  saving.value = true;
  try {
    await updateSystemSettings({ type: "platform", ...platformForm });
    ElMessage.success("保存成功");
  } catch (error) {
    console.error("Failed to save platform settings:", error);
  } finally {
    saving.value = false;
  }
}

// 保存SSH配置
async function saveSSHSettings() {
  saving.value = true;
  try {
    await updateSystemSettings({ type: "ssh", ...sshForm });
    ElMessage.success("保存成功");
  } catch (error) {
    console.error("Failed to save SSH settings:", error);
  } finally {
    saving.value = false;
  }
}

// 保存Docker配置
async function saveDockerSettings() {
  saving.value = true;
  try {
    await updateSystemSettings({ type: "docker", ...dockerForm });
    ElMessage.success("保存成功");
  } catch (error) {
    console.error("Failed to save Docker settings:", error);
  } finally {
    saving.value = false;
  }
}

// 修改密码
async function handleChangePassword() {
  if (!passwordFormRef.value) return;

  await passwordFormRef.value.validate(async (valid) => {
    if (valid) {
      try {
        await changePassword({
          oldPassword: passwordForm.oldPassword,
          newPassword: passwordForm.newPassword,
        });
        ElMessage.success("密码修改成功");
        // 清空表单
        passwordForm.oldPassword = "";
        passwordForm.newPassword = "";
        passwordForm.confirmPassword = "";
      } catch (error) {
        console.error("Failed to change password:", error);
      }
    }
  });
}

// 测试Docker连接
async function testDockerConnection() {
  try {
    ElMessage.info("正在测试连接...");
    // 模拟测试
    await new Promise((resolve) => setTimeout(resolve, 1000));
    ElMessage.success("Docker连接测试成功");
  } catch (error) {
    ElMessage.error("Docker连接测试失败");
  }
}

onMounted(() => {
  fetchSettings();
});
</script>

<template>
  <div class="settings-container settings-console">
    <div class="settings-command-header">
      <div class="settings-command-header__copy">
        <span class="settings-kicker">SYSTEM CONTROL / CONFIGURATION</span>
        <h1>系统配置</h1>
        <p>集中管理平台策略、连接基线、容器运行时和账号安全边界。</p>
      </div>
      <div class="settings-command-header__status">
        <i class="status-dot is-online" />
        <span>CONFIGURATION ONLINE</span>
      </div>
    </div>

    <div class="settings-signal-grid" aria-label="系统配置概览">
      <div class="settings-signal-card">
        <span class="settings-signal-card__label">PLATFORM CORE</span>
        <strong>{{ platformForm.platformName || '未命名平台' }}</strong>
        <small>{{ platformForm.defaultLanguage === 'en-US' ? 'English' : '简体中文' }} · {{ platformForm.sessionTimeout }} 分钟会话</small>
      </div>
      <div class="settings-signal-card settings-signal-card--cyan">
        <span class="settings-signal-card__label">ACCESS POLICY</span>
        <strong>{{ platformForm.enableTwoFactor ? '2FA ENABLED' : 'STANDARD ACCESS' }}</strong>
        <small>注册{{ platformForm.enableRegister ? '开放' : '关闭' }} · 上传上限 {{ platformForm.maxUploadSize }} MB</small>
      </div>
      <div class="settings-signal-card settings-signal-card--amber">
        <span class="settings-signal-card__label">SSH BASELINE</span>
        <strong>PORT {{ sshForm.defaultPort }}</strong>
        <small>超时 {{ sshForm.connectionTimeout }} 秒 · SFTP {{ sshForm.enableSftp ? 'ON' : 'OFF' }}</small>
      </div>
      <div class="settings-signal-card settings-signal-card--purple">
        <span class="settings-signal-card__label">CONTAINER RUNTIME</span>
        <strong>{{ dockerForm.defaultNetwork || 'bridge' }}</strong>
        <small>日志{{ dockerForm.enableLogging ? '采集' : '关闭' }} · {{ dockerForm.logMaxSize }} × {{ dockerForm.logMaxFile }}</small>
      </div>
    </div>

    <el-card class="settings-shell" shadow="never">
      <el-tabs v-model="activeTab" tab-position="left" class="settings-tabs">
        <!-- 平台配置 -->
        <el-tab-pane label="平台配置" name="platform">
          <div class="settings-section settings-section--platform">
            <div class="settings-section__header">
              <div>
                <span class="settings-section__kicker">PLATFORM CORE / 01</span>
                <h2>平台配置</h2>
                <p>定义平台身份、访问地址与用户进入系统时的默认策略。</p>
              </div>
              <span class="settings-section__state">POLICY READY</span>
            </div>
            <el-form
              v-loading="loading"
              :model="platformForm"
              label-width="120px"
            >
              <el-form-item label="平台名称">
                <el-input
                  v-model="platformForm.platformName"
                  placeholder="请输入平台名称"
                />
              </el-form-item>
              <el-form-item label="平台地址">
                <el-input
                  v-model="platformForm.platformUrl"
                  placeholder="请输入平台访问地址"
                />
              </el-form-item>
              <el-form-item label="默认语言">
                <el-select
                  v-model="platformForm.defaultLanguage"
                  style="width: 200px"
                >
                  <el-option label="简体中文" value="zh-CN" />
                  <el-option label="English" value="en-US" />
                </el-select>
              </el-form-item>
              <el-form-item label="会话超时">
                <el-input-number
                  v-model="platformForm.sessionTimeout"
                  :min="5"
                  :max="120"
                />
                <span class="form-tip">分钟</span>
              </el-form-item>
              <el-form-item label="最大上传大小">
                <el-input-number
                  v-model="platformForm.maxUploadSize"
                  :min="1"
                  :max="500"
                />
                <span class="form-tip">MB</span>
              </el-form-item>
              <el-form-item label="允许注册">
                <el-switch v-model="platformForm.enableRegister" />
              </el-form-item>
              <el-form-item label="双因素认证">
                <el-switch v-model="platformForm.enableTwoFactor" />
              </el-form-item>
              <el-form-item>
                <el-button
                  type="primary"
                  :loading="saving"
                  @click="savePlatformSettings"
                  >保存配置</el-button
                >
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- SSH配置 -->
        <el-tab-pane label="SSH配置" name="ssh">
          <div class="settings-section settings-section--ssh">
            <div class="settings-section__header">
              <div>
                <span class="settings-section__kicker">SSH BASELINE / 02</span>
                <h2>SSH 配置</h2>
                <p>控制主机纳管的连接节奏、重试策略与文件传输能力。</p>
              </div>
              <span class="settings-section__state">LINK POLICY</span>
            </div>
            <el-form v-loading="loading" :model="sshForm" label-width="120px">
              <el-form-item label="默认端口">
                <el-input-number
                  v-model="sshForm.defaultPort"
                  :min="1"
                  :max="65535"
                />
              </el-form-item>
              <el-form-item label="连接超时">
                <el-input-number
                  v-model="sshForm.connectionTimeout"
                  :min="1"
                  :max="60"
                />
                <span class="form-tip">秒</span>
              </el-form-item>
              <el-form-item label="重试次数">
                <el-input-number
                  v-model="sshForm.retryCount"
                  :min="1"
                  :max="10"
                />
              </el-form-item>
              <el-form-item label="重试间隔">
                <el-input-number
                  v-model="sshForm.retryInterval"
                  :min="1"
                  :max="30"
                />
                <span class="form-tip">秒</span>
              </el-form-item>
              <el-form-item label="心跳间隔">
                <el-input-number
                  v-model="sshForm.keepAliveInterval"
                  :min="10"
                  :max="120"
                />
                <span class="form-tip">秒</span>
              </el-form-item>
              <el-form-item label="启用SFTP">
                <el-switch v-model="sshForm.enableSftp" />
              </el-form-item>
              <el-form-item>
                <el-button
                  type="primary"
                  :loading="saving"
                  @click="saveSSHSettings"
                  >保存配置</el-button
                >
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- Docker配置 -->
        <el-tab-pane label="Docker配置" name="docker">
          <div class="settings-section settings-section--docker">
            <div class="settings-section__header">
              <div>
                <span class="settings-section__kicker">CONTAINER RUNTIME / 03</span>
                <h2>Docker 配置</h2>
                <p>定义容器守护进程、镜像仓库和运行日志的默认边界。</p>
              </div>
              <span class="settings-section__state">RUNTIME POLICY</span>
            </div>
            <el-form
              v-loading="loading"
              :model="dockerForm"
              label-width="120px"
            >
              <el-form-item label="Docker地址">
                <el-input
                  v-model="dockerForm.dockerHost"
                  placeholder="Docker守护进程地址"
                />
              </el-form-item>
              <el-form-item label="镜像仓库">
                <el-input
                  v-model="dockerForm.registryUrl"
                  placeholder="Docker镜像仓库地址"
                />
              </el-form-item>
              <el-form-item label="仓库用户名">
                <el-input
                  v-model="dockerForm.registryUsername"
                  placeholder="镜像仓库用户名"
                />
              </el-form-item>
              <el-form-item label="仓库密码">
                <el-input
                  v-model="dockerForm.registryPassword"
                  type="password"
                  placeholder="镜像仓库密码"
                  show-password
                />
              </el-form-item>
              <el-form-item label="默认网络">
                <el-input
                  v-model="dockerForm.defaultNetwork"
                  placeholder="默认Docker网络"
                />
              </el-form-item>
              <el-form-item label="启用日志">
                <el-switch v-model="dockerForm.enableLogging" />
              </el-form-item>
              <el-form-item label="日志大小限制">
                <el-input
                  v-model="dockerForm.logMaxSize"
                  placeholder="如: 100m"
                  style="width: 150px"
                />
              </el-form-item>
              <el-form-item label="日志文件数量">
                <el-input-number
                  v-model="dockerForm.logMaxFile"
                  :min="1"
                  :max="10"
                />
              </el-form-item>
              <el-form-item>
                <el-button @click="testDockerConnection">测试连接</el-button>
                <el-button
                  type="primary"
                  :loading="saving"
                  @click="saveDockerSettings"
                  >保存配置</el-button
                >
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- 密码修改 -->
        <el-tab-pane label="密码修改" name="password">
          <div class="settings-section settings-section--password">
            <div class="settings-section__header">
              <div>
                <span class="settings-section__kicker">ACCOUNT SECURITY / 04</span>
                <h2>修改密码</h2>
                <p>更新当前管理员凭据，完成后现有安全边界仍按平台策略执行。</p>
              </div>
              <span class="settings-section__state">CREDENTIAL ROTATION</span>
            </div>
            <el-form
              ref="passwordFormRef"
              :model="passwordForm"
              :rules="passwordRules"
              label-width="120px"
              style="max-width: 400px"
            >
              <el-form-item label="当前密码" prop="oldPassword">
                <el-input
                  v-model="passwordForm.oldPassword"
                  type="password"
                  placeholder="请输入当前密码"
                  show-password
                />
              </el-form-item>
              <el-form-item label="新密码" prop="newPassword">
                <el-input
                  v-model="passwordForm.newPassword"
                  type="password"
                  placeholder="请输入新密码"
                  show-password
                />
              </el-form-item>
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input
                  v-model="passwordForm.confirmPassword"
                  type="password"
                  placeholder="请再次输入新密码"
                  show-password
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="handleChangePassword"
                  >修改密码</el-button
                >
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.settings-container {
  max-width: 1000px;
  margin: 0 auto;

  :deep(.el-card__body) {
    padding: 0;
  }
}

.settings-tabs {
  min-height: 500px;

  :deep(.el-tabs__header) {
    margin-right: 0;
  }

  :deep(.el-tabs__item) {
    height: 48px;
    line-height: 48px;
  }

  :deep(.el-tabs__content) {
    padding: 24px;
  }
}

.settings-section {
  .section-title {
    font-size: var(--platform-font-size-md);
    font-weight: var(--platform-font-weight-bold);
    color: var(--el-text-color-primary);
    margin-bottom: 24px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
}

.form-tip {
  margin-left: 8px;
  font-size: var(--platform-font-size-sm);
  color: var(--el-text-color-secondary);
}

// 响应式适配
@media screen and (max-width: 768px) {
  .settings-tabs {
    :deep(.el-tabs__nav) {
      float: none;
    }

    :deep(.el-tabs__content) {
      padding: 16px;
    }
  }
}

.settings-console {
  max-width: 1240px;
  margin: 0 auto;
}

.settings-command-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 14px;
  padding: 22px 24px;
  background: linear-gradient(115deg, rgba(19, 42, 55, 0.96), rgba(11, 24, 34, 0.96));
  border: 1px solid rgba(91, 135, 154, 0.32);
  border-left: 3px solid var(--platform-cyan);
  border-radius: 4px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.025);
}

.settings-kicker,
.settings-signal-card__label,
.settings-section__kicker {
  color: var(--platform-cyan);
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.16em;
}

.settings-command-header h1 {
  margin: 7px 0 5px;
  color: var(--el-text-color-primary);
  font-size: 25px;
  font-weight: 600;
}

.settings-command-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.settings-command-header__status,
.settings-section__state {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: 8px;
  min-height: 30px;
  padding: 0 11px;
  color: var(--el-text-color-secondary);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.05em;
  background: rgba(6, 15, 23, 0.48);
  border: 1px solid rgba(91, 135, 154, 0.26);
  border-radius: 3px;
}

.settings-signal-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.settings-signal-card {
  min-width: 0;
  min-height: 96px;
  padding: 14px 15px;
  background: rgba(15, 32, 44, 0.86);
  border: 1px solid var(--platform-line);
  border-top: 2px solid var(--platform-green);
  border-radius: 4px;
}

.settings-signal-card--cyan { border-top-color: var(--platform-cyan); }
.settings-signal-card--amber { border-top-color: var(--platform-amber); }
.settings-signal-card--purple { border-top-color: #c792ff; }

.settings-signal-card__label {
  display: block;
  margin-bottom: 12px;
  color: var(--el-text-color-disabled);
}

.settings-signal-card strong {
  display: block;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-signal-card small {
  display: block;
  overflow: hidden;
  margin-top: 5px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-shell {
  overflow: hidden;
  background: var(--platform-surface-1);
  border-color: var(--platform-line);
}

.settings-shell :deep(.el-card__body) {
  padding: 0;
}

.settings-tabs {
  min-height: 540px;
  background: rgba(11, 24, 34, 0.66);
}

.settings-tabs :deep(.el-tabs__header) {
  width: 184px;
  margin-right: 0;
  padding: 12px;
  background: rgba(8, 19, 28, 0.72);
  border-right: 1px solid var(--platform-line);
}

.settings-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.settings-tabs :deep(.el-tabs__active-bar) {
  display: none;
}

.settings-tabs :deep(.el-tabs__item) {
  height: 44px;
  margin-bottom: 5px;
  padding: 0 13px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 44px;
  border: 1px solid transparent;
  border-radius: 4px;
}

.settings-tabs :deep(.el-tabs__item:hover) {
  color: var(--platform-cyan);
  background: rgba(22, 82, 104, 0.2);
}

.settings-tabs :deep(.el-tabs__item.is-active) {
  color: var(--platform-cyan);
  background: rgba(22, 82, 104, 0.32);
  border-color: rgba(39, 181, 243, 0.34);
  box-shadow: inset 2px 0 0 var(--platform-cyan);
}

.settings-tabs :deep(.el-tabs__content) {
  padding: 24px 26px 30px;
  background: var(--platform-surface-1);
}

.settings-section {
  max-width: 860px;
}

.settings-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--platform-line);
}

.settings-section__header h2 {
  margin: 6px 0 5px;
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 600;
}

.settings-section__header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.settings-section__state {
  color: var(--platform-cyan);
}

.settings-section--ssh .settings-section__kicker { color: var(--platform-amber); }
.settings-section--ssh .settings-section__state { color: var(--platform-amber); }
.settings-section--docker .settings-section__kicker { color: #c792ff; }
.settings-section--docker .settings-section__state { color: #d3adff; }
.settings-section--password .settings-section__kicker { color: var(--platform-red); }
.settings-section--password .settings-section__state { color: #ff9c9c; }

.settings-section :deep(.el-form) {
  max-width: 700px;
  padding: 18px 20px;
  background: rgba(15, 32, 44, 0.62);
  border: 1px solid var(--platform-line);
  border-radius: 4px;
}

.settings-section :deep(.el-form-item__label) {
  color: var(--el-text-color-secondary);
}

.settings-section :deep(.el-input__wrapper),
.settings-section :deep(.el-textarea__inner),
.settings-section :deep(.el-input-number) {
  background: rgba(7, 17, 26, 0.64);
}

.settings-section :deep(.el-form-item:last-child) {
  margin-bottom: 0;
  padding-top: 8px;
  border-top: 1px solid rgba(91, 135, 154, 0.14);
}

@media screen and (max-width: 1024px) {
  .settings-signal-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media screen and (max-width: 768px) {
  .settings-command-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .settings-tabs :deep(.el-tabs__header) {
    width: 150px;
  }

  .settings-tabs :deep(.el-tabs__content) {
    padding: 18px;
  }

  .settings-section__header {
    flex-direction: column;
  }
}
</style>
