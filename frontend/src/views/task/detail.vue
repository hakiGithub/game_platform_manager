<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from "vue";
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
    // 终态时补拉一次日志（可能还有未拉取的日志）
    if (status) {
      fetchLogs();
    }
  }
}

function startStatusPolling() {
  if (statusTimer) return;
  statusTimer = setInterval(() => {
    fetchTaskDetail();
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

onBeforeUnmount(() => {
  stopAllPolling();
});
</script>

<template>
  <div class="task-detail-page" v-loading="loading">
    <!-- 顶部操作栏 -->
    <el-card class="header-card" shadow="never">
      <div class="header-bar">
        <div class="header-left">
          <el-button :icon="'ArrowLeft'" link @click="handleBack">返回</el-button>
          <el-divider direction="vertical" />
          <span class="task-title">
            {{ task?.taskTypeName || task?.taskType || "任务详情" }}
          </span>
          <el-tag v-if="task" :type="getStatusType(task.status)" size="small" class="status-tag">
            {{ getStatusLabel(task.status) }}
          </el-tag>
          <el-tag v-if="task" size="small" type="info" class="source-tag">
            {{ task.source }}
          </el-tag>
        </div>
        <div class="header-right">
          <el-button
            v-if="task && canCancel(task.status)"
            type="warning"
            size="small"
            :loading="opLoading.cancel"
            @click="handleCancel"
          >
            取消任务
          </el-button>
          <el-button
            v-if="task && task.retryable && isTerminal(task.status)"
            type="primary"
            size="small"
            :loading="opLoading.retry"
            @click="handleRetry"
          >
            重试
          </el-button>
          <el-button
            v-if="task && isTerminal(task.status)"
            type="danger"
            size="small"
            :loading="opLoading.delete"
            @click="handleDelete"
          >
            删除
          </el-button>
          <el-button size="small" @click="fetchTaskDetail">刷新</el-button>
        </div>
      </div>

      <!-- 进度条 -->
      <div v-if="task && (task.status === 'RUNNING' || task.status === 'PENDING')" class="progress-wrapper">
        <el-progress
          :percentage="task.progress || 0"
          :status="task.status === 'RUNNING' ? undefined : 'warning'"
          :stroke-width="18"
          :text-inside="true"
        />
        <div v-if="task.progressMessage" class="progress-message">
          {{ task.progressMessage }}
        </div>
      </div>
    </el-card>

    <template v-if="task">
      <!-- 基础信息卡片 -->
      <el-card class="info-card" shadow="never">
        <template #header>
          <div class="card-title">
            <el-icon><InfoFilled /></el-icon>
            <span>基础信息</span>
          </div>
        </template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="任务ID">
            <span class="mono-text">{{ task.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="任务类型">
            {{ task.taskTypeName || task.taskType }}
            <div class="sub-text">{{ task.source }} / {{ task.taskType }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(task.status)" size="small">
              {{ getStatusLabel(task.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源">
            <el-tag size="small" type="info">{{ task.source }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="提交者">
            {{ task.submitter || "-" }}
          </el-descriptions-item>
          <el-descriptions-item label="作用域">
            <span v-if="task.scopeName">{{ task.scopeName }}</span>
            <span v-else-if="task.scopeKey" class="mono-text">{{ task.scopeKey }}</span>
            <span v-else>-</span>
            <div v-if="task.scopeType" class="sub-text">{{ task.scopeType }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatTime(task.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">
            {{ formatTime(task.startedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="完成时间">
            {{ formatTime(task.completedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="耗时">
            {{ formatDuration(task.durationMs) }}
          </el-descriptions-item>
          <el-descriptions-item label="重试">
            <span v-if="task.retryCount > 0 || task.maxRetryCount > 0">
              {{ task.retryCount || 0 }} / {{ task.maxRetryCount || 0 }}
            </span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="task.parentTaskId" label="原任务ID">
            <router-link :to="`/task/detail/${task.parentTaskId}`" class="link-text">
              {{ task.parentTaskId }}
            </router-link>
          </el-descriptions-item>
          <el-descriptions-item v-if="task.resultSummary" label="结果摘要" :span="3">
            {{ task.resultSummary }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 错误信息卡片（仅 FAILED） -->
      <el-card v-if="task.status === 'FAILED' && (task.errorMessage || task.stackTrace)" class="error-card" shadow="never">
        <template #header>
          <div class="card-title error-title">
            <el-icon><WarningFilled /></el-icon>
            <span>错误信息</span>
          </div>
        </template>
        <div v-if="task.errorMessage" class="error-message">
          {{ task.errorMessage }}
        </div>
        <el-collapse v-if="task.stackTrace" class="stack-collapse">
          <el-collapse-item title="堆栈跟踪" name="stack">
            <pre class="stack-trace">{{ task.stackTrace }}</pre>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <!-- 输入参数 / 输出结果 -->
      <el-row :gutter="16" class="json-row">
        <el-col :span="12">
          <el-card class="json-card" shadow="never">
            <template #header>
              <div class="card-title">
                <el-icon><Document /></el-icon>
                <span>输入参数 (Payload)</span>
              </div>
            </template>
            <pre v-if="task.payload !== null && task.payload !== undefined" class="json-viewer">{{ formatJson(task.payload) }}</pre>
            <el-empty v-else description="无参数" :image-size="60" />
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card class="json-card" shadow="never">
            <template #header>
              <div class="card-title">
                <el-icon><DocumentChecked /></el-icon>
                <span>输出结果 (Result)</span>
              </div>
            </template>
            <pre v-if="task.result !== null && task.result !== undefined" class="json-viewer">{{ formatJson(task.result) }}</pre>
            <el-empty v-else description="暂无结果" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>

      <!-- 任务日志 -->
      <el-card class="log-card" shadow="never">
        <template #header>
          <div class="card-title">
            <el-icon><Tickets /></el-icon>
            <span>任务日志</span>
            <el-tag v-if="logs.length > 0" size="small" type="info" class="log-count">
              {{ logs.length }} 条
            </el-tag>
            <el-button
              v-if="task && !isTerminal(task.status)"
              link
              type="primary"
              size="small"
              class="log-refresh-btn"
              @click="fetchLogs"
            >
              手动刷新
            </el-button>
          </div>
        </template>
        <div ref="logContainerRef" class="log-container">
          <div v-if="logs.length === 0" class="log-empty">暂无日志</div>
          <div v-for="log in logs" :key="log.id" class="log-item">
            <span class="log-time">{{ formatTime(log.createTime) }}</span>
            <el-tag :type="getLogLevelType(log.level)" size="small" class="log-level">
              {{ (log.level || "INFO").toUpperCase() }}
            </el-tag>
            <span class="log-message">{{ log.message }}</span>
          </div>
        </div>
      </el-card>
    </template>

    <!-- 任务不存在 -->
    <el-card v-else-if="!loading" shadow="never" class="not-found-card">
      <el-empty description="任务不存在或已被删除">
        <el-button type="primary" @click="handleBack">返回任务列表</el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.task-detail-page {
  padding: var(--spacing-md);

  .header-card {
    margin-bottom: var(--spacing-md);

    :deep(.el-card__body) {
      padding: var(--spacing-md) var(--spacing-lg);
    }
  }

  .header-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--spacing-sm);

    .header-left {
      display: flex;
      align-items: center;
      gap: var(--spacing-sm);

      .task-title {
        font-size: 16px;
        font-weight: 600;
      }

      .status-tag,
      .source-tag {
        margin-left: 4px;
      }
    }

    .header-right {
      display: flex;
      gap: var(--spacing-sm);
    }
  }

  .progress-wrapper {
    margin-top: var(--spacing-md);

    .progress-message {
      margin-top: var(--spacing-xs);
      font-size: 12px;
      color: var(--el-text-color-secondary);
      text-align: center;
    }
  }

  .info-card,
  .error-card,
  .json-card,
  .log-card {
    margin-bottom: var(--spacing-md);
  }

  .card-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 600;

    .log-count {
      margin-left: 8px;
    }

    .log-refresh-btn {
      margin-left: auto;
    }
  }

  .error-title {
    color: var(--el-color-danger);
  }

  .error-message {
    color: var(--el-color-danger);
    font-weight: 500;
    padding: var(--spacing-sm);
    background-color: var(--el-color-danger-light-9);
    border-radius: 4px;
    word-break: break-word;
    white-space: pre-wrap;
  }

  .stack-collapse {
    margin-top: var(--spacing-sm);
  }

  .stack-trace {
    background-color: #1e1e1e;
    color: #d4d4d4;
    padding: var(--spacing-md);
    border-radius: 4px;
    font-family: "Consolas", "Monaco", monospace;
    font-size: 12px;
    line-height: 1.6;
    overflow-x: auto;
    max-height: 400px;
    overflow-y: auto;
    margin: 0;
    white-space: pre-wrap;
    word-break: break-word;
  }

  .json-row {
    margin-bottom: var(--spacing-md);
  }

  .json-viewer {
    background-color: #f5f7fa;
    padding: var(--spacing-md);
    border-radius: 4px;
    font-family: "Consolas", "Monaco", monospace;
    font-size: 12px;
    line-height: 1.6;
    overflow-x: auto;
    max-height: 400px;
    overflow-y: auto;
    margin: 0;
    white-space: pre-wrap;
    word-break: break-word;
    color: #303133;
  }

  .log-container {
    max-height: 500px;
    overflow-y: auto;
    background-color: #1e1e1e;
    border-radius: 4px;
    padding: var(--spacing-sm);

    .log-empty {
      text-align: center;
      color: #909399;
      padding: var(--spacing-lg) 0;
      font-size: 13px;
    }

    .log-item {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      padding: 4px 0;
      font-family: "Consolas", "Monaco", monospace;
      font-size: 12px;
      line-height: 1.6;

      .log-time {
        color: #909399;
        flex-shrink: 0;
        width: 150px;
      }

      .log-level {
        flex-shrink: 0;
        min-width: 50px;
        text-align: center;
      }

      .log-message {
        color: #d4d4d4;
        white-space: pre-wrap;
        word-break: break-word;
        flex: 1;
      }
    }
  }

  .sub-text {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    margin-top: 2px;
  }

  .mono-text {
    font-family: "Consolas", "Monaco", monospace;
    font-size: 12px;
  }

  .link-text {
    color: var(--el-color-primary);
    text-decoration: none;
    font-family: "Consolas", "Monaco", monospace;
    font-size: 12px;

    &:hover {
      text-decoration: underline;
    }
  }

  .not-found-card {
    :deep(.el-card__body) {
      padding: var(--spacing-xl) 0;
    }
  }
}
</style>
