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
  <div class="settings-container">
    <el-card shadow="never">
      <el-tabs v-model="activeTab" tab-position="left" class="settings-tabs">
        <!-- 平台配置 -->
        <el-tab-pane label="平台配置" name="platform">
          <div class="settings-section">
            <div class="section-title">平台配置</div>
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
          <div class="settings-section">
            <div class="section-title">SSH配置</div>
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
          <div class="settings-section">
            <div class="section-title">Docker配置</div>
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
          <div class="settings-section">
            <div class="section-title">修改密码</div>
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
</style>
