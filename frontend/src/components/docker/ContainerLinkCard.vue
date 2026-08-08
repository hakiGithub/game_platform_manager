<template>
  <el-card class="container-link-card" shadow="never">
    <template #header>
      <div class="card-header">
        <div class="header-left">
          <el-icon><Box /></el-icon>
          <span>关联容器</span>
        </div>
        <div class="header-right">
          <el-button
            v-if="!containerInfo"
            type="primary"
            size="small"
            @click="showLinkDialog"
          >
            <el-icon><Link /></el-icon>
            关联容器
          </el-button>
          <el-button
            v-else
            type="danger"
            size="small"
            :loading="unlinking"
            @click="handleUnlink"
          >
            <el-icon><Disconnect /></el-icon>
            解除关联
          </el-button>
        </div>
      </div>
    </template>

    <!-- 已关联容器 -->
    <div v-if="containerInfo" class="linked-container">
      <div class="container-status">
        <el-tag
          :type="getStatusType(containerInfo.status)"
          size="small"
          effect="dark"
        >
          {{ getStatusText(containerInfo.status) }}
        </el-tag>
        <el-tag
          v-if="containerInfo.healthStatus"
          :type="getHealthStatusType(containerInfo.healthStatus)"
          size="small"
          effect="plain"
          style="margin-left: 8px"
        >
          {{ getHealthStatusText(containerInfo.healthStatus) }}
        </el-tag>
      </div>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="容器名称">
          <el-link type="primary" @click="goToContainer">
            {{ containerInfo.containerName }}
          </el-link>
        </el-descriptions-item>
        <el-descriptions-item label="容器ID">
          <el-tooltip :content="containerInfo.containerIdFull" placement="top">
            <span class="mono-text">{{ containerInfo.containerId }}</span>
          </el-tooltip>
        </el-descriptions-item>
        <el-descriptions-item label="镜像">
          {{ containerInfo.imageName }}:{{ containerInfo.imageTag }}
        </el-descriptions-item>
        <el-descriptions-item label="主机">
          <el-link type="primary" @click="goToHost">
            {{ hostName }}
          </el-link>
        </el-descriptions-item>
        <el-descriptions-item label="端口映射" :span="2">
          <div
            v-if="containerInfo.ports && containerInfo.ports.length > 0"
            class="ports-list"
          >
            <el-tag
              v-for="(port, index) in containerInfo.ports"
              :key="index"
              size="small"
              effect="plain"
              class="port-tag"
            >
              {{ port.hostPort }}:{{ port.containerPort }}/{{ port.protocol }}
            </el-tag>
          </div>
          <span v-else class="text-muted">无端口映射</span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 资源使用 -->
      <div v-if="containerInfo.status === 'running'" class="resource-usage">
        <div class="usage-item">
          <span class="label">CPU:</span>
          <el-progress
            :percentage="containerInfo.cpuUsage || 0"
            :stroke-width="8"
            :color="containerInfo.cpuUsage > 80 ? '#f56c6c' : '#67c23a'"
            style="flex: 1"
          />
        </div>
        <div class="usage-item">
          <span class="label">内存:</span>
          <el-progress
            :percentage="containerInfo.memoryPercentage || 0"
            :stroke-width="8"
            :color="containerInfo.memoryPercentage > 80 ? '#f56c6c' : '#67c23a'"
            style="flex: 1"
          />
          <span class="value">{{
            formatFileSize(containerInfo.memoryUsed * 1024 * 1024)
          }}</span>
        </div>
      </div>

      <!-- 快捷操作 -->
      <div class="quick-actions">
        <el-button-group>
          <el-button
            v-if="containerInfo.status === 'stopped'"
            type="success"
            size="small"
            :loading="operating"
            @click="handleStart"
          >
            <el-icon><VideoPlay /></el-icon>
            启动
          </el-button>
          <el-button
            v-if="containerInfo.status === 'running'"
            type="warning"
            size="small"
            :loading="operating"
            @click="handleStop"
          >
            <el-icon><VideoPause /></el-icon>
            停止
          </el-button>
          <el-button
            v-if="containerInfo.status === 'running'"
            type="info"
            size="small"
            :loading="operating"
            @click="handleRestart"
          >
            <el-icon><RefreshRight /></el-icon>
            重启
          </el-button>
          <el-button type="primary" size="small" @click="goToContainer">
            <el-icon><View /></el-icon>
            详情
          </el-button>
        </el-button-group>
      </div>
    </div>

    <!-- 未关联容器 -->
    <div v-else class="unlinked-container">
      <el-empty description="暂未关联容器">
        <el-button type="primary" @click="showLinkDialog">
          <el-icon><Link /></el-icon>
          关联容器
        </el-button>
      </el-empty>
    </div>

    <!-- 关联容器对话框 -->
    <el-dialog
      v-model="linkDialogVisible"
      title="关联容器"
      width="800px"
      :close-on-click-modal="false"
    >
      <div class="link-dialog-content">
        <!-- 搜索 -->
        <div class="search-section">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索容器名称或镜像名称"
            clearable
            style="width: 300px"
            @keyup.enter="searchContainers"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button
            type="primary"
            :loading="searching"
            @click="searchContainers"
          >
            搜索
          </el-button>
        </div>

        <!-- 容器列表 -->
        <el-table
          v-loading="searching"
          :data="availableContainers"
          highlight-current-row
          style="width: 100%"
          @current-change="handleCurrentChange"
        >
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag
                :type="getStatusType(row.status)"
                size="small"
                effect="dark"
              >
                {{ getStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            prop="containerName"
            label="容器名称"
            min-width="180"
          />
          <el-table-column label="镜像" min-width="150">
            <template #default="{ row }">
              {{ row.imageName }}:{{ row.imageTag }}
            </template>
          </el-table-column>
          <el-table-column label="主机" min-width="120">
            <template #default="{ row }">
              {{ row.hostName }}
            </template>
          </el-table-column>
          <el-table-column label="端口映射" min-width="150">
            <template #default="{ row }">
              <div v-if="row.ports && row.ports.length > 0">
                <div
                  v-for="(port, index) in row.ports.slice(0, 2)"
                  :key="index"
                >
                  {{ port.hostPort }}:{{ port.containerPort }}
                </div>
                <div v-if="row.ports.length > 2">...</div>
              </div>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="linkDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="linking"
          :disabled="!selectedContainer"
          @click="handleLink"
        >
          确认关联
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Box,
  Link,
  Disconnect,
  VideoPlay,
  VideoPause,
  RefreshRight,
  View,
  Search,
} from "@element-plus/icons-vue";
import { useDockerStore } from "@/stores/docker";
import { useHostStore } from "@/stores/host";
import {
  getContainerList,
  getContainerDetail,
  startContainer,
  stopContainer,
  restartContainer,
  createLink,
  deleteLink,
} from "@/api/docker";

const props = defineProps({
  instanceId: {
    type: Number,
    required: true,
  },
  instanceName: {
    type: String,
    default: "",
  },
  // 已关联的容器信息
  linkedContainer: {
    type: Object,
    default: null,
  },
  // 关联ID
  linkId: {
    type: Number,
    default: null,
  },
});

const emit = defineEmits(["linked", "unlinked"]);

const router = useRouter();
const dockerStore = useDockerStore();
const hostStore = useHostStore();

// 容器信息
const containerInfo = ref(props.linkedContainer);

// 主机名称
const hostName = computed(() => {
  if (!containerInfo.value?.hostId) return "未知主机";
  const host = hostStore.hostList.find(
    (h) => h.id === containerInfo.value.hostId,
  );
  return host?.name || "未知主机";
});

// 操作状态
const operating = ref(false);
const unlinking = ref(false);

// 关联对话框
const linkDialogVisible = ref(false);
const searchKeyword = ref("");
const searching = ref(false);
const availableContainers = ref([]);
const selectedContainer = ref(null);
const linking = ref(false);

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

// 显示关联对话框
function showLinkDialog() {
  searchKeyword.value = "";
  selectedContainer.value = null;
  linkDialogVisible.value = true;
  searchContainers();
}

// 搜索容器
async function searchContainers() {
  searching.value = true;

  try {
    // 获取所有主机的容器
    const hosts = hostStore.hostList.filter((h) => h.status === 1);
    const allContainers = [];

    for (const host of hosts) {
      try {
        const data = await getContainerList(host.id, {
          keyword: searchKeyword.value,
          linked: false, // 只获取未关联的容器
        });

        const containers = (data.containers || []).map((c) => ({
          ...c,
          hostId: host.id,
          hostName: host.name,
        }));

        allContainers.push(...containers);
      } catch (error) {
        console.error(
          `Failed to fetch containers from host ${host.id}:`,
          error,
        );
      }
    }

    availableContainers.value = allContainers;
  } catch (error) {
    console.error("Failed to search containers:", error);
    ElMessage.error("搜索容器失败");
  } finally {
    searching.value = false;
  }
}

// 选择容器
function handleCurrentChange(row) {
  selectedContainer.value = row;
}

// 关联容器
async function handleLink() {
  if (!selectedContainer.value) return;

  linking.value = true;

  try {
    await createLink({
      instanceId: props.instanceId,
      instanceName: props.instanceName,
      containerId: selectedContainer.value.containerId,
      containerName: selectedContainer.value.containerName,
      hostId: selectedContainer.value.hostId,
    });

    ElMessage.success("关联成功");
    linkDialogVisible.value = false;

    // 更新容器信息
    containerInfo.value = selectedContainer.value;

    emit("linked", selectedContainer.value);
  } catch (error) {
    ElMessage.error("关联失败: " + (error.message || "未知错误"));
    console.error("Failed to link container:", error);
  } finally {
    linking.value = false;
  }
}

// 解除关联
async function handleUnlink() {
  try {
    await ElMessageBox.confirm("确定要解除容器关联吗？", "确认操作", {
      type: "warning",
    });

    unlinking.value = true;

    await deleteLink(props.linkId);

    ElMessage.success("已解除关联");
    containerInfo.value = null;

    emit("unlinked");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("解除关联失败: " + (error.message || "未知错误"));
      console.error("Failed to unlink container:", error);
    }
  } finally {
    unlinking.value = false;
  }
}

// 启动容器
async function handleStart() {
  if (!containerInfo.value) return;

  operating.value = true;

  try {
    await startContainer(
      containerInfo.value.hostId,
      containerInfo.value.containerId,
    );
    ElMessage.success("容器启动成功");
    containerInfo.value.status = "running";
  } catch (error) {
    ElMessage.error("启动失败: " + (error.message || "未知错误"));
  } finally {
    operating.value = false;
  }
}

// 停止容器
async function handleStop() {
  if (!containerInfo.value) return;

  try {
    await ElMessageBox.confirm("确定要停止该容器吗？", "确认操作", {
      type: "warning",
    });

    operating.value = true;

    await stopContainer(
      containerInfo.value.hostId,
      containerInfo.value.containerId,
    );
    ElMessage.success("容器已停止");
    containerInfo.value.status = "stopped";
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("停止失败: " + (error.message || "未知错误"));
    }
  } finally {
    operating.value = false;
  }
}

// 重启容器
async function handleRestart() {
  if (!containerInfo.value) return;

  try {
    await ElMessageBox.confirm("确定要重启该容器吗？", "确认操作", {
      type: "warning",
    });

    operating.value = true;

    await restartContainer(
      containerInfo.value.hostId,
      containerInfo.value.containerId,
    );
    ElMessage.success("容器重启成功");
    containerInfo.value.status = "running";
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("重启失败: " + (error.message || "未知错误"));
    }
  } finally {
    operating.value = false;
  }
}

// 跳转到容器详情
function goToContainer() {
  if (!containerInfo.value) return;

  router.push({
    path: `/docker/container/${containerInfo.value.containerId}`,
    query: {
      hostId: containerInfo.value.hostId,
      name: containerInfo.value.containerName,
    },
  });
}

// 跳转到主机详情
function goToHost() {
  if (!containerInfo.value?.hostId) return;
  router.push(`/host/detail/${containerInfo.value.hostId}`);
}

// 监听 props 变化
watch(
  () => props.linkedContainer,
  (newVal) => {
    containerInfo.value = newVal;
  },
  { deep: true },
);

onMounted(() => {
  hostStore.fetchHostList();
});
</script>

<style lang="scss" scoped>
.container-link-card {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .header-left {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
    }
  }

  .linked-container {
    .container-status {
      margin-bottom: 16px;
    }

    .mono-text {
      font-family: var(--el-font-family-mono);
      font-size: 12px;
    }

    .text-muted {
      color: var(--el-text-color-placeholder);
    }

    .ports-list {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;

      .port-tag {
        margin: 0;
      }
    }

    .resource-usage {
      margin-top: 16px;
      padding: 16px;
      background: var(--el-fill-color-light);
      border-radius: var(--el-border-radius-base);

      .usage-item {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 12px;

        &:last-child {
          margin-bottom: 0;
        }

        .label {
          width: 50px;
          font-size: 13px;
          color: var(--el-text-color-secondary);
        }

        .value {
          min-width: 80px;
          text-align: right;
          font-size: 13px;
          color: var(--el-text-color-secondary);
        }
      }
    }

    .quick-actions {
      margin-top: 16px;
      display: flex;
      justify-content: flex-end;
    }
  }

  .unlinked-container {
    padding: 20px 0;
  }

  .link-dialog-content {
    .search-section {
      display: flex;
      gap: 12px;
      margin-bottom: 16px;
    }
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .container-link-card {
    .linked-container {
      .resource-usage {
        .usage-item {
          flex-wrap: wrap;

          .label {
            width: 100%;
          }
        }
      }

      .quick-actions {
        :deep(.el-button-group) {
          display: flex;
          flex-wrap: wrap;

          .el-button {
            flex: 1;
          }
        }
      }
    }
  }
}
</style>
