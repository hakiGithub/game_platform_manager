/**
 * WebSocket 工具类
 * 用于Web SSH终端和其他实时通信
 */

class WebSocketClient {
  constructor(options = {}) {
    this.url = options.url || "";
    this.protocols = options.protocols || [];
    this.reconnectEnabled = options.reconnectEnabled !== false;
    this.reconnectInterval = options.reconnectInterval || 3000;
    this.reconnectAttempts = options.reconnectAttempts || 5;
    this.heartbeatInterval = options.heartbeatInterval || 30000;
    this.heartbeatMessage = options.heartbeatMessage || "ping";

    this.ws = null;
    this.reconnectCount = 0;
    this.heartbeatTimer = null;
    this.isConnecting = false;
    this.isManualClose = false;
    this.isReconnecting = false;

    // 回调函数
    this.onOpen = options.onOpen || (() => {});
    this.onMessage = options.onMessage || (() => {});
    this.onError = options.onError || (() => {});
    this.onClose = options.onClose || (() => {});
    this.onReconnect = options.onReconnect || (() => {});
  }

  /**
   * 连接WebSocket
   * @returns {Promise<WebSocket>}
   */
  connect() {
    return new Promise((resolve, reject) => {
      if (
        this.ws &&
        (this.ws.readyState === WebSocket.OPEN ||
          this.ws.readyState === WebSocket.CONNECTING)
      ) {
        resolve(this.ws);
        return;
      }

      this.isConnecting = true;
      this.isManualClose = false;

      try {
        // 获取token
        const token = localStorage.getItem("token") || "";

        // 构建WebSocket URL，添加token参数
        let wsUrl = this.url;
        if (token) {
          const separator = wsUrl.includes("?") ? "&" : "?";
          wsUrl = `${wsUrl}${separator}token=${encodeURIComponent(token)}`;
        }

        this.ws = new WebSocket(wsUrl, this.protocols);

        this.ws.onopen = (event) => {
          console.log("[WebSocket] Connected");
          this.isConnecting = false;
          this.isReconnecting = false;
          this.reconnectCount = 0;
          this.startHeartbeat();
          this.onOpen(event);
          resolve(this.ws);
        };

        this.ws.onmessage = (event) => {
          // 处理心跳响应：可能是纯文本 "pong" 或 JSON {"type":"pong",...}
          if (event.data === "pong") {
            return;
          }
          try {
            const msg = JSON.parse(event.data);
            if (msg && msg.type === "pong") {
              return;
            }
          } catch (e) {
            // 非 JSON，交给业务回调处理
          }
          this.onMessage(event);
        };

        this.ws.onerror = (event) => {
          console.error("[WebSocket] Error:", event);
          this.isConnecting = false;
          this.onError(event);
          reject(event);
        };

        this.ws.onclose = (event) => {
          console.log("[WebSocket] Closed:", event.code, event.reason);
          this.isConnecting = false;
          this.stopHeartbeat();
          this.onClose(event);

          // 自动重连
          if (
            this.reconnectEnabled &&
            !this.isManualClose &&
            this.reconnectCount < this.reconnectAttempts
          ) {
            this.reconnect();
          }
        };
      } catch (error) {
        this.isConnecting = false;
        reject(error);
      }
    });
  }

  /**
   * 重连
   */
  reconnect() {
    // 防止重复重连
    if (this.isReconnecting) {
      return;
    }

    this.isReconnecting = true;
    this.reconnectCount++;
    console.log(
      `[WebSocket] Reconnecting... (${this.reconnectCount}/${this.reconnectAttempts})`,
    );
    this.onReconnect(this.reconnectCount);

    setTimeout(() => {
      this.connect()
        .catch((error) => {
          console.error("[WebSocket] Reconnect failed:", error);
        })
        .finally(() => {
          this.isReconnecting = false;
        });
    }, this.reconnectInterval);
  }

  /**
   * 发送消息
   * @param {string|Object} data - 消息数据
   */
  send(data) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn("[WebSocket] Connection is not open");
      return false;
    }

    try {
      const message = typeof data === "object" ? JSON.stringify(data) : data;
      this.ws.send(message);
      return true;
    } catch (error) {
      console.error("[WebSocket] Send error:", error);
      return false;
    }
  }

  /**
   * 发送二进制数据
   * @param {ArrayBuffer|Blob} data - 二进制数据
   */
  sendBinary(data) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn("[WebSocket] Connection is not open");
      return false;
    }

    try {
      this.ws.send(data);
      return true;
    } catch (error) {
      console.error("[WebSocket] Send binary error:", error);
      return false;
    }
  }

  /**
   * 关闭连接
   * @param {number} [code=1000] - 关闭码
   * @param {string} [reason=''] - 关闭原因
   */
  close(code = 1000, reason = "") {
    this.isManualClose = true;
    this.stopHeartbeat();

    if (this.ws) {
      this.ws.close(code, reason);
      this.ws = null;
    }
  }

  /**
   * 开始心跳
   */
  startHeartbeat() {
    this.stopHeartbeat();

    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send(this.heartbeatMessage);
      }
    }, this.heartbeatInterval);
  }

  /**
   * 停止心跳
   */
  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /**
   * 获取连接状态
   * @returns {number} 0-CONNECTING, 1-OPEN, 2-CLOSING, 3-CLOSED
   */
  getReadyState() {
    return this.ws ? this.ws.readyState : WebSocket.CLOSED;
  }

  /**
   * 是否已连接
   * @returns {boolean}
   */
  isConnected() {
    return this.ws && this.ws.readyState === WebSocket.OPEN;
  }
}

/**
 * 构建WebSocket URL
 * 开发环境使用相对路径（通过Vite代理），生产环境使用完整URL
 * @param {string} path - WebSocket路径（如 /ws/ssh/1）
 * @returns {string} 完整的WebSocket URL
 */
function buildWebSocketUrl(path) {
  // 如果环境变量指定了完整的WebSocket基础URL，直接使用
  if (import.meta.env.VITE_WS_BASE_URL) {
    return `${import.meta.env.VITE_WS_BASE_URL}${path}`;
  }

  // 开发环境：使用相对路径，让Vite代理处理
  if (import.meta.env.DEV) {
    // 使用当前页面的协议和主机，但WebSocket协议
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${location.host}${path}`;
  }

  // 生产环境：基于当前页面URL构建
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${location.host}${path}`;
}

/**
 * 创建SSH终端WebSocket连接
 * @param {Object} options - 配置选项
 * @param {number} options.hostId - 主机ID
 * @param {Function} options.onMessage - 消息回调
 * @param {Function} options.onOpen - 连接成功回调
 * @param {Function} options.onClose - 连接关闭回调
 * @param {Function} options.onError - 错误回调
 * @returns {WebSocketClient}
 */
export function createSSHTerminal(options) {
  const wsUrl = buildWebSocketUrl(`/ws/ssh/${options.hostId}`);

  return new WebSocketClient({
    url: wsUrl,
    reconnectEnabled: true,
    reconnectAttempts: 3,
    reconnectInterval: 5000,
    heartbeatInterval: 30000,
    onMessage: options.onMessage,
    onOpen: options.onOpen,
    onClose: options.onClose,
    onError: options.onError,
    onReconnect: options.onReconnect,
  });
}

/**
 * 创建实例日志WebSocket连接
 * @param {Object} options - 配置选项
 * @param {number} options.instanceId - 实例ID
 * @param {Function} options.onMessage - 消息回调
 * @param {Function} options.onOpen - 连接成功回调
 * @param {Function} options.onClose - 连接关闭回调
 * @param {Function} options.onError - 错误回调
 * @returns {WebSocketClient}
 */
export function createInstanceLogStream(options) {
  const wsUrl = buildWebSocketUrl(`/ws/instance/${options.instanceId}/logs`);

  return new WebSocketClient({
    url: wsUrl,
    reconnectEnabled: true,
    reconnectAttempts: 5,
    reconnectInterval: 3000,
    heartbeatInterval: 30000,
    onMessage: options.onMessage,
    onOpen: options.onOpen,
    onClose: options.onClose,
    onError: options.onError,
    onReconnect: options.onReconnect,
  });
}

/**
 * 创建实例控制台WebSocket连接
 * @param {Object} options - 配置选项
 * @param {number} options.instanceId - 实例ID
 * @param {Function} options.onMessage - 消息回调
 * @param {Function} options.onOpen - 连接成功回调
 * @param {Function} options.onClose - 连接关闭回调
 * @param {Function} options.onError - 错误回调
 * @returns {WebSocketClient}
 */
export function createInstanceConsole(options) {
  const wsUrl = buildWebSocketUrl(`/ws/instance/${options.instanceId}/console`);

  return new WebSocketClient({
    url: wsUrl,
    reconnectEnabled: true,
    reconnectAttempts: 3,
    reconnectInterval: 5000,
    heartbeatInterval: 30000,
    // 后端期望 {type:"ping", data:""} 格式的 JSON 心跳
    heartbeatMessage: JSON.stringify({ type: "ping", data: "" }),
    onMessage: options.onMessage,
    onOpen: options.onOpen,
    onClose: options.onClose,
    onError: options.onError,
    onReconnect: options.onReconnect,
  });
}

export { WebSocketClient };
export default WebSocketClient;
