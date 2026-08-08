<script setup>
import { ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { getGameList, getGamePage, getGameDetail, deleteGame } from "@/api/game";
import {
  getInstancesByGameId,
  startInstance,
  stopInstance,
} from "@/api/instance";

const router = useRouter();

// 加载状态
const loading = ref(false);

// 搜索表单
const searchForm = reactive({
  keyword: "",
});

// 游戏列表
const gameList = ref([]);

// 分页状态
const pagination = reactive({
  current: 1,
  size: 10,
  total: 0,
});

// 详情抽屉
const drawerVisible = ref(false);
const drawerLoading = ref(false);
const currentGame = ref(null);

// 实例展开缓存：{ [gameId]: { loading, loaded, list } }
const instanceMap = ref({});

// 获取游戏列表
async function fetchGameList() {
  loading.value = true;
  try {
    const data = await getGamePage({
      current: pagination.current,
      size: pagination.size,
      keyword: searchForm.keyword || undefined,
    });
    gameList.value = data.records || [];
    pagination.total = data.total || 0;
    // 重置实例缓存
    instanceMap.value = {};
  } catch (error) {
    console.error("Failed to fetch game list:", error);
    ElMessage.error("获取游戏列表失败");
  } finally {
    loading.value = false;
  }
}

// 分页变化
function handlePageChange(page) {
  pagination.current = page;
  fetchGameList();
}

// 每页条数变化
function handleSizeChange(size) {
  pagination.size = size;
  pagination.current = 1;
  fetchGameList();
}

// 展开行时懒加载实例列表
async function handleExpandChange(row, expandedRows) {
  const isExpanded = expandedRows.some((r) => r.id === row.id);
  if (!isExpanded) return;
  // 已加载则跳过
  const cached = instanceMap.value[row.id];
  if (cached && cached.loaded) return;
  await loadGameInstances(row.id);
}

// 加载某游戏的实例列表
async function loadGameInstances(gameId) {
  instanceMap.value[gameId] = {
    loading: true,
    loaded: false,
    list: [],
  };
  try {
    const data = await getInstancesByGameId(gameId);
    instanceMap.value[gameId] = {
      loading: false,
      loaded: true,
      list: data || [],
    };
  } catch (error) {
    console.error("Failed to fetch instances by game id:", error);
    instanceMap.value[gameId] = {
      loading: false,
      loaded: true,
      list: [],
    };
  }
}

// 跳转到实例详情
function handleViewInstance(instance) {
  router.push(`/instance/detail/${instance.id}`);
}

// 启动实例
async function handleStartInstance(instance) {
  try {
    await startInstance(instance.id);
    ElMessage.success("启动成功");
    await loadGameInstances(instance.gameId);
  } catch (error) {
    ElMessage.error("启动失败: " + (error.message || "未知错误"));
  }
}

// 停止实例
async function handleStopInstance(instance) {
  try {
    await ElMessageBox.confirm(
      `确定要停止实例「${instance.instanceName}」吗？`,
      "确认操作",
      { type: "warning" },
    );
    await stopInstance(instance.id);
    ElMessage.success("停止成功");
    await loadGameInstances(instance.gameId);
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("停止失败: " + (error.message || "未知错误"));
    }
  }
}

// 部署新实例
function handleDeployGame(row) {
  router.push({ path: "/instance/deploy", query: { gameId: row.id } });
}

// 搜索
function handleSearch() {
  pagination.current = 1;
  fetchGameList();
}

// 重置
function handleReset() {
  searchForm.keyword = "";
  pagination.current = 1;
  fetchGameList();
}

// 查看详情
async function handleDetail(row) {
  drawerVisible.value = true;
  drawerLoading.value = true;
  try {
    const data = await getGameDetail(row.id);
    currentGame.value = data;
  } catch (error) {
    console.error("Failed to fetch game detail:", error);
    ElMessage.error("获取游戏详情失败");
    drawerVisible.value = false;
  } finally {
    drawerLoading.value = false;
  }
}

// 删除游戏
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除游戏「${row.gameName}」吗？删除后相关实例配置将受影响，此操作不可恢复。`,
      "删除确认",
      {
        confirmButtonText: "确定删除",
        cancelButtonText: "取消",
        type: "warning",
      },
    );

    await deleteGame(row.id);
    ElMessage.success("删除成功");
    fetchGameList();
  } catch (error) {
    if (error !== "cancel") {
      console.error("Failed to delete game:", error);
      ElMessage.error("删除失败");
    }
  }
}

// 获取部署类型标签
function getDeployTypeTag(type) {
  const typeMap = {
    "docker": { label: "Docker", type: "primary" },
    "docker-compose": { label: "Docker Compose", type: "success" },
    "linuxgsm": { label: "LinuxGSM", type: "warning" },
    "linuxgsm-docker": { label: "LinuxGSM Docker", type: "danger" },
    "native": { label: "原生", type: "info" },
  };
  return typeMap[type] || { label: type, type: "info" };
}

// 格式化时间
function formatTime(time) {
  if (!time) return "-";
  return new Date(time).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

// 实例运行状态映射
function getInstanceStatusType(status) {
  const types = {
    running: "success",
    stopped: "info",
    error: "danger",
    starting: "warning",
    stopping: "warning",
    deploying: "warning",
  };
  return types[status] || "info";
}

function getInstanceStatusText(status) {
  const texts = {
    running: "运行中",
    stopped: "已停止",
    error: "异常",
    starting: "启动中",
    stopping: "停止中",
    deploying: "部署中",
  };
  return texts[status] || status || "未知";
}

// 获取实例主端口（IP:Port 形式）
function getInstanceEndpoint(instance) {
  const port = instance.portConfig?.game || instance.portConfig?.default;
  if (!port) return instance.hostIp || "-";
  return `${instance.hostIp || "-"}:${port}`;
}

// 获取实例运行数
function getRunningCount(gameId) {
  const cached = instanceMap.value[gameId];
  if (!cached || !cached.list) return 0;
  return cached.list.filter((i) => i.status === "running").length;
}

onMounted(() => {
  fetchGameList();
});
</script>

<template>
  <div class="game-container">
    <!-- 搜索区域 -->
    <el-card class="search-card" shadow="never">
      <el-form :model="searchForm" inline>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="游戏名称/编码"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            搜索
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 表格区域 -->
    <el-card class="table-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">游戏列表</span>
          <div class="header-actions">
            <el-button @click="fetchGameList">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="gameList"
        style="width: 100%"
        stripe
        empty-text="暂无游戏数据"
        row-key="id"
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="instance-expand">
              <div v-loading="instanceMap[row.id]?.loading" class="instance-list-wrapper">
                <template v-if="instanceMap[row.id]?.loaded">
                  <div
                    v-if="instanceMap[row.id].list.length === 0"
                    class="empty-instances"
                  >
                    <el-empty description="暂无实例" :image-size="60" />
                    <el-button
                      type="primary"
                      size="small"
                      @click="handleDeployGame(row)"
                    >
                      <el-icon><Plus /></el-icon>
                      立即部署
                    </el-button>
                  </div>
                  <div v-else class="instance-cards">
                    <div
                      v-for="instance in instanceMap[row.id].list"
                      :key="instance.id"
                      class="instance-card"
                      :class="`status-${instance.status}`"
                    >
                      <div class="instance-card-header">
                        <div class="instance-name-wrap">
                          <span class="status-dot" :class="`dot-${instance.status}`"></span>
                          <span class="instance-name">{{ instance.instanceName }}</span>
                          <el-tag
                            :type="getInstanceStatusType(instance.status)"
                            size="small"
                            effect="light"
                          >
                            {{ getInstanceStatusText(instance.status) }}
                          </el-tag>
                        </div>
                        <div class="instance-actions">
                          <el-button
                            v-if="instance.status === 'stopped' || instance.status === 'error'"
                            type="success"
                            link
                            size="small"
                            @click="handleStartInstance(instance)"
                          >
                            启动
                          </el-button>
                          <el-button
                            v-if="instance.status === 'running'"
                            type="warning"
                            link
                            size="small"
                            @click="handleStopInstance(instance)"
                          >
                            停止
                          </el-button>
                          <el-button
                            type="primary"
                            link
                            size="small"
                            @click="handleViewInstance(instance)"
                          >
                            详情
                          </el-button>
                        </div>
                      </div>
                      <div class="instance-card-body">
                        <div class="instance-meta">
                          <span class="meta-label">主机</span>
                          <span class="meta-value">{{ instance.hostName || "-" }}</span>
                        </div>
                        <div class="instance-meta">
                          <span class="meta-label">地址</span>
                          <span class="meta-value mono">{{ getInstanceEndpoint(instance) }}</span>
                        </div>
                        <div class="instance-meta">
                          <span class="meta-label">玩家</span>
                          <span class="meta-value">{{ instance.onlinePlayers || 0 }}</span>
                        </div>
                        <div class="instance-meta">
                          <span class="meta-label">部署方式</span>
                          <span class="meta-value">{{ getDeployTypeTag(instance.deployType).label }}</span>
                        </div>
                      </div>
                    </div>
                    <div class="instance-card add-card" @click="handleDeployGame(row)">
                      <el-icon class="add-icon"><Plus /></el-icon>
                      <span>部署新实例</span>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="游戏名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="game-name-cell">
              <el-avatar
                v-if="row.iconUrl"
                :src="row.iconUrl"
                :size="32"
                shape="square"
              />
              <el-avatar v-else :size="32" shape="square">
                <el-icon><Grid /></el-icon>
              </el-avatar>
              <div class="game-info">
                <span class="game-name">{{ row.gameName }}</span>
                <span class="game-code">{{ row.gameCode }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="部署方式" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="deploy-types">
              <el-tag
                v-for="type in row.supportedDeployTypes"
                :key="type"
                :type="getDeployTypeTag(type).type"
                size="small"
                effect="plain"
              >
                {{ getDeployTypeTag(type).label }}
              </el-tag>
              <span v-if="!row.supportedDeployTypes?.length" class="text-muted"
                >-</span
              >
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="defaultPort" label="默认端口" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.defaultPort || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实例" width="130" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <el-badge
              v-if="instanceMap[row.id]?.loaded && instanceMap[row.id].list.length > 0"
              :value="instanceMap[row.id].list.length"
              :type="getRunningCount(row.id) > 0 ? 'success' : 'info'"
              class="instance-badge"
            >
              <span class="instance-count-text">
                {{ getRunningCount(row.id) }}/{{ instanceMap[row.id].list.length }} 运行
              </span>
            </el-badge>
            <span v-else class="text-muted instance-count-text">点击展开</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="description-text">{{ row.description || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ formatTime(row.updateTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              type="success"
              link
              size="small"
              @click="handleDeployGame(row)"
            >
              部署
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="handleDetail(row)"
            >
              详情
            </el-button>
            <el-button
              type="danger"
              link
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      title="游戏详情"
      direction="rtl"
      size="500px"
    >
      <div v-loading="drawerLoading" class="detail-content">
        <template v-if="currentGame">
          <!-- 基本信息 -->
          <div class="detail-section">
            <div class="section-title">基本信息</div>
            <div class="detail-grid">
              <div class="detail-item">
                <span class="label">游戏名称</span>
                <span class="value">{{ currentGame.gameName }}</span>
              </div>
              <div class="detail-item">
                <span class="label">游戏编码</span>
                <span class="value">{{ currentGame.gameCode }}</span>
              </div>
              <div class="detail-item">
                <span class="label">默认端口</span>
                <span class="value">{{ currentGame.defaultPort || "-" }}</span>
              </div>
              <div class="detail-item">
                <span class="label">创建时间</span>
                <span class="value">{{
                  formatTime(currentGame.createTime)
                }}</span>
              </div>
              <div class="detail-item">
                <span class="label">更新时间</span>
                <span class="value">{{
                  formatTime(currentGame.updateTime)
                }}</span>
              </div>
            </div>
          </div>

          <!-- 游戏描述 -->
          <div class="detail-section">
            <div class="section-title">游戏描述</div>
            <div class="description-box">
              {{ currentGame.description || "暂无描述" }}
            </div>
          </div>

          <!-- 支持的部署方式 -->
          <div class="detail-section">
            <div class="section-title">支持的部署方式</div>
            <div class="deploy-types-box">
              <el-tag
                v-for="type in currentGame.supportedDeployTypes"
                :key="type"
                :type="getDeployTypeTag(type).type"
                effect="plain"
              >
                {{ getDeployTypeTag(type).label }}
              </el-tag>
              <span v-if="!currentGame.supportedDeployTypes?.length">暂无</span>
            </div>
          </div>

          <!-- 环境依赖 -->
          <div
            v-if="
              currentGame.environmentDeps &&
              Object.keys(currentGame.environmentDeps).length
            "
            class="detail-section"
          >
            <div class="section-title">环境依赖</div>
            <div class="json-box">
              <pre>{{
                JSON.stringify(currentGame.environmentDeps, null, 2)
              }}</pre>
            </div>
          </div>

          <!-- 部署配置模板 -->
          <div
            v-if="
              currentGame.deployConfig &&
              Object.keys(currentGame.deployConfig).length
            "
            class="detail-section"
          >
            <div class="section-title">部署配置模板</div>
            <div class="json-box">
              <pre>{{ JSON.stringify(currentGame.deployConfig, null, 2) }}</pre>
            </div>
          </div>

          <!-- 自定义操作 -->
          <div
            v-if="
              currentGame.customOperations &&
              Object.keys(currentGame.customOperations).length
            "
            class="detail-section"
          >
            <div class="section-title">自定义操作</div>
            <div class="json-box">
              <pre>{{
                JSON.stringify(currentGame.customOperations, null, 2)
              }}</pre>
            </div>
          </div>

          <!-- 备注 -->
          <div v-if="currentGame.remark" class="detail-section">
            <div class="section-title">备注</div>
            <div class="description-box">
              {{ currentGame.remark }}
            </div>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.game-container {
  .search-card {
    margin-bottom: 16px;

    :deep(.el-card__body) {
      padding-bottom: 0;
    }
  }

  .table-card {
    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;

      .title {
        font-size: var(--platform-font-size-md);
        font-weight: var(--platform-font-weight-bold);
        color: var(--el-text-color-primary);
      }

      .header-actions {
        display: flex;
        gap: 8px;
      }
    }

    .pagination-wrap {
      display: flex;
      justify-content: flex-end;
      margin-top: 16px;
    }
  }
}

// 实例展开区
.instance-expand {
  padding: 12px 24px 20px 48px;
  background-color: var(--el-fill-color-lighter);
}

.instance-list-wrapper {
  min-height: 80px;
}

.empty-instances {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
}

.instance-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.instance-card {
  display: flex;
  flex-direction: column;
  background-color: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px 14px;
  transition: all 0.2s;

  &:hover {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
    border-color: var(--el-color-primary-light-5);
  }

  &.status-running {
    border-left: 3px solid var(--el-color-success);
  }

  &.status-stopped {
    border-left: 3px solid var(--el-color-info);
  }

  &.status-error {
    border-left: 3px solid var(--el-color-danger);
  }

  &.status-deploying,
  &.status-starting,
  &.status-stopping {
    border-left: 3px solid var(--el-color-warning);
  }

  .instance-card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;

    .instance-name-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      min-width: 0;
    }

    .instance-name {
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .instance-actions {
      display: flex;
      gap: 4px;
      flex-shrink: 0;
    }
  }

  .instance-card-body {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 6px 16px;
  }

  .instance-meta {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: var(--platform-font-size-sm);

    .meta-label {
      color: var(--el-text-color-secondary);
      min-width: 48px;
    }

    .meta-value {
      color: var(--el-text-color-primary);

      &.mono {
        font-family: "Consolas", "Monaco", monospace;
        font-size: var(--platform-font-size-xs);
      }
    }
  }
}

.add-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed var(--el-border-color);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  min-height: 110px;

  &:hover {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary);
    background-color: var(--el-color-primary-light-9);
  }

  .add-icon {
    font-size: 24px;
  }
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;

  &.dot-running {
    background-color: var(--el-color-success);
    box-shadow: 0 0 4px var(--el-color-success);
  }

  &.dot-stopped {
    background-color: var(--el-color-info);
  }

  &.dot-error {
    background-color: var(--el-color-danger);
  }

  &.dot-deploying,
  &.dot-starting,
  &.dot-stopping {
    background-color: var(--el-color-warning);
    animation: pulse 1.5s infinite;
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.instance-count-text {
  font-size: var(--platform-font-size-sm);
}

.instance-badge {
  margin: 0 auto;
}

.game-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;

  .game-info {
    display: flex;
    flex-direction: column;
    gap: 2px;

    .game-name {
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
    }

    .game-code {
      font-size: var(--platform-font-size-xs);
      color: var(--el-text-color-secondary);
    }
  }
}

.deploy-types {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.text-muted {
  color: var(--el-text-color-placeholder);
}

.description-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

// 详情抽屉样式
.detail-content {
  padding: 0 16px;
}

.detail-section {
  margin-bottom: 24px;

  .section-title {
    font-size: var(--platform-font-size-base);
    font-weight: var(--platform-font-weight-medium);
    color: var(--el-text-color-primary);
    margin-bottom: 12px;
    padding-left: 8px;
    border-left: 3px solid var(--el-color-primary);
  }
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;

  .detail-item {
    display: flex;
    flex-direction: column;
    gap: 4px;

    .label {
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-secondary);
    }

    .value {
      font-size: var(--platform-font-size-base);
      color: var(--el-text-color-primary);
    }
  }
}

.description-box {
  padding: 12px;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: var(--platform-font-size-sm);
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.deploy-types-box {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.json-box {
  padding: 12px;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
  overflow-x: auto;

  pre {
    margin: 0;
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-regular);
    white-space: pre-wrap;
    word-break: break-all;
  }
}

// 响应式适配
@media screen and (max-width: 1366px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
