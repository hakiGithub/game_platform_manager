<template>
  <div class="docker-page">
    <!-- 主机选择器 -->
    <el-card class="host-selector-card" shadow="never">
      <div class="host-selector">
        <div class="selector-left">
          <span class="label">选择主机:</span>
          <el-select
            v-model="selectedHostId"
            placeholder="请选择主机"
            style="width: 240px"
            @change="handleRefresh"
          >
            <el-option
              v-for="host in hostList"
              :key="host.id"
              :label="host.name"
              :value="host.id"
              :disabled="host.status !== 1"
            >
              <div class="host-option">
                <span>{{ host.name }}</span>
                <el-tag
                  :type="host.status === 1 ? 'success' : 'danger'"
                  size="small"
                  effect="plain"
                >
                  {{ host.status === 1 ? "在线" : "离线" }}
                </el-tag>
              </div>
            </el-option>
          </el-select>
        </div>
        <div class="selector-right">
          <el-button :disabled="!selectedHostId" @click="handleRefresh">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-button
            type="primary"
            :disabled="!selectedHostId"
            :loading="autoLinking"
            @click="handleAutoLink"
          >
            <el-icon><Link /></el-icon>
            自动关联容器
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- Tab内容 -->
    <el-card class="content-card" shadow="never">
      <el-tabs v-model="activeTab">
        <!-- 容器列表 -->
        <el-tab-pane name="containers">
          <template #label>
            <span>
              <el-icon><Box /></el-icon>
              容器列表
              <el-badge :value="stats.total" :max="99" class="tab-badge" />
            </span>
          </template>

          <!-- 筛选区域 -->
          <div class="filter-section">
            <el-form :inline="true" :model="queryParams">
              <el-form-item label="状态">
                <el-select
                  v-model="queryParams.status"
                  placeholder="全部"
                  clearable
                  style="width: 120px"
                >
                  <el-option label="运行中" value="running" />
                  <el-option label="已停止" value="stopped" />
                  <el-option label="已暂停" value="paused" />
                  <el-option label="重启中" value="restarting" />
                </el-select>
              </el-form-item>
              <el-form-item label="关联状态">
                <el-select
                  v-model="queryParams.linked"
                  placeholder="全部"
                  clearable
                  style="width: 120px"
                >
                  <el-option label="已关联" :value="true" />
                  <el-option label="未关联" :value="false" />
                </el-select>
              </el-form-item>
              <el-form-item label="关键词">
                <el-input
                  v-model="queryParams.keyword"
                  placeholder="容器名称/镜像名称"
                  clearable
                  style="width: 200px"
                  @keyup.enter="handleSearch"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="handleSearch">搜索</el-button>
                <el-button @click="handleReset">重置</el-button>
              </el-form-item>
            </el-form>
          </div>

          <!-- 统计信息 -->
          <div class="stats-section">
            <el-row :gutter="16">
              <el-col :span="4">
                <div class="stat-item">
                  <div class="stat-value">{{ stats.total }}</div>
                  <div class="stat-label">总容器</div>
                </div>
              </el-col>
              <el-col :span="4">
                <div class="stat-item running">
                  <div class="stat-value">{{ stats.running }}</div>
                  <div class="stat-label">运行中</div>
                </div>
              </el-col>
              <el-col :span="4">
                <div class="stat-item stopped">
                  <div class="stat-value">{{ stats.stopped }}</div>
                  <div class="stat-label">已停止</div>
                </div>
              </el-col>
              <el-col :span="4">
                <div class="stat-item linked">
                  <div class="stat-value">{{ stats.linked }}</div>
                  <div class="stat-label">已关联</div>
                </div>
              </el-col>
              <el-col :span="4">
                <div class="stat-item unlinked">
                  <div class="stat-value">{{ stats.unlinked }}</div>
                  <div class="stat-label">未关联</div>
                </div>
              </el-col>
            </el-row>
          </div>

          <!-- 容器表格 -->
          <el-table
            v-loading="containerLoading"
            :data="containerList"
            style="width: 100%"
            @row-click="goToContainerDetail"
          >
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag
                  :type="getStatusType(row.status)"
                  size="small"
                  effect="dark"
                >
                  <el-icon v-if="row.status === 'running'" class="status-icon"
                    ><VideoPlay
                  /></el-icon>
                  <el-icon
                    v-else-if="row.status === 'stopped'"
                    class="status-icon"
                    ><VideoPause
                  /></el-icon>
                  <el-icon v-else class="status-icon"><Loading /></el-icon>
                  {{ getStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="containerName"
              label="容器名称"
              min-width="180"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <div class="container-name">
                  <span>{{ row.containerName }}</span>
                  <el-tag
                    v-if="row.isLinked"
                    type="success"
                    size="small"
                    effect="plain"
                    class="link-tag"
                  >
                    已关联
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="镜像" min-width="150">
              <template #default="{ row }">
                <el-tooltip
                  :content="`${row.imageName}:${row.imageTag}`"
                  placement="top"
                >
                  <span class="image-name"
                    >{{ row.imageName }}:{{ row.imageTag }}</span
                  >
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="端口映射" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="ports-list">
                  <div
                    v-for="(port, index) in row.ports"
                    :key="index"
                    class="port-item"
                  >
                    {{ port.hostPort }}:{{ port.containerPort }}/{{
                      port.protocol
                    }}
                  </div>
                  <span v-if="!row.ports || row.ports.length === 0">-</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="资源占用" width="150">
              <template #default="{ row }">
                <div v-if="row.status === 'running'" class="resource-usage">
                  <div class="cpu-usage">
                    <span class="label">CPU:</span>
                    <el-progress
                      :percentage="row.cpuUsage || 0"
                      :stroke-width="6"
                      :color="row.cpuUsage > 80 ? '#f56c6c' : '#67c23a'"
                      style="width: 80px"
                    />
                  </div>
                  <div class="mem-usage">
                    <span class="label">MEM:</span>
                    <span
                      >{{ formatFileSize(row.memoryUsed * 1024 * 1024) }} /
                      {{ formatFileSize(row.memoryLimit * 1024 * 1024) }}</span
                    >
                  </div>
                </div>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="关联实例" width="120" show-overflow-tooltip>
              <template #default="{ row }">
                <el-link
                  v-if="row.isLinked && row.linkedInstanceName"
                  type="primary"
                  @click.stop="
                    router.push(`/instance/detail/${row.linkedInstanceId}`)
                  "
                >
                  {{ row.linkedInstanceName }}
                </el-link>
                <span v-else class="text-muted">未关联</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button-group>
                  <el-button
                    v-if="row.status === 'stopped'"
                    type="success"
                    size="small"
                    @click.stop="handleStart(row)"
                  >
                    <el-icon><VideoPlay /></el-icon>
                  </el-button>
                  <el-button
                    v-if="row.status === 'running'"
                    type="warning"
                    size="small"
                    @click.stop="handleStop(row)"
                  >
                    <el-icon><VideoPause /></el-icon>
                  </el-button>
                  <el-button
                    v-if="row.status === 'running'"
                    type="info"
                    size="small"
                    @click.stop="handleRestart(row)"
                  >
                    <el-icon><RefreshRight /></el-icon>
                  </el-button>
                  <el-button
                    type="primary"
                    size="small"
                    @click.stop="goToContainerDetail(row)"
                  >
                    <el-icon><View /></el-icon>
                  </el-button>
                  <el-button
                    type="danger"
                    size="small"
                    @click.stop="showDeleteConfirm(row)"
                  >
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </el-button-group>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 镜像列表 -->
        <el-tab-pane name="images">
          <template #label>
            <span>
              <el-icon><Files /></el-icon>
              镜像列表
            </span>
          </template>

          <!-- 筛选区域 -->
          <div class="filter-section">
            <el-form :inline="true" :model="imageQueryParams">
              <el-form-item label="关键词">
                <el-input
                  v-model="imageQueryParams.keyword"
                  placeholder="镜像名称"
                  clearable
                  style="width: 200px"
                  @keyup.enter="handleSearch"
                />
              </el-form-item>
              <el-form-item label="悬空镜像">
                <el-switch v-model="imageQueryParams.dangling" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="handleSearch">搜索</el-button>
                <el-button @click="handleReset">重置</el-button>
                <el-button
                  type="danger"
                  :disabled="!selectedHostId"
                  @click="handlePruneImages"
                >
                  <el-icon><Delete /></el-icon>
                  清理悬空镜像
                </el-button>
              </el-form-item>
            </el-form>
          </div>

          <!-- 镜像表格 -->
          <el-table
            v-loading="imagesLoading"
            :data="imageList"
            style="width: 100%"
          >
            <el-table-column prop="imageId" label="镜像ID" width="120">
              <template #default="{ row }">
                <el-tooltip :content="row.imageIdFull" placement="top">
                  <span class="image-id">{{ row.imageId }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="仓库标签" min-width="200">
              <template #default="{ row }">
                <div class="repo-tags">
                  <el-tag
                    v-for="(tag, index) in row.repoTags"
                    :key="index"
                    :type="tag === '<none>:<none>' ? 'danger' : 'primary'"
                    size="small"
                    class="repo-tag"
                  >
                    {{ tag }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="size" label="大小" width="100">
              <template #default="{ row }">
                {{ formatFileSize(row.size * 1024 * 1024) }}
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="160" />
            <el-table-column
              prop="usedByContainers"
              label="使用容器数"
              width="100"
            >
              <template #default="{ row }">
                <el-tag
                  :type="row.usedByContainers > 0 ? 'success' : 'info'"
                  size="small"
                >
                  {{ row.usedByContainers }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  type="danger"
                  size="small"
                  :disabled="row.usedByContainers > 0 && !row.isDangling"
                  @click="showDeleteImageConfirm(row)"
                >
                  <el-icon><Delete /></el-icon>
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 删除容器确认对话框 -->
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
          <el-input :value="deleteConfirmContainer?.containerName" disabled />
        </el-form-item>
        <el-form-item label="确认名称">
          <el-input
            v-model="deleteConfirmName"
            :placeholder="`请输入 ${deleteConfirmContainer?.containerName}`"
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
          :disabled="
            deleteConfirmName !== deleteConfirmContainer?.containerName
          "
          @click="handleDeleteContainer"
        >
          确认删除
        </el-button>
      </template>
    </el-dialog>

    <!-- 删除镜像确认对话框 -->
    <el-dialog
      v-model="deleteImageDialogVisible"
      title="删除镜像"
      width="500px"
    >
      <el-alert
        v-if="deleteImageConfirm?.usedByContainers > 0"
        type="warning"
        title="该镜像正被容器使用，删除可能导致容器无法正常运行。"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />
      <p>
        确定要删除镜像 <strong>{{ deleteImageConfirm?.imageId }}</strong> 吗？
      </p>
      <template #footer>
        <el-button @click="deleteImageDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="deletingImage"
          @click="handleDeleteImage"
        >
          确认删除
        </el-button>
      </template>
    </el-dialog>

    <!-- 自动关联结果对话框 -->
    <el-dialog
      v-model="autoLinkResultVisible"
      title="自动关联结果"
      width="600px"
    >
      <el-descriptions :column="2" border>
        <el-descriptions-item label="扫描容器数">
          {{ autoLinkResult?.totalContainers || 0 }}
        </el-descriptions-item>
        <el-descriptions-item label="成功关联">
          <el-tag type="success">{{ autoLinkResult?.linkedCount || 0 }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="跳过数量">
          {{ autoLinkResult?.skippedCount || 0 }}
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="autoLinkResult?.links?.length > 0" class="link-result-list">
        <h4>关联详情:</h4>
        <div
          v-for="(link, index) in autoLinkResult.links"
          :key="index"
          class="link-item"
        >
          <el-icon><Link /></el-icon>
          <span class="container-name">{{ link.containerName }}</span>
          <el-icon><Right /></el-icon>
          <span class="instance-name">{{ link.instanceName }}</span>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="autoLinkResultVisible = false"
          >确定</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Box,
  Files,
  Refresh,
  Link,
  VideoPlay,
  VideoPause,
  Delete,
  View,
  RefreshRight,
  Right,
  Loading,
} from "@element-plus/icons-vue";
import { useHostStore } from "@/stores/host";
import { useDockerStore } from "@/stores/docker";
import {
  startContainer,
  stopContainer,
  restartContainer,
  deleteContainer,
  deleteImage,
  pruneImages,
} from "@/api/docker";

const router = useRouter();
const hostStore = useHostStore();
const dockerStore = useDockerStore();

// 主机列表
const hostList = computed(() => hostStore.hostList);

// 选中的主机ID
const selectedHostId = ref(null);

// 当前Tab
const activeTab = ref("containers");

// 查询参数
const queryParams = ref({
  status: "",
  linked: null,
  keyword: "",
});

const imageQueryParams = ref({
  keyword: "",
  dangling: false,
});

// 容器列表
const containerList = computed(() => dockerStore.containers);
const containerLoading = computed(() => dockerStore.containerLoading);

// 镜像列表
const imageList = computed(() => dockerStore.images);
const imagesLoading = computed(() => dockerStore.imagesLoading);

// 统计数据
const stats = computed(() => dockerStore.containerStats_summary);

// 删除确认相关
const deleteConfirmVisible = ref(false);
const deleteConfirmContainer = ref(null);
const deleteConfirmName = ref("");
const deleteConfirmForce = ref(false);
const deleteConfirmVolumes = ref(false);
const deleting = ref(false);

// 删除镜像相关
const deleteImageDialogVisible = ref(false);
const deleteImageConfirm = ref(null);
const deletingImage = ref(false);

// 自动关联相关
const autoLinking = ref(false);
const autoLinkResultVisible = ref(false);
const autoLinkResult = ref(null);

// 定时刷新
let refreshTimer = null;

// 获取状态类型
function getStatusType(status) {
  return dockerStore.getContainerStatusType(status);
}

// 获取状态文本
function getStatusText(status) {
  return dockerStore.getContainerStatusText(status);
}

// 格式化文件大小
function formatFileSize(bytes) {
  return dockerStore.formatFileSize(bytes);
}

// 刷新数据
async function handleRefresh() {
  if (!selectedHostId.value) return;

  if (activeTab.value === "containers") {
    await fetchContainers();
  } else {
    await fetchImages();
  }
}

// 获取容器列表
async function fetchContainers() {
  if (!selectedHostId.value) return;

  try {
    await dockerStore.fetchContainers(selectedHostId.value, queryParams.value);
  } catch (error) {
    console.error("Failed to fetch containers:", error);
  }
}

// 获取镜像列表
async function fetchImages() {
  if (!selectedHostId.value) return;

  try {
    await dockerStore.fetchImages(selectedHostId.value, imageQueryParams.value);
  } catch (error) {
    console.error("Failed to fetch images:", error);
  }
}

// 搜索
function handleSearch() {
  if (activeTab.value === "containers") {
    fetchContainers();
  } else {
    fetchImages();
  }
}

// 重置
function handleReset() {
  if (activeTab.value === "containers") {
    queryParams.value = {
      status: "",
      linked: null,
      keyword: "",
    };
    fetchContainers();
  } else {
    imageQueryParams.value = {
      keyword: "",
      dangling: false,
    };
    fetchImages();
  }
}

// 启动容器
async function handleStart(row) {
  try {
    await startContainer(selectedHostId.value, row.containerId);
    ElMessage.success("容器启动成功");
    await fetchContainers();
  } catch (error) {
    ElMessage.error("启动失败: " + (error.message || "未知错误"));
  }
}

// 停止容器
async function handleStop(row) {
  try {
    await ElMessageBox.confirm("确定要停止该容器吗？", "确认操作", {
      type: "warning",
    });
    await stopContainer(selectedHostId.value, row.containerId);
    ElMessage.success("容器已停止");
    await fetchContainers();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("停止失败: " + (error.message || "未知错误"));
    }
  }
}

// 重启容器
async function handleRestart(row) {
  try {
    await ElMessageBox.confirm("确定要重启该容器吗？", "确认操作", {
      type: "warning",
    });
    await restartContainer(selectedHostId.value, row.containerId);
    ElMessage.success("容器重启成功");
    await fetchContainers();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("重启失败: " + (error.message || "未知错误"));
    }
  }
}

// 显示删除确认
function showDeleteConfirm(row) {
  deleteConfirmContainer.value = row;
  deleteConfirmName.value = "";
  deleteConfirmForce.value = row.status === "running";
  deleteConfirmVolumes.value = false;
  deleteConfirmVisible.value = true;
}

// 删除容器
async function handleDeleteContainer() {
  if (!deleteConfirmContainer.value) return;

  deleting.value = true;
  try {
    await deleteContainer(
      selectedHostId.value,
      deleteConfirmContainer.value.containerId,
      {
        force: deleteConfirmForce.value,
        volumes: deleteConfirmVolumes.value,
      },
    );
    ElMessage.success("容器已删除");
    deleteConfirmVisible.value = false;
    await fetchContainers();
  } catch (error) {
    ElMessage.error("删除失败: " + (error.message || "未知错误"));
  } finally {
    deleting.value = false;
  }
}

// 显示删除镜像确认
function showDeleteImageConfirm(row) {
  deleteImageConfirm.value = row;
  deleteImageDialogVisible.value = true;
}

// 删除镜像
async function handleDeleteImage() {
  if (!deleteImageConfirm.value) return;

  deletingImage.value = true;
  try {
    await deleteImage(selectedHostId.value, deleteImageConfirm.value.imageId);
    ElMessage.success("镜像已删除");
    deleteImageDialogVisible.value = false;
    await fetchImages();
  } catch (error) {
    ElMessage.error("删除失败: " + (error.message || "未知错误"));
  } finally {
    deletingImage.value = false;
  }
}

// 清理悬空镜像
async function handlePruneImages() {
  try {
    await ElMessageBox.confirm(
      "确定要清理所有悬空镜像吗？此操作不可逆。",
      "确认清理",
      { type: "warning" },
    );

    const result = await pruneImages(selectedHostId.value);
    ElMessage.success(
      `清理完成，释放 ${dockerStore.formatFileSize(result.spaceReclaimed * 1024 * 1024)} 空间`,
    );
    await fetchImages();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("清理失败: " + (error.message || "未知错误"));
    }
  }
}

// 自动关联
async function handleAutoLink() {
  autoLinking.value = true;
  try {
    const result = await dockerStore.executeAutoLink(selectedHostId.value);
    autoLinkResult.value = result;
    autoLinkResultVisible.value = true;
  } catch (error) {
    ElMessage.error("自动关联失败: " + (error.message || "未知错误"));
  } finally {
    autoLinking.value = false;
  }
}

// 跳转到容器详情
function goToContainerDetail(row) {
  router.push({
    path: `/docker/container/${row.containerId}`,
    query: {
      hostId: selectedHostId.value,
      name: row.containerName,
    },
  });
}

// 监听主机切换
watch(selectedHostId, (newVal) => {
  if (newVal) {
    if (activeTab.value === "containers") {
      fetchContainers();
    } else {
      fetchImages();
    }
  }
});

// 监听Tab切换
watch(activeTab, (newVal) => {
  if (selectedHostId.value) {
    if (newVal === "containers") {
      fetchContainers();
    } else {
      fetchImages();
    }
  }
});

// 定时刷新
function startRefreshTimer() {
  refreshTimer = setInterval(() => {
    if (selectedHostId.value && activeTab.value === "containers") {
      fetchContainers();
    }
  }, 30000); // 30秒刷新一次
}

function stopRefreshTimer() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

onMounted(() => {
  fetchContainers();
  startRefreshTimer();
});

onBeforeUnmount(() => {
  stopRefreshTimer();
});
</script>

<style lang="scss" scoped>
.docker-page {
  .host-selector-card {
    margin-bottom: 16px;

    :deep(.el-card__body) {
      padding: 16px 20px;
    }
  }

  .host-selector {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 16px;

    .selector-left {
      display: flex;
      align-items: center;
      gap: 12px;

      .label {
        font-weight: 500;
        color: var(--el-text-color-primary);
      }
    }

    .selector-right {
      display: flex;
      gap: 8px;
    }
  }

  .host-option {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
  }

  .content-card {
    :deep(.el-card__body) {
      padding: 0;
    }

    :deep(.el-tabs__content) {
      padding: 20px;
    }
  }

  .tab-badge {
    margin-left: 8px;
  }

  .filter-section {
    margin-bottom: 16px;
    padding: 16px;
    background: var(--el-fill-color-light);
    border-radius: var(--el-border-radius-base);
  }

  .stats-section {
    margin-bottom: 16px;

    .stat-item {
      text-align: center;
      padding: 16px;
      background: var(--el-fill-color-light);
      border-radius: var(--el-border-radius-base);

      .stat-value {
        font-size: 28px;
        font-weight: 600;
        line-height: 1;
        margin-bottom: 8px;
      }

      .stat-label {
        font-size: 13px;
        color: var(--el-text-color-secondary);
      }

      &.running .stat-value {
        color: var(--el-color-success);
      }

      &.stopped .stat-value {
        color: var(--el-color-info);
      }

      &.linked .stat-value {
        color: var(--el-color-primary);
      }

      &.unlinked .stat-value {
        color: var(--el-color-warning);
      }
    }
  }

  .container-name {
    display: flex;
    align-items: center;
    gap: 8px;

    .link-tag {
      margin-left: 8px;
    }
  }

  .image-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    display: block;
  }

  .ports-list {
    .port-item {
      font-family: var(--el-font-family-mono);
      font-size: 12px;
      line-height: 1.6;
    }
  }

  .resource-usage {
    .cpu-usage {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 4px;

      .label {
        font-size: 12px;
        color: var(--el-text-color-secondary);
        width: 35px;
      }
    }

    .mem-usage {
      font-size: 12px;
      color: var(--el-text-color-secondary);

      .label {
        margin-right: 8px;
      }
    }
  }

  .status-icon {
    margin-right: 4px;
  }

  .image-id {
    font-family: var(--el-font-family-mono);
    font-size: 12px;
  }

  .repo-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;

    .repo-tag {
      margin: 0;
    }
  }

  .form-hint {
    margin-left: 12px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .link-result-list {
    margin-top: 16px;
    max-height: 300px;
    overflow-y: auto;

    h4 {
      margin-bottom: 12px;
      font-size: 14px;
    }

    .link-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      background: var(--el-fill-color-light);
      border-radius: var(--el-border-radius-base);
      margin-bottom: 8px;

      .container-name {
        font-weight: 500;
      }

      .instance-name {
        color: var(--el-color-primary);
      }
    }
  }

  :deep(.el-table) {
    .el-table__row {
      cursor: pointer;

      &:hover {
        background-color: var(--el-fill-color-light);
      }
    }
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .docker-page {
    .host-selector {
      flex-direction: column;
      align-items: stretch;

      .selector-left,
      .selector-right {
        width: 100%;
        justify-content: space-between;
      }
    }

    .stats-section {
      .stat-item {
        padding: 12px;

        .stat-value {
          font-size: 24px;
        }
      }
    }
  }
}
</style>
