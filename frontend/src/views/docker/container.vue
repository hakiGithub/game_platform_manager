<template>
  <div class="container-detail-page">
    <!-- 返回导航 -->
    <div class="page-header">
      <el-page-header @back="goBack">
        <template #content>
          <div class="header-content">
            <span class="container-name">{{ containerName }}</span>
            <el-tag
              :type="getStatusType(containerDetail?.status)"
              size="small"
              effect="dark"
              class="status-tag"
            >
              {{ getStatusText(containerDetail?.status) }}
            </el-tag>
          </div>
        </template>
        <template #extra>
          <div class="header-actions">
            <el-button
              v-if="containerDetail?.status === 'stopped'"
              type="success"
              @click="handleStart"
            >
              <el-icon><VideoPlay /></el-icon>
              启动
            </el-button>
            <el-button
              v-if="containerDetail?.status === 'running'"
              type="warning"
              @click="handleStop"
            >
              <el-icon><VideoPause /></el-icon>
              停止
            </el-button>
            <el-button
              v-if="containerDetail?.status === 'running'"
              type="info"
              @click="handleRestart"
            >
              <el-icon><RefreshRight /></el-icon>
              重启
            </el-button>
            <el-button type="danger" @click="showDeleteConfirm">
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </div>
        </template>
      </el-page-header>
    </div>

    <!-- 加载状态 -->
    <el-skeleton v-if="loading" :rows="10" animated />

    <!-- 主要内容 -->
    <template v-else-if="containerDetail">
      <!-- 基本信息卡片 -->
      <el-card class="info-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>基本信息</span>
            <el-button link type="primary" @click="refreshDetail">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </template>

        <el-descriptions :column="3" border>
          <el-descriptions-item label="容器ID">
            <el-tooltip
              :content="containerDetail.containerIdFull"
              placement="top"
            >
              <span class="mono-text">{{ containerDetail.containerId }}</span>
            </el-tooltip>
          </el-descriptions-item>
          <el-descriptions-item label="镜像">
            {{ containerDetail.imageName }}:{{ containerDetail.imageTag }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ containerDetail.createdAt }}
          </el-descriptions-item>
          <el-descriptions-item label="主机">
            <el-link type="primary" @click="goToHost">
              {{ hostName }}
            </el-link>
          </el-descriptions-item>
          <el-descriptions-item label="关联实例">
            <el-link
              v-if="
                containerDetail.isLinked && containerDetail.linkedInstanceName
              "
              type="primary"
              @click="goToInstance"
            >
              {{ containerDetail.linkedInstanceName }}
            </el-link>
            <span v-else class="text-muted">未关联</span>
          </el-descriptions-item>
          <el-descriptions-item label="健康状态">
            <el-tag
              :type="getHealthStatusType(containerDetail.healthStatus)"
              size="small"
            >
              {{ getHealthStatusText(containerDetail.healthStatus) }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 资源监控卡片 -->
      <el-card class="stats-card" shadow="never">
        <template #header>
          <span>资源监控</span>
        </template>

        <el-row :gutter="24">
          <el-col :span="8">
            <div class="stat-box">
              <div class="stat-title">CPU 使用率</div>
              <el-progress
                type="dashboard"
                :percentage="containerStats.cpuUsage || 0"
                :color="getProgressColor(containerStats.cpuUsage)"
                :width="120"
              >
                <template #default="{ percentage }">
                  <span class="percentage-value">{{ percentage }}%</span>
                </template>
              </el-progress>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="stat-box">
              <div class="stat-title">内存使用</div>
              <el-progress
                type="dashboard"
                :percentage="containerStats.memoryPercentage || 0"
                :color="getProgressColor(containerStats.memoryPercentage)"
                :width="120"
              >
                <template #default="{ percentage }">
                  <span class="percentage-value">{{ percentage }}%</span>
                  <span class="percentage-label">
                    {{
                      formatFileSize(containerStats.memoryUsed * 1024 * 1024)
                    }}
                    /
                    {{
                      formatFileSize(containerStats.memoryLimit * 1024 * 1024)
                    }}
                  </span>
                </template>
              </el-progress>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="stat-box">
              <div class="stat-title">网络 I/O</div>
              <div class="network-stats">
                <div class="network-item">
                  <el-icon><Download /></el-icon>
                  <span class="label">接收:</span>
                  <span class="value">{{
                    formatFileSize(containerStats.networkRx * 1024 * 1024)
                  }}</span>
                </div>
                <div class="network-item">
                  <el-icon><Upload /></el-icon>
                  <span class="label">发送:</span>
                  <span class="value">{{
                    formatFileSize(containerStats.networkTx * 1024 * 1024)
                  }}</span>
                </div>
              </div>
            </div>
          </el-col>
        </el-row>
      </el-card>

      <!-- 功能Tab -->
      <el-card class="tabs-card" shadow="never">
        <el-tabs v-model="activeTab">
          <!-- 终端 -->
          <el-tab-pane name="terminal">
            <template #label>
              <span>
                <el-icon><Monitor /></el-icon>
                终端
              </span>
            </template>
            <DockerTerminal
              v-if="containerDetail.status === 'running'"
              :host-id="hostId"
              :container-id="containerId"
            />
            <el-empty v-else description="容器未运行，无法使用终端" />
          </el-tab-pane>

          <!-- 日志 -->
          <el-tab-pane name="logs">
            <template #label>
              <span>
                <el-icon><Document /></el-icon>
                日志
              </span>
            </template>
            <DockerLogViewer
              :host-id="hostId"
              :container-id="containerId"
              :container-status="containerDetail.status"
            />
          </el-tab-pane>

          <!-- 文件管理 -->
          <el-tab-pane name="files">
            <template #label>
              <span>
                <el-icon><FolderOpened /></el-icon>
                文件管理
              </span>
            </template>
            <DockerFileManager
              :host-id="hostId"
              :container-id="containerId"
              :container-status="containerDetail.status"
            />
          </el-tab-pane>

          <!-- 环境变量 -->
          <el-tab-pane name="env">
            <template #label>
              <span>
                <el-icon><Setting /></el-icon>
                环境变量
              </span>
            </template>
            <div class="env-list">
              <el-table :data="envList" style="width: 100%">
                <el-table-column prop="key" label="变量名" width="300" />
                <el-table-column prop="value" label="值">
                  <template #default="{ row }">
                    <el-input
                      :model-value="row.value"
                      readonly
                      type="textarea"
                      :autosize="{ minRows: 1, maxRows: 5 }"
                    />
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="envList.length === 0" description="无环境变量" />
            </div>
          </el-tab-pane>

          <!-- 端口映射 -->
          <el-tab-pane name="ports">
            <template #label>
              <span>
                <el-icon><Connection /></el-icon>
                端口映射
              </span>
            </template>
            <div class="ports-list">
              <el-table :data="portList" style="width: 100%">
                <el-table-column prop="hostPort" label="主机端口" width="150" />
                <el-table-column
                  prop="containerPort"
                  label="容器端口"
                  width="150"
                />
                <el-table-column prop="protocol" label="协议" width="100" />
                <el-table-column prop="hostIp" label="主机IP" />
              </el-table>
              <el-empty v-if="portList.length === 0" description="无端口映射" />
            </div>
          </el-tab-pane>

          <!-- 卷挂载 -->
          <el-tab-pane name="volumes">
            <template #label>
              <span>
                <el-icon><Coin /></el-icon>
                卷挂载
              </span>
            </template>
            <div class="volumes-list">
              <el-table :data="volumeList" style="width: 100%">
                <el-table-column prop="source" label="源路径" min-width="300" />
                <el-table-column
                  prop="destination"
                  label="目标路径"
                  min-width="300"
                />
                <el-table-column prop="mode" label="模式" width="100" />
                <el-table-column prop="type" label="类型" width="100" />
              </el-table>
              <el-empty v-if="volumeList.length === 0" description="无卷挂载" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </template>

    <!-- 错误状态 -->
    <el-empty v-else description="容器不存在或已被删除" />

    <!-- 删除确认对话框 -->
    <el-dialog
      v-model="deleteConfirmVisible"
      title="删除容器"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        title="此操作不可逆，将删除容器及其数据。"
        :closable="false"
        show-icon
        style="margin-bottom: 20px"
      />
      <el-form label-width="120px">
        <el-form-item label="容器名称">
          <el-input :value="containerDetail?.containerName" disabled />
        </el-form-item>
        <el-form-item label="确认名称">
          <el-input
            v-model="deleteConfirmName"
            :placeholder="`请输入 ${containerDetail?.containerName}`"
          />
        </el-form-item>
        <el-form-item label="强制删除">
          <el-switch v-model="deleteConfirmForce" />
          <span class="form-hint">运行中的容器需强制删除</span>
        </el-form-item>
        <el-form-item label="删除关联卷">
          <el-switch v-model="deleteConfirmVolumes" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deleteConfirmVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="deleting"
          :disabled="deleteConfirmName !== containerDetail?.containerName"
          @click="handleDelete"
        >
          确认删除
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  VideoPlay,
  VideoPause,
  RefreshRight,
  Delete,
  Refresh,
  Monitor,
  Document,
  FolderOpened,
  Setting,
  Connection,
  Coin,
  Download,
  Upload,
} from "@element-plus/icons-vue";
import { useDockerStore } from "@/stores/docker";
import { useHostStore } from "@/stores/host";
import {
  getContainerDetail,
  getContainerStats,
  startContainer,
  stopContainer,
  restartContainer,
  deleteContainer,
} from "@/api/docker";
import DockerTerminal from "@/components/docker/DockerTerminal.vue";
import DockerLogViewer from "@/components/docker/DockerLogViewer.vue";
import DockerFileManager from "@/components/docker/DockerFileManager.vue";

const route = useRoute();
const router = useRouter();
const dockerStore = useDockerStore();
const hostStore = useHostStore();

// 路由参数
const containerId = computed(() => route.params.id);
const hostId = computed(() => Number(route.query.hostId));
const containerName = computed(() => route.query.name || "容器详情");

// 主机名称
const hostName = computed(() => {
  const host = hostStore.hostList.find((h) => h.id === hostId.value);
  return host?.name || "未知主机";
});

// 加载状态
const loading = ref(true);

// 容器详情
const containerDetail = ref(null);

// 容器统计
const containerStats = ref({
  cpuUsage: 0,
  memoryUsed: 0,
  memoryLimit: 0,
  memoryPercentage: 0,
  networkRx: 0,
  networkTx: 0,
});

// 当前Tab
const activeTab = ref("terminal");

// 删除确认
const deleteConfirmVisible = ref(false);
const deleteConfirmName = ref("");
const deleteConfirmForce = ref(false);
const deleteConfirmVolumes = ref(false);
const deleting = ref(false);

// 定时刷新
let statsTimer = null;

// 环境变量列表
const envList = computed(() => {
  if (!containerDetail.value?.environment) return [];
  return Object.entries(containerDetail.value.environment).map(
    ([key, value]) => ({
      key,
      value,
    }),
  );
});

// 端口映射列表
const portList = computed(() => {
  return containerDetail.value?.ports || [];
});

// 卷挂载列表
const volumeList = computed(() => {
  return containerDetail.value?.volumes || [];
});

// 获取状态类型
function getStatusType(status) {
  return dockerStore.getContainerStatusType(status);
}

// 获取状态文本
function getStatusText(status) {
  return dockerStore.getContainerStatusText(status);
}

// 获取健康状态类型
function getHealthStatusType(status) {
  return dockerStore.getHealthStatusType(status);
}

// 获取健康状态文本
function getHealthStatusText(status) {
  return dockerStore.getHealthStatusText(status);
}

// 格式化文件大小
function formatFileSize(bytes) {
  return dockerStore.formatFileSize(bytes);
}

// 获取进度条颜色
function getProgressColor(percentage) {
  if (percentage >= 80) return "#f0646a";
  if (percentage >= 60) return "#f2b84b";
  return "#52cf82";
}

// 获取容器详情
async function fetchContainerDetail() {
  if (!hostId.value || !containerId.value) return;

  loading.value = true;
  try {
    const data = await getContainerDetail(hostId.value, containerId.value);
    containerDetail.value = data;
  } catch (error) {
    console.error("Failed to fetch container detail:", error);
    ElMessage.error("获取容器详情失败");
  } finally {
    loading.value = false;
  }
}

// 获取容器统计
async function fetchContainerStats() {
  if (!hostId.value || !containerId.value) return;
  if (containerDetail.value?.status !== "running") return;

  try {
    const data = await getContainerStats(hostId.value, containerId.value);
    containerStats.value = {
      cpuUsage: Math.round(data.cpuUsage || 0),
      memoryUsed: data.memoryUsed || 0,
      memoryLimit: data.memoryLimit || 0,
      memoryPercentage:
        Math.round((data.memoryUsed / data.memoryLimit) * 100) || 0,
      networkRx: data.networkRx || 0,
      networkTx: data.networkTx || 0,
    };
  } catch (error) {
    console.error("Failed to fetch container stats:", error);
  }
}

// 刷新详情
async function refreshDetail() {
  await fetchContainerDetail();
  await fetchContainerStats();
}

// 启动容器
async function handleStart() {
  try {
    await startContainer(hostId.value, containerId.value);
    ElMessage.success("容器启动成功");
    await refreshDetail();
  } catch (error) {
    ElMessage.error("启动失败: " + (error.message || "未知错误"));
  }
}

// 停止容器
async function handleStop() {
  try {
    await ElMessageBox.confirm("确定要停止该容器吗？", "确认操作", {
      type: "warning",
    });
    await stopContainer(hostId.value, containerId.value);
    ElMessage.success("容器已停止");
    await refreshDetail();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("停止失败: " + (error.message || "未知错误"));
    }
  }
}

// 重启容器
async function handleRestart() {
  try {
    await ElMessageBox.confirm("确定要重启该容器吗？", "确认操作", {
      type: "warning",
    });
    await restartContainer(hostId.value, containerId.value);
    ElMessage.success("容器重启成功");
    await refreshDetail();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("重启失败: " + (error.message || "未知错误"));
    }
  }
}

// 显示删除确认
function showDeleteConfirm() {
  deleteConfirmName.value = "";
  deleteConfirmForce.value = containerDetail.value?.status === "running";
  deleteConfirmVolumes.value = false;
  deleteConfirmVisible.value = true;
}

// 删除容器
async function handleDelete() {
  deleting.value = true;
  try {
    await deleteContainer(hostId.value, containerId.value, {
      force: deleteConfirmForce.value,
      volumes: deleteConfirmVolumes.value,
    });
    ElMessage.success("容器已删除");
    router.push("/docker/list");
  } catch (error) {
    ElMessage.error("删除失败: " + (error.message || "未知错误"));
  } finally {
    deleting.value = false;
  }
}

// 返回
function goBack() {
  router.push("/docker/list");
}

// 跳转到主机
function goToHost() {
  router.push(`/host/detail/${hostId.value}`);
}

// 跳转到实例
function goToInstance() {
  if (containerDetail.value?.linkedInstanceId) {
    router.push(`/instance/detail/${containerDetail.value.linkedInstanceId}`);
  }
}

// 启动统计定时刷新
function startStatsTimer() {
  statsTimer = setInterval(() => {
    fetchContainerStats();
  }, 5000); // 5秒刷新一次
}

// 停止统计定时刷新
function stopStatsTimer() {
  if (statsTimer) {
    clearInterval(statsTimer);
    statsTimer = null;
  }
}

// 监听容器状态变化
watch(
  () => containerDetail.value?.status,
  (newStatus) => {
    if (newStatus === "running") {
      startStatsTimer();
    } else {
      stopStatsTimer();
    }
  },
);

onMounted(() => {
  fetchContainerDetail();
  hostStore.fetchHostList();
});
</script>

<style lang="scss" scoped>
.container-detail-page {
  .page-header {
    margin-bottom: 20px;
    padding: 16px 20px;
    background: var(--platform-surface-1);
    border: 1px solid var(--platform-line);
    border-radius: var(--el-border-radius-base);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);

    .header-content {
      display: flex;
      align-items: center;
      gap: 12px;

      .container-name {
        font-size: 18px;
        font-weight: 600;
      }
    }

    .header-actions {
      display: flex;
      gap: 8px;
    }
  }

  .info-card,
  .stats-card,
  .tabs-card {
    margin-bottom: 16px;

    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      color: var(--platform-text-primary);
    }
  }

  .info-card,
  .stats-card,
  .tabs-card {
    :deep(.el-card__header) {
      background: var(--platform-surface-2);
      border-bottom-color: var(--platform-line);
    }

    :deep(.el-card__body) {
      background: var(--platform-surface-1);
    }
  }

  .mono-text {
    font-family: var(--el-font-family-mono);
    font-size: 13px;
  }

  .text-muted {
    color: var(--el-text-color-placeholder);
  }

  .stat-box {
    text-align: center;
    padding: 20px;
    background: var(--platform-surface-0);
    border: 1px solid var(--platform-line);
    border-radius: var(--el-border-radius-base);

    .stat-title {
      font-size: 14px;
      color: var(--el-text-color-secondary);
      margin-bottom: 16px;
    }

    .percentage-value {
      font-size: 24px;
      font-weight: 600;
      color: var(--platform-text-primary);
    }

    .percentage-label {
      display: block;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      margin-top: 4px;
    }
  }

  .network-stats {
    .network-item {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 12px 0;

      .label {
        color: var(--el-text-color-secondary);
      }

      .value {
        font-weight: 600;
        color: var(--el-color-primary);
      }
    }
  }

  .env-list,
  .ports-list,
  .volumes-list {
    padding: 16px 0;
  }

  .form-hint {
    margin-left: 12px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .container-detail-page {
    .page-header {
      .header-content {
        flex-direction: column;
        align-items: flex-start;
      }

      .header-actions {
        flex-wrap: wrap;
      }
    }

    .stat-box {
      padding: 12px;
    }
  }
}
</style>
