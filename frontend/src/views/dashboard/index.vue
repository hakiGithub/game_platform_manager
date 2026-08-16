<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { useRouter } from "vue-router";
import { getHostList, getHostResources } from "@/api/host";
import {
  getInstanceList,
  restartInstance,
  startInstance,
  stopInstance,
} from "@/api/instance";
import { getOperationLogs } from "@/api/system";
import { statusType } from "@/utils/instanceStatus";

const router = useRouter();

const statistics = ref([
  {
    title: "主机状态",
    key: "hostStatus",
    value: "0/0",
    icon: "Monitor",
    color: "var(--platform-cyan)",
    description: "在线节点 / 总节点",
    status: "",
    route: "/host/list",
  },
  {
    title: "实例状态",
    key: "instanceStatus",
    value: "0/0",
    icon: "Grid",
    color: "var(--platform-green)",
    description: "运行中 / 总实例",
    status: "",
    route: "/instance/list",
  },
  {
    title: "异常实例",
    key: "errorInstances",
    value: 0,
    icon: "Warning",
    color: "var(--platform-red)",
    description: "需要人工处置",
    status: "",
    route: "/instance/list?status=2",
  },
  {
    title: "今日备份",
    key: "todayBackups",
    value: 0,
    icon: "Download",
    color: "var(--platform-amber)",
    description: "当前周期累计",
    status: "info",
    route: "/instance/list",
  },
]);

const resourceTop5 = ref([]);
const instanceStatusData = ref({ running: 0, stopped: 0, error: 0 });
const instanceList = ref([]);
const operationLogs = ref([]);
const loading = ref(false);
const lastRefreshAt = ref("等待首次刷新");
let refreshTimer = null;

const totalInstances = computed(
  () =>
    instanceStatusData.value.running +
    instanceStatusData.value.stopped +
    instanceStatusData.value.error,
);

const totalOnlinePlayers = computed(() =>
  instanceList.value.reduce(
    (total, instance) => total + (instance.onlinePlayers || 0),
    0,
  ),
);

const healthyRate = computed(() => {
  if (!totalInstances.value) return 0;
  return Math.round(
    (instanceStatusData.value.running / totalInstances.value) * 100,
  );
});

async function fetchDashboardData() {
  loading.value = true;
  try {
    const [hosts, instances] = await Promise.all([
      getHostList({ size: 100 }).catch(() => ({ records: [], total: 0 })),
      getInstanceList({ size: 100 }).catch(() => ({ records: [], total: 0 })),
    ]);

    const hostList = hosts.records || [];
    const onlineHosts = hostList.filter((host) => host.status === 1).length;
    statistics.value[0].value = `${onlineHosts}/${hostList.length}`;
    statistics.value[0].status =
      onlineHosts === hostList.length
        ? "success"
        : onlineHosts > 0
          ? "warning"
          : "danger";

    const instancesList = instances.records || [];
    const runningInstances = instancesList.filter(
      (instance) => instance.status === "running",
    ).length;
    const errorInstances = instancesList.filter(
      (instance) => instance.status === "error",
    ).length;
    const stoppedInstances = instancesList.filter(
      (instance) => instance.status === "stopped",
    ).length;

    statistics.value[1].value = `${runningInstances}/${instancesList.length}`;
    statistics.value[1].status =
      runningInstances === instancesList.length
        ? "success"
        : runningInstances > 0
          ? "warning"
          : "info";
    statistics.value[2].value = errorInstances;
    statistics.value[2].status = errorInstances > 0 ? "danger" : "success";

    instanceStatusData.value = {
      running: runningInstances,
      stopped: stoppedInstances,
      error: errorInstances,
    };
    instanceList.value = instancesList.slice(0, 5);

    await fetchResourceTop5(hostList);
    await fetchOperationLogs();
    lastRefreshAt.value = new Date().toLocaleTimeString("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    });
  } catch (error) {
    console.error("Failed to fetch dashboard data:", error);
  } finally {
    loading.value = false;
  }
}

async function fetchResourceTop5(hostList) {
  const onlineHosts = hostList.filter((host) => host.status === 1);
  const resourceData = [];

  for (const host of onlineHosts.slice(0, 5)) {
    const resources = await getHostResources(host.id).catch(() => null);
    if (resources) {
      resourceData.push({
        name: host.name,
        cpu: resources.cpu?.usage || 0,
        memory: resources.memory?.usage || 0,
        disk: resources.disk?.usage || 0,
      });
    }
  }

  resourceTop5.value = resourceData.sort((a, b) => b.cpu - a.cpu);
}

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

function getProgressColor(value) {
  if (value >= 80) return "var(--platform-red)";
  if (value >= 60) return "var(--platform-amber)";
  return "var(--platform-green)";
}

function getStatStatusLabel(status) {
  return (
    {
      success: "正常",
      warning: "注意",
      danger: "异常",
      info: "待确认",
    }[status] || "待确认"
  );
}

function getStatusPercent(value) {
  if (!totalInstances.value) return 0;
  return Math.round((value / totalInstances.value) * 100);
}

function formatPort(portConfig) {
  if (!portConfig || typeof portConfig !== "object") return "-";
  const keys = Object.keys(portConfig);
  if (keys.length === 0) return "-";
  const mainKey =
    keys.find((key) => /port/i.test(key) && !/container|name/i.test(key)) ||
    keys[0];
  return portConfig[mainKey] != null ? portConfig[mainKey] : "-";
}

function handleStatClick(stat) {
  router.push(stat.route);
}

function handleInstanceDetail(row) {
  router.push(`/instance/detail/${row.id}`);
}

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

function handleRefresh() {
  fetchDashboardData();
}

function startAutoRefresh() {
  refreshTimer = setInterval(fetchDashboardData, 30000);
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

onBeforeUnmount(stopAutoRefresh);
</script>

<template>
  <div v-loading="loading" class="dashboard-container">
    <section class="dashboard-hero">
      <div class="hero-copy">
        <span class="section-kicker">NIGHT OPERATIONS / CONTROL PLANE</span>
        <h1>运营总览</h1>
        <p>先看系统态势，再进入具体工作台。所有关键处置入口都从这里开始。</p>
      </div>
      <div class="hero-actions">
        <div class="hero-status">
          <span class="live-pulse" aria-hidden="true"></span>
          <div>
            <strong>控制面正常</strong>
            <small>自动刷新 · 30 秒</small>
          </div>
        </div>
        <el-button type="primary" :loading="loading" @click="handleRefresh">
          <el-icon><Refresh /></el-icon>
          刷新数据
        </el-button>
      </div>
    </section>

    <section class="situation-strip" aria-label="当前运行态势">
      <div class="strip-intro">
        <span class="section-kicker">CURRENT SITUATION</span>
        <strong>当前运行态势</strong>
        <small>上次同步 {{ lastRefreshAt }}</small>
      </div>
      <div class="strip-stat">
        <span>在线主机</span>
        <strong>{{ statistics[0].value }}</strong>
      </div>
      <div class="strip-stat">
        <span>运行实例</span>
        <strong>{{ statistics[1].value }}</strong>
      </div>
      <div class="strip-stat">
        <span>在线玩家</span>
        <strong>{{ totalOnlinePlayers }}</strong>
      </div>
      <div class="strip-action">
        <el-button link @click="router.push('/host/list')">
          主机工作台 <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
    </section>

    <section class="metric-grid" aria-label="核心指标">
      <article
        v-for="item in statistics"
        :key="item.key"
        class="metric-card"
        :class="`metric-${item.key}`"
        tabindex="0"
        role="button"
        @click="handleStatClick(item)"
        @keydown.enter="handleStatClick(item)"
      >
        <div class="metric-card-top">
          <span class="metric-title">{{ item.title }}</span>
          <span class="metric-icon" :style="{ color: item.color }">
            <el-icon :size="18"><component :is="item.icon" /></el-icon>
          </span>
        </div>
        <div class="metric-value">{{ item.value }}</div>
        <div class="metric-description">{{ item.description }}</div>
        <div class="metric-footer">
          <span class="metric-state" :class="`is-${item.status || 'info'}`">
            <i></i>{{ getStatStatusLabel(item.status) }}
          </span>
          <span class="metric-link">查看详情 <el-icon><ArrowUpRight /></el-icon></span>
        </div>
      </article>
    </section>

    <section class="primary-grid">
      <el-card class="workspace-card resource-card" shadow="never">
        <template #header>
          <div class="workspace-header">
            <div>
              <span class="section-kicker">HOST RESOURCES</span>
              <h2>主机资源</h2>
            </div>
            <div class="header-actions">
              <span class="header-context">Top {{ Math.min(resourceTop5.length, 5) }}</span>
              <el-button link size="small" :loading="loading" @click="handleRefresh">
                <el-icon><Refresh /></el-icon>刷新
              </el-button>
            </div>
          </div>
        </template>

        <div v-if="resourceTop5.length > 0" class="resource-list">
          <div v-for="(item, index) in resourceTop5" :key="index" class="resource-item">
            <div class="resource-heading">
              <div class="resource-identity">
                <span class="resource-rank">0{{ index + 1 }}</span>
                <strong>{{ item.name }}</strong>
              </div>
              <span class="resource-state"><i></i>在线</span>
            </div>
            <div class="resource-bars">
              <div class="resource-bar">
                <span>CPU</span>
                <el-progress :percentage="Math.round(item.cpu)" :stroke-width="5" :show-text="false" :color="getProgressColor(item.cpu)" />
                <strong>{{ Math.round(item.cpu) }}%</strong>
              </div>
              <div class="resource-bar">
                <span>内存</span>
                <el-progress :percentage="Math.round(item.memory)" :stroke-width="5" :show-text="false" :color="getProgressColor(item.memory)" />
                <strong>{{ Math.round(item.memory) }}%</strong>
              </div>
              <div class="resource-bar">
                <span>磁盘</span>
                <el-progress :percentage="Math.round(item.disk)" :stroke-width="5" :show-text="false" :color="getProgressColor(item.disk)" />
                <strong>{{ Math.round(item.disk) }}%</strong>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">
          <el-icon><Monitor /></el-icon>
          <strong>暂无在线主机</strong>
          <span>纳管主机后，资源遥测会显示在这里</span>
        </div>
      </el-card>

      <el-card class="workspace-card health-card" shadow="never">
        <template #header>
          <div class="workspace-header">
            <div>
              <span class="section-kicker">INSTANCE HEALTH</span>
              <h2>实例健康度</h2>
            </div>
            <el-button link size="small" @click="router.push('/instance/list')">
              全部实例 <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
        </template>

        <div class="health-summary">
          <div class="health-score">
            <strong>{{ healthyRate }}%</strong>
            <span>运行健康度</span>
          </div>
          <div class="health-copy">
            <span class="health-status"><i></i>{{ instanceStatusData.error ? "有异常需要处理" : "当前无异常实例" }}</span>
            <p>{{ totalInstances }} 个实例 · {{ totalOnlinePlayers }} 名玩家在线</p>
          </div>
        </div>

        <div class="status-list">
          <div class="status-line">
            <div><i class="status-dot running"></i><span>运行中</span></div>
            <strong>{{ instanceStatusData.running }}</strong>
            <el-progress :percentage="getStatusPercent(instanceStatusData.running)" :show-text="false" :stroke-width="5" color="var(--platform-green)" />
          </div>
          <div class="status-line">
            <div><i class="status-dot stopped"></i><span>已停止</span></div>
            <strong>{{ instanceStatusData.stopped }}</strong>
            <el-progress :percentage="getStatusPercent(instanceStatusData.stopped)" :show-text="false" :stroke-width="5" color="var(--platform-text-muted)" />
          </div>
          <div class="status-line">
            <div><i class="status-dot error"></i><span>异常</span></div>
            <strong>{{ instanceStatusData.error }}</strong>
            <el-progress :percentage="getStatusPercent(instanceStatusData.error)" :show-text="false" :stroke-width="5" color="var(--platform-red)" />
          </div>
        </div>
      </el-card>
    </section>

    <section class="table-panel">
      <div class="panel-heading">
        <div>
          <span class="section-kicker">RUNNING INSTANCES</span>
          <h2>实例运行状态</h2>
        </div>
        <el-button link @click="router.push('/instance/list')">
          查看全部 <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <el-table :data="instanceList" style="width: 100%" empty-text="暂无实例">
        <el-table-column label="实例名称" min-width="190">
          <template #default="{ row }">
            <el-link type="primary" @click="handleInstanceDetail(row)">
              {{ row.instanceName || row.name }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="游戏" min-width="160">
          <template #default="{ row }">
            <div class="game-cell">
              <el-avatar v-if="row.iconUrl" :src="row.iconUrl" :size="24" shape="square" />
              <el-avatar v-else :size="24" shape="square"><el-icon><Grid /></el-icon></el-avatar>
              <span>{{ row.gameName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="hostName" label="主机" min-width="130" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.runStatusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在线玩家" width="100">
          <template #default="{ row }">{{ row.onlinePlayers || 0 }}</template>
        </el-table-column>
        <el-table-column label="端口" width="100">
          <template #default="{ row }">{{ formatPort(row.portConfig) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'running'">
              <el-button type="warning" link size="small" :loading="row._loading" @click="handleStop(row)">停止</el-button>
              <el-button type="info" link size="small" :loading="row._loading" @click="handleRestart(row)">重启</el-button>
            </template>
            <template v-else-if="row.status === 'stopped'">
              <el-button type="success" link size="small" :loading="row._loading" @click="handleStart(row)">启动</el-button>
            </template>
            <template v-else-if="row.status === 'error'">
              <el-button type="warning" link size="small" :loading="row._loading" @click="handleRestart(row)">重启</el-button>
            </template>
            <template v-else><el-tag type="info" size="small">{{ row.runStatusDesc }}</el-tag></template>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="activity-panel">
      <div class="panel-heading">
        <div>
          <span class="section-kicker">AUDIT TRAIL</span>
          <h2>最近操作</h2>
        </div>
        <el-button link @click="router.push('/system/logs')">
          查看全部 <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <div v-if="operationLogs.length" class="activity-list">
        <div v-for="(log, index) in operationLogs" :key="`${log.time}-${index}`" class="activity-row">
          <span class="activity-time">{{ log.time }}</span>
          <span class="activity-marker" :class="log.result"></span>
          <div class="activity-copy">
            <strong>{{ log.action }}</strong>
            <span>{{ log.operator }}</span>
          </div>
          <el-tag :type="log.result === 'success' ? 'success' : 'danger'" size="small">
            {{ log.result === "success" ? "成功" : "失败" }}
          </el-tag>
        </div>
      </div>
      <div v-else class="empty-state activity-empty">
        <el-icon><Tickets /></el-icon>
        <strong>暂无操作记录</strong>
        <span>系统产生的处置记录会显示在这里</span>
      </div>
    </section>

    <div class="dashboard-footer">
      <span><i class="live-pulse" aria-hidden="true"></i> 数据状态：已连接</span>
      <span>实例遥测每 30 秒更新 · 最后同步 {{ lastRefreshAt }}</span>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.dashboard-container {
  --dashboard-gap: 14px;
  padding: 4px 2px 24px;
  color: var(--platform-text-primary);
}

.dashboard-hero,
.situation-strip,
.metric-card,
.workspace-card,
.table-panel,
.activity-panel {
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-1);
}

.dashboard-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  min-height: 144px;
  padding: 24px 26px;
  border-radius: 6px;
  background:
    linear-gradient(115deg, rgba(39, 181, 243, 0.12), transparent 43%),
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
  max-width: 560px;
  margin: 0;
  color: var(--platform-text-secondary);
  font-size: 13px;
}

.hero-actions,
.hero-status,
.strip-action,
.workspace-header,
.header-actions,
.resource-heading,
.resource-identity,
.metric-card-top,
.metric-footer,
.health-summary,
.health-status,
.panel-heading,
.activity-row,
.activity-copy,
.game-cell {
  display: flex;
  align-items: center;
}

.hero-actions {
  gap: 18px;
}

.hero-status {
  gap: 10px;
  color: var(--platform-text-regular);

  strong,
  small {
    display: block;
  }

  strong {
    font-size: 13px;
  }

  small {
    margin-top: 3px;
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.live-pulse,
.resource-state i,
.health-status i,
.metric-state i,
.activity-marker,
.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--platform-green);
  box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.12);
}

.live-pulse {
  width: 9px;
  height: 9px;
}

.situation-strip {
  display: grid;
  grid-template-columns: minmax(220px, 1.5fr) repeat(3, minmax(100px, 0.7fr)) auto;
  align-items: center;
  gap: 0;
  margin-top: var(--dashboard-gap);
  min-height: 76px;
  padding: 12px 18px;
  border-radius: 5px;
  background: var(--platform-surface-2);
}

.strip-intro {
  display: grid;
  gap: 3px;

  strong {
    font-size: 13px;
    font-weight: 600;
  }

  small {
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.strip-stat {
  display: grid;
  gap: 4px;
  padding: 0 18px;
  border-left: 1px solid var(--platform-line);

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  strong {
    color: var(--platform-text-primary);
    font-size: 17px;
    font-weight: 600;
  }
}

.strip-action {
  justify-content: flex-end;
  padding-left: 12px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--dashboard-gap);
  margin-top: var(--dashboard-gap);
}

.metric-card {
  position: relative;
  min-height: 164px;
  padding: 17px 18px 14px;
  overflow: hidden;
  cursor: pointer;
  border-radius: 5px;
  transition: transform 160ms ease, border-color 160ms ease, box-shadow 160ms ease;

  &::after {
    position: absolute;
    right: -30px;
    bottom: -45px;
    width: 130px;
    height: 130px;
    border: 1px solid currentColor;
    border-radius: 50%;
    opacity: 0.05;
    content: "";
  }

  &:hover,
  &:focus-visible {
    border-color: var(--platform-cyan);
    box-shadow: 0 10px 22px rgba(0, 0, 0, 0.14);
    outline: none;
    transform: translateY(-2px);
  }
}

.metric-card-top {
  justify-content: space-between;
  color: var(--platform-text-secondary);
}

.metric-title {
  font-size: 12px;
}

.metric-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid currentColor;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.02);
}

.metric-value {
  margin-top: 16px;
  color: var(--platform-text-primary);
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.04em;
}

.metric-description {
  margin-top: 3px;
  color: var(--platform-text-muted);
  font-size: 11px;
}

.metric-footer {
  justify-content: space-between;
  margin-top: 18px;
  padding-top: 11px;
  border-top: 1px solid var(--platform-line);
  font-size: 11px;
}

.metric-state,
.resource-state,
.health-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--platform-green);
}

.metric-state.is-warning {
  color: var(--platform-amber);
}

.metric-state.is-danger {
  color: var(--platform-red);
}

.metric-state.is-info {
  color: var(--platform-text-muted);
}

.metric-state.is-warning i {
  background: var(--platform-amber);
  box-shadow: 0 0 0 3px rgba(242, 184, 75, 0.12);
}

.metric-state.is-danger i {
  background: var(--platform-red);
  box-shadow: 0 0 0 3px rgba(240, 100, 106, 0.12);
}

.metric-state.is-info i {
  background: var(--platform-text-muted);
  box-shadow: 0 0 0 3px rgba(104, 120, 138, 0.12);
}

.metric-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--platform-text-muted);
}

.primary-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(320px, 0.82fr);
  gap: var(--dashboard-gap);
  margin-top: var(--dashboard-gap);
}

.workspace-card {
  min-height: 286px;
  border-radius: 5px;

  :deep(.el-card__header) {
    padding: 16px 18px;
    border-bottom-color: var(--platform-line);
    background: rgba(255, 255, 255, 0.015);
  }

  :deep(.el-card__body) {
    padding: 18px;
  }
}

.workspace-header,
.panel-heading {
  justify-content: space-between;
  gap: 16px;
}

.workspace-header h2,
.panel-heading h2 {
  margin: 5px 0 0;
  color: var(--platform-text-primary);
  font-size: 15px;
  font-weight: 600;
}

.header-actions {
  gap: 14px;
}

.header-context {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 11px;
}

.resource-list {
  display: grid;
  gap: 10px;
}

.resource-item {
  padding: 13px 14px 12px;
  border: 1px solid var(--platform-line);
  border-radius: 5px;
  background: var(--platform-surface-2);
}

.resource-heading {
  justify-content: space-between;
  margin-bottom: 12px;
}

.resource-identity {
  gap: 10px;

  strong {
    font-size: 13px;
    font-weight: 600;
  }
}

.resource-rank {
  color: var(--platform-cyan);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
}

.resource-state {
  color: var(--platform-text-secondary);
  font-size: 11px;
}

.resource-bars {
  display: grid;
  gap: 8px;
}

.resource-bar {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 40px;
  align-items: center;
  gap: 10px;

  > span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  > strong {
    color: var(--platform-text-secondary);
    font-family: var(--el-font-family-mono);
    font-size: 11px;
    font-weight: 500;
    text-align: right;
  }

  :deep(.el-progress-bar__outer) {
    background: #1b2b39;
  }
}

.health-summary {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 2px 0 20px;
  border-bottom: 1px solid var(--platform-line);
}

.health-score {
  display: grid;
  min-width: 104px;
  gap: 2px;

  strong {
    color: var(--platform-green);
    font-size: 32px;
    font-weight: 700;
    letter-spacing: -0.05em;
  }

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.health-copy {
  min-width: 0;

  p {
    margin: 8px 0 0;
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.health-status {
  color: var(--platform-green);
  font-size: 12px;
}

.status-list {
  display: grid;
  gap: 15px;
  padding-top: 18px;
}

.status-line {
  display: grid;
  grid-template-columns: 86px 28px minmax(0, 1fr);
  align-items: center;
  gap: 10px;

  > div {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--platform-text-secondary);
    font-size: 11px;
  }

  > strong {
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 600;
    text-align: right;
  }

  :deep(.el-progress-bar__outer) {
    background: #1b2b39;
  }
}

.status-dot {
  width: 6px;
  height: 6px;
  box-shadow: none;

  &.stopped {
    background: var(--platform-text-muted);
  }

  &.error {
    background: var(--platform-red);
  }
}

.table-panel,
.activity-panel {
  margin-top: var(--dashboard-gap);
  overflow: hidden;
  border-radius: 5px;
}

.panel-heading {
  padding: 16px 18px;
  border-bottom: 1px solid var(--platform-line);
  background: rgba(255, 255, 255, 0.015);
}

.table-panel {
  :deep(.el-table) {
    background: transparent;
  }

  :deep(.el-table th.el-table__cell),
  :deep(.el-table td.el-table__cell) {
    padding: 12px 0;
  }

  :deep(.el-table__inner-wrapper::before) {
    display: none;
  }
}

.game-cell {
  gap: 8px;
  color: var(--platform-text-regular);
}

.activity-list {
  display: grid;
}

.activity-row {
  gap: 14px;
  min-height: 58px;
  padding: 9px 18px;
  border-bottom: 1px solid var(--platform-line);

  &:last-child {
    border-bottom: 0;
  }
}

.activity-time {
  width: 140px;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 11px;
}

.activity-marker {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  background: var(--platform-red);
  box-shadow: 0 0 0 3px rgba(240, 100, 106, 0.12);

  &.success {
    background: var(--platform-green);
    box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.12);
  }
}

.activity-copy {
  flex: 1;
  justify-content: space-between;
  gap: 14px;
  min-width: 0;

  strong {
    overflow: hidden;
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.empty-state {
  display: grid;
  min-height: 190px;
  place-content: center;
  justify-items: center;
  gap: 7px;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--platform-text-secondary);
    font-size: 24px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 13px;
    font-weight: 500;
  }

  span {
    font-size: 11px;
  }
}

.activity-empty {
  min-height: 110px;
}

.dashboard-footer {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 2px 0;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;

  span:first-child {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
}

@media screen and (max-width: 1180px) {
  .situation-strip {
    grid-template-columns: minmax(190px, 1.4fr) repeat(3, minmax(90px, 0.7fr));
  }

  .strip-action {
    display: none;
  }
}

@media screen and (max-width: 900px) {
  .dashboard-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .metric-grid,
  .primary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .situation-strip {
    grid-template-columns: repeat(3, 1fr);
    gap: 12px 0;
  }

  .strip-intro {
    grid-column: 1 / -1;
  }

  .strip-stat:first-of-type {
    border-left: 0;
    padding-left: 0;
  }
}

@media screen and (max-width: 640px) {
  .metric-grid,
  .primary-grid {
    grid-template-columns: 1fr;
  }

  .hero-actions,
  .dashboard-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .situation-strip {
    grid-template-columns: 1fr;
  }

  .strip-stat,
  .strip-stat:first-of-type {
    padding: 0;
    border-left: 0;
  }

  .activity-time {
    width: auto;
  }
}
</style>
