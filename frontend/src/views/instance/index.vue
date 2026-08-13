<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getInstanceList,
  startInstance,
  stopInstance,
  restartInstance,
  deleteInstance,
  retryDeploy,
} from "@/api/instance";
import { getHostList } from "@/api/host";
import DeployProgress from "@/components/DeployProgress.vue";
import { statusType, statusIcon, statusColor, ACTIVE_STATUSES } from "@/utils/instanceStatus";

const router = useRouter();

// 加载状态
const loading = ref(false);

// 搜索表单
const searchForm = reactive({
  keyword: "",
  status: "",
  hostId: "",
  game: "",
});

// 主机选项
const hostOptions = ref([]);

// 游戏选项
const gameOptions = ref([
  { id: "minecraft", name: "Minecraft" },
  { id: "palworld", name: "幻兽帕鲁" },
  { id: "l4d2", name: "求生之路2" },
  { id: "ark", name: "方舟生存进化" },
  { id: "rust", name: "Rust" },
  { id: "csgo", name: "CS:GO" },
  { id: "valheim", name: "英灵神殿" },
]);

// 表格数据
const tableData = ref([]);
const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
});

// 日志查看弹窗
const logDialogVisible = ref(false);
const currentLogInstanceId = ref("");
const currentLogMode = ref("deploy");

function handleViewLogs(row) {
  currentLogInstanceId.value = row.id;
  // 安装中/更新中/启动中/异常 用 deploy 模式（异常状态需要查看部署失败日志），其他用 runtime 模式
  currentLogMode.value = ["installing", "updating", "starting", "error"].includes(row.status) ? "deploy" : "runtime";
  logDialogVisible.value = true;
}

// 重试部署
async function handleRetryDeploy(row) {
  try {
    await ElMessageBox.confirm(
      `确定要重新部署实例「${row.instanceName}」吗？`,
      "确认重试",
      { type: "warning" }
    );
    await retryDeploy(row.id);
    ElMessage.success("已重新触发部署");
    fetchData();
    startAutoRefresh();
  } catch (e) {
    if (e !== "cancel") {
      ElMessage.error("重试部署失败: " + (e.message || "未知错误"));
    }
  }
}

// 删除确认弹窗
const deleteDialogVisible = ref(false);
const deleteConfirmName = ref("");
const deleteTarget = ref(null);

// 获取主机列表
async function fetchHostOptions() {
  try {
    const data = await getHostList({ pageSize: 100 });
    hostOptions.value = (data.records || []).map((h) => ({
      id: h.id,
      name: h.name,
    }));
  } catch (error) {
    console.error("Failed to fetch host options:", error);
  }
}

// 获取列表
async function fetchData() {
  loading.value = true;
  try {
    const data = await getInstanceList({
      ...searchForm,
      page: pagination.current,
      pageSize: pagination.pageSize,
    });
    tableData.value = data.records || [];
    pagination.total = data.total || 0;
  } catch (error) {
    console.error("Failed to fetch instance list:", error);
  } finally {
    loading.value = false;
  }
}

// 列表自动刷新（存在活跃过渡态时）
let autoRefreshTimer = null;

function startAutoRefresh() {
  stopAutoRefresh();
  autoRefreshTimer = setInterval(() => {
    const hasActive = tableData.value.some((row) =>
      ACTIVE_STATUSES.includes(row.status)
    );
    if (hasActive) {
      fetchData();
    } else {
      stopAutoRefresh();
    }
  }, 5000);
}

function stopAutoRefresh() {
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
  }
}

// 搜索
function handleSearch() {
  pagination.current = 1;
  fetchData();
}

// 重置
function handleReset() {
  searchForm.keyword = "";
  searchForm.status = "";
  searchForm.hostId = "";
  searchForm.game = "";
  handleSearch();
}

// 部署实例
function handleDeploy() {
  router.push("/instance/deploy");
}

// 查看详情
function handleDetail(row) {
  router.push(`/instance/detail/${row.id}`);
}

// 启动实例
async function handleStart(row) {
  try {
    row._loading = true;
    await startInstance(row.id);
    ElMessage.success("启动成功");
    fetchData();
  } catch (error) {
    console.error("Failed to start instance:", error);
  } finally {
    row._loading = false;
  }
}

// 停止实例
async function handleStop(row) {
  try {
    await ElMessageBox.confirm(
      `确定要停止实例「${row.instanceName}」吗？停止后玩家将无法连接服务器。`,
      "确认操作",
      {
        confirmButtonText: "确定停止",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
    row._loading = true;
    await stopInstance(row.id);
    ElMessage.success("停止成功");
    fetchData();
  } catch (error) {
    if (error !== "cancel") {
      console.error("Failed to stop instance:", error);
    }
  } finally {
    row._loading = false;
  }
}

// 重启实例
async function handleRestart(row) {
  try {
    await ElMessageBox.confirm(
      `确定要重启实例「${row.instanceName}」吗？`,
      "确认操作",
      {
        confirmButtonText: "确定重启",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
    row._loading = true;
    await restartInstance(row.id);
    ElMessage.success("重启成功");
    fetchData();
  } catch (error) {
    if (error !== "cancel") {
      console.error("Failed to restart instance:", error);
    }
  } finally {
    row._loading = false;
  }
}

// 删除实例
function handleDelete(row) {
  deleteTarget.value = row;
  deleteConfirmName.value = "";
  deleteDialogVisible.value = true;
}

// 确认删除
async function confirmDelete() {
  if (deleteConfirmName.value !== deleteTarget.value.instanceName) {
    ElMessage.warning("请输入正确的实例名称");
    return;
  }

  try {
    await deleteInstance(deleteTarget.value.id);
    ElMessage.success("删除成功");
    deleteDialogVisible.value = false;
    fetchData();
  } catch (error) {
    console.error("Failed to delete instance:", error);
  }
}

// 更多操作
function handleCommand(command, row) {
  switch (command) {
    case "restart":
      handleRestart(row);
      break;
    case "config":
      router.push(`/instance/detail/${row.id}?tab=config`);
      break;
    case "files":
      router.push(`/instance/detail/${row.id}?tab=files`);
      break;
    case "logs":
      router.push(`/instance/detail/${row.id}?tab=logs`);
      break;
    case "backup":
      router.push(`/instance/detail/${row.id}?tab=backup`);
      break;
    case "delete":
      handleDelete(row);
      break;
  }
}

// 分页变化
function handlePageChange(page) {
  pagination.current = page;
  fetchData();
}

function handleSizeChange(size) {
  pagination.pageSize = size;
  pagination.current = 1;
  fetchData();
}

// 表格行样式
function tableRowClassName({ row }) {
  if (row.status === "error") return "row-error";
  if (row.status === "stopped") return "row-stopped";
  return "";
}

// 获取可用操作
function getAvailableActions(status) {
  const actions = {
    running: [
      { command: "restart", label: "重启", icon: "RefreshRight" },
      { command: "config", label: "配置管理", icon: "Setting" },
      { command: "files", label: "文件管理", icon: "Folder" },
      { command: "logs", label: "查看日志", icon: "Tickets" },
      { command: "backup", label: "备份还原", icon: "Download" },
    ],
    stopped: [
      { command: "config", label: "配置管理", icon: "Setting" },
      { command: "files", label: "文件管理", icon: "Folder" },
      { command: "delete", label: "卸载实例", icon: "Delete", danger: true },
    ],
    error: [
      { command: "restart", label: "重启", icon: "RefreshRight" },
      { command: "logs", label: "查看日志", icon: "Tickets" },
      { command: "delete", label: "卸载实例", icon: "Delete", danger: true },
    ],
    installing: [{ command: "logs", label: "查看进度", icon: "Tickets" }],
    updating: [{ command: "logs", label: "查看进度", icon: "Tickets" }],
  };
  return actions[status] || [];
}

onMounted(() => {
  fetchHostOptions();
  fetchData().then(() => startAutoRefresh());
});

onBeforeUnmount(() => {
  stopAutoRefresh();
});
</script>

<template>
  <div class="instance-container">
    <!-- 搜索区域 -->
    <el-card class="search-card" shadow="never">
      <el-form :model="searchForm" inline>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="实例名称"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="主机">
          <el-select
            v-model="searchForm.hostId"
            placeholder="全部"
            clearable
            style="width: 140px"
          >
            <el-option
              v-for="item in hostOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="游戏">
          <el-select
            v-model="searchForm.game"
            placeholder="全部"
            clearable
            style="width: 140px"
          >
            <el-option
              v-for="item in gameOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="searchForm.status"
            placeholder="全部"
            clearable
            style="width: 120px"
          >
            <el-option label="运行中" value="running" />
            <el-option label="已停止" value="stopped" />
            <el-option label="异常" value="error" />
            <el-option label="安装中" value="installing" />
            <el-option label="更新中" value="updating" />
          </el-select>
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
          <span class="title">实例列表</span>
          <div class="header-actions">
            <el-button @click="fetchData">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
            <el-button type="primary" @click="handleDeploy">
              <el-icon><Plus /></el-icon>
              部署实例
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="tableData"
        style="width: 100%"
        :row-class-name="tableRowClassName"
      >
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <div class="status-cell">
              <el-icon :color="statusColor(row.status)" :size="16">
                <component
                  :is="statusIcon(row.status)"
                  :class="{ 'is-loading': ACTIVE_STATUSES.includes(row.status) }"
                />
              </el-icon>
              <el-tag
                :type="statusType(row.status)"
                size="small"
                effect="plain"
              >
                {{ row.runStatusDesc }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="实例名称" min-width="150">
          <template #default="{ row }">
            <el-link type="primary" @click="handleDetail(row)">
              {{ row.instanceName }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="游戏" min-width="160">
          <template #default="{ row }">
            <div class="game-name-cell">
              <el-avatar
                v-if="row.iconUrl"
                :src="row.iconUrl"
                :size="28"
                shape="square"
              />
              <el-avatar v-else :size="28" shape="square">
                <el-icon><Grid /></el-icon>
              </el-avatar>
              <span class="game-name-text">{{ row.gameName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="hostName" label="主机" width="120" />
        <el-table-column label="端口" width="120">
          <template #default="{ row }">
            <span>{{ row.portConfig?.game || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="玩家数" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.status === 'running'"
              >{{ row.onlinePlayers || 0 }} / {{ row.configInfo?.maxPlayers || 0 }}</span
            >
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <!-- 运行中状态 -->
              <template v-if="row.status === 'running'">
                <el-button type="warning" link size="small" :loading="row._loading" @click="handleStop(row)">停止</el-button>
                <el-button type="primary" link size="small" @click="handleViewLogs(row)">查看日志</el-button>
              </template>
              <!-- 已停止状态 -->
              <template v-else-if="row.status === 'stopped'">
                <el-button type="success" link size="small" :loading="row._loading" @click="handleStart(row)">启动</el-button>
                <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
              </template>
              <!-- 异常状态 -->
              <template v-else-if="row.status === 'error'">
                <el-button type="warning" link size="small" @click="handleRetryDeploy(row)">重试</el-button>
                <el-button type="primary" link size="small" @click="handleViewLogs(row)">查看日志</el-button>
                <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
              </template>
              <!-- 活跃过渡态（安装中/更新中/启动中/停止中） -->
              <template v-else-if="ACTIVE_STATUSES.includes(row.status)">
                <el-button type="primary" link size="small" @click="handleViewLogs(row)">
                  <el-icon class="is-loading"><Loading /></el-icon>
                  查看日志
                </el-button>
              </template>
              <!-- 其他状态 -->
              <template v-else>
                <el-tag type="info" size="small">{{ row.runStatusDesc }}</el-tag>
              </template>
              <!-- 更多操作下拉 -->
              <el-dropdown v-if="getAvailableActions(row.status).length > 0" trigger="click" @command="(cmd) => handleCommand(cmd, row)">
                <el-button type="primary" link size="small">更多<el-icon><ArrowDown /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="action in getAvailableActions(row.status)" :key="action.command" :command="action.command" :class="{ 'dropdown-danger': action.danger }">
                      <el-icon><component :is="action.icon" /></el-icon>
                      {{ action.label }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- 删除确认弹窗 -->
    <el-dialog
      v-model="deleteDialogVisible"
      title="卸载实例"
      width="420px"
      :close-on-click-modal="false"
    >
      <div class="delete-confirm-content">
        <el-icon class="danger-icon" :size="32" color="#F56C6C"
          ><Warning
        /></el-icon>
        <p class="confirm-text">
          确定要卸载实例「{{ deleteTarget?.instanceName }}」吗？
        </p>
        <p class="confirm-desc">
          卸载将删除实例所有配置、进程、相关文件，此操作不可恢复。
        </p>
        <div class="confirm-input">
          <p>请输入实例名称以确认卸载：</p>
          <el-input v-model="deleteConfirmName" placeholder="请输入实例名称" />
        </div>
      </div>
      <template #footer>
        <el-button @click="deleteDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="deleteConfirmName !== deleteTarget?.instanceName"
          @click="confirmDelete"
        >
          确定卸载
        </el-button>
      </template>
    </el-dialog>

    <!-- 日志查看弹窗（DeployProgress 组件自带 el-dialog，无需外层嵌套） -->
    <DeployProgress
      v-model:visible="logDialogVisible"
      :instance-id="currentLogInstanceId"
      :mode="currentLogMode"
    />
  </div>
</template>

<style lang="scss" scoped>
.instance-container {
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
  }

  .pagination-wrapper {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
}

.status-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.game-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;

  .game-name-text {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.action-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.text-muted {
  color: var(--el-text-color-placeholder);
}

// 表格行样式
:deep(.row-error) {
  background-color: var(--platform-bg-error-row) !important;
}

:deep(.row-stopped) {
  opacity: 0.7;
}

// 下拉菜单危险操作样式
.dropdown-danger {
  color: var(--el-color-danger) !important;

  .el-icon {
    color: var(--el-color-danger) !important;
  }
}

// 删除确认弹窗
.delete-confirm-content {
  text-align: center;

  .danger-icon {
    margin-bottom: 16px;
  }

  .confirm-text {
    font-size: var(--platform-font-size-base);
    color: var(--el-text-color-primary);
    margin-bottom: 8px;
  }

  .confirm-desc {
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-secondary);
    margin-bottom: 16px;
    line-height: 1.6;
  }

  .confirm-input {
    text-align: left;

    p {
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-regular);
      margin-bottom: 8px;
    }
  }
}

// 响应式适配
@media screen and (max-width: 1366px) {
  .search-card {
    :deep(.el-form-item) {
      margin-bottom: 12px;
    }
  }
}
</style>
