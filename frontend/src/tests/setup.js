/**
 * 测试环境设置文件
 */

import { vi } from "vitest";
import { config } from "@vue/test-utils";

// 设置全局测试环境
config.global.stubs = {};

// Mock localStorage
const localStorageMock = {
  store: {},
  getItem(key) {
    return this.store[key] || null;
  },
  setItem(key, value) {
    this.store[key] = value.toString();
  },
  removeItem(key) {
    delete this.store[key];
  },
  clear() {
    this.store = {};
  },
};
Object.defineProperty(global, "localStorage", {
  value: localStorageMock,
});

// Mock sessionStorage
const sessionStorageMock = {
  store: {},
  getItem(key) {
    return this.store[key] || null;
  },
  setItem(key, value) {
    this.store[key] = value.toString();
  },
  removeItem(key) {
    delete this.store[key];
  },
  clear() {
    this.store = {};
  },
};
Object.defineProperty(global, "sessionStorage", {
  value: sessionStorageMock,
});

// Mock WebSocket
class MockWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  constructor(url, protocols) {
    this.url = url;
    this.protocols = protocols;
    this.readyState = MockWebSocket.OPEN;
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.onclose = null;

    // 模拟异步连接
    setTimeout(() => {
      if (this.onopen) {
        this.onopen({ type: "open" });
      }
    }, 0);
  }

  send(data) {
    // 模拟发送消息
  }

  close(code = 1000, reason = "") {
    this.readyState = MockWebSocket.CLOSED;
    if (this.onclose) {
      this.onclose({ code, reason, type: "close" });
    }
  }
}
Object.defineProperty(global, "WebSocket", {
  value: MockWebSocket,
});

// Mock navigator.clipboard
Object.defineProperty(global.navigator, "clipboard", {
  value: {
    writeText: vi.fn().mockResolvedValue(undefined),
  },
});

// Mock location
const originalLocation = global.location;
Object.defineProperty(global, "location", {
  value: {
    ...originalLocation,
    protocol: "http:",
    host: "localhost:3000",
    href: "http://localhost:3000",
  },
  writable: true,
});

// Mock import.meta.env
vi.stubGlobal("import.meta", {
  env: {
    VITE_API_BASE_URL: "/api",
    VITE_WS_BASE_URL: "ws://localhost:8080",
  },
});

// 全局 beforeEach 钩子 - 清理 localStorage
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

// 全局 afterEach 钩子 - 清理所有 mock
afterEach(() => {
  vi.clearAllMocks();
});
