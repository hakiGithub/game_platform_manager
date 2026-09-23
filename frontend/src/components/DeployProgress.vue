<script setup>
import {
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  nextTick,
  watch,
} from "vue";
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
// DeployProgressVO 顶层新增 stage（直投 DeployTaskStatus.stage，design §14.11）：
// 只用于「当前正在扩展」的激活态，不参与阶段带是否出现的判定。
const stage = ref("");
const logs = ref([]);
const error = ref("");
const startTime = ref(null);
const elapsedTime = ref(0);

// 定时器
let progressTimer = null;
let elapsedTimer = null;

// 日志容器引用
const logsContainerRef = ref(null);

// 扩展阶段常量（design §14.6 字段契约）
const EXTENSION_STAGE = "EXTENSION";
// 步骤种类词面（ui-spec §6.1）：界面一律走中文词，声明侧 PATCH / SCRIPT 字面量不得出现（§6.4）
const STEP_KIND_LABELS = { PATCH: "补丁替换", SCRIPT: "脚本执行" };
// 进「步骤」形状的 stepEvent：START / SUCCESS / FAILURE 三行一组（ui-spec §6.2）
const STEP_ROW_EVENTS = ["START", "SUCCESS", "FAILURE"];
// 既有的五个步骤点，及其原有 20% 分桶阈值（插入「扩展」点不得改变它们，X-08）
const BASE_PROGRESS_STEPS = ["准备", "下载", "安装", "配置", "启动"];

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

// 是否成功
const isSuccess = computed(() => {
  return status.value === "completed";
});

// 格式化耗时：参数化为纯函数（design §14.6 末段 / SUG-6），
// 对话框顶部「已用时」与扩展阶段步骤行共用同一套读法（N秒 / N分N秒 / N小时N分，不引入裸 ms）
function formatElapsed(seconds) {
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
}

// 格式化耗时
const formattedElapsedTime = computed(() => formatElapsed(elapsedTime.value));

// elapsedMs（毫秒权威值）→ 秒：Math.max(1, Math.round(ms / 1000))。
// <1000ms 渲染成「0秒」等于没显示（AC-03「耗时可见」），故下限取 1 秒；
// 换算只发生在渲染层——VO 与核对脚本永不出现秒值（design §14.6）。
function stepElapsedSeconds(ms) {
  const value = Number(ms);
  if (!Number.isFinite(value)) return null;
  return Math.max(1, Math.round(value / 1000));
}

// P3「本次真的进过扩展阶段」= latch（design §14.11）：任一日志行的 stage 为 EXTENSION。
// 组件已累计全部日志行并按 log.id 去重，故该谓词在 HEALTH_CHECK 之后仍为真，
// 不需要额外状态位；statusText 会变、progress 会跨过，都不能当 latch。
const hasExtensionStage = computed(() =>
  logs.value.some((l) => l.stage === EXTENSION_STAGE),
);

// 阶段带恒在该阶段第一行之前（ui-spec §6.2 规则 1 / §7 X-07 锚点一）
const firstExtensionLogIndex = computed(() =>
  logs.value.findIndex((l) => l.stage === EXTENSION_STAGE),
);

// 「扩展」步骤点的状态：激活态取顶层 stage；终止 / 完成取部署终态（ui-spec §4.2 K…V）。
// 不由百分比分桶猜（design §14.11）。
const extensionStepState = computed(() => {
  if (!hasExtensionStage.value) return "";
  if (stage.value === EXTENSION_STAGE) return "active";
  if (isCompleted.value && !isSuccess.value) return "terminated";
  if (isCompleted.value && isSuccess.value) return "completed";
  return "";
});

// 步骤点：既有 5 项之上，进入扩展阶段时在「配置」与「启动」之间插入「扩展」（ui-spec §3.2-2）；
// 五个既有点的 completed / active 一律沿用原有阈值，插入瞬间逐项不变（§7 X-08）。
const progressSteps = computed(() => {
  const base = BASE_PROGRESS_STEPS.map((label, index) => ({
    key: label,
    label,
    ext: false,
    completed: progress.value >= (index + 1) * 20,
    active: progress.value >= index * 20 && progress.value < (index + 1) * 20,
  }));

  if (!hasExtensionStage.value) return base;

  const extState = extensionStepState.value;
  const extension = {
    key: "扩展",
    label: "扩展",
    ext: true,
    active: extState === "active",
    completed: extState === "completed",
    terminated: extState === "terminated",
  };

  return [...base.slice(0, 4), extension, ...base.slice(4)];
});

// 归一化日志级别（design §14.9 / F-01）：
// 后端 appendLog 写入的取值集合是 INFO / WARN / ERROR / SUCCESS（大写，见 DeployService.java:565），
// 而本组件的分支键是小写、且 WARN 与 warning 词根也不同，故先 toLowerCase() 再把 warn 别名为 warning。
// 未知取值一律落默认（log-info / InfoFilled），不抛异常。
function normalizeLogLevel(level) {
  const key = String(level ?? "").toLowerCase();
  return key === "warn" ? "warning" : key;
}

// 获取日志级别样式
function getLogClass(level) {
  const classes = {
    info: "log-info",
    success: "log-success",
    warning: "log-warning",
    error: "log-error",
    debug: "log-debug",
  };
  return classes[normalizeLogLevel(level)] || "log-info";
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
  return icons[normalizeLogLevel(level)] || "InfoFilled";
}

// ---- 扩展阶段日志行的呈现（design §14.6 契约驱动、ui-spec §6.2 词面） ----
// 条目类型分支：stepId != null 且 stepEvent ∈ 三行一组 ⇒ 「步骤」形状，按字段渲染；
// stepId == null 的七类阶段级行（进入 / 交棒 / 停实例 / BR-12 拦截 / 目录不合法说明 /
// 收尾 / 阶段完成）不进「步骤」形状，直出服务端 message。
function isStepRow(log) {
  return (
    !!log && log.stepId != null && STEP_ROW_EVENTS.includes(log.stepEvent)
  );
}

function stepKindLabel(stepType) {
  return STEP_KIND_LABELS[stepType] || "";
}

// label 缺省时的渲染回退（ui-spec §6.2）：种类 + 序号 ⇒「补丁替换 〈序号〉」/「脚本执行 〈序号〉」，
// 绝不让 PATCH / SCRIPT 字面量出现在界面（§6.4）
function stepDisplayLabel(log) {
  if (log.stepLabel) return log.stepLabel;
  const kind = stepKindLabel(log.stepType);
  return kind ? `${kind} ${log.stepIndex}` : `${log.stepIndex}`;
}

// 步骤终态行结果段：非致命失败取 WARN（ui-spec §6.2 三行一组）
function stepOutcomeLabel(log) {
  if (normalizeLogLevel(log.level) === "warning") return "失败（非致命）";
  return log.stepEvent === "SUCCESS" ? "成功" : "失败";
}

// 失败行的原因段（ui-spec §6.3：以「原因：」引导并置于行尾），内容承载在 message
function stepReason(log) {
  return String(log.message ?? "").replace(/^原因：/, "");
}

// 步骤行文本：`步骤 〈序号〉/〈总数〉 〈步骤标签〉 · 〈种类〉 [· 成功 · 耗时 〈时长〉 · 原因：〈原因〉]`
function stepRowText(log) {
  const kind = stepKindLabel(log.stepType);
  const head =
    log.stepTotal == null
      ? `步骤 ${log.stepIndex} ${stepDisplayLabel(log)}`
      : `步骤 ${log.stepIndex}/${log.stepTotal} ${stepDisplayLabel(log)}`;

  const parts = [head];
  if (kind) parts.push(kind);

  if (log.stepEvent === "START") {
    parts.push("开始");
    return parts.join(" · ");
  }

  parts.push(stepOutcomeLabel(log));

  const seconds = stepElapsedSeconds(log.elapsedMs);
  if (seconds !== null) parts.push(`耗时 ${formatElapsed(seconds)}`);

  const reason = stepReason(log);
  if (reason) parts.push(`原因：${reason}`);

  return parts.join(" · ");
}

function logRowText(log) {
  return isStepRow(log) ? stepRowText(log) : log.message;
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
    // 顶层 stage（DeployProgressVO 新增字段）：仅驱动「当前正在扩展」的激活态
    stage.value = data.stage || "";

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
  stage.value = "";
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
// DeployProgress 是只读日志观察窗，不是任务控制器：用户点 X / 按 ESC 收起弹框时，
// 仅停止前端轮询、服务端部署任务继续在后台运行、状态仍可在实例列表看到。
// 任务控制（取消/重试）由实例列表或任务中心负责，不在本组件职责内。
function handleClose() {
  if (props.mode === "runtime") {
    stopProgressPolling();
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
      stage.value = "";
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
              v-for="step in progressSteps"
              :key="step.key"
              class="progress-step"
              :class="{
                'is-active': step.active,
                'is-completed': step.completed,
                'is-terminated': step.terminated,
                ext: step.ext,
              }"
            >
              <div class="step-dot" />
              <div class="step-label">{{ step.label }}</div>
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
          <template v-for="(log, index) in logs" :key="log.id || index">
            <!-- 阶段带：恒在扩展阶段第一行（「进入部署扩展阶段」）之前，只插一次 -->
            <div
              v-if="index === firstExtensionLogIndex"
              class="log-stage-band"
            >
              部署扩展
            </div>

            <div
              class="log-item"
              :class="[getLogClass(log.level), { 'log-step': isStepRow(log) }]"
              :data-step-index="isStepRow(log) ? log.stepIndex : undefined"
              :data-step-total="isStepRow(log) ? log.stepTotal : undefined"
              :data-step-event="isStepRow(log) ? log.stepEvent : undefined"
            >
              <el-icon :size="14" class="log-icon">
                <component :is="getLogIcon(log.level)" />
              </el-icon>
              <span class="log-time">{{ log.time }}</span>
              <span class="log-level">[{{ log.level?.toUpperCase() }}]</span>
              <span class="log-message">{{ logRowText(log) }}</span>
            </div>
          </template>

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
        <!-- 进行中：DeployProgress 仅作只读日志观察窗，无控制按钮；关闭走顶部 X / ESC -->
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
  background: var(--platform-surface-2);
  border: 1px solid var(--platform-line);
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
        border: 2px solid var(--platform-surface-2);
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

      // 「扩展」步骤点（ui-spec §8.1 本期新增的 ext 描边）
      &.ext {
        outline: 1px dashed rgba(242, 184, 75, 0.6);
        outline-offset: 4px;
        border-radius: 4px;
      }

      // 扩展阶段终止态（ui-spec §4.2 N / P / Q / R / S）
      &.is-terminated {
        .step-dot {
          background: var(--platform-red);
        }

        .step-label {
          color: var(--platform-red);
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
  border: 1px solid var(--platform-line);
  border-radius: var(--border-radius-base);
  overflow: hidden;
}

.logs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--platform-surface-2);
  border-bottom: 1px solid var(--platform-line);

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
  background: var(--platform-surface-0);
  font-family: var(--el-font-family-mono);
  font-size: 13px;
  line-height: 1.6;
}

// 日志阶段带（ui-spec §8.2：本期新增的视觉元素之一，色值取既有 --platform-amber）
.log-stage-band {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0 6px;
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  line-height: 1;
  letter-spacing: 0.09em;
  color: var(--platform-amber);

  &::after {
    content: "";
    flex: 1;
    height: 1px;
    background: rgba(242, 184, 75, 0.35);
  }
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
    color: var(--platform-text-muted);
    flex-shrink: 0;
    min-width: 64px;
  }

  .log-level {
    color: var(--platform-text-secondary);
    flex-shrink: 0;
    min-width: 50px;
  }

  .log-message {
    color: var(--platform-text-regular);
    word-break: break-all;
    flex: 1;
  }

  &.log-info {
    .log-icon {
      color: var(--platform-cyan);
    }
  }

  &.log-success {
    .log-icon {
      color: var(--platform-green);
    }
  }

  &.log-warning {
    .log-icon {
      color: var(--platform-amber);
    }
  }

  &.log-error {
    .log-icon {
      color: var(--platform-red);
    }
    .log-message {
      color: var(--platform-red);
    }
  }

  &.log-debug {
    .log-icon {
      color: var(--platform-text-secondary);
    }
    .log-message {
      color: var(--platform-text-secondary);
    }
  }

  &.log-pending {
    .log-icon {
      color: var(--platform-text-secondary);
    }
    .log-message {
      color: var(--platform-text-secondary);
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
    background: var(--platform-surface-0);
  }

  &::-webkit-scrollbar-thumb {
    background: var(--platform-line);
    border-radius: 4px;
  }

  &::-webkit-scrollbar-thumb:hover {
    background: var(--platform-cyan);
  }
}
</style>
