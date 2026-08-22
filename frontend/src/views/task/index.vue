<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getTaskList,
  getTaskTypes,
  getTaskStats,
  cancelTask,
  retryTask,
  deleteTask,
} from "@/api/task";
import SchedulePanel from "./SchedulePanel.vue";

const router = useRouter();

// ==================== Tab 切换 ====================

const activeTab = ref("queue");

// ==================== 状态 ====================

const loading = ref(false);
const taskList = ref([]);
const total = ref(0);
const taskTypes = ref([]);
const taskStats = ref({ statusCounts: {}, sourceCounts: {}, typeCounts: {}, total: 0 });
const lastRefreshAt = ref("等待调度器同步");

// 查询参数
const query = reactive({
  source: "",
  taskType: "",
  status: "",
  keyword: "",
  page: 1,
  size: 20,
});

// 操作加载状态
const opLoading = ref({});

// 自动刷新定时器（仅当有未完成任务时启动）
let refreshTimer = null;

// ==================== 状态选项 ====================

const statusOptions = [
  { label: "全部", value: "" },
  { label: "等待中", value: "PENDING" },
  { label: "运行中", value: "RUNNING" },
  { label: "已完成", value: "COMPLETED" },
  { label: "失败", value: "FAILED" },
  { label: "已取消", value: "CANCELLED" },
];

// 来源选项（从任务类型列表动态构建）
const sourceOptions = computed(() => {
  const sources = new Set(taskTypes.value.map((t) => t.source));
  return [{ label: "全部", value: "" }, ...[...sources].map((s) => ({ label: s, value: s }))];
});

// 类型选项（根据来源过滤）
const typeOptions = computed(() => {
  const filtered = query.source
    ? taskTypes.value.filter((t) => t.source === query.source)
    : taskTypes.value;
  return [{ label: "全部", value: "" }, ...filtered.map((t) => ({ label: t.displayName, value: t.taskType }))];
});

const pendingTaskCount = computed(() => taskStats.value.statusCounts?.PENDING || 0);
const runningTaskCount = computed(() => taskStats.value.statusCounts?.RUNNING || 0);
const failedTaskCount = computed(() => taskStats.value.statusCounts?.FAILED || 0);
const completedTaskCount = computed(() => taskStats.value.statusCounts?.COMPLETED || 0);

// ==================== 数据加载 ====================

async function fetchTaskList() {
  loading.value = true;
  try {
    const params = { ...query };
    // 清空空字符串参数
    Object.keys(params).forEach((k) => {
      if (params[k] === "" || params[k] === null) delete params[k];
    });
    const data = await getTaskList(params);
    taskList.value = data?.records || [];
    total.value = data?.total || 0;
    lastRefreshAt.value = formatRefreshTime();
    // 根据是否有未完成任务决定是否启动自动刷新
    updateAutoRefresh();
  } catch (error) {
    ElMessage.error("获取任务列表失败：" + (error.message || ""));
    taskList.value = [];
  } finally {
    loading.value = false;
  }
}

async function fetchTaskStats() {
  try {
    const data = await getTaskStats();
    taskStats.value = data || { statusCounts: {}, sourceCounts: {}, typeCounts: {}, total: 0 };
  } catch (error) {
    console.error("Failed to fetch task stats:", error);
  }
}

async function fetchTaskTypes() {
  try {
    const data = await getTaskTypes();
    taskTypes.value = Array.isArray(data) ? data : [];
  } catch (error) {
    console.error("Failed to fetch task types:", error);
  }
}

// ==================== 自动刷新 ====================

function hasUnfinishedTasks() {
  return taskList.value.some(
    (t) => t.status === "PENDING" || t.status === "RUNNING"
  );
}

function updateAutoRefresh() {
  if (hasUnfinishedTasks()) {
    if (!refreshTimer) {
      refreshTimer = setInterval(() => {
        fetchTaskList();
      }, 5000);
    }
  } else {
    stopAutoRefresh();
  }
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

// ==================== 操作 ====================

function handleSearch() {
  query.page = 1;
  fetchTaskList();
}

function handleReset() {
  query.source = "";
  query.taskType = "";
  query.status = "";
  query.keyword = "";
  query.page = 1;
  fetchTaskList();
}

function handlePageChange(page) {
  query.page = page;
  fetchTaskList();
}

function handleSizeChange(size) {
  query.size = size;
  query.page = 1;
  fetchTaskList();
}

function handleViewDetail(row) {
  router.push(`/task/detail/${row.id}`);
}

async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(
      `确定要取消任务「${row.taskTypeName || row.taskType}」吗？运行中的任务将等待 Handler 优雅退出。`,
      "取消任务",
      { type: "warning" }
    );
  } catch {
    return;
  }
  const key = `cancel-${row.id}`;
  opLoading.value[key] = true;
  try {
    await cancelTask(row.id);
    ElMessage.success("取消请求已发送");
    await fetchTaskList();
  } catch (error) {
    ElMessage.error(error.message || "取消失败");
  } finally {
    opLoading.value[key] = false;
  }
}

async function handleRetry(row) {
  try {
    await ElMessageBox.confirm(
      `确定要重试任务「${row.taskTypeName || row.taskType}」吗？将基于原参数创建新任务。`,
      "重试任务",
      { type: "info" }
    );
  } catch {
    return;
  }
  const key = `retry-${row.id}`;
  opLoading.value[key] = true;
  try {
    const newTaskId = await retryTask(row.id);
    ElMessage.success(`重试已提交，新任务ID: ${newTaskId}`);
    await fetchTaskList();
  } catch (error) {
    ElMessage.error(error.message || "重试失败");
  } finally {
    opLoading.value[key] = false;
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除任务「${row.taskTypeName || row.taskType}」的记录吗？此操作不可恢复。`,
      "删除任务",
      { type: "error" }
    );
  } catch {
    return;
  }
  const key = `delete-${row.id}`;
  opLoading.value[key] = true;
  try {
    await deleteTask(row.id);
    ElMessage.success("任务已删除");
    await fetchTaskList();
  } catch (error) {
    ElMessage.error(error.message || "删除失败");
  } finally {
    opLoading.value[key] = false;
  }
}

// ==================== 工具方法 ====================

function getStatusType(status) {
  switch (status) {
    case "PENDING":
      return "info";
    case "RUNNING":
      return "warning";
    case "COMPLETED":
      return "success";
    case "FAILED":
      return "danger";
    case "CANCELLED":
      return "info";
    default:
      return "info";
  }
}

function getStatusLabel(status) {
  switch (status) {
    case "PENDING":
      return "等待中";
    case "RUNNING":
      return "运行中";
    case "COMPLETED":
      return "已完成";
    case "FAILED":
      return "失败";
    case "CANCELLED":
      return "已取消";
    default:
      return status;
  }
}

function formatDuration(ms) {
  if (!ms || ms < 0) return "-";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`;
  return `${Math.floor(ms / 3_600_000)}h${Math.floor((ms % 3_600_000) / 60_000)}m`;
}

function formatTime(time) {
  if (!time) return "-";
  return time.replace("T", " ").substring(0, 19);
}

function isTerminal(status) {
  return ["COMPLETED", "FAILED", "CANCELLED"].includes(status);
}

function canCancel(status) {
  return ["PENDING", "RUNNING"].includes(status);
}

function formatRefreshTime() {
  return new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

// ==================== 生命周期 ====================

onMounted(() => {
  fetchTaskTypes();
  fetchTaskStats();
  fetchTaskList();
});

onUnmounted(() => {
  stopAutoRefresh();
});
</script>

<template>
  <div class="task-list-page task-operations-page">
    <section class="task-hero">
      <div class="hero-copy">
        <span class="section-kicker">EXECUTION CONTROL / TASK ORCHESTRATION</span>
        <h1>任务中心</h1>
        <p>统一追踪部署、备份、重启与插件操作，把每一次异步执行都沉淀成可回放的运行轨迹。</p>
      </div>
      <div class="hero-actions">
        <div class="hero-status">
          <span class="task-pulse" aria-hidden="true"></span>
          <div>
            <strong>调度器在线</strong>
            <small>上次同步 {{ lastRefreshAt }}</small>
          </div>
        </div>
        <el-button @click="fetchTaskList">
          <el-icon><Refresh /></el-icon>
          刷新队列
        </el-button>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="task-center-tabs">
      <el-tab-pane label="执行队列" name="queue">

    <section class="task-rail" aria-label="任务执行态势">
      <div class="rail-intro">
        <span class="section-kicker">QUEUE TELEMETRY</span>
        <strong>执行队列</strong>
        <small>任务状态实时分布</small>
      </div>
      <div class="task-stat">
        <span>总任务</span>
        <strong>{{ taskStats.total || total }}</strong>
      </div>
      <div class="task-stat is-active">
        <span>待处理</span>
        <strong>{{ pendingTaskCount }}</strong>
      </div>
      <div class="task-stat is-running">
        <span>执行中</span>
        <strong>{{ runningTaskCount }}</strong>
      </div>
      <div class="task-stat is-failed">
        <span>失败</span>
        <strong>{{ failedTaskCount }}</strong>
      </div>
      <div class="task-stat is-completed">
        <span>已完成</span>
        <strong>{{ completedTaskCount }}</strong>
      </div>
    </section>

    <section class="task-filter-panel" aria-label="任务执行记录筛选">
      <div class="panel-heading filter-heading">
        <div>
          <span class="section-kicker">ROUTE / SCOPE</span>
          <h2>筛选执行记录</h2>
        </div>
        <span class="filter-hint">按来源、任务类型、状态或作用域定位执行链</span>
      </div>
      <el-form class="task-filter-form" :inline="true" :model="query" @submit.prevent>
        <el-form-item label="来源">
          <el-select
            v-model="query.source"
            placeholder="全部"
            clearable
            style="width: 112px"
            @change="handleSearch"
          >
            <el-option
              v-for="opt in sourceOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select
            v-model="query.taskType"
            placeholder="全部"
            clearable
            style="width: 136px"
            @change="handleSearch"
          >
            <el-option
              v-for="opt in typeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            placeholder="全部"
            clearable
            style="width: 108px"
            @change="handleSearch"
          >
            <el-option
              v-for="opt in statusOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            placeholder="搜索类型/作用域/错误信息"
            clearable
            style="width: 190px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            应用筛选
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <!-- 任务列表 -->
    <el-card class="table-card task-console-panel" shadow="never">
      <div class="panel-heading console-heading">
        <div>
          <span class="section-kicker">EXECUTION STREAM</span>
          <h2>执行记录</h2>
          <p>{{ total }} 条任务轨迹 · 点击行查看完整执行上下文</p>
        </div>
        <div class="stream-legend">
          <span><i class="legend-dot is-live"></i>实时调度</span>
          <span><i class="legend-dot is-alert"></i>需关注</span>
        </div>
      </div>
      <el-table
        v-loading="loading"
        class="task-console-table"
        :data="taskList"
        style="width: 100%"
        @row-click="handleViewDetail"
      >
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <div class="task-status-cell" :class="`is-${row.status.toLowerCase()}`">
              <span class="status-dot"></span>
              <strong>{{ getStatusLabel(row.status) }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="任务类型" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="task-identity">
              <strong>{{ row.taskTypeName || row.taskType }}</strong>
              <span>{{ row.source }} / {{ row.taskType }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="作用域" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="scope-cell">
              <strong>{{ row.scopeName || row.scopeKey || "-" }}</strong>
              <span>{{ row.scopeType || "GLOBAL" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="170">
          <template #default="{ row }">
            <div class="task-progress-cell">
              <div class="progress-heading">
                <span>{{ row.status === "RUNNING" ? row.progressMessage || "执行中" : getStatusLabel(row.status) }}</span>
                <strong>{{ row.progress || 0 }}%</strong>
              </div>
              <el-progress
                :percentage="row.progress || 0"
                :status="row.status === 'FAILED' ? 'exception' : row.status === 'COMPLETED' ? 'success' : undefined"
                :stroke-width="5"
                :show-text="false"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="结果/错误" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.status === 'FAILED'" class="result-cell is-error">
              {{ row.errorMessage }}
            </span>
            <span v-else class="result-cell">{{ row.resultSummary || row.progressMessage || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="center">
          <template #default="{ row }">
            {{ formatDuration(row.durationMs) }}
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="135">
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="155" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="handleViewDetail(row)">
              详情
            </el-button>
            <el-button
              v-if="canCancel(row.status)"
              link
              type="warning"
              size="small"
              :loading="opLoading[`cancel-${row.id}`]"
              @click.stop="handleCancel(row)"
            >
              取消
            </el-button>
            <el-button
              v-if="row.retryable && isTerminal(row.status)"
              link
              type="primary"
              size="small"
              :loading="opLoading[`retry-${row.id}`]"
              @click.stop="handleRetry(row)"
            >
              重试
            </el-button>
            <el-button
              v-if="isTerminal(row.status)"
              link
              type="danger"
              size="small"
              :loading="opLoading[`delete-${row.id}`]"
              @click.stop="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="task-empty">
            <el-icon><List /></el-icon>
            <strong>暂无执行记录</strong>
            <span>调整筛选条件或等待新的异步任务进入队列</span>
          </div>
        </template>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper task-console-footer">
        <span><i class="task-pulse" aria-hidden="true"></i> 调度轨迹已接入</span>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

      </el-tab-pane>
      <el-tab-pane label="定时计划" name="schedules" lazy>
        <SchedulePanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style lang="scss" scoped>
.task-list-page {
  padding: var(--spacing-md);

  .filter-card {
    margin-bottom: var(--spacing-md);

    :deep(.el-card__body) {
      padding: var(--spacing-md) var(--spacing-lg);
    }
  }

  .table-card {
    :deep(.el-card__body) {
      padding: 0;
    }
  }

  .task-type-sub {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    margin-top: 2px;
  }

  .text-danger {
    color: var(--el-color-danger);
  }

  .pagination-wrapper {
    display: flex;
    justify-content: flex-end;
    padding: var(--spacing-md) var(--spacing-lg);
  }

  :deep(.el-table) {
    cursor: pointer;
  }
}
</style>

<style lang="scss" scoped>
.task-operations-page {
  --task-gap: 14px;
  --task-accent: #ee8358;
  --task-accent-soft: rgba(238, 131, 88, 0.12);
  --task-cyan: #55c9dc;

  padding: 4px 2px 24px;
  color: var(--platform-text-primary);
}

.task-hero,
.task-rail,
.task-filter-panel,
.task-console-panel {
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-1);
}

.task-center-tabs {
  margin-top: var(--task-gap);

  :deep(.el-tabs__nav-wrap::after) {
    height: 1px;
    background: var(--platform-line);
  }

  :deep(.el-tabs__item) {
    height: 40px;
    color: var(--platform-text-secondary);
    font-size: 13px;
    font-weight: 600;

    &.is-active {
      color: var(--task-accent);
    }

    &:hover {
      color: var(--platform-text-primary);
    }
  }

  :deep(.el-tabs__active-bar) {
    background: var(--task-accent);
  }

  :deep(.el-tab-pane) {
    outline: none;
  }
}

.task-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  min-height: 138px;
  padding: 24px 26px;
  border-radius: 6px;
  background:
    linear-gradient(118deg, rgba(238, 131, 88, 0.16), transparent 44%),
    var(--platform-surface-1);
  box-shadow: 0 14px 28px rgba(0, 0, 0, 0.14);
}

.task-operations-page .section-kicker {
  display: block;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  line-height: 1.4;
}

.task-operations-page .hero-copy h1 {
  margin: 8px 0 7px;
  color: var(--platform-text-primary);
  font-size: clamp(24px, 2.4vw, 32px);
  font-weight: 700;
  letter-spacing: -0.03em;
}

.task-operations-page .hero-copy p {
  max-width: 680px;
  margin: 0;
  color: var(--platform-text-secondary);
  font-size: 13px;
}

.task-operations-page .hero-actions,
.task-operations-page .hero-status,
.task-operations-page .panel-heading,
.task-operations-page .stream-legend,
.task-operations-page .task-console-footer {
  display: flex;
  align-items: center;
}

.task-operations-page .hero-actions {
  gap: 20px;
}

.task-operations-page .hero-status {
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

.task-pulse,
.status-dot,
.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.task-pulse {
  background: var(--task-accent);
  box-shadow: 0 0 0 3px var(--task-accent-soft);
}

.task-rail {
  display: grid;
  grid-template-columns: minmax(190px, 1.35fr) repeat(5, minmax(74px, 0.68fr));
  align-items: stretch;
  gap: 0;
  min-height: 78px;
  margin-top: var(--task-gap);
  padding: 12px 18px;
  border-radius: 5px;
  background:
    linear-gradient(90deg, rgba(238, 131, 88, 0.05), transparent 52%),
    var(--platform-surface-2);
}

.rail-intro,
.task-stat {
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

.task-stat {
  position: relative;
  padding: 0 13px;
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

  > span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  > strong {
    color: var(--platform-text-primary);
    font-size: 18px;
    font-weight: 650;
  }

  &.is-active::before {
    background: var(--task-accent);
  }

  &.is-active > strong {
    color: var(--task-accent);
  }

  &.is-running::before,
  &.is-running > strong {
    background: none;
    color: var(--task-cyan);
  }

  &.is-running::before {
    background: var(--task-cyan);
  }

  &.is-failed::before,
  &.is-failed > strong {
    background: none;
    color: var(--platform-red);
  }

  &.is-failed::before {
    background: var(--platform-red);
  }

  &.is-completed::before {
    background: var(--platform-green);
  }
}

.task-filter-panel,
.task-console-panel {
  margin-top: var(--task-gap);
  border-radius: 5px;
  overflow: hidden;
}

.task-filter-panel {
  padding: 17px 18px 5px;
}

.task-operations-page .panel-heading {
  justify-content: space-between;
  gap: 16px;
}

.task-operations-page .panel-heading h2 {
  margin: 5px 0 0;
  color: var(--platform-text-primary);
  font-size: 16px;
  font-weight: 650;
}

.task-operations-page .filter-hint,
.task-operations-page .console-heading p {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.task-filter-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 13px;

  :deep(.el-form-item) {
    margin-right: 0;
    margin-bottom: 10px;
  }

  :deep(.el-input) {
    width: 220px;
  }

  .el-form-item:last-child {
    display: flex;
    gap: 8px;
  }
}

.task-console-panel {
  :deep(.el-card__body) {
    padding: 0;
  }
}

.console-heading {
  min-height: 76px;
  padding: 17px 18px 15px;
  border-bottom: 1px solid var(--platform-line);
}

.console-heading p {
  margin: 5px 0 0;
}

.stream-legend {
  gap: 14px;

  span {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.04em;
  }
}

.legend-dot.is-live {
  background: var(--task-cyan);
  box-shadow: 0 0 0 3px rgba(85, 201, 220, 0.12);
}

.legend-dot.is-alert {
  background: var(--platform-red);
  box-shadow: 0 0 0 3px rgba(240, 100, 106, 0.12);
}

.task-console-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(238, 131, 88, 0.07);
  --el-table-header-bg-color: rgba(20, 39, 53, 0.86);
  --el-table-border-color: var(--platform-line);
  --el-table-text-color: var(--platform-text-regular);
  --el-table-header-text-color: var(--platform-text-secondary);

  :deep(.el-table__inner-wrapper::before) {
    display: none;
  }

  :deep(.el-table__header-wrapper th.el-table__cell) {
    height: 42px;
    background: var(--el-table-header-bg-color);
    color: var(--platform-text-secondary);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.03em;
  }

  :deep(.el-table__body-wrapper td.el-table__cell) {
    height: 78px;
    border-bottom-color: rgba(38, 56, 71, 0.72);
  }

  :deep(.el-table__row) {
    cursor: pointer;
    transition: background 0.18s ease;
  }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: rgba(238, 131, 88, 0.07);
  }

  :deep(.el-button.is-link) {
    font-size: 12px;
  }
}

.task-status-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 600;
  }

  .status-dot {
    background: var(--platform-text-muted);
  }

  &.is-running,
  &.is-pending {
    .status-dot {
      background: var(--task-cyan);
      box-shadow: 0 0 0 3px rgba(85, 201, 220, 0.12);
    }
  }

  &.is-running strong {
    color: var(--task-cyan);
  }

  &.is-pending {
    .status-dot {
      background: var(--task-accent);
      box-shadow: 0 0 0 3px var(--task-accent-soft);
    }

    strong {
      color: var(--task-accent);
    }
  }

  &.is-failed {
    .status-dot {
      background: var(--platform-red);
      box-shadow: 0 0 0 3px rgba(240, 100, 106, 0.12);
    }

    strong {
      color: var(--platform-red);
    }
  }

  &.is-completed .status-dot {
    background: var(--platform-green);
  }
}

.task-identity,
.scope-cell,
.task-progress-cell {
  display: grid;
  gap: 5px;
}

.task-identity {
  strong {
    overflow: hidden;
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.03em;
  }
}

.source-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  padding: 4px 7px;
  border: 1px solid rgba(85, 201, 220, 0.24);
  border-radius: 3px;
  background: rgba(85, 201, 220, 0.08);
  color: var(--task-cyan);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
}

.scope-cell {
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
    font-family: var(--el-font-family-mono);
    font-size: 10px;
  }
}

.task-progress-cell {
  min-width: 120px;

  .progress-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;

    span {
      overflow: hidden;
      color: var(--platform-text-muted);
      font-size: 10px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      color: var(--platform-text-regular);
      font-family: var(--el-font-family-mono);
      font-size: 11px;
      font-weight: 600;
    }
  }

  :deep(.el-progress-bar__outer) {
    background: rgba(104, 120, 138, 0.18);
  }

  :deep(.el-progress-bar__inner) {
    background: var(--task-cyan);
  }
}

.result-cell {
  display: block;
  overflow: hidden;
  color: var(--platform-text-secondary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.is-error {
    color: var(--platform-red);
  }
}

.task-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 150px;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--task-accent);
    font-size: 22px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 600;
  }

  span {
    font-size: 11px;
  }
}

.task-console-footer {
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 0 18px;
  border-top: 1px solid var(--platform-line);

  > span {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.04em;
  }

  .task-pulse {
    width: 6px;
    height: 6px;
    box-shadow: none;
  }
}

.task-operations-page :deep(.el-pagination) {
  --el-pagination-bg-color: transparent;
  --el-pagination-button-bg-color: transparent;
  --el-pagination-hover-color: var(--task-accent);
}

@media screen and (max-width: 1180px) {
  .task-rail {
    grid-template-columns: minmax(180px, 1.15fr) repeat(5, minmax(64px, 0.68fr));
    padding-inline: 12px;
  }

  .task-stat {
    padding-inline: 9px;
  }

  .task-console-table {
    :deep(.el-table__body-wrapper td.el-table__cell),
    :deep(.el-table__header-wrapper th.el-table__cell) {
      padding-inline: 8px;
    }
  }
}

@media screen and (max-width: 780px) {
  .task-hero,
  .task-operations-page .panel-heading,
  .task-console-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .task-hero {
    gap: 18px;
  }

  .task-operations-page .hero-actions {
    justify-content: space-between;
    width: 100%;
  }

  .task-rail {
    grid-template-columns: repeat(2, 1fr);
    gap: 14px 0;
  }

  .rail-intro {
    grid-column: span 2;
  }

  .task-stat:nth-child(2n) {
    border-left: 0;
  }

  .task-filter-form {
    align-items: stretch;
    flex-direction: column;

    :deep(.el-input) {
      width: 100%;
    }
  }

  .console-heading {
    gap: 10px;
  }

  .task-console-footer {
    gap: 10px;
    padding-block: 12px;
  }
}
</style>
