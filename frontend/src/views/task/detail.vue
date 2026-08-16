<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getTaskDetail,
  getTaskLogs,
  cancelTask,
  retryTask,
  deleteTask,
} from "@/api/task";

const route = useRoute();
const router = useRouter();

const taskId = computed(() => route.params.id);
const currentPhaseIndex = computed(() => {
  const status = task.value?.status;
  if (status === "PENDING") return 0;
  if (status === "RUNNING") return 1;
  if (isTerminal(status)) return 2;
  return 0;
});

const phaseItems = computed(() => {
  const status = task.value?.status;
  const terminalLabel = status === "FAILED" ? "执行失败" : status === "CANCELLED" ? "已取消" : status === "RUNNING" ? "等待收敛" : "执行完成";
  return [
    { label: "已入队", time: task.value?.createTime, key: "queued" },
    { label: "开始执行", time: task.value?.startedAt, key: "running" },
    { label: terminalLabel, time: task.value?.completedAt, key: "terminal" },
  ];
});

// ==================== 状态 ====================

const loading = ref(false);
const task = ref(null);
const logs = ref([]);
const lastLogId = ref(null);
const opLoading = ref({});

// 轮询定时器
let statusTimer = null;
let logTimer = null;

// 日志容器引用（自动滚动到底）
const logContainerRef = ref(null);

// ==================== 数据加载 ====================

async function fetchTaskDetail() {
  loading.value = true;
  try {
    const data = await getTaskDetail(taskId.value);
    task.value = data;
    // 根据状态调整轮询
    updatePolling();
  } catch (error) {
    ElMessage.error("获取任务详情失败：" + (error.message || ""));
    task.value = null;
  } finally {
    loading.value = false;
  }
}

async function fetchLogs() {
  if (!taskId.value) return;
  try {
    const data = await getTaskLogs(taskId.value, lastLogId.value);
    const list = Array.isArray(data) ? data : [];
    if (list.length > 0) {
      logs.value.push(...list);
      lastLogId.value = list[list.length - 1].id;
      // 自动滚动到底部
      await nextTick();
      scrollToLogBottom();
    }
  } catch (error) {
    console.error("Failed to fetch task logs:", error);
  }
}

// ==================== 轮询 ====================

function isTerminal(status) {
  return ["COMPLETED", "FAILED", "CANCELLED"].includes(status);
}

function canCancel(status) {
  return ["PENDING", "RUNNING"].includes(status);
}

function updatePolling() {
  const status = task.value?.status;
  if (status && !isTerminal(status)) {
    // 任务进行中：3s 轮询状态 + 3s 增量拉日志
    startStatusPolling();
    startLogPolling();
  } else {
    stopAllPolling();
  }
}

function startStatusPolling() {
  if (statusTimer) return;
  statusTimer = setInterval(() => {
    fetchTaskDetail().then(() => {
      if (isTerminal(task.value?.status)) fetchLogs();
    });
  }, 3000);
}

function startLogPolling() {
  if (logTimer) return;
  logTimer = setInterval(() => {
    fetchLogs();
  }, 3000);
}

function stopAllPolling() {
  if (statusTimer) {
    clearInterval(statusTimer);
    statusTimer = null;
  }
  if (logTimer) {
    clearInterval(logTimer);
    logTimer = null;
  }
}

function scrollToLogBottom() {
  const el = logContainerRef.value;
  if (el) {
    el.scrollTop = el.scrollHeight;
  }
}

// ==================== 操作 ====================

async function handleCancel() {
  try {
    await ElMessageBox.confirm(
      `确定要取消任务「${task.value.taskTypeName || task.value.taskType}」吗？运行中的任务将等待 Handler 优雅退出。`,
      "取消任务",
      { type: "warning" }
    );
  } catch {
    return;
  }
  opLoading.value.cancel = true;
  try {
    await cancelTask(taskId.value);
    ElMessage.success("取消请求已发送");
    await fetchTaskDetail();
  } catch (error) {
    ElMessage.error(error.message || "取消失败");
  } finally {
    opLoading.value.cancel = false;
  }
}

async function handleRetry() {
  try {
    await ElMessageBox.confirm(
      `确定要重试任务「${task.value.taskTypeName || task.value.taskType}」吗？将基于原参数创建新任务。`,
      "重试任务",
      { type: "info" }
    );
  } catch {
    return;
  }
  opLoading.value.retry = true;
  try {
    const newTaskId = await retryTask(taskId.value);
    ElMessage.success(`重试已提交，新任务ID: ${newTaskId}`);
    router.push(`/task/detail/${newTaskId}`);
  } catch (error) {
    ElMessage.error(error.message || "重试失败");
  } finally {
    opLoading.value.retry = false;
  }
}

async function handleDelete() {
  try {
    await ElMessageBox.confirm(
      `确定要删除任务「${task.value.taskTypeName || task.value.taskType}」的记录吗？此操作不可恢复。`,
      "删除任务",
      { type: "error" }
    );
  } catch {
    return;
  }
  opLoading.value.delete = true;
  try {
    await deleteTask(taskId.value);
    ElMessage.success("任务已删除");
    router.push("/task");
  } catch (error) {
    ElMessage.error(error.message || "删除失败");
  } finally {
    opLoading.value.delete = false;
  }
}

function handleBack() {
  router.push("/task");
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
      return status || "-";
  }
}

function getLogLevelType(level) {
  switch ((level || "").toUpperCase()) {
    case "ERROR":
      return "danger";
    case "WARN":
      return "warning";
    case "INFO":
      return "info";
    default:
      return "";
  }
}

function formatDuration(ms) {
  if (!ms || ms < 0) return "-";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000)
    return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`;
  return `${Math.floor(ms / 3_600_000)}h${Math.floor((ms % 3_600_000) / 60_000)}m`;
}

function formatTime(time) {
  if (!time) return "-";
  return String(time).replace("T", " ").substring(0, 19);
}

function formatLogTime(log) {
  return formatTime(log?.createTime || log?.time);
}

function getPhaseState(index) {
  if (index < currentPhaseIndex.value) return "is-complete";
  if (index === currentPhaseIndex.value) return "is-current";
  return "is-pending";
}

// JSON 格式化展示
function formatJson(obj) {
  if (obj === null || obj === undefined) return "";
  try {
    return JSON.stringify(obj, null, 2);
  } catch {
    return String(obj);
  }
}

// ==================== 生命周期 ====================

onMounted(() => {
  fetchTaskDetail().then(() => {
    // 首次拉取日志
    fetchLogs();
  });
});

watch(taskId, async (nextId, previousId) => {
  if (!nextId || nextId === previousId) return;
  stopAllPolling();
  task.value = null;
  logs.value = [];
  lastLogId.value = null;
  await fetchTaskDetail();
  await fetchLogs();
});

onBeforeUnmount(() => {
  stopAllPolling();
});
</script>

<template>
  <div class="task-detail-page task-trace-page" v-loading="loading">
    <template v-if="task">
      <section class="trace-hero">
        <div class="trace-heading">
          <el-button class="trace-back" link @click="handleBack">
            <el-icon><ArrowLeft /></el-icon>
            返回执行流
          </el-button>
          <div class="trace-title-row">
            <div>
              <span class="section-kicker">EXECUTION TRACE / TASK RUN</span>
              <h1>{{ task.taskTypeName || task.taskType || "任务详情" }}</h1>
              <p>{{ task.scopeName || task.scopeKey || "全局调度任务" }} · {{ task.source }} / {{ task.taskType }}</p>
            </div>
            <el-tag :type="getStatusType(task.status)" effect="dark" class="trace-status">
              <span class="status-dot"></span>
              {{ getStatusLabel(task.status) }}
            </el-tag>
          </div>
          <div class="trace-id-line">
            <span>RUN ID</span>
            <code>{{ task.id }}</code>
            <span class="trace-separator">·</span>
            <span>作用域 {{ task.scopeType || "GLOBAL" }}</span>
          </div>
        </div>
        <div class="trace-actions">
          <div class="live-indicator" :class="{ 'is-live': !isTerminal(task.status) }">
            <span class="live-dot"></span>
            <span>{{ isTerminal(task.status) ? "轨迹已归档" : "实时追踪中" }}</span>
          </div>
          <div class="action-row">
            <el-button
              v-if="canCancel(task.status)"
              type="warning"
              plain
              :loading="opLoading.cancel"
              @click="handleCancel"
            >
              取消任务
            </el-button>
            <el-button
              v-if="task.retryable && isTerminal(task.status)"
              type="primary"
              :loading="opLoading.retry"
              @click="handleRetry"
            >
              重试
            </el-button>
            <el-button
              v-if="isTerminal(task.status)"
              type="danger"
              plain
              :loading="opLoading.delete"
              @click="handleDelete"
            >
              删除记录
            </el-button>
            <el-button @click="fetchTaskDetail">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </div>
      </section>

      <section class="phase-card" aria-label="任务执行阶段">
        <div class="phase-header">
          <div>
            <span class="section-kicker">RUN LIFECYCLE</span>
            <h2>执行轨迹</h2>
          </div>
          <div class="phase-summary">
            <strong>{{ task.progress || 0 }}%</strong>
            <span>{{ task.progressMessage || task.resultSummary || "等待执行器回报" }}</span>
          </div>
        </div>
        <div class="phase-track">
          <div
            v-for="(phase, index) in phaseItems"
            :key="phase.key"
            class="phase-item"
            :class="[
              getPhaseState(index),
              {
                'is-failure': index === 2 && task.status === 'FAILED',
                'is-cancelled': index === 2 && task.status === 'CANCELLED',
              },
            ]"
          >
            <div class="phase-node">
              <span>{{ index + 1 }}</span>
            </div>
            <div class="phase-copy">
              <strong>{{ phase.label }}</strong>
              <span>{{ formatTime(phase.time) }}</span>
            </div>
            <div v-if="index < phaseItems.length - 1" class="phase-connector"></div>
          </div>
        </div>
        <div class="trace-progress">
          <div class="progress-label">
            <span>当前执行进度</span>
            <strong>{{ task.progress || 0 }}%</strong>
          </div>
          <el-progress
            :percentage="task.progress || 0"
            :status="task.status === 'FAILED' ? 'exception' : task.status === 'COMPLETED' ? 'success' : undefined"
            :stroke-width="6"
            :show-text="false"
          />
        </div>
      </section>

      <section class="trace-metrics" aria-label="任务运行指标">
        <div class="trace-metric">
          <span>执行状态</span>
          <strong :class="`tone-${task.status.toLowerCase()}`">{{ getStatusLabel(task.status) }}</strong>
          <small>{{ task.progressMessage || task.resultSummary || "暂无状态描述" }}</small>
        </div>
        <div class="trace-metric">
          <span>任务耗时</span>
          <strong>{{ formatDuration(task.durationMs) }}</strong>
          <small>{{ formatTime(task.startedAt) }} 开始</small>
        </div>
        <div class="trace-metric">
          <span>重试额度</span>
          <strong>{{ task.retryCount || 0 }} / {{ task.maxRetryCount || 0 }}</strong>
          <small>{{ task.retryable ? "允许失败后重试" : "当前任务不可重试" }}</small>
        </div>
        <div class="trace-metric">
          <span>日志事件</span>
          <strong>{{ logs.length }}</strong>
          <small>{{ isTerminal(task.status) ? "已收敛" : "每 3 秒增量同步" }}</small>
        </div>
      </section>

      <div class="trace-grid">
        <div class="context-column">
          <section class="trace-panel context-panel">
            <div class="panel-heading">
              <div>
                <span class="section-kicker">RUN CONTEXT</span>
                <h2>运行上下文</h2>
              </div>
              <span class="panel-index">01</span>
            </div>
            <div class="context-list">
              <div class="context-row is-highlight">
                <span>作用域</span>
                <strong>{{ task.scopeName || task.scopeKey || "-" }}</strong>
                <small>{{ task.scopeType || "GLOBAL" }}</small>
              </div>
              <div class="context-row">
                <span>任务来源</span>
                <strong>{{ task.source || "-" }}</strong>
                <small>{{ task.submitter || "系统调度器" }}</small>
              </div>
              <div class="context-row">
                <span>创建时间</span>
                <strong>{{ formatTime(task.createTime) }}</strong>
                <small>提交任务</small>
              </div>
              <div class="context-row">
                <span>完成时间</span>
                <strong>{{ formatTime(task.completedAt) }}</strong>
                <small>{{ task.parentTaskId ? "由重试任务衍生" : "当前执行链" }}</small>
              </div>
              <div v-if="task.parentTaskId" class="context-row">
                <span>原任务</span>
                <router-link :to="`/task/detail/${task.parentTaskId}`" class="link-text">
                  {{ task.parentTaskId }}
                </router-link>
                <small>父级执行链</small>
              </div>
            </div>
          </section>

          <section v-if="task.status === 'FAILED' && (task.errorMessage || task.stackTrace)" class="trace-panel failure-panel">
            <div class="panel-heading">
              <div>
                <span class="section-kicker">FAILURE SIGNAL</span>
                <h2>失败信号</h2>
              </div>
              <el-icon><WarningFilled /></el-icon>
            </div>
            <div v-if="task.errorMessage" class="failure-message">{{ task.errorMessage }}</div>
            <el-collapse v-if="task.stackTrace" class="stack-collapse">
              <el-collapse-item title="展开堆栈跟踪" name="stack">
                <pre class="stack-trace">{{ task.stackTrace }}</pre>
              </el-collapse-item>
            </el-collapse>
          </section>

          <section class="payload-grid">
            <div class="trace-panel data-panel">
              <div class="panel-heading compact-heading">
                <div>
                  <span class="section-kicker">INPUT</span>
                  <h2>输入参数</h2>
                </div>
                <span class="data-label">PAYLOAD</span>
              </div>
              <pre v-if="task.payload !== null && task.payload !== undefined" class="json-viewer">{{ formatJson(task.payload) }}</pre>
              <el-empty v-else description="无参数" :image-size="48" />
            </div>
            <div class="trace-panel data-panel">
              <div class="panel-heading compact-heading">
                <div>
                  <span class="section-kicker">OUTPUT</span>
                  <h2>输出结果</h2>
                </div>
                <span class="data-label">RESULT</span>
              </div>
              <pre v-if="task.result !== null && task.result !== undefined" class="json-viewer">{{ formatJson(task.result) }}</pre>
              <el-empty v-else description="暂无结果" :image-size="48" />
            </div>
          </section>
        </div>

        <section class="trace-panel log-panel">
          <div class="panel-heading log-heading">
            <div>
              <span class="section-kicker">EVENT STREAM</span>
              <h2>执行日志</h2>
              <p>按时间顺序回放任务执行证据</p>
            </div>
            <div class="log-tools">
              <span class="log-count">{{ logs.length }} EVENTS</span>
              <el-button v-if="!isTerminal(task.status)" link type="primary" size="small" @click="fetchLogs">
                手动刷新
              </el-button>
            </div>
          </div>
          <div ref="logContainerRef" class="log-container">
            <div v-if="logs.length === 0" class="log-empty">
              <el-icon><Tickets /></el-icon>
              <strong>等待首条执行事件</strong>
              <span>日志会随着任务运行自动出现</span>
            </div>
            <div v-for="log in logs" :key="log.id" class="log-item" :class="`is-${(log.level || 'INFO').toLowerCase()}`">
              <div class="log-rail">
                <span class="log-node"></span>
                <span class="log-time">{{ formatLogTime(log) }}</span>
              </div>
              <div class="log-body">
                <div class="log-meta">
                  <el-tag :type="getLogLevelType(log.level)" size="small" effect="plain" class="log-level">
                    {{ (log.level || "INFO").toUpperCase() }}
                  </el-tag>
                  <span>event/{{ log.id }}</span>
                </div>
                <p>{{ log.message }}</p>
              </div>
            </div>
          </div>
        </section>
      </div>
    </template>

    <section v-else-if="!loading" class="trace-panel not-found-card">
      <el-empty description="任务不存在或已被删除">
        <el-button type="primary" @click="handleBack">返回任务列表</el-button>
      </el-empty>
    </section>
  </div>
</template>

<style lang="scss" scoped>
.task-trace-page {
  --trace-accent: #ee8358;
  --trace-cyan: #55c9dc;
  --trace-line: rgba(63, 83, 101, 0.7);
  --trace-panel: var(--platform-surface-1);
  --trace-deep: var(--platform-surface-0);

  padding: 4px 2px 28px;
  color: var(--platform-text-primary);
}

.trace-hero,
.phase-card,
.trace-metrics,
.trace-panel {
  border: 1px solid var(--platform-line);
  background: var(--trace-panel);
}

.trace-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  min-height: 190px;
  padding: 22px 26px 24px;
  border-radius: 6px;
  background:
    linear-gradient(122deg, rgba(238, 131, 88, 0.17), transparent 42%),
    linear-gradient(90deg, rgba(85, 201, 220, 0.06), transparent 68%),
    var(--trace-panel);
  box-shadow: 0 16px 32px rgba(0, 0, 0, 0.15);
}

.trace-heading {
  min-width: 0;
  flex: 1;
}

.trace-back {
  padding: 0;
  color: var(--platform-text-secondary);

  &:hover {
    color: var(--trace-cyan);
  }
}

.trace-title-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-top: 18px;
}

.section-kicker {
  display: block;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  line-height: 1.4;
}

.trace-title-row h1 {
  margin: 7px 0 6px;
  color: var(--platform-text-primary);
  font-size: clamp(24px, 2.6vw, 34px);
  font-weight: 700;
  letter-spacing: -0.035em;
}

.trace-title-row p {
  margin: 0;
  color: var(--platform-text-secondary);
  font-size: 13px;
}

.trace-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 19px;
  border-color: transparent;
  font-size: 11px;
  letter-spacing: 0.03em;
}

.status-dot,
.live-dot,
.log-node {
  display: inline-block;
  border-radius: 50%;
}

.status-dot {
  width: 7px;
  height: 7px;
  background: currentColor;
}

.trace-id-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 22px;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.03em;

  code {
    color: var(--platform-text-regular);
    font-family: inherit;
  }

  .trace-separator {
    color: var(--trace-line);
  }
}

.trace-actions {
  display: grid;
  justify-items: end;
  gap: 16px;
}

.live-indicator {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.04em;

  &.is-live {
    color: var(--trace-cyan);

    .live-dot {
      background: var(--trace-cyan);
      box-shadow: 0 0 0 4px rgba(85, 201, 220, 0.12);
    }
  }
}

.live-dot {
  width: 7px;
  height: 7px;
  background: var(--platform-text-muted);
}

.action-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.phase-card {
  margin-top: 14px;
  padding: 18px 22px 20px;
  border-radius: 5px;
  background:
    linear-gradient(90deg, rgba(85, 201, 220, 0.05), transparent 58%),
    var(--platform-surface-2);
}

.phase-header,
.panel-heading,
.log-heading,
.progress-label,
.phase-summary {
  display: flex;
  align-items: center;
}

.phase-header,
.panel-heading,
.log-heading {
  justify-content: space-between;
  gap: 16px;
}

.phase-header h2,
.panel-heading h2 {
  margin: 5px 0 0;
  color: var(--platform-text-primary);
  font-size: 16px;
  font-weight: 650;
}

.phase-summary {
  align-items: baseline;
  gap: 10px;
  text-align: right;

  strong {
    color: var(--trace-cyan);
    font-family: var(--el-font-family-mono);
    font-size: 20px;
    font-weight: 600;
  }

  span {
    max-width: 260px;
    overflow: hidden;
    color: var(--platform-text-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.phase-track {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin: 22px 0 19px;
}

.phase-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-height: 38px;

  &:not(:first-child) {
    padding-left: 30px;
  }

  &.is-complete,
  &.is-current {
    .phase-node {
      border-color: var(--trace-cyan);
      background: rgba(85, 201, 220, 0.14);
      color: var(--trace-cyan);
    }

    .phase-copy strong {
      color: var(--platform-text-primary);
    }
  }

  &.is-current {
    .phase-node {
      box-shadow: 0 0 0 4px rgba(85, 201, 220, 0.1);
    }

    .phase-copy strong {
      color: var(--trace-cyan);
    }
  }

  &.is-pending {
    .phase-node {
      border-color: var(--trace-line);
      color: var(--platform-text-muted);
    }
  }

  &.is-failure.is-current {
    .phase-node {
      border-color: var(--platform-red);
      background: rgba(240, 100, 106, 0.14);
      color: var(--platform-red);
      box-shadow: 0 0 0 4px rgba(240, 100, 106, 0.1);
    }

    .phase-copy strong {
      color: var(--platform-red);
    }
  }

  &.is-cancelled.is-current {
    .phase-node {
      border-color: var(--trace-accent);
      background: rgba(238, 131, 88, 0.14);
      color: var(--trace-accent);
    }

    .phase-copy strong {
      color: var(--trace-accent);
    }
  }
}

.phase-node {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  border: 1px solid var(--trace-line);
  border-radius: 50%;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  transition: all 0.2s ease;
}

.phase-copy {
  display: grid;
  gap: 4px;

  strong {
    color: var(--platform-text-secondary);
    font-size: 12px;
    font-weight: 600;
  }

  span {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
  }
}

.phase-connector {
  position: absolute;
  top: 12px;
  right: 18px;
  left: 68px;
  height: 1px;
  background: var(--trace-line);
}

.phase-item.is-complete .phase-connector {
  background: rgba(85, 201, 220, 0.55);
}

.trace-progress {
  display: grid;
  gap: 8px;

  :deep(.el-progress-bar__outer) {
    background: rgba(104, 120, 138, 0.18);
  }

  :deep(.el-progress-bar__inner) {
    background: var(--trace-cyan);
  }
}

.progress-label {
  justify-content: space-between;

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  strong {
    color: var(--platform-text-regular);
    font-family: var(--el-font-family-mono);
    font-size: 11px;
  }
}

.trace-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin-top: 14px;
  border-radius: 5px;
  background: var(--platform-surface-1);
}

.trace-metric {
  display: grid;
  gap: 5px;
  min-height: 88px;
  padding: 14px 17px;
  border-right: 1px solid var(--platform-line);

  &:last-child {
    border-right: 0;
  }

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  strong {
    color: var(--platform-text-primary);
    font-family: var(--el-font-family-mono);
    font-size: 17px;
    font-weight: 600;
  }

  small {
    overflow: hidden;
    color: var(--platform-text-muted);
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tone-running {
    color: var(--trace-cyan);
  }

  .tone-pending {
    color: var(--trace-accent);
  }

  .tone-failed {
    color: var(--platform-red);
  }

  .tone-completed {
    color: var(--platform-green);
  }
}

.trace-grid {
  display: grid;
  grid-template-columns: minmax(320px, 0.84fr) minmax(0, 1.16fr);
  gap: 14px;
  margin-top: 14px;
}

.context-column {
  display: grid;
  align-content: start;
  gap: 14px;
  min-width: 0;
}

.trace-panel {
  min-width: 0;
  border-radius: 5px;
  overflow: hidden;
}

.panel-heading {
  min-height: 68px;
  padding: 14px 17px;
  border-bottom: 1px solid var(--platform-line);
}

.panel-heading h2 {
  font-size: 15px;
}

.panel-index,
.data-label,
.log-count {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
}

.context-list {
  padding: 3px 17px 7px;
}

.context-row {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 48px;
  border-bottom: 1px solid rgba(63, 83, 101, 0.46);

  &:last-child {
    border-bottom: 0;
  }

  > span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  > strong,
  > a {
    overflow: hidden;
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 550;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  > small {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
  }

  &.is-highlight > strong {
    color: var(--trace-cyan);
  }
}

.link-text {
  text-decoration: none;

  &:hover {
    color: var(--trace-cyan);
  }
}

.failure-panel {
  border-color: rgba(240, 100, 106, 0.36);
  background:
    linear-gradient(120deg, rgba(240, 100, 106, 0.1), transparent 62%),
    var(--trace-panel);

  .panel-heading {
    .el-icon {
      color: var(--platform-red);
      font-size: 18px;
    }
  }
}

.failure-message {
  margin: 14px 17px 0;
  padding: 11px 12px;
  border-left: 2px solid var(--platform-red);
  background: rgba(240, 100, 106, 0.08);
  color: #ff9b9f;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.stack-collapse {
  margin: 12px 17px 15px;
  border-top: 1px solid var(--platform-line);
  border-bottom: 0;

  :deep(.el-collapse-item__header) {
    height: 36px;
    border-bottom: 0;
    background: transparent;
    color: var(--platform-text-secondary);
    font-size: 11px;
  }

  :deep(.el-collapse-item__wrap) {
    border-bottom: 0;
    background: transparent;
  }

  :deep(.el-collapse-item__content) {
    padding-bottom: 2px;
  }
}

.stack-trace,
.json-viewer {
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--platform-line);
  border-radius: 3px;
  background: var(--trace-deep);
  color: var(--platform-text-regular);
  font-family: var(--el-font-family-mono);
  font-size: 11px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.stack-trace {
  max-height: 260px;
  color: #ffafb3;
}

.payload-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.compact-heading {
  min-height: 60px;
  padding: 11px 13px;
}

.data-label {
  color: var(--trace-cyan);
}

.data-panel :deep(.el-empty) {
  min-height: 120px;
  padding: 0;
}

.data-panel :deep(.el-empty__description p) {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.data-panel .json-viewer {
  min-height: 128px;
  max-height: 220px;
  border: 0;
  border-radius: 0;
}

.log-panel {
  min-height: 560px;
}

.log-heading {
  align-items: flex-start;
  min-height: 86px;
  padding: 16px 18px 14px;
}

.log-heading p {
  margin: 5px 0 0;
  color: var(--platform-text-muted);
  font-size: 11px;
}

.log-tools {
  display: grid;
  justify-items: end;
  gap: 9px;
}

.log-count {
  color: var(--trace-cyan);
}

.log-container {
  max-height: 650px;
  padding: 8px 18px 18px;
  overflow-y: auto;
  background:
    linear-gradient(90deg, rgba(85, 201, 220, 0.025), transparent 45%),
    var(--trace-deep);
}

.log-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 100px 0;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--trace-cyan);
    font-size: 24px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
  }

  span {
    font-size: 11px;
  }
}

.log-item {
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr);
  gap: 14px;
  min-height: 76px;
  border-bottom: 1px solid rgba(63, 83, 101, 0.5);

  &:last-child {
    border-bottom: 0;
  }

  &.is-error {
    .log-node {
      background: var(--platform-red);
      box-shadow: 0 0 0 4px rgba(240, 100, 106, 0.1);
    }

    .log-body p {
      color: #ffafb3;
    }
  }

  &.is-warn .log-node {
    background: var(--trace-accent);
    box-shadow: 0 0 0 4px rgba(238, 131, 88, 0.1);
  }
}

.log-rail {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding-top: 20px;

  &::after {
    position: absolute;
    top: 33px;
    bottom: -1px;
    left: 4px;
    width: 1px;
    background: rgba(63, 83, 101, 0.7);
    content: "";
  }
}

.log-node {
  position: relative;
  z-index: 1;
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
  margin-top: 2px;
  background: var(--trace-cyan);
  box-shadow: 0 0 0 4px rgba(85, 201, 220, 0.08);
}

.log-time {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
}

.log-body {
  min-width: 0;
  padding: 16px 0 17px;
}

.log-meta {
  display: flex;
  align-items: center;
  gap: 9px;

  > span {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
  }
}

.log-level {
  min-width: 48px;
  justify-content: center;
  font-family: var(--el-font-family-mono);
  font-size: 9px;
}

.log-body p {
  margin: 8px 0 0;
  color: var(--platform-text-regular);
  font-family: var(--el-font-family-mono);
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.not-found-card {
  min-height: 310px;

  :deep(.el-empty) {
    padding-top: 82px;
  }
}

.task-trace-page :deep(.el-button) {
  --el-button-hover-text-color: var(--trace-cyan);
}

.task-trace-page :deep(.el-progress-bar__inner.is-success) {
  background: var(--platform-green);
}

.task-trace-page :deep(.el-progress-bar__inner.is-exception) {
  background: var(--platform-red);
}

@media screen and (max-width: 1120px) {
  .trace-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .trace-actions {
    justify-items: start;
    width: 100%;
  }

  .trace-grid {
    grid-template-columns: 1fr;
  }

  .log-panel {
    min-height: auto;
  }
}

@media screen and (max-width: 760px) {
  .trace-hero,
  .phase-card {
    padding: 17px;
  }

  .trace-title-row,
  .phase-header,
  .log-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .trace-title-row {
    gap: 4px;
  }

  .trace-status {
    margin-top: 7px;
  }

  .trace-id-line {
    flex-wrap: wrap;
    margin-top: 16px;
  }

  .action-row {
    flex-wrap: wrap;
  }

  .phase-summary {
    text-align: left;
  }

  .phase-track {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .phase-item:not(:first-child) {
    padding-left: 0;
  }

  .phase-connector {
    top: 32px;
    right: auto;
    bottom: -14px;
    left: 12px;
    width: 1px;
    height: auto;
  }

  .trace-metrics {
    grid-template-columns: repeat(2, 1fr);
  }

  .trace-metric:nth-child(2) {
    border-right: 0;
  }

  .trace-metric:nth-child(-n + 2) {
    border-bottom: 1px solid var(--platform-line);
  }

  .payload-grid {
    grid-template-columns: 1fr;
  }

  .context-row {
    grid-template-columns: 70px minmax(0, 1fr);

    > small {
      grid-column: 2;
      margin-top: -8px;
    }
  }

  .log-item {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .log-rail {
    padding-top: 15px;

    &::after {
      top: 28px;
    }
  }

  .log-body {
    padding: 4px 0 15px 19px;
  }
}
</style>
