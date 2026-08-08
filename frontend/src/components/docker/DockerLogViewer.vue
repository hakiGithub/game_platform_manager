<template>
  <div class="docker-log-viewer">
    <!-- 日志工具栏 -->
    <div class="log-toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索日志..."
          clearable
          style="width: 200px"
          @input="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-select
          v-model="logLevel"
          placeholder="日志级别"
          clearable
          style="width: 120px"
          @change="handleLevelChange"
        >
          <el-option label="全部" value="" />
          <el-option label="INFO" value="info" />
          <el-option label="WARN" value="warn" />
          <el-option label="ERROR" value="error" />
          <el-option label="DEBUG" value="debug" />
        </el-select>
        <el-switch v-model="autoScroll" active-text="自动滚动" />
        <el-switch v-model="showTimestamp" active-text="显示时间戳" />
      </div>
      <div class="toolbar-right">
        <el-button size="small" :loading="loading" @click="fetchLogs">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button size="small" @click="clearLogs">
          <el-icon><Delete /></el-icon>
          清空
        </el-button>
        <el-button size="small" @click="downloadLogs">
          <el-icon><Download /></el-icon>
          下载
        </el-button>
        <el-button
          size="small"
          :type="isStreaming ? 'danger' : 'primary'"
          @click="toggleStreaming"
        >
          <el-icon v-if="isStreaming"><VideoPause /></el-icon>
          <el-icon v-else><VideoPlay /></el-icon>
          {{ isStreaming ? "停止" : "实时" }}
        </el-button>
      </div>
    </div>

    <!-- 日志配置 -->
    <div class="log-options">
      <el-form :inline="true" size="small">
        <el-form-item label="显示行数">
          <el-select
            v-model="logLines"
            style="width: 100px"
            @change="fetchLogs"
          >
            <el-option label="100行" :value="100" />
            <el-option label="500行" :value="500" />
            <el-option label="1000行" :value="1000" />
            <el-option label="2000行" :value="2000" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 360px"
            @change="fetchLogs"
          />
        </el-form-item>
      </el-form>
    </div>

    <!-- 日志内容 -->
    <div ref="logContainer" class="log-content">
      <div v-if="loading && !logs.length" class="log-loading">
        <el-icon class="is-loading" :size="32"><Loading /></el-icon>
        <span>加载中...</span>
      </div>

      <div v-else-if="!logs.length" class="log-empty">
        <el-empty description="暂无日志" />
      </div>

      <div v-else class="log-lines">
        <div
          v-for="(log, index) in filteredLogs"
          :key="index"
          class="log-line"
          :class="getLogClass(log)"
        >
          <span v-if="showTimestamp" class="log-timestamp">{{
            log.timestamp
          }}</span>
          <span class="log-level" :class="`level-${log.level}`">{{
            log.level
          }}</span>
          <span
            class="log-message"
            v-html="highlightKeyword(log.message)"
          ></span>
        </div>
      </div>
    </div>

    <!-- 日志统计 -->
    <div class="log-stats">
      <span>共 {{ filteredLogs.length }} 条日志</span>
      <span v-if="searchKeyword"> | 搜索到 {{ matchedCount }} 条匹配 </span>
    </div>
  </div>
</template>

<script setup>
import {
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  watch,
  nextTick,
} from "vue";
import { ElMessage } from "element-plus";
import {
  Search,
  Refresh,
  Delete,
  Download,
  VideoPlay,
  VideoPause,
  Loading,
} from "@element-plus/icons-vue";
import { getContainerLogs } from "@/api/docker";
import { useUserStore } from "@/stores/user";

const props = defineProps({
  hostId: {
    type: Number,
    required: true,
  },
  containerId: {
    type: String,
    required: true,
  },
  containerStatus: {
    type: String,
    default: "running",
  },
});

const userStore = useUserStore();

// 日志配置
const logLines = ref(500);
const timeRange = ref(null);
const autoScroll = ref(true);
const showTimestamp = ref(true);
const logLevel = ref("");
const searchKeyword = ref("");

// 日志数据
const logs = ref([]);
const loading = ref(false);

// 实时流
const isStreaming = ref(false);
let ws = null;

// 日志容器
const logContainer = ref(null);

// 过滤后的日志
const filteredLogs = computed(() => {
  let result = logs.value;

  // 按级别过滤
  if (logLevel.value) {
    result = result.filter(
      (log) => log.level?.toLowerCase() === logLevel.value.toLowerCase(),
    );
  }

  // 按关键词过滤
  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase();
    result = result.filter((log) =>
      log.message?.toLowerCase().includes(keyword),
    );
  }

  return result;
});

// 匹配数量
const matchedCount = computed(() => {
  if (!searchKeyword.value) return 0;
  const keyword = searchKeyword.value.toLowerCase();
  return logs.value.filter((log) =>
    log.message?.toLowerCase().includes(keyword),
  ).length;
});

// 获取日志级别样式类
function getLogClass(log) {
  const level = log.level?.toLowerCase() || "info";
  return `log-level-${level}`;
}

// 高亮关键词
function highlightKeyword(message) {
  if (!searchKeyword.value || !message) return message;

  const keyword = searchKeyword.value;
  const regex = new RegExp(`(${escapeRegExp(keyword)})`, "gi");
  return message.replace(regex, "<mark>$1</mark>");
}

// 转义正则特殊字符
function escapeRegExp(string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// 获取日志
async function fetchLogs() {
  if (!props.hostId || !props.containerId) return;

  loading.value = true;

  try {
    const params = {
      tail: logLines.value,
    };

    if (timeRange.value && timeRange.value.length === 2) {
      params.since = timeRange.value[0];
      params.until = timeRange.value[1];
    }

    const data = await getContainerLogs(
      props.hostId,
      props.containerId,
      params,
    );

    // 解析日志
    logs.value = parseLogs(data.logs || []);

    // 自动滚动到底部
    if (autoScroll.value) {
      nextTick(() => {
        scrollToBottom();
      });
    }
  } catch (error) {
    console.error("Failed to fetch logs:", error);
    ElMessage.error("获取日志失败");
  } finally {
    loading.value = false;
  }
}

// 解析日志
function parseLogs(rawLogs) {
  if (!Array.isArray(rawLogs)) return [];

  return rawLogs.map((line) => {
    const lineStr = String(line ?? "");
    // 尝试解析日志格式
    const timestampMatch = lineStr.match(
      /^(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)\s*/,
    );
    const timestamp = timestampMatch ? timestampMatch[1] : "";
    const message = timestampMatch
      ? lineStr.substring(timestampMatch[0].length)
      : lineStr;

    // 检测日志级别
    let level = "info";
    const lowerMessage = message.toLowerCase();
    if (
      lowerMessage.includes("error") ||
      lowerMessage.includes("err") ||
      lowerMessage.includes("fatal")
    ) {
      level = "error";
    } else if (
      lowerMessage.includes("warn") ||
      lowerMessage.includes("warning")
    ) {
      level = "warn";
    } else if (
      lowerMessage.includes("debug") ||
      lowerMessage.includes("trace")
    ) {
      level = "debug";
    }

    return {
      timestamp,
      level,
      message,
    };
  });
}

// 清空日志
function clearLogs() {
  logs.value = [];
}

// 下载日志
function downloadLogs() {
  if (!logs.value.length) {
    ElMessage.warning("暂无日志可下载");
    return;
  }

  const content = logs.value
    .map((log) => {
      if (showTimestamp.value) {
        return `[${log.timestamp}] [${log.level.toUpperCase()}] ${log.message}`;
      }
      return `[${log.level.toUpperCase()}] ${log.message}`;
    })
    .join("\n");

  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `container-${props.containerId}-logs-${new Date().toISOString().slice(0, 10)}.txt`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);

  ElMessage.success("日志下载成功");
}

// 搜索处理
function handleSearch() {
  // 搜索时滚动到第一个匹配项
  if (searchKeyword.value && filteredLogs.value.length > 0) {
    nextTick(() => {
      const firstMatch = logContainer.value?.querySelector(".log-line");
      if (firstMatch) {
        firstMatch.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    });
  }
}

// 级别变化处理
function handleLevelChange() {
  // 级别变化时滚动到顶部
  if (logContainer.value) {
    logContainer.value.scrollTop = 0;
  }
}

// 滚动到底部
function scrollToBottom() {
  if (logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight;
  }
}

// 切换实时流
function toggleStreaming() {
  if (isStreaming.value) {
    stopStreaming();
  } else {
    startStreaming();
  }
}

// 启动实时流
function startStreaming() {
  if (props.containerStatus !== "running") {
    ElMessage.warning("容器未运行，无法获取实时日志");
    return;
  }

  isStreaming.value = true;

  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const host = window.location.host;
  const token = userStore.token || localStorage.getItem("token");
  const wsUrl = `${protocol}//${host}/ws/docker/${props.hostId}/containers/${props.containerId}/logs?token=${token}`;

  try {
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      ElMessage.success("已开始实时日志流");
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === "log") {
          const parsedLogs = parseLogs([data.message]);
          logs.value.push(...parsedLogs);

          // 自动滚动
          if (autoScroll.value) {
            nextTick(() => {
              scrollToBottom();
            });
          }
        }
      } catch (e) {
        // 直接添加原始日志
        logs.value.push({
          timestamp: "",
          level: "info",
          message: event.data,
        });
      }
    };

    ws.onerror = (error) => {
      console.error("WebSocket error:", error);
      ElMessage.error("实时日志连接失败");
      isStreaming.value = false;
    };

    ws.onclose = () => {
      isStreaming.value = false;
    };
  } catch (error) {
    console.error("Failed to create WebSocket:", error);
    ElMessage.error("创建实时日志连接失败");
    isStreaming.value = false;
  }
}

// 停止实时流
function stopStreaming() {
  if (ws) {
    ws.close();
    ws = null;
  }
  isStreaming.value = false;
  ElMessage.info("已停止实时日志流");
}

// 监听容器状态
watch(
  () => props.containerStatus,
  (newStatus) => {
    if (newStatus !== "running" && isStreaming.value) {
      stopStreaming();
    }
  },
);

onMounted(() => {
  fetchLogs();
});

onBeforeUnmount(() => {
  stopStreaming();
});
</script>

<style lang="scss" scoped>
.docker-log-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 400px;

  .log-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    background: var(--el-fill-color-light);
    border-bottom: 1px solid var(--el-border-color);
    border-radius: var(--el-border-radius-base) var(--el-border-radius-base) 0 0;
    flex-wrap: wrap;
    gap: 12px;

    .toolbar-left,
    .toolbar-right {
      display: flex;
      gap: 12px;
      align-items: center;
    }
  }

  .log-options {
    padding: 12px 16px;
    background: var(--el-fill-color-lighter);
    border-bottom: 1px solid var(--el-border-color);
  }

  .log-content {
    flex: 1;
    background: #1e1e1e;
    overflow: auto;
    font-family: "Consolas", "Monaco", "Courier New", monospace;
    font-size: 13px;
    line-height: 1.6;

    &::-webkit-scrollbar {
      width: 8px;
      height: 8px;
    }

    &::-webkit-scrollbar-track {
      background: #2d2d2d;
    }

    &::-webkit-scrollbar-thumb {
      background: #555;
      border-radius: 4px;

      &:hover {
        background: #666;
      }
    }

    .log-loading,
    .log-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 200px;
      color: #999;
    }

    .log-lines {
      padding: 8px 12px;
    }

    .log-line {
      display: flex;
      padding: 2px 0;
      color: #d4d4d4;

      &:hover {
        background: rgba(255, 255, 255, 0.05);
      }

      .log-timestamp {
        color: #6a9955;
        margin-right: 12px;
        flex-shrink: 0;
      }

      .log-level {
        margin-right: 12px;
        padding: 0 6px;
        border-radius: 3px;
        font-size: 11px;
        font-weight: 600;
        flex-shrink: 0;

        &.level-info {
          background: #264f78;
          color: #9cdcfe;
        }

        &.level-warn {
          background: #6a4f1e;
          color: #dcdcaa;
        }

        &.level-error {
          background: #5a1d1d;
          color: #f48771;
        }

        &.level-debug {
          background: #3a3a3a;
          color: #808080;
        }
      }

      .log-message {
        flex: 1;
        word-break: break-all;
        white-space: pre-wrap;

        :deep(mark) {
          background: #613214;
          color: #f8f8f8;
          padding: 0 2px;
          border-radius: 2px;
        }
      }
    }

    // 不同级别日志的颜色
    .log-level-error {
      .log-message {
        color: #f48771;
      }
    }

    .log-level-warn {
      .log-message {
        color: #dcdcaa;
      }
    }
  }

  .log-stats {
    padding: 8px 16px;
    background: var(--el-fill-color-light);
    border-top: 1px solid var(--el-border-color);
    border-radius: 0 0 var(--el-border-radius-base) var(--el-border-radius-base);
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .docker-log-viewer {
    .log-toolbar {
      flex-direction: column;
      align-items: stretch;

      .toolbar-left,
      .toolbar-right {
        flex-wrap: wrap;
      }
    }

    .log-options {
      :deep(.el-form-item) {
        margin-bottom: 8px;
      }
    }
  }
}
</style>
