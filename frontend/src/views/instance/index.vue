<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
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
const lastRefreshAt = ref("等待编队同步");

const runningInstanceCount = computed(
  () => tableData.value.filter((row) => row.status === "running").length,
);
const transitionInstanceCount = computed(
  () => tableData.value.filter((row) => ACTIVE_STATUSES.includes(row.status)).length,
);
const attentionInstanceCount = computed(
  () => tableData.value.filter((row) => row.status === "error").length,
);
const onlinePlayerCount = computed(
  () =>
    tableData.value.reduce(
      (total, row) => total + (row.status === "running" ? Number(row.onlinePlayers || 0) : 0),
      0,
    ),
);
const lifecycleSegments = computed(() => [
  { key: "running", label: "运行中", value: runningInstanceCount.value, tone: "running" },
  { key: "transition", label: "过渡中", value: transitionInstanceCount.value, tone: "transition" },
  { key: "error", label: "需处置", value: attentionInstanceCount.value, tone: "error" },
  {
    key: "stopped",
    label: "已停止",
    value: tableData.value.filter((row) => row.status === "stopped").length,
    tone: "stopped",
  },
]);

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
    lastRefreshAt.value = formatRefreshTime();
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

function getPlayerLoad(row) {
  if (row.status !== "running") return 0;
  const online = Number(row.onlinePlayers || 0);
  const max = Number(row.configInfo?.maxPlayers || 0);
  return max > 0 ? Math.min(100, Math.round((online / max) * 100)) : 0;
}

function getPlayerLoadTone(row) {
  const load = getPlayerLoad(row);
  if (load >= 85) return "danger";
  if (load >= 65) return "warning";
  return "normal";
}

function formatRefreshTime() {
  return new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
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
  <div class="instance-container instance-command-page">
    <section class="instance-hero">
      <div class="hero-copy">
        <span class="section-kicker">INSTANCE COMMAND / SERVICE FLEET</span>
        <h1>服务编队</h1>
        <p>从实例生命周期、玩家负载和部署来源判断服务状态，再进入详情或执行运行编排。</p>
      </div>
      <div class="hero-actions">
        <div class="hero-status">
          <span class="fleet-pulse" aria-hidden="true"></span>
          <div>
            <strong>编队监控中</strong>
            <small>自动刷新 · 上次同步 {{ lastRefreshAt }}</small>
          </div>
        </div>
        <el-button type="primary" @click="handleDeploy">
          <el-icon><Plus /></el-icon>
          部署实例
        </el-button>
      </div>
    </section>

    <section class="lifecycle-rail" aria-label="实例生命周期态势">
      <div class="rail-intro">
        <span class="section-kicker">SERVICE FLEET</span>
        <strong>运行编队</strong>
        <small>实例正在经历什么</small>
      </div>
      <div v-for="segment in lifecycleSegments" :key="segment.key" class="life-segment" :class="`is-${segment.tone}`">
        <span>{{ segment.label }}</span>
        <strong>{{ segment.value }}</strong>
      </div>
      <div class="player-segment">
        <span>玩家在线</span>
        <strong>{{ onlinePlayerCount }}</strong>
        <small>活跃服务负载</small>
      </div>
    </section>

    <section class="instance-filter-panel" aria-label="实例编队筛选">
      <div class="panel-heading filter-heading">
        <div>
          <span class="section-kicker">FILTER / LIFECYCLE</span>
          <h2>编队筛选</h2>
        </div>
        <span class="filter-hint">按服务、游戏类型、宿主机或生命周期定位实例</span>
      </div>
      <el-form class="instance-filter-form" :model="searchForm" inline>
        <el-form-item label="实例名称">
          <el-input v-model="searchForm.keyword" placeholder="例如：生存服-01" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="宿主机">
          <el-select v-model="searchForm.hostId" placeholder="全部宿主机" clearable>
            <el-option v-for="item in hostOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="游戏类型">
          <el-select v-model="searchForm.game" placeholder="全部游戏" clearable>
            <el-option v-for="item in gameOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="生命周期">
          <el-select v-model="searchForm.status" placeholder="全部状态" clearable>
            <el-option label="运行中" value="running" />
            <el-option label="已停止" value="stopped" />
            <el-option label="异常" value="error" />
            <el-option label="安装中" value="installing" />
            <el-option label="更新中" value="updating" />
          </el-select>
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            应用条件
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="fleet-panel" aria-label="实例编队清单">
      <div class="panel-heading fleet-heading">
        <div>
          <span class="section-kicker">MANAGED SERVICES</span>
          <h2>实例编队</h2>
          <p>{{ pagination.total }} 个服务单元 · 活跃实例每 5 秒同步</p>
        </div>
        <div class="fleet-actions">
          <el-button @click="fetchData">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-button type="primary" @click="handleDeploy">
            <el-icon><Plus /></el-icon>
            新增编队
          </el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        class="fleet-table"
        :data="tableData"
        style="width: 100%"
        :row-class-name="tableRowClassName"
      >
        <el-table-column label="生命周期" width="112">
          <template #default="{ row }">
            <div class="lifecycle-pill" :class="`is-${row.status}`">
              <el-icon :class="{ 'is-loading': ACTIVE_STATUSES.includes(row.status) }"><component :is="statusIcon(row.status)" /></el-icon>
              <span>{{ row.runStatusDesc }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="服务单元" min-width="230">
          <template #default="{ row }">
            <div class="service-cell">
              <span class="service-icon"><el-icon><Grid /></el-icon></span>
              <div>
                <button class="service-name-button" type="button" @click="handleDetail(row)">{{ row.instanceName }}</button>
                <div class="service-meta">
                  <span>{{ row.gameName }}</span>
                  <i></i>
                  <span>{{ row.hostName }}</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="玩家负载" min-width="180">
          <template #default="{ row }">
            <div v-if="row.status === 'running'" class="player-load" :class="`is-${getPlayerLoadTone(row)}`">
              <div class="player-load-heading">
                <strong>{{ row.onlinePlayers || 0 }} / {{ row.configInfo?.maxPlayers || 0 }}</strong>
                <span>{{ getPlayerLoad(row) }}%</span>
              </div>
              <el-progress :percentage="getPlayerLoad(row)" :stroke-width="5" :show-text="false" :color="statusColor(row.status)" />
            </div>
            <span v-else class="load-unavailable">{{ row.runStatusDesc }} · 无在线玩家</span>
          </template>
        </el-table-column>
        <el-table-column label="服务端点" min-width="128">
          <template #default="{ row }">
            <div class="endpoint-cell">
              <strong>{{ row.portConfig?.game || "—" }}</strong>
              <span>RCON {{ row.portConfig?.rcon || "—" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="部署来源" min-width="140">
          <template #default="{ row }">
            <div class="deployment-cell">
              <strong>{{ row.deployType || "未知" }}</strong>
              <span>{{ row.hostIp || row.hostName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="运行编排" width="258" fixed="right">
          <template #default="{ row }">
            <div class="service-actions">
              <template v-if="row.status === 'running'">
                <el-button type="warning" link size="small" :loading="row._loading" @click="handleStop(row)">停止</el-button>
                <el-button type="primary" link size="small" @click="handleViewLogs(row)">日志</el-button>
              </template>
              <template v-else-if="row.status === 'stopped'">
                <el-button type="success" link size="small" :loading="row._loading" @click="handleStart(row)">启动</el-button>
                <el-button type="danger" link size="small" @click="handleDelete(row)">卸载</el-button>
              </template>
              <template v-else-if="row.status === 'error'">
                <el-button type="warning" link size="small" @click="handleRetryDeploy(row)">重试</el-button>
                <el-button type="primary" link size="small" @click="handleViewLogs(row)">日志</el-button>
                <el-button type="danger" link size="small" @click="handleDelete(row)">卸载</el-button>
              </template>
              <template v-else-if="ACTIVE_STATUSES.includes(row.status)">
                <el-button type="primary" link size="small" @click="handleViewLogs(row)"><el-icon class="is-loading"><Loading /></el-icon> 进度</el-button>
              </template>
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
        <template #empty>
          <div class="fleet-empty-state">
            <el-icon><Grid /></el-icon>
            <strong>暂无匹配服务</strong>
            <span>调整编队条件或部署一个新的游戏实例</span>
          </div>
        </template>
      </el-table>

      <div class="fleet-footer">
        <span class="fleet-footer-note"><i class="fleet-pulse" aria-hidden="true"></i> 生命周期监控已接入</span>
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
    </section>

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

<style lang="scss" scoped>
.instance-command-page {
  --instance-gap: 14px;
  --instance-accent: #b58cff;
  --instance-accent-soft: rgba(181, 140, 255, 0.1);
  --instance-player: #7de2b1;
  padding: 4px 2px 24px;
  color: var(--platform-text-primary);
}

.instance-hero,
.lifecycle-rail,
.instance-filter-panel,
.fleet-panel {
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-1);
}

.instance-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  min-height: 138px;
  padding: 24px 26px;
  border-radius: 6px;
  background:
    linear-gradient(118deg, rgba(181, 140, 255, 0.14), transparent 46%),
    var(--platform-surface-1);
  box-shadow: 0 14px 28px rgba(0, 0, 0, 0.14);
}

.section-kicker {
  display: block;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  line-height: 1.4;
}

.hero-copy h1 {
  margin: 8px 0 7px;
  color: var(--platform-text-primary);
  font-size: clamp(24px, 2.4vw, 32px);
  font-weight: 700;
  letter-spacing: -0.03em;
}

.hero-copy p {
  max-width: 620px;
  margin: 0;
  color: var(--platform-text-secondary);
  font-size: 13px;
}

.hero-actions,
.hero-status,
.panel-heading,
.fleet-actions,
.service-cell,
.player-load-heading,
.service-actions,
.fleet-footer {
  display: flex;
  align-items: center;
}

.hero-actions {
  gap: 20px;
}

.hero-status {
  gap: 10px;

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 13px;
  }

  small {
    margin-top: 3px;
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.fleet-pulse {
  display: inline-block;
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--instance-accent);
  box-shadow: 0 0 0 3px var(--instance-accent-soft);
}

.lifecycle-rail {
  display: grid;
  grid-template-columns: minmax(200px, 1.4fr) repeat(4, minmax(78px, 0.7fr)) minmax(110px, 0.8fr);
  align-items: stretch;
  gap: 0;
  min-height: 78px;
  margin-top: var(--instance-gap);
  padding: 12px 18px;
  border-radius: 5px;
  background:
    linear-gradient(90deg, rgba(181, 140, 255, 0.04), transparent 50%),
    var(--platform-surface-2);
}

.rail-intro,
.life-segment,
.player-segment {
  display: grid;
  align-content: center;
  gap: 4px;
}

.rail-intro {
  strong {
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 600;
  }

  small {
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.life-segment,
.player-segment {
  position: relative;
  padding: 0 15px;
  border-left: 1px solid var(--platform-line);

  &::before {
    position: absolute;
    top: 14px;
    left: -1px;
    width: 2px;
    height: 28px;
    background: var(--platform-text-muted);
    content: "";
  }

  > span,
  > small {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  > strong {
    color: var(--platform-text-primary);
    font-size: 18px;
    font-weight: 650;
  }

  &.is-running {
    &::before {
      background: var(--platform-green);
    }
  }

  &.is-transition {
    &::before {
      background: var(--platform-amber);
    }
  }

  &.is-error {
    &::before {
      background: var(--platform-red);
    }

    > strong {
      color: var(--platform-red);
    }
  }
}

.player-segment {
  &::before {
    background: var(--instance-player);
  }

  > strong {
    color: var(--instance-player);
  }
}

.instance-filter-panel,
.fleet-panel {
  margin-top: var(--instance-gap);
  border-radius: 5px;
  overflow: hidden;
}

.instance-filter-panel {
  padding: 17px 18px 5px;
  background: var(--platform-surface-1);
}

.panel-heading {
  justify-content: space-between;
  gap: 16px;
}

.panel-heading h2 {
  margin: 5px 0 0;
  color: var(--platform-text-primary);
  font-size: 16px;
  font-weight: 650;
}

.filter-hint,
.fleet-heading p {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.instance-filter-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 13px;

  :deep(.el-form-item) {
    margin-right: 0;
    margin-bottom: 10px;
  }

  :deep(.el-form-item__label) {
    padding-bottom: 5px;
    color: var(--platform-text-muted);
    font-size: 11px;
    line-height: 1.3;
  }

  :deep(.el-input),
  :deep(.el-select) {
    width: 160px;
  }

  :deep(.el-input) {
    width: 190px;
  }

  .filter-actions {
    margin-left: auto;
  }
}

.fleet-panel {
  background: var(--platform-surface-1);
}

.fleet-heading {
  padding: 17px 18px 15px;
  border-bottom: 1px solid var(--platform-line);
}

.fleet-heading p {
  margin: 5px 0 0;
}

.fleet-actions {
  gap: 8px;
}

.fleet-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(181, 140, 255, 0.06);
  --el-table-header-bg-color: rgba(255, 255, 255, 0.015);
  --el-table-border-color: var(--platform-line);
  --el-table-text-color: var(--platform-text-regular);
  --el-table-header-text-color: var(--platform-text-muted);

  :deep(.el-table__inner-wrapper::before) {
    display: none;
  }

  :deep(.el-table__header-wrapper th.el-table__cell) {
    height: 42px;
    background: rgba(255, 255, 255, 0.015);
    color: var(--platform-text-muted);
    font-size: 11px;
    font-weight: 500;
  }

  :deep(.el-table__body-wrapper td.el-table__cell) {
    height: 82px;
    padding: 10px 0;
    border-bottom-color: var(--platform-line);
  }

  :deep(.el-table__body tr:last-child td.el-table__cell) {
    border-bottom: 0;
  }

  :deep(.el-table__body tr.row-error) {
    background: rgba(235, 87, 87, 0.045);
  }

  :deep(.el-table__body tr.row-stopped) {
    opacity: 0.66;
  }
}

.lifecycle-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--platform-text-muted);
  font-size: 11px;

  .el-icon {
    font-size: 15px;
  }

  &.is-running {
    color: var(--platform-green);
  }

  &.is-starting,
  &.is-stopping,
  &.is-installing,
  &.is-updating {
    color: var(--platform-amber);
  }

  &.is-error {
    color: var(--platform-red);
  }
}

.service-cell {
  gap: 10px;
  min-width: 0;
}

.service-icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgba(181, 140, 255, 0.48);
  border-radius: 6px;
  color: var(--instance-accent);
  background: var(--instance-accent-soft);
}

.service-name-button {
  max-width: 170px;
  padding: 0;
  overflow: hidden;
  border: 0;
  color: var(--platform-text-primary);
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;

  &:hover,
  &:focus-visible {
    color: var(--instance-accent);
    outline: none;
  }
}

.service-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  color: var(--platform-text-muted);
  font-size: 10px;

  span {
    max-width: 105px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  i {
    width: 3px;
    height: 3px;
    flex: 0 0 auto;
    border-radius: 50%;
    background: var(--platform-text-muted);
  }
}

.player-load {
  display: grid;
  gap: 7px;
  min-width: 135px;

  &.is-danger .player-load-heading strong,
  &.is-danger .player-load-heading span {
    color: var(--platform-red);
  }

  &.is-warning .player-load-heading span {
    color: var(--platform-amber);
  }

  :deep(.el-progress-bar__outer) {
    background: var(--platform-surface-3);
  }
}

.player-load-heading {
  justify-content: space-between;

  strong {
    color: var(--instance-player);
    font-family: var(--el-font-family-mono);
    font-size: 12px;
    font-weight: 600;
  }

  span {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
  }
}

.load-unavailable,
.endpoint-cell span,
.deployment-cell span {
  color: var(--platform-text-muted);
  font-size: 10px;
}

.endpoint-cell,
.deployment-cell {
  display: grid;
  gap: 4px;

  strong {
    color: var(--platform-text-primary);
    font-family: var(--el-font-family-mono);
    font-size: 12px;
    font-weight: 550;
  }
}

.deployment-cell {
  strong {
    color: var(--instance-accent);
    font-family: inherit;
    font-size: 11px;
    text-transform: uppercase;
  }
}

.service-actions {
  flex-wrap: wrap;
  gap: 2px 8px;

  :deep(.el-button) {
    margin-left: 0;
    padding: 2px 0;
    color: var(--instance-accent);
    font-size: 11px;
  }

  :deep(.el-button:hover:not(.is-disabled)) {
    color: var(--platform-text-primary);
  }

  :deep(.el-button--warning) {
    color: var(--platform-amber);
  }

  :deep(.el-button--success) {
    color: var(--platform-green);
  }

  :deep(.el-button--danger),
  :deep(.dropdown-danger) {
    color: var(--platform-red);
  }
}

.fleet-footer {
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 10px 18px;
  border-top: 1px solid var(--platform-line);
}

.fleet-footer-note {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--platform-text-muted);
  font-size: 11px;
}

.fleet-footer-note .fleet-pulse {
  width: 7px;
  height: 7px;
}

.fleet-empty-state {
  display: grid;
  justify-items: center;
  gap: 7px;
  padding: 36px 0;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--instance-accent);
    font-size: 26px;
  }

  strong {
    color: var(--platform-text-primary);
    font-size: 13px;
  }

  span {
    font-size: 11px;
  }
}

@media screen and (max-width: 1180px) {
  .lifecycle-rail {
    grid-template-columns: minmax(170px, 1.3fr) repeat(4, minmax(68px, 0.7fr)) minmax(90px, 0.8fr);
  }

  .instance-filter-form {
    flex-wrap: wrap;

    .filter-actions {
      margin-left: 0;
    }
  }

  .fleet-table {
    :deep(.el-table__body-wrapper),
    :deep(.el-table__header-wrapper) {
      overflow-x: auto;
    }
  }
}

@media screen and (max-width: 780px) {
  .instance-hero,
  .panel-heading,
  .fleet-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .instance-hero {
    gap: 18px;
    padding: 20px;
  }

  .hero-actions {
    width: 100%;
    justify-content: space-between;
  }

  .lifecycle-rail {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px 0;
    padding: 16px;
  }

  .rail-intro {
    grid-column: 1 / -1;
  }

  .life-segment,
  .player-segment {
    padding: 0 12px;

    &:nth-of-type(2n + 1) {
      border-left: 0;
    }
  }

  .instance-filter-panel {
    padding: 16px 14px 4px;
  }

  .filter-hint {
    display: none;
  }

  .instance-filter-form {
    display: grid;
    grid-template-columns: 1fr 1fr;

    :deep(.el-input),
    :deep(.el-select) {
      width: 100%;
    }

    .filter-actions {
      grid-column: 1 / -1;
    }
  }

  .fleet-heading {
    padding: 16px 14px;
  }

  .fleet-actions {
    width: 100%;
  }
}
</style>
