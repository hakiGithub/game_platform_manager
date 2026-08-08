<template>
  <div class="docker-terminal">
    <!-- 终端工具栏 -->
    <div class="terminal-toolbar">
      <div class="toolbar-left">
        <el-select
          v-model="selectedShell"
          size="small"
          style="width: 100px"
          @change="reconnect"
        >
          <el-option label="/bin/bash" value="/bin/bash" />
          <el-option label="/bin/sh" value="/bin/sh" />
          <el-option label="/bin/ash" value="/bin/ash" />
        </el-select>
        <el-select
          v-model="terminalCols"
          size="small"
          style="width: 80px"
          @change="resizeTerminal"
        >
          <el-option label="80列" :value="80" />
          <el-option label="120列" :value="120" />
          <el-option label="160列" :value="160" />
        </el-select>
        <el-select
          v-model="terminalRows"
          size="small"
          style="width: 80px"
          @change="resizeTerminal"
        >
          <el-option label="24行" :value="24" />
          <el-option label="36行" :value="36" />
          <el-option label="48行" :value="48" />
        </el-select>
      </div>
      <div class="toolbar-right">
        <el-button size="small" @click="clearTerminal">
          <el-icon><Delete /></el-icon>
          清屏
        </el-button>
        <el-button size="small" :loading="connecting" @click="reconnect">
          <el-icon><Refresh /></el-icon>
          重连
        </el-button>
        <el-button size="small" @click="toggleFullscreen">
          <el-icon><FullScreen /></el-icon>
          {{ isFullscreen ? "退出全屏" : "全屏" }}
        </el-button>
      </div>
    </div>

    <!-- 终端容器 -->
    <div
      ref="terminalContainer"
      class="terminal-container"
      :class="{ 'is-fullscreen': isFullscreen }"
    ></div>

    <!-- 连接状态提示 -->
    <div v-if="!connected && !connecting" class="connection-status">
      <el-result
        icon="warning"
        title="终端连接已断开"
        sub-title="请点击重连按钮重新连接"
      >
        <template #extra>
          <el-button type="primary" @click="reconnect">重新连接</el-button>
        </template>
      </el-result>
    </div>

    <!-- 连接中提示 -->
    <div v-if="connecting" class="connection-status">
      <el-result icon="info" title="正在连接终端...">
        <template #extra>
          <el-icon class="is-loading" :size="32"><Loading /></el-icon>
        </template>
      </el-result>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from "vue";
import { Terminal } from "xterm";
import { FitAddon } from "xterm-addon-fit";
import { WebLinksAddon } from "xterm-addon-web-links";
import { SearchAddon } from "xterm-addon-search";
import { ElMessage } from "element-plus";
import { Delete, Refresh, FullScreen, Loading } from "@element-plus/icons-vue";
import "xterm/css/xterm.css";
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
});

const userStore = useUserStore();

// 终端配置
const selectedShell = ref("/bin/bash");
const terminalCols = ref(120);
const terminalRows = ref(36);

// 终端状态
const connected = ref(false);
const connecting = ref(false);
const isFullscreen = ref(false);

// 终端实例
const terminalContainer = ref(null);
let terminal = null;
let fitAddon = null;
let ws = null;

// WebSocket URL
function getWebSocketUrl() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const host = window.location.host;
  const token = userStore.token || localStorage.getItem("token");
  return `${protocol}//${host}/ws/docker/${props.hostId}/containers/${props.containerId}/exec?token=${token}&shell=${selectedShell.value}&cols=${terminalCols.value}&rows=${terminalRows.value}`;
}

// 初始化终端
function initTerminal() {
  if (terminal) {
    terminal.dispose();
  }

  terminal = new Terminal({
    cols: terminalCols.value,
    rows: terminalRows.value,
    fontSize: 14,
    fontFamily: 'Consolas, "Courier New", monospace',
    theme: {
      background: "#1e1e1e",
      foreground: "#d4d4d4",
      cursor: "#ffffff",
      cursorAccent: "#000000",
      selection: "rgba(255, 255, 255, 0.3)",
      black: "#000000",
      red: "#cd3131",
      green: "#0dbc79",
      yellow: "#e5e510",
      blue: "#2472c8",
      magenta: "#bc3fbc",
      cyan: "#11a8cd",
      white: "#e5e5e5",
      brightBlack: "#666666",
      brightRed: "#f14c4c",
      brightGreen: "#23d18b",
      brightYellow: "#f5f543",
      brightBlue: "#3b8eea",
      brightMagenta: "#d670d6",
      brightCyan: "#29b8db",
      brightWhite: "#e5e5e5",
    },
    cursorBlink: true,
    cursorStyle: "block",
    scrollback: 10000,
    allowProposedApi: true,
  });

  // 加载插件
  fitAddon = new FitAddon();
  terminal.loadAddon(fitAddon);
  terminal.loadAddon(new WebLinksAddon());
  terminal.loadAddon(new SearchAddon());

  // 打开终端
  terminal.open(terminalContainer.value);
  fitAddon.fit();

  // 监听终端数据
  terminal.onData((data) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "input", data }));
    }
  });

  // 监听终端大小变化
  terminal.onResize(({ cols, rows }) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "resize", cols, rows }));
    }
  });

  // 监听窗口大小变化
  window.addEventListener("resize", handleResize);
}

// 连接 WebSocket
function connectWebSocket() {
  if (ws) {
    ws.close();
  }

  connecting.value = true;
  connected.value = false;

  try {
    ws = new WebSocket(getWebSocketUrl());

    ws.onopen = () => {
      connecting.value = false;
      connected.value = true;
      terminal.write("\x1b[32m[系统] 终端连接成功\x1b[0m\r\n");
    };

    ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        if (message.type === "output") {
          terminal.write(message.data);
        } else if (message.type === "error") {
          terminal.write(`\x1b[31m[错误] ${message.message}\x1b[0m\r\n`);
        }
      } catch (e) {
        // 如果不是 JSON，直接输出
        terminal.write(event.data);
      }
    };

    ws.onerror = (error) => {
      connecting.value = false;
      connected.value = false;
      terminal.write("\x1b[31m[错误] 终端连接失败\x1b[0m\r\n");
      console.error("WebSocket error:", error);
    };

    ws.onclose = (event) => {
      connecting.value = false;
      connected.value = false;
      if (event.code !== 1000) {
        terminal.write("\x1b[33m[系统] 终端连接已断开\x1b[0m\r\n");
      }
    };
  } catch (error) {
    connecting.value = false;
    connected.value = false;
    ElMessage.error("创建 WebSocket 连接失败");
    console.error("Failed to create WebSocket:", error);
  }
}

// 重连
function reconnect() {
  terminal.write("\x1b[33m[系统] 正在重新连接...\x1b[0m\r\n");
  connectWebSocket();
}

// 清屏
function clearTerminal() {
  terminal.clear();
}

// 调整终端大小
function resizeTerminal() {
  if (terminal) {
    terminal.resize(terminalCols.value, terminalRows.value);
    if (fitAddon) {
      fitAddon.fit();
    }
  }
}

// 处理窗口大小变化
function handleResize() {
  if (fitAddon && terminal) {
    fitAddon.fit();
  }
}

// 切换全屏
function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value;
  nextTick(() => {
    if (fitAddon) {
      fitAddon.fit();
    }
  });
}

// 监听 props 变化
watch(
  () => [props.hostId, props.containerId],
  () => {
    if (props.hostId && props.containerId) {
      nextTick(() => {
        initTerminal();
        connectWebSocket();
      });
    }
  },
);

onMounted(() => {
  nextTick(() => {
    initTerminal();
    connectWebSocket();
  });
});

onBeforeUnmount(() => {
  // 关闭 WebSocket
  if (ws) {
    ws.close();
    ws = null;
  }

  // 销毁终端
  if (terminal) {
    terminal.dispose();
    terminal = null;
  }

  // 移除事件监听
  window.removeEventListener("resize", handleResize);
});
</script>

<style lang="scss" scoped>
.docker-terminal {
  position: relative;
  height: 100%;
  min-height: 400px;
  display: flex;
  flex-direction: column;

  .terminal-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 12px;
    background: var(--el-fill-color-light);
    border-bottom: 1px solid var(--el-border-color);
    border-radius: var(--el-border-radius-base) var(--el-border-radius-base) 0 0;

    .toolbar-left,
    .toolbar-right {
      display: flex;
      gap: 8px;
      align-items: center;
    }
  }

  .terminal-container {
    flex: 1;
    background: #1e1e1e;
    padding: 8px;
    border-radius: 0 0 var(--el-border-radius-base) var(--el-border-radius-base);
    overflow: hidden;

    &.is-fullscreen {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      z-index: 9999;
      border-radius: 0;
    }

    :deep(.xterm) {
      height: 100%;
      padding: 4px;
    }

    :deep(.xterm-viewport) {
      &::-webkit-scrollbar {
        width: 8px;
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
    }
  }

  .connection-status {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    z-index: 10;
    background: rgba(255, 255, 255, 0.95);
    padding: 20px;
    border-radius: var(--el-border-radius-base);
    box-shadow: var(--el-box-shadow-light);
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .docker-terminal {
    .terminal-toolbar {
      flex-direction: column;
      gap: 8px;

      .toolbar-left,
      .toolbar-right {
        width: 100%;
        justify-content: center;
      }
    }
  }
}
</style>
