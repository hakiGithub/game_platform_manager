<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { getHostList, getHostResources } from "@/api/host";
import {
  getInstanceList,
  startInstance,
  stopInstance,
  restartInstance,
} from "@/api/instance";
import { getOperationLogs } from "@/api/system";
import { statusType } from "@/utils/instanceStatus";

const router = useRouter();

// 统计数据
const statistics = ref([
  {
    title: "主机状态",
    key: "hostStatus",
    value: "0/0",
    icon: "Monitor",
    color: "#409EFF",
    status: "",
    route: "/host/list",
  },
  {
    title: "实例状态",
    key: "instanceStatus",
    value: "0/0",
    icon: "Grid",
    color: "#67C23A",
    status: "",
    route: "/instance/list",
  },
  {
    title: "异常实例",
    key: "errorInstances",
    value: 0,
    icon: "Warning",
    color: "#F56C6C",
    status: "",
    route: "/instance/list?status=2",
  },
  {
    title: "今日备份",
    key: "todayBackups",
    value: 0,
    icon: "Download",
    color: "#E6A23C",
    status: "",
    route: "/instance/list",
  },
]);

// 资源使用率Top5
const resourceTop5 = ref([]);

// 实例状态分布
const instanceStatusData = ref({
  running: 0,
  stopped: 0,
  error: 0,
});

// 实例列表
const instanceList = ref([]);
const instanceLoading = ref(false);

// 操作日志
const operationLogs = ref([]);

// 自动刷新定时器
let refreshTimer = null;

// 加载状态
const loading = ref(false);

// 获取仪表盘数据
async function fetchDashboardData() {
  loading.value = true;
  try {
    // 并行请求
    const [hosts, instances] = await Promise.all([
      getHostList({ size: 100 }).catch(() => ({ records: [], total: 0 })),
      getInstanceList({ size: 100 }).catch(() => ({ records: [], total: 0 })),
    ]);

    // 处理主机数据 - 状态: 0-离线，1-在线
    const hostList = hosts.records || [];
    const onlineHosts = hostList.filter((h) => h.status === 1).length;
    statistics.value[0].value = `${onlineHosts}/${hostList.length}`;
    statistics.value[0].status =
      onlineHosts === hostList.length
        ? "success"
        : onlineHosts > 0
          ? "warning"
          : "danger";

    // 处理实例数据 - 后端 status 为 wireKey 字符串：running/stopped/error/starting/stopping/installing/updating
    const instancesList = instances.records || [];
    const runningInstances = instancesList.filter((i) => i.status === "running").length;
    const errorInstances = instancesList.filter((i) => i.status === "error").length;
    const stoppedInstances = instancesList.filter((i) => i.status === "stopped").length;

    statistics.value[1].value = `${runningInstances}/${instancesList.length}`;
    statistics.value[1].status =
      runningInstances === instancesList.length
        ? "success"
        : runningInstances > 0
          ? "warning"
          : "info";

    statistics.value[2].value = errorInstances;
    statistics.value[2].status = errorInstances > 0 ? "danger" : "success";

    // 实例状态分布
    instanceStatusData.value = {
      running: runningInstances,
      stopped: stoppedInstances,
      error: errorInstances,
    };

    // 实例列表（显示前5条）
    instanceList.value = instancesList.slice(0, 5);

    // 获取主机资源Top5
    await fetchResourceTop5(hostList);

    // 获取操作日志
    await fetchOperationLogs();
  } catch (error) {
    console.error("Failed to fetch dashboard data:", error);
  } finally {
    loading.value = false;
  }
}

// 获取资源使用率Top5
async function fetchResourceTop5(hostList) {
  const onlineHosts = hostList.filter((h) => h.status === 1);
  const resourceData = [];

  for (const host of onlineHosts.slice(0, 5)) {
    try {
      const resources = await getHostResources(host.id).catch(() => null);
      if (resources) {
        resourceData.push({
          name: host.name,
          cpu: resources.cpu?.usage || 0,
          memory: resources.memory?.usage || 0,
          disk: resources.disk?.usage || 0,
        });
      }
    } catch (e) {
      // ignore
    }
  }

  // 按CPU排序
  resourceTop5.value = resourceData.sort((a, b) => b.cpu - a.cpu);
}

// 获取操作日志
async function fetchOperationLogs() {
  try {
    const data = await getOperationLogs({ size: 5 });
    operationLogs.value = (data.records || []).map((log) => ({
      time: log.operationTime,
      operator: log.operatorName,
      action: log.operationContent,
      result: log.responseStatus === 1 ? "success" : "fail",
    }));
  } catch (error) {
    console.error("Failed to fetch operation logs:", error);
  }
}

// 获取进度条颜色
function getProgressColor(value) {
  if (value >= 80) return "var(--el-color-danger)";
  if (value >= 60) return "var(--el-color-warning)";
  return "var(--el-color-success)";
}

// 从 portConfig 中提取主端口用于表格展示
function formatPort(portConfig) {
  if (!portConfig || typeof portConfig !== "object") return "-";
  const keys = Object.keys(portConfig);
  if (keys.length === 0) return "-";
  // 优先取常见主端口键
  const mainKey = keys.find((k) =>
    /port/i.test(k) && !/container|name/i.test(k)
  ) || keys[0];
  const v = portConfig[mainKey];
  return v != null ? v : "-";
}

// 点击统计卡片
function handleStatClick(stat) {
  router.push(stat.route);
}

// 查看实例详情
function handleInstanceDetail(row) {
  router.push(`/instance/detail/${row.id}`);
}

// 启动实例
async function handleStart(row) {
  try {
    row._loading = true;
    await startInstance(row.id);
    ElMessage.success("启动成功");
    fetchDashboardData();
  } catch (error) {
    console.error("Failed to start instance:", error);
  } finally {
    row._loading = false;
  }
}

// 停止实例
async function handleStop(row) {
  try {
    row._loading = true;
    await stopInstance(row.id);
    ElMessage.success("停止成功");
    fetchDashboardData();
  } catch (error) {
    console.error("Failed to stop instance:", error);
  } finally {
    row._loading = false;
  }
}

// 重启实例
async function handleRestart(row) {
  try {
    row._loading = true;
    await restartInstance(row.id);
    ElMessage.success("重启成功");
    fetchDashboardData();
  } catch (error) {
    console.error("Failed to restart instance:", error);
  } finally {
    row._loading = false;
  }
}

// 刷新数据
function handleRefresh() {
  fetchDashboardData();
}

// 定时刷新
function startAutoRefresh() {
  refreshTimer = setInterval(() => {
    fetchDashboardData();
  }, 30000); // 30秒刷新一次
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

onMounted(() => {
  fetchDashboardData();
  startAutoRefresh();
});

onBeforeUnmount(() => {
  stopAutoRefresh();
});
</script>

<template>
  <div v-loading="loading" class="dashboard-container">
    <!-- 统计卡片 -->
    <el-row :gutter="20" class="statistics-row">
      <el-col
        v-for="item in statistics"
        :key="item.key"
        :xs="24"
        :sm="12"
        :md="6"
      >
        <el-card
          class="statistic-card"
          shadow="hover"
          @click="handleStatClick(item)"
        >
          <div class="statistic-content">
            <div
              class="statistic-icon"
              :style="{ backgroundColor: item.color }"
            >
              <el-icon :size="28"><component :is="item.icon" /></el-icon>
            </div>
            <div class="statistic-info">
              <div class="statistic-value">{{ item.value }}</div>
              <div class="statistic-title">{{ item.title }}</div>
            </div>
          </div>
          <div v-if="item.status" class="statistic-footer">
            <el-tag :type="item.status" size="small" effect="plain">
              {{
                item.status === "success"
                  ? "正常"
                  : item.status === "warning"
                    ? "注意"
                    : item.status === "danger"
                      ? "异常"
                      : ""
              }}
            </el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 数据展示行 -->
    <el-row :gutter="20" class="data-row">
      <!-- 主机资源 Top5 -->
      <el-col :xs="24" :lg="14">
        <el-card class="resource-card">
          <template #header>
            <div class="card-header">
              <span class="title">
                <el-icon><TrendCharts /></el-icon>
                主机资源 Top5
              </span>
              <el-button
                type="primary"
                link
                size="small"
                @click="handleRefresh"
              >
                <el-icon><Refresh /></el-icon>
                刷新
              </el-button>
            </div>
          </template>

          <div v-if="resourceTop5.length > 0" class="resource-list">
            <div
              v-for="(item, index) in resourceTop5"
              :key="index"
              class="resource-item"
            >
              <div class="resource-header">
                <span class="resource-name">{{ item.name }}</span>
                <span class="resource-rank">#{{ index + 1 }}</span>
              </div>
              <div class="resource-bars">
                <div class="bar-item">
                  <span class="bar-label">CPU</span>
                  <el-progress
                    :percentage="Math.round(item.cpu)"
                    :stroke-width="6"
                    :color="getProgressColor(item.cpu)"
                  />
                  <span class="bar-value">{{ Math.round(item.cpu) }}%</span>
                </div>
                <div class="bar-item">
                  <span class="bar-label">内存</span>
                  <el-progress
                    :percentage="Math.round(item.memory)"
                    :stroke-width="6"
                    :color="getProgressColor(item.memory)"
                  />
                  <span class="bar-value">{{ Math.round(item.memory) }}%</span>
                </div>
                <div class="bar-item">
                  <span class="bar-label">磁盘</span>
                  <el-progress
                    :percentage="Math.round(item.disk)"
                    :stroke-width="6"
                    :color="getProgressColor(item.disk)"
                  />
                  <span class="bar-value">{{ Math.round(item.disk) }}%</span>
                </div>
              </div>
            </div>
          </div>
          <el-empty v-else description="暂无在线主机" :image-size="100" />
        </el-card>
      </el-col>

      <!-- 实例状态分布 -->
      <el-col :xs="24" :lg="10">
        <el-card class="status-card">
          <template #header>
            <div class="card-header">
              <span class="title">
                <el-icon><PieChart /></el-icon>
                实例状态分布
              </span>
            </div>
          </template>

          <div class="status-content">
            <!-- 状态统计 -->
            <div class="status-stats">
              <div class="status-item">
                <div class="status-icon running">
                  <el-icon><CircleCheck /></el-icon>
                </div>
                <div class="status-info">
                  <span class="status-value">{{
                    instanceStatusData.running
                  }}</span>
                  <span class="status-label">运行中</span>
                </div>
              </div>
              <div class="status-item">
                <div class="status-icon stopped">
                  <el-icon><CircleClose /></el-icon>
                </div>
                <div class="status-info">
                  <span class="status-value">{{
                    instanceStatusData.stopped
                  }}</span>
                  <span class="status-label">已停止</span>
                </div>
              </div>
              <div class="status-item">
                <div class="status-icon error">
                  <el-icon><Warning /></el-icon>
                </div>
                <div class="status-info">
                  <span class="status-value">{{
                    instanceStatusData.error
                  }}</span>
                  <span class="status-label">异常</span>
                </div>
              </div>
            </div>

            <!-- 总计 -->
            <div class="status-total">
              <div class="total-item">
                <span class="total-label">实例总数</span>
                <span class="total-value">{{
                  instanceStatusData.running +
                  instanceStatusData.stopped +
                  instanceStatusData.error
                }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 实例运行状态 -->
    <el-row :gutter="20" class="instance-row">
      <el-col :span="24">
        <el-card class="instance-card">
          <template #header>
            <div class="card-header">
              <span class="title">
                <el-icon><Grid /></el-icon>
                实例运行状态
              </span>
              <el-button
                type="primary"
                link
                @click="router.push('/instance/list')"
              >
                查看全部
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </div>
          </template>

          <el-table
            :data="instanceList"
            style="width: 100%"
            empty-text="暂无实例"
          >
            <el-table-column label="实例名称" min-width="150">
              <template #default="{ row }">
                <el-link type="primary" @click="handleInstanceDetail(row)">
                  {{ row.instanceName || row.name }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column label="游戏" width="140">
              <template #default="{ row }">
                <div class="game-cell">
                  <el-avatar
                    v-if="row.iconUrl"
                    :src="row.iconUrl"
                    :size="24"
                    shape="square"
                  />
                  <el-avatar v-else :size="24" shape="square">
                    <el-icon><Grid /></el-icon>
                  </el-avatar>
                  <span>{{ row.gameName }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="hostName" label="主机" width="120" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">
                  {{ row.runStatusDesc }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="端口" width="100">
              <template #default="{ row }">
                {{ formatPort(row.portConfig) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'running'">
                  <el-button
                    type="warning"
                    link
                    size="small"
                    :loading="row._loading"
                    @click="handleStop(row)"
                  >
                    停止
                  </el-button>
                  <el-button
                    type="info"
                    link
                    size="small"
                    :loading="row._loading"
                    @click="handleRestart(row)"
                  >
                    重启
                  </el-button>
                </template>
                <template v-else-if="row.status === 'stopped'">
                  <el-button
                    type="success"
                    link
                    size="small"
                    :loading="row._loading"
                    @click="handleStart(row)"
                  >
                    启动
                  </el-button>
                </template>
                <template v-else-if="row.status === 'error'">
                  <el-button
                    type="warning"
                    link
                    size="small"
                    :loading="row._loading"
                    @click="handleRestart(row)"
                  >
                    重启
                  </el-button>
                </template>
                <template v-else>
                  <el-tag type="info" size="small">{{
                    row.runStatusDesc
                  }}</el-tag>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近操作日志 -->
    <el-row :gutter="20" class="log-row">
      <el-col :span="24">
        <el-card class="log-card">
          <template #header>
            <div class="card-header">
              <span class="title">
                <el-icon><Tickets /></el-icon>
                最近操作日志
              </span>
              <el-button
                type="primary"
                link
                @click="router.push('/system/logs')"
              >
                查看全部
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </div>
          </template>

          <el-table
            :data="operationLogs"
            style="width: 100%"
            empty-text="暂无日志记录"
          >
            <el-table-column prop="time" label="操作时间" width="180" />
            <el-table-column prop="operator" label="操作人" width="100" />
            <el-table-column prop="action" label="操作内容" min-width="300" />
            <el-table-column label="结果" width="100">
              <template #default="{ row }">
                <el-tag
                  :type="row.result === 'success' ? 'success' : 'danger'"
                  size="small"
                >
                  {{ row.result === "success" ? "成功" : "失败" }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
.dashboard-container {
  padding: 4px;
}

.statistics-row {
  margin-bottom: 20px;
}

.statistic-card {
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  border: none;
  height: 100%;
  display: flex;
  flex-direction: column;

  &:hover {
    transform: translateY(-3px);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  }

  :deep(.el-card__body) {
    padding: 20px;
    flex: 1;
    display: flex;
    flex-direction: column;
  }
}

.statistic-content {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
}

.statistic-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.statistic-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 12px;
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.statistic-info {
  flex: 1;
  min-width: 0;
}

.statistic-value {
  font-size: 26px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 1.2;
  margin-bottom: 4px;
}

.statistic-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.statistic-footer {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.data-row {
  margin-bottom: 20px;

  :deep(.el-col) {
    margin-bottom: 0;
  }
}

.resource-card,
.status-card {
  height: 100%;
  min-height: 320px;
  display: flex;
  flex-direction: column;

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }
}

.resource-list {
  flex: 1;
  overflow-y: auto;

  .resource-item {
    padding: 16px;
    border-bottom: 1px solid var(--el-border-color-lighter);
    background: var(--el-fill-color-light);
    border-radius: 8px;
    margin-bottom: 12px;

    &:last-child {
      border-bottom: none;
      margin-bottom: 0;
    }
  }

  .resource-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
  }

  .resource-name {
    font-size: 14px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .resource-rank {
    font-size: 12px;
    color: var(--el-color-primary);
    font-weight: 600;
    background: var(--el-color-primary-light-9);
    padding: 2px 8px;
    border-radius: 10px;
  }

  .resource-bars {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .bar-item {
    display: flex;
    align-items: center;
    gap: 12px;

    .bar-label {
      width: 36px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      flex-shrink: 0;
    }

    .el-progress {
      flex: 1;
    }

    .bar-value {
      width: 40px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      text-align: right;
      flex-shrink: 0;
    }
  }
}

.status-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 20px 0;
}

.status-stats {
  display: flex;
  justify-content: space-around;
  align-items: center;
  flex: 1;
}

.status-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 20px;
  border-radius: 12px;
  transition: all 0.3s ease;
  min-width: 80px;

  &:hover {
    background: var(--el-fill-color-light);
  }
}

.status-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 16px;
  font-size: 28px;
  transition: all 0.3s ease;

  &.running {
    background: linear-gradient(
      135deg,
      var(--el-color-success-light-8),
      var(--el-color-success-light-9)
    );
    color: var(--el-color-success);
  }

  &.stopped {
    background: linear-gradient(
      135deg,
      var(--el-color-info-light-8),
      var(--el-color-info-light-9)
    );
    color: var(--el-color-info);
  }

  &.error {
    background: linear-gradient(
      135deg,
      var(--el-color-danger-light-8),
      var(--el-color-danger-light-9)
    );
    color: var(--el-color-danger);
  }
}

.status-info {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.status-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--el-text-color-primary);
}

.status-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.status-total {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--el-border-color-lighter);

  .total-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    background: var(--el-fill-color-light);
    border-radius: 8px;
  }

  .total-label {
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }

  .total-value {
    font-size: 20px;
    font-weight: 700;
    color: var(--el-color-primary);
  }
}

.instance-row,
.log-row {
  margin-bottom: 20px;

  &:last-child {
    margin-bottom: 0;
  }
}

.instance-card,
.log-card {
  :deep(.el-card__body) {
    padding: 0;
  }

  :deep(.el-table) {
    border-radius: 0 0 4px 4px;
  }
}

// 响应式适配
@media screen and (max-width: 992px) {
  .resource-card,
  .status-card {
    min-height: auto;
    margin-bottom: 16px;
  }

  .status-stats {
    flex-wrap: wrap;
    gap: 16px;
  }

  .status-item {
    flex: 1;
    min-width: 100px;
  }
}

@media screen and (max-width: 768px) {
  .dashboard-container {
    padding: 0;
  }

  .statistic-icon {
    width: 48px;
    height: 48px;

    .el-icon {
      font-size: 24px !important;
    }
  }

  .statistic-value {
    font-size: 22px;
  }

  .status-icon {
    width: 52px;
    height: 52px;
    font-size: 22px;
  }

  .status-value {
    font-size: 22px;
  }
}

.game-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
