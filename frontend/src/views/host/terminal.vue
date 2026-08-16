<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Terminal } from "xterm";
import { FitAddon } from "xterm-addon-fit";
import { WebLinksAddon } from "xterm-addon-web-links";
import { SearchAddon } from "xterm-addon-search";
import "xterm/css/xterm.css";
import { createSSHTerminal } from "@/utils/websocket";
import { nightOpsTerminalTheme } from "@/utils/terminalTheme";

const route = useRoute();
const router = useRouter();

// 主机信息
const hostInfo = ref({
  id: "",
  name: "",
  ip: "",
});

// 终端实例
let terminal = null;
let fitAddon = null;
let searchAddon = null;

// WebSocket客户端
const wsClient = ref(null);

// 终端容器
const terminalRef = ref(null);
const terminalWrapperRef = ref(null);

// 连接状态
const connected = ref(false);
const connecting = ref(false);
const reconnecting = ref(false);
const reconnectCount = ref(0);
const maxReconnectCount = 5;

// 全屏状态
const isFullscreen = ref(false);

// 搜索框
const searchVisible = ref(false);
const searchKeyword = ref("");
const searchCaseSensitive = ref(false);

// 初始化终端
function initTerminal() {
  // 窄屏提示遮罩下终端容器未渲染，跳过初始化避免 xterm 报错（ISSUE-003 兼容）
  if (!terminalRef.value) {
    console.warn('[SSH Terminal] terminalRef not available, skip xterm init (narrow screen notice may be active)');
    return;
  }

  // 创建终端实例
  terminal = new Terminal({
    fontSize: 14,
    fontFamily: 'Consolas, "Courier New", monospace, "Microsoft YaHei"',
    theme: nightOpsTerminalTheme,
    cursorBlink: true,
    cursorStyle: "block",
    scrollback: 10000,
    tabStopWidth: 4,
    allowProposedApi: true,
    rightClickSelectsWord: true,
    windowsMode: true,
  });

  // 加载插件
  fitAddon = new FitAddon();
  searchAddon = new SearchAddon();
  terminal.loadAddon(fitAddon);
  terminal.loadAddon(searchAddon);
  terminal.loadAddon(new WebLinksAddon());

  // 打开终端
  terminal.open(terminalRef.value);
  fitAddon.fit();

  // 终端自动聚焦
  terminal.focus();

  // 点击终端区域聚焦
  terminalRef.value.addEventListener("click", () => {
    terminal.focus();
  });

  // 监听复制事件
  terminalRef.value.addEventListener("copy", (e) => {
    const selection = terminal.getSelection();
    if (selection) {
      e.clipboardData.setData("text/plain", selection);
      e.preventDefault();
      ElMessage.success("已复制到剪贴板");
    }
  });

  // 监听粘贴事件
  terminalRef.value.addEventListener("paste", (e) => {
    e.preventDefault();
    const text = e.clipboardData.getData("text/plain");
    if (text && wsClient.value && wsClient.value.isConnected()) {
      wsClient.value.send({
        type: "command",
        data: text,
      });
    }
  });

  // 连接 WebSocket
  connectWebSocket();

  // 监听窗口大小变化
  window.addEventListener("resize", handleResize);

  // 监听全屏变化
  document.addEventListener("fullscreenchange", handleFullscreenChange);

  // 监听终端输入
  terminal.onData((data) => {
    if (wsClient.value && wsClient.value.isConnected()) {
      wsClient.value.send({
        type: "command",
        data: data,
      });
    }
  });

  // 监听终端大小变化
  terminal.onResize(({ cols, rows }) => {
    if (wsClient.value && wsClient.value.isConnected()) {
      wsClient.value.send({
        type: "resize",
        cols,
        rows,
      });
    }
  });

  // 显示欢迎信息
  terminal.writeln("\x1b[32m正在连接到主机...\x1b[0m");
}

// 连接 WebSocket
function connectWebSocket() {
  if (wsClient.value) {
    wsClient.value.close();
  }

  connecting.value = true;
  reconnecting.value = false;

  wsClient.value = createSSHTerminal({
    hostId: hostInfo.value.id,
    onMessage: (event) => {
      if (terminal) {
        try {
          // 尝试解析JSON格式的消息
          const message = JSON.parse(event.data);
          if (message.type === "output" && message.data) {
            // 输出类型消息，写入终端
            terminal.write(message.data);
          } else if (message.type === "connected") {
            // 连接成功消息，已在onOpen中处理
            console.log("[SSH] Connected:", message.data);
          } else if (message.type === "error") {
            // 错误消息
            terminal.writeln(`\r\n\x1b[31m错误: ${message.data}\x1b[0m`);
          } else if (message.type === "pong") {
            // 心跳响应，忽略
            console.log("[SSH] Pong received");
          } else {
            // 其他类型，直接显示
            terminal.write(message.data || event.data);
          }
        } catch (e) {
          // 非JSON格式，直接写入（兼容原始文本格式）
          terminal.write(event.data);
        }
      }
    },
    onOpen: () => {
      connected.value = true;
      connecting.value = false;
      reconnecting.value = false;
      reconnectCount.value = 0;

      if (terminal) {
        terminal.writeln("");
        terminal.writeln(
          `\x1b[32m已连接到 ${hostInfo.value.name} (${hostInfo.value.ip})\x1b[0m`,
        );
        terminal.writeln(
          "\x1b[90m提示: 输入 exit 或按 Ctrl+D 可断开连接\x1b[0m",
        );
        terminal.writeln("");
      }

      // 发送初始终端大小
      if (fitAddon) {
        const { cols, rows } = fitAddon.proposeDimensions();
        wsClient.value.send({
          type: "resize",
          cols,
          rows,
        });
      }
    },
    onClose: (event) => {
      connected.value = false;
      connecting.value = false;

      if (terminal) {
        if (event.wasClean) {
          terminal.writeln("");
          terminal.writeln("\x1b[33m连接已关闭\x1b[0m");
        } else {
          terminal.writeln("");
          terminal.writeln("\x1b[31m连接意外断开\x1b[0m");
        }
      }

      // 自动重连
      if (!event.wasClean && reconnectCount.value < maxReconnectCount) {
        autoReconnect();
      }
    },
    onError: () => {
      connected.value = false;
      connecting.value = false;

      if (terminal) {
        terminal.writeln("");
        terminal.writeln("\x1b[31m连接错误\x1b[0m");
      }
    },
    onReconnect: (count) => {
      reconnectCount.value = count;
      reconnecting.value = true;

      if (terminal) {
        terminal.writeln(
          `\x1b[33m正在重连 (${count}/${maxReconnectCount})...\x1b[0m`,
        );
      }
    },
  });

  wsClient.value.connect();
}

// 自动重连
function autoReconnect() {
  setTimeout(() => {
    if (!connected.value && reconnectCount.value < maxReconnectCount) {
      connectWebSocket();
    }
  }, 2000);
}

// 手动重连
function handleReconnect() {
  if (wsClient.value) {
    wsClient.value.close();
  }
  reconnectCount.value = 0;

  if (terminal) {
    terminal.writeln("");
    terminal.writeln("\x1b[33m正在重新连接...\x1b[0m");
  }

  connectWebSocket();
}

// 断开连接
function disconnect() {
  if (wsClient.value) {
    wsClient.value.close();
  }
}

// 处理窗口大小变化
function handleResize() {
  nextTick(() => {
    if (fitAddon) {
      fitAddon.fit();
    }
  });
}

// 处理全屏变化
function handleFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement;
  handleResize();
}

// 切换全屏
function toggleFullscreen() {
  if (!document.fullscreenElement) {
    terminalWrapperRef.value?.requestFullscreen().catch((err) => {
      ElMessage.error("无法进入全屏模式: " + err.message);
    });
  } else {
    document.exitFullscreen();
  }
}

// 清屏
function clearTerminal() {
  if (terminal) {
    terminal.clear();
  }
}

// 搜索功能
function toggleSearch() {
  searchVisible.value = !searchVisible.value;
  if (!searchVisible.value) {
    searchAddon.clearDecorations();
  } else {
    nextTick(() => {
      document.querySelector(".search-input")?.focus();
    });
  }
}

function handleSearch() {
  if (!searchKeyword.value) {
    searchAddon.clearDecorations();
    return;
  }

  searchAddon.findNext(searchKeyword.value, {
    caseSensitive: searchCaseSensitive.value,
  });
}

function handleSearchPrev() {
  if (!searchKeyword.value) return;

  searchAddon.findPrevious(searchKeyword.value, {
    caseSensitive: searchCaseSensitive.value,
  });
}

// 复制选中内容
function copySelection() {
  if (terminal) {
    const selection = terminal.getSelection();
    if (selection) {
      navigator.clipboard
        .writeText(selection)
        .then(() => {
          ElMessage.success("已复制到剪贴板");
        })
        .catch(() => {
          ElMessage.error("复制失败");
        });
    }
  }
}

// 粘贴
async function pasteToTerminal() {
  try {
    const text = await navigator.clipboard.readText();
    if (wsClient.value && wsClient.value.isConnected()) {
      wsClient.value.send({
        type: "command",
        data: text,
      });
    }
  } catch (err) {
    ElMessage.error("无法访问剪贴板");
  }
}

// 断开连接并返回
async function handleDisconnect() {
  try {
    await ElMessageBox.confirm("确定要断开SSH连接吗？", "断开确认", {
      confirmButtonText: "确定",
      cancelButtonText: "取消",
      type: "warning",
    });
    disconnect();
    goBack();
  } catch {
    // 取消操作
  }
}

// 返回列表
function goBack() {
  router.push("/host/list");
}

// 键盘快捷键
function handleKeyDown(e) {
  // Ctrl+C 复制选中内容
  if (e.ctrlKey && e.key === "c" && terminal && terminal.hasSelection()) {
    e.preventDefault();
    copySelection();
    return;
  }

  // Ctrl+Shift+C 复制
  if (e.ctrlKey && e.shiftKey && e.key === "C") {
    e.preventDefault();
    copySelection();
    return;
  }

  // Ctrl+Shift+V 粘贴
  if (e.ctrlKey && e.shiftKey && e.key === "V") {
    e.preventDefault();
    pasteToTerminal();
    return;
  }

  // Ctrl+F 搜索
  if (e.ctrlKey && e.key === "f") {
    e.preventDefault();
    toggleSearch();
    return;
  }

  // Ctrl+L 清屏
  if (e.ctrlKey && e.key === "l") {
    e.preventDefault();
    clearTerminal();
    return;
  }

  // F11 全屏
  if (e.key === "F11") {
    e.preventDefault();
    toggleFullscreen();
    return;
  }

  // Esc 退出搜索或全屏
  if (e.key === "Escape") {
    if (searchVisible.value) {
      searchVisible.value = false;
      searchAddon.clearDecorations();
    } else if (document.fullscreenElement) {
      document.exitFullscreen();
    }
  }
}

onMounted(() => {
  hostInfo.value = {
    id: route.params.id,
    name: route.query.name || "Unknown",
    ip: route.query.ip || "",
  };

  nextTick(() => {
    initTerminal();
  });

  // 添加键盘事件监听
  document.addEventListener("keydown", handleKeyDown);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize);
  document.removeEventListener("fullscreenchange", handleFullscreenChange);
  document.removeEventListener("keydown", handleKeyDown);

  disconnect();

  if (terminal) {
    terminal.dispose();
    terminal = null;
  }
});
</script>

<template>
  <div
    ref="terminalWrapperRef"
    class="terminal-container"
    :class="{ 'is-fullscreen': isFullscreen }"
  >
    <!-- 顶部工具栏 -->
    <div class="terminal-header">
      <div class="header-left">
        <el-button v-if="!isFullscreen" link @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <div class="host-info">
          <el-icon><Monitor /></el-icon>
          <span class="host-name">{{ hostInfo.name }}</span>
          <span class="host-ip">{{ hostInfo.ip }}</span>
          <el-tag
            :type="connected ? 'success' : reconnecting ? 'warning' : 'danger'"
            size="small"
            effect="dark"
          >
            <el-icon v-if="connecting || reconnecting" class="is-loading"
              ><Loading
            /></el-icon>
            <el-icon v-else
              ><component :is="connected ? 'CircleCheck' : 'CircleClose'"
            /></el-icon>
            {{
              connected
                ? "已连接"
                : connecting
                  ? "连接中..."
                  : reconnecting
                    ? "重连中"
                    : "未连接"
            }}
          </el-tag>
        </div>
      </div>
      <div class="header-right">
        <el-button-group>
          <el-button
            size="small"
            title="复制 (Ctrl+Shift+C)"
            @click="copySelection"
          >
            <el-icon><DocumentCopy /></el-icon>
          </el-button>
          <el-button
            size="small"
            title="粘贴 (Ctrl+Shift+V)"
            @click="pasteToTerminal"
          >
            <el-icon><DocumentAdd /></el-icon>
          </el-button>
          <el-button size="small" title="搜索 (Ctrl+F)" @click="toggleSearch">
            <el-icon><Search /></el-icon>
          </el-button>
          <el-button size="small" title="清屏 (Ctrl+L)" @click="clearTerminal">
            <el-icon><Delete /></el-icon>
          </el-button>
          <el-button
            size="small"
            :title="isFullscreen ? '退出全屏 (F11)' : '全屏 (F11)'"
            @click="toggleFullscreen"
          >
            <el-icon><FullScreen /></el-icon>
          </el-button>
          <el-button
            size="small"
            :disabled="connected || connecting"
            title="重新连接"
            @click="handleReconnect"
          >
            <el-icon><RefreshRight /></el-icon>
          </el-button>
          <el-button
            size="small"
            type="danger"
            title="断开连接"
            @click="handleDisconnect"
          >
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </el-button-group>
      </div>
    </div>

    <!-- 搜索框 -->
    <div v-show="searchVisible" class="search-box">
      <el-input
        ref="searchInputRef"
        v-model="searchKeyword"
        placeholder="搜索..."
        size="small"
        class="search-input"
        @keyup.enter="handleSearch"
      >
        <template #append>
          <el-button size="small" @click="handleSearchPrev">
            <el-icon><ArrowUp /></el-icon>
          </el-button>
          <el-button size="small" @click="handleSearch">
            <el-icon><ArrowDown /></el-icon>
          </el-button>
        </template>
      </el-input>
      <el-checkbox v-model="searchCaseSensitive" size="small"
        >区分大小写</el-checkbox
      >
      <el-button link size="small" @click="toggleSearch">
        <el-icon><Close /></el-icon>
      </el-button>
    </div>

    <!-- 终端区域 -->
    <div class="terminal-wrapper">
      <div
        ref="terminalRef"
        class="terminal"
        @contextmenu.prevent="pasteToTerminal"
      />
    </div>

    <!-- 快捷键提示 -->
    <div v-if="isFullscreen" class="terminal-footer">
      <span>Ctrl+Shift+C: 复制</span>
      <span>Ctrl+Shift+V: 粘贴</span>
      <span>Ctrl+F: 搜索</span>
      <span>Ctrl+L: 清屏</span>
      <span>F11: 退出全屏</span>
      <span>Esc: 关闭搜索</span>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.terminal-container {
  display: flex;
  flex-direction: column;
  height: calc(
    100vh - var(--platform-header-height) - var(--platform-content-padding) * 2
  );
  background-color: var(--platform-surface-0);
  border: 1px solid var(--platform-line);
  border-radius: var(--platform-card-radius);
  overflow: hidden;

  &.is-fullscreen {
    height: 100vh;
    border-radius: 0;
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 9999;
  }
}

.terminal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background-color: var(--platform-surface-1);
  border-bottom: 1px solid var(--platform-line);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;

  .host-info {
    display: flex;
    align-items: center;
    gap: 8px;
      color: var(--platform-text-primary);

    .el-icon {
      color: var(--el-color-primary);
    }

    .host-name {
      font-size: var(--platform-font-size-base);
      font-weight: var(--platform-font-weight-medium);
    }

    .host-ip {
      font-size: var(--platform-font-size-sm);
      color: var(--platform-text-muted);
      font-family: var(--el-font-family-mono);
    }
  }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

// 搜索框
.search-box {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background-color: var(--platform-surface-1);
  border-bottom: 1px solid var(--platform-line);
  flex-shrink: 0;

  .search-input {
    width: 300px;
  }

  .el-checkbox {
    color: var(--platform-text-primary);
  }
}

.terminal-wrapper {
  flex: 1;
  padding: 8px;
  overflow: hidden;

  .terminal {
    width: 100%;
    height: 100%;

    :deep(.xterm) {
      height: 100%;

      .xterm-screen {
        height: 100% !important;
      }
    }
  }
}

.terminal-footer {
  display: flex;
  justify-content: center;
  gap: 24px;
  padding: 8px 16px;
  background-color: var(--platform-surface-1);
  border-top: 1px solid var(--platform-line);
  flex-shrink: 0;

  span {
    font-size: var(--platform-font-size-xs);
    color: var(--platform-text-muted);
  }
}

// 全屏模式下的样式调整
.is-fullscreen {
  .terminal-header {
    padding: 8px 16px;
  }

  .terminal-wrapper {
    padding: 4px;
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .terminal-header {
    padding: 8px 12px;
    flex-wrap: wrap;
    gap: 8px;
  }

  .header-left {
    .host-info {
      .host-ip {
        display: none;
      }
    }
  }

  .header-right {
    .el-button-group {
      display: flex;
      flex-wrap: wrap;
    }
  }

  .search-box {
    flex-wrap: wrap;

    .search-input {
      width: 100%;
    }
  }

  .terminal-footer {
    display: none;
  }
}
</style>
