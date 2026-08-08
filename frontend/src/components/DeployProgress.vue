<script setup>
import {
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  nextTick,
  watch,
} from "vue";
import { ElMessage } from "element-plus";
import { getDeployProgress, getInstanceLogs } from "@/api/instance";

const props = defineProps({
  visible: { type: Boolean, default: false },
  taskId: { type: String, default: "" }, // 保留向后兼容
  instanceId: { type: [Number, String], default: "" }, // 新增，优先使用
  mode: { type: String, default: "deploy" }, // "deploy" | "runtime"
});

const emit = defineEmits(["update:visible", "complete"]);

// 进度数据
const progress = ref(0);
const status = ref("pending");
const statusText = ref("准备中...");
const logs = ref([]);
const error = ref("");
const startTime = ref(null);
const elapsedTime = ref(0);

// 定时器
let progressTimer = null;
let elapsedTimer = null;

// 日志容器引用
const logsContainerRef = ref(null);

// 状态映射
const statusMap = {
  pending: { text: "等待中", type: "info", icon: "Timer" },
  preparing: { text: "准备中", type: "primary", icon: "Box" },
  downloading: { text: "下载中", type: "primary", icon: "Download" },
  installing: { text: "安装中", type: "warning", icon: "SetUp" },
  configuring: { text: "配置中", type: "warning", icon: "Tools" },
  starting: { text: "启动中", type: "warning", icon: "VideoPlay" },
  checking: { text: "检查中", type: "warning", icon: "Search" },
  completed: { text: "已完成", type: "success", icon: "CircleCheck" },
  failed: { text: "失败", type: "danger", icon: "CircleClose" },
  cancelled: { text: "已取消", type: "info", icon: "CircleClose" },
};

// 当前状态信息
const currentStatus = computed(() => {
  return statusMap[status.value] || statusMap.pending;
});

// 对话框标题（根据模式动态显示）
const dialogTitle = computed(() => {
  return props.mode === "runtime" ? "实例日志" : "部署进度";
});

// 是否已完成
const isCompleted = computed(() => {
  return ["completed", "failed", "cancelled"].includes(status.value);
});

// 是否允许关闭（runtime 模式随时可关闭，deploy 模式需完成后或强制关闭）
const canClose = computed(() => {
  return props.mode === "runtime" || isCompleted.value;
});

// 是否成功
const isSuccess = computed(() => {
  return status.value === "completed";
});

// 格式化耗时
const formattedElapsedTime = computed(() => {
  const seconds = elapsedTime.value;
  if (seconds < 60) {
    return `${seconds}秒`;
  } else if (seconds < 3600) {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}分${secs}秒`;
  } else {
    const hours = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    return `${hours}小时${mins}分`;
  }
});

// 获取日志级别样式
function getLogClass(level) {
  const classes = {
    info: "log-info",
    success: "log-success",
    warning: "log-warning",
    error: "log-error",
    debug: "log-debug",
  };
  return classes[level] || "log-info";
}

// 获取日志图标
function getLogIcon(level) {
  const icons = {
    info: "InfoFilled",
    success: "CircleCheck",
    warning: "Warning",
    error: "CircleClose",
    debug: "View",
  };
  return icons[level] || "InfoFilled";
}

// 滚动到底部
function scrollToBottom() {
  nextTick(() => {
    if (logsContainerRef.value) {
      logsContainerRef.value.scrollTop = logsContainerRef.value.scrollHeight;
    }
  });
}

// 运行时模式：最后 5 行 hash 用于去重
let runtimeLastHash = "";

function appendRuntimeLogs(content) {
  if (!content) return;
  const lines = content.split("\n").filter((l) => l.trim());
  if (lines.length === 0) return;

  // 按最后 5 行 hash 去重
  const last5 = lines.slice(-5).join("|");
  if (runtimeLastHash && last5 === runtimeLastHash) {
    return; // 内容未变化
  }

  // 找到与已存在日志的重复点
  const existingMessages = new Set(logs.value.map((l) => l.message));
  const newLogs = [];
  for (const line of lines) {
    if (!existingMessages.has(line)) {
      newLogs.push({
        id: Date.now() + Math.random(),
        level: "info",
        message: line,
        time: new Date().toLocaleTimeString(),
      });
    }
  }
  if (newLogs.length > 0) {
    logs.value.push(...newLogs);
    // 限制最多 1000 条
    if (logs.value.length > 1000) {
      logs.value = logs.value.slice(-1000);
    }
    scrollToBottom();
  }
  runtimeLastHash = last5;
}

// 获取部署进度
async function fetchProgress() {
  const targetId = props.instanceId || props.taskId;
  if (!targetId) return;

  try {
    if (props.mode === "runtime") {
      // 运行时模式：轮询容器日志
      const data = await getInstanceLogs(targetId, { lines: 200 });
      // 后端返回 { instanceId, lines, content }
      appendRuntimeLogs(data.content || data.logs || "");
      return;
    }

    // 部署模式：轮询 deploy-progress
    const data = await getDeployProgress(targetId);

    progress.value = data.progress || 0;
    status.value = data.status || "pending";
    statusText.value =
      data.statusText || statusMap[status.value]?.text || "处理中...";

    // 添加新日志（按 id 去重）
    if (data.logs && data.logs.length > 0) {
      const existingIds = new Set(logs.value.map((l) => l.id));
      const newLogs = data.logs.filter((l) => !existingIds.has(l.id));

      if (newLogs.length > 0) {
        logs.value.push(...newLogs);
        scrollToBottom();
      }
    }

    // 更新错误信息
    if (data.error) {
      error.value = data.error;
    }

    // 检查是否完成
    if (data.completed || isCompleted.value) {
      stopProgressPolling();
      emit("complete", isSuccess.value);
    }
  } catch (err) {
    console.error("Failed to fetch progress:", err);
    addLog({
      level: "error",
      message: "获取进度失败: " + (err.message || "未知错误"),
      time: new Date().toLocaleTimeString(),
    });
  }
}

// 添加日志
function addLog(log) {
  logs.value.push({
    id: Date.now(),
    ...log,
  });
  scrollToBottom();
}

// 开始轮询进度
function startProgressPolling() {
  // 先清除可能存在的旧定时器，避免重复调用导致定时器泄漏
  // 场景：visible 和 instanceId 同时变化会触发两个 watch，各自调用一次 startProgressPolling
  // 若不先清除，第一次创建的定时器会被第二次覆盖变量，永远无法被 stopProgressPolling 清除
  stopProgressPolling();

  // 立即获取一次
  fetchProgress();

  // 设置定时器
  progressTimer = setInterval(fetchProgress, 2000);

  // 启动耗时计时器
  elapsedTimer = setInterval(() => {
    elapsedTime.value++;
  }, 1000);
}

// 停止轮询
function stopProgressPolling() {
  if (progressTimer) {
    clearInterval(progressTimer);
    progressTimer = null;
  }
  if (elapsedTimer) {
    clearInterval(elapsedTimer);
    elapsedTimer = null;
  }
}

// 重试部署
function handleRetry() {
  error.value = "";
  logs.value = [];
  progress.value = 0;
  status.value = "pending";
  elapsedTime.value = 0;
  startTime.value = Date.now();

  addLog({
    level: "info",
    message: "重新启动部署...",
    time: new Date().toLocaleTimeString(),
  });

  startProgressPolling();
}

// 关闭弹窗
function handleClose() {
  if (props.mode === "runtime") {
    stopProgressPolling();
    emit("update:visible", false);
    return;
  }
  if (!isCompleted.value) {
    ElMessage.warning("部署正在进行中，请等待完成或取消部署");
    return;
  }
  emit("update:visible", false);
}

// 强制关闭
function handleForceClose() {
  stopProgressPolling();
  emit("update:visible", false);
}

// 监听显示状态
watch(
  () => props.visible,
  (val) => {
    const targetId = props.instanceId || props.taskId;
    if (val && targetId) {
      // 重置状态
      progress.value = 0;
      status.value = "pending";
      logs.value = [];
      error.value = "";
      elapsedTime.value = 0;
      startTime.value = Date.now();
      runtimeLastHash = "";
      if (props.mode === "deploy") {
        addLog({
          level: "info",
          message: "开始部署...",
          time: new Date().toLocaleTimeString(),
        });
      } else {
        addLog({
          level: "info",
          message: "开始获取运行日志...",
          time: new Date().toLocaleTimeString(),
        });
      }
      startProgressPolling();
    } else {
      stopProgressPolling();
    }
  },
);

// 监听任务ID变化
watch(
  () => [props.instanceId, props.taskId],
  () => {
    const targetId = props.instanceId || props.taskId;
    if (targetId && props.visible) {
      startProgressPolling();
    }
  },
);

onBeforeUnmount(() => {
  stopProgressPolling();
});
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="700px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="canClose"
    class="deploy-progress-dialog"
    @close="handleClose"
  >
    <div class="deploy-progress-content">
      <!-- 进度概览 -->
      <div v-if="mode === 'deploy'" class="progress-overview">
        <div class="progress-status">
          <el-icon :size="48" :class="currentStatus.type">
            <component :is="currentStatus.icon" />
          </el-icon>
          <div class="status-info">
            <div class="status-text" :class="currentStatus.type">
              {{ statusText }}
            </div>
            <div class="status-detail">
              <span v-if="isCompleted && isSuccess" class="success-text">
                <el-icon><CircleCheck /></el-icon>
                部署成功
              </span>
              <span v-else-if="isCompleted && !isSuccess" class="error-text">
                <el-icon><CircleClose /></el-icon>
                部署失败
              </span>
              <span v-else>
                <el-icon><Timer /></el-icon>
                已用时 {{ formattedElapsedTime }}
              </span>
            </div>
          </div>
        </div>

        <!-- 进度条 -->
        <div class="progress-bar-section">
          <el-progress
            :percentage="progress"
            :status="isCompleted ? (isSuccess ? 'success' : 'exception') : ''"
            :stroke-width="12"
            :show-text="true"
            class="deploy-progress-bar"
          />
          <div class="progress-steps">
            <div
              v-for="(step, index) in ['准备', '下载', '安装', '配置', '启动']"
              :key="index"
              class="progress-step"
              :class="{
                'is-active':
                  progress >= index * 20 && progress < (index + 1) * 20,
                'is-completed': progress >= (index + 1) * 20,
              }"
            >
              <div class="step-dot" />
              <div class="step-label">{{ step }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 错误信息 -->
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
        class="error-alert"
      />

      <!-- 日志输出 -->
      <div class="logs-section">
        <div class="logs-header">
          <span class="logs-title">
            <el-icon><Tickets /></el-icon>
            部署日志
          </span>
          <div class="logs-actions">
            <el-button
              link
              size="small"
              :disabled="logs.length === 0"
              @click="logs = []"
            >
              <el-icon><Delete /></el-icon>
              清空
            </el-button>
            <el-button
              link
              size="small"
              :disabled="logs.length === 0"
              @click="scrollToBottom"
            >
              <el-icon><Bottom /></el-icon>
              到底部
            </el-button>
          </div>
        </div>

        <div ref="logsContainerRef" class="logs-container">
          <div
            v-for="(log, index) in logs"
            :key="log.id || index"
            class="log-item"
            :class="getLogClass(log.level)"
          >
            <el-icon :size="14" class="log-icon">
              <component :is="getLogIcon(log.level)" />
            </el-icon>
            <span class="log-time">{{ log.time }}</span>
            <span class="log-level">[{{ log.level?.toUpperCase() }}]</span>
            <span class="log-message">{{ log.message }}</span>
          </div>

          <!-- 实时指示器 -->
          <div v-if="!isCompleted" class="log-item log-pending">
            <el-icon class="is-loading" :size="14"><Loading /></el-icon>
            <span class="log-time">{{ new Date().toLocaleTimeString() }}</span>
            <span class="log-message">等待更多输出...</span>
          </div>

          <el-empty v-if="logs.length === 0" description="暂无日志" />
        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <template #footer>
      <div class="dialog-footer">
        <template v-if="mode === 'runtime'">
          <el-button @click="handleForceClose">关闭</el-button>
        </template>
        <template v-else-if="isCompleted">
          <el-button v-if="!isSuccess" type="primary" @click="handleRetry">
            <el-icon><Refresh /></el-icon>
            重试
          </el-button>
          <el-button @click="handleForceClose">
            {{ isSuccess ? "完成" : "关闭" }}
          </el-button>
        </template>
        <template v-else>
          <el-button disabled>
            <el-icon class="is-loading"><Loading /></el-icon>
            部署中...
          </el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.deploy-progress-dialog {
  :deep(.el-dialog__body) {
    padding: 0;
  }
}

.deploy-progress-content {
  padding: 20px;
}

// 进度概览
.progress-overview {
  margin-bottom: 20px;
  padding: 20px;
  background: var(--el-fill-color-light);
  border-radius: var(--border-radius-base);
}

.progress-status {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;

  .el-icon {
    &.primary {
      color: var(--el-color-primary);
    }

    &.success {
      color: var(--el-color-success);
    }

    &.warning {
      color: var(--el-color-warning);
    }

    &.danger {
      color: var(--el-color-danger);
    }

    &.info {
      color: var(--el-text-color-secondary);
    }
  }

  .status-info {
    flex: 1;

    .status-text {
      font-size: var(--platform-font-size-lg);
      font-weight: var(--platform-font-weight-bold);
      margin-bottom: 4px;

      &.primary {
        color: var(--el-color-primary);
      }

      &.success {
        color: var(--el-color-success);
      }

      &.warning {
        color: var(--el-color-warning);
      }

      &.danger {
        color: var(--el-color-danger);
      }
    }

    .status-detail {
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-secondary);
      display: flex;
      align-items: center;
      gap: 4px;

      .success-text {
        color: var(--el-color-success);
        display: flex;
        align-items: center;
        gap: 4px;
      }

      .error-text {
        color: var(--el-color-danger);
        display: flex;
        align-items: center;
        gap: 4px;
      }
    }
  }
}

// 进度条
.progress-bar-section {
  .deploy-progress-bar {
    margin-bottom: 12px;
  }

  .progress-steps {
    display: flex;
    justify-content: space-between;
    position: relative;
    padding: 0 10px;

    &::before {
      content: "";
      position: absolute;
      top: 5px;
      left: 20px;
      right: 20px;
      height: 2px;
      background: var(--el-border-color);
      z-index: 0;
    }

    .progress-step {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      position: relative;
      z-index: 1;

      .step-dot {
        width: 12px;
        height: 12px;
        border-radius: 50%;
        background: var(--el-border-color);
        border: 2px solid var(--el-bg-color);
        transition: all 0.3s ease;
      }

      .step-label {
        font-size: var(--platform-font-size-xs);
        color: var(--el-text-color-secondary);
        transition: all 0.3s ease;
      }

      &.is-active {
        .step-dot {
          background: var(--el-color-primary);
          box-shadow: 0 0 0 3px var(--el-color-primary-light-8);
        }

        .step-label {
          color: var(--el-color-primary);
          font-weight: var(--platform-font-weight-medium);
        }
      }

      &.is-completed {
        .step-dot {
          background: var(--el-color-success);
        }

        .step-label {
          color: var(--el-color-success);
        }
      }
    }
  }
}

// 错误提示
.error-alert {
  margin-bottom: 16px;
}

// 日志区域
.logs-section {
  border: 1px solid var(--el-border-color);
  border-radius: var(--border-radius-base);
  overflow: hidden;
}

.logs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color);

  .logs-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: var(--platform-font-weight-medium);
    color: var(--el-text-color-primary);
  }

  .logs-actions {
    display: flex;
    gap: 8px;
  }
}

.logs-container {
  height: 300px;
  overflow-y: auto;
  padding: 12px 16px;
  background: #1e1e1e;
  font-family: var(--el-font-family-mono);
  font-size: 13px;
  line-height: 1.6;
}

.log-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 4px;
  padding: 2px 0;

  .log-icon {
    flex-shrink: 0;
    margin-top: 2px;
  }

  .log-time {
    color: #6e7681;
    flex-shrink: 0;
    min-width: 64px;
  }

  .log-level {
    color: #8b949e;
    flex-shrink: 0;
    min-width: 50px;
  }

  .log-message {
    color: #c9d1d9;
    word-break: break-all;
    flex: 1;
  }

  &.log-info {
    .log-icon {
      color: #58a6ff;
    }
  }

  &.log-success {
    .log-icon {
      color: #3fb950;
    }
  }

  &.log-warning {
    .log-icon {
      color: #d29922;
    }
  }

  &.log-error {
    .log-icon {
      color: #f85149;
    }
    .log-message {
      color: #f85149;
    }
  }

  &.log-debug {
    .log-icon {
      color: #8b949e;
    }
    .log-message {
      color: #8b949e;
    }
  }

  &.log-pending {
    .log-icon {
      color: #8b949e;
    }
    .log-message {
      color: #8b949e;
      font-style: italic;
    }
  }
}

// 底部按钮
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

// 滚动条样式
.logs-container {
  &::-webkit-scrollbar {
    width: 8px;
    height: 8px;
  }

  &::-webkit-scrollbar-track {
    background: #1e1e1e;
  }

  &::-webkit-scrollbar-thumb {
    background: #484f58;
    border-radius: 4px;
  }

  &::-webkit-scrollbar-thumb:hover {
    background: #6e7681;
  }
}
</style>
