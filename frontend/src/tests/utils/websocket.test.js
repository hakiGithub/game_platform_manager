/**
 * websocket.js 单元测试
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  WebSocketClient,
  createSSHTerminal,
  createInstanceLogStream,
  createInstanceConsole,
} from "@/utils/websocket";

describe("websocket.js", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  describe("WebSocketClient", () => {
    describe("构造函数", () => {
      it("应该使用默认选项创建实例", () => {
        const client = new WebSocketClient();

        expect(client.url).toBe("");
        expect(client.protocols).toEqual([]);
        expect(client.reconnectEnabled).toBe(true);
        expect(client.reconnectInterval).toBe(3000);
        expect(client.reconnectAttempts).toBe(5);
        expect(client.heartbeatInterval).toBe(30000);
        expect(client.heartbeatMessage).toBe("ping");
      });

      it("应该使用自定义选项创建实例", () => {
        const options = {
          url: "ws://localhost:8080/ws",
          protocols: ["protocol1"],
          reconnectEnabled: false,
          reconnectInterval: 5000,
          reconnectAttempts: 10,
          heartbeatInterval: 60000,
          heartbeatMessage: "heartbeat",
        };

        const client = new WebSocketClient(options);

        expect(client.url).toBe("ws://localhost:8080/ws");
        expect(client.protocols).toEqual(["protocol1"]);
        expect(client.reconnectEnabled).toBe(false);
        expect(client.reconnectInterval).toBe(5000);
        expect(client.reconnectAttempts).toBe(10);
        expect(client.heartbeatInterval).toBe(60000);
        expect(client.heartbeatMessage).toBe("heartbeat");
      });

      it("应该设置回调函数", () => {
        const onOpen = vi.fn();
        const onMessage = vi.fn();
        const onError = vi.fn();
        const onClose = vi.fn();
        const onReconnect = vi.fn();

        const client = new WebSocketClient({
          onOpen,
          onMessage,
          onError,
          onClose,
          onReconnect,
        });

        expect(client.onOpen).toBe(onOpen);
        expect(client.onMessage).toBe(onMessage);
        expect(client.onError).toBe(onError);
        expect(client.onClose).toBe(onClose);
        expect(client.onReconnect).toBe(onReconnect);
      });
    });

    describe("connect", () => {
      it("应该成功连接 WebSocket", async () => {
        const onOpen = vi.fn();
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          onOpen,
        });

        const promise = client.connect();

        // 等待 setTimeout 触发 onopen
        await vi.runAllTimersAsync();

        const ws = await promise;

        expect(ws).toBeDefined();
        expect(onOpen).toHaveBeenCalled();
        expect(client.isConnected()).toBe(true);
      });

      it("应该在 URL 中添加 token 参数", async () => {
        localStorage.setItem("token", "test-token-123");

        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        expect(ws.url).toContain("token=test-token-123");
      });

      it("应该处理已有查询参数的 URL", async () => {
        localStorage.setItem("token", "test-token-456");

        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws?param=value",
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        expect(ws.url).toContain("param=value");
        expect(ws.url).toContain("&token=test-token-456");
      });

      it("如果已连接应该返回现有连接", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        // 第一次连接
        const promise1 = client.connect();
        await vi.runAllTimersAsync();
        const ws1 = await promise1;

        // 第二次连接
        const ws2 = await client.connect();

        expect(ws1).toBe(ws2);
      });
    });

    describe("send", () => {
      it("应该发送字符串消息", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        const sendSpy = vi.spyOn(ws, "send");

        const result = client.send("test message");

        expect(result).toBe(true);
        expect(sendSpy).toHaveBeenCalledWith("test message");
      });

      it("应该发送 JSON 对象消息", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        const sendSpy = vi.spyOn(ws, "send");

        const result = client.send({ type: "test", data: "value" });

        expect(result).toBe(true);
        expect(sendSpy).toHaveBeenCalledWith(
          JSON.stringify({ type: "test", data: "value" }),
        );
      });

      it("连接未打开时应该返回 false", () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        const result = client.send("test");

        expect(result).toBe(false);
      });
    });

    describe("sendBinary", () => {
      it("应该发送二进制数据", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        const sendSpy = vi.spyOn(ws, "send");
        const buffer = new ArrayBuffer(10);

        const result = client.sendBinary(buffer);

        expect(result).toBe(true);
        expect(sendSpy).toHaveBeenCalledWith(buffer);
      });

      it("连接未打开时应该返回 false", () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        const result = client.sendBinary(new ArrayBuffer(10));

        expect(result).toBe(false);
      });
    });

    describe("close", () => {
      it("应该关闭连接", async () => {
        const onClose = vi.fn();
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          onClose,
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        await promise;

        client.close();

        expect(client.isManualClose).toBe(true);
        expect(client.ws).toBeNull();
      });

      it("应该使用指定的关闭码和原因", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        const closeSpy = vi.spyOn(ws, "close");

        client.close(1001, "Going Away");

        expect(closeSpy).toHaveBeenCalledWith(1001, "Going Away");
      });
    });

    describe("心跳机制", () => {
      it("应该定期发送心跳消息", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          heartbeatInterval: 1000,
          heartbeatMessage: "heartbeat",
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        const sendSpy = vi.spyOn(client, "send");

        // 推进时间触发心跳
        vi.advanceTimersByTime(1000);

        expect(sendSpy).toHaveBeenCalledWith("heartbeat");
      });

      it("关闭连接时应该停止心跳", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          heartbeatInterval: 1000,
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        await promise;

        client.close();

        expect(client.heartbeatTimer).toBeNull();
      });
    });

    describe("消息处理", () => {
      it("应该触发 onMessage 回调", async () => {
        const onMessage = vi.fn();
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          onMessage,
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        // 模拟接收消息
        const messageEvent = { data: "test message" };
        ws.onmessage(messageEvent);

        expect(onMessage).toHaveBeenCalledWith(messageEvent);
      });

      it("应该忽略 pong 消息", async () => {
        const onMessage = vi.fn();
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          onMessage,
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        // 模拟接收 pong 消息
        ws.onmessage({ data: "pong" });

        expect(onMessage).not.toHaveBeenCalled();
      });
    });

    describe("重连机制", () => {
      it("应该在连接断开时尝试重连", async () => {
        const onReconnect = vi.fn();
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          reconnectAttempts: 3,
          reconnectInterval: 1000,
          onReconnect,
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        // 模拟连接断开
        ws.onclose({ code: 1000, reason: "" });

        // 推进时间触发重连
        vi.advanceTimersByTime(1000);

        expect(onReconnect).toHaveBeenCalled();
      });

      it("手动关闭时不应重连", async () => {
        const onReconnect = vi.fn();
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          onReconnect,
        });

        const promise = client.connect();
        await vi.runAllTimersAsync();
        await promise;

        client.close();

        // 推进时间
        vi.advanceTimersByTime(5000);

        expect(onReconnect).not.toHaveBeenCalled();
      });

      it("达到最大重连次数后应停止重连", async () => {
        const onReconnect = vi.fn();
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
          reconnectAttempts: 2,
          reconnectInterval: 100,
          onReconnect,
        });

        // 手动设置重连次数
        client.reconnectCount = 2;

        const promise = client.connect();
        await vi.runAllTimersAsync();
        const ws = await promise;

        // 重置重连次数以便测试
        client.reconnectCount = 2;

        // 模拟连接断开
        ws.onclose({ code: 1000, reason: "" });

        // 推进时间
        vi.advanceTimersByTime(1000);

        // 因为已经达到最大重连次数，不应该再触发重连
        expect(onReconnect).not.toHaveBeenCalled();
      });
    });

    describe("状态方法", () => {
      it("getReadyState 应该返回正确的状态", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        expect(client.getReadyState()).toBe(WebSocket.CLOSED);

        const promise = client.connect();
        await vi.runAllTimersAsync();
        await promise;

        expect(client.getReadyState()).toBe(WebSocket.OPEN);
      });

      it("isConnected 应该返回正确的连接状态", async () => {
        const client = new WebSocketClient({
          url: "ws://localhost:8080/ws",
        });

        expect(client.isConnected()).toBe(false);

        const promise = client.connect();
        await vi.runAllTimersAsync();
        await promise;

        expect(client.isConnected()).toBe(true);
      });
    });
  });

  describe("createSSHTerminal", () => {
    it("应该创建 SSH 终端 WebSocket 客户端", () => {
      const options = {
        hostId: 1,
        onMessage: vi.fn(),
        onOpen: vi.fn(),
        onClose: vi.fn(),
        onError: vi.fn(),
      };

      const client = createSSHTerminal(options);

      expect(client).toBeInstanceOf(WebSocketClient);
      expect(client.url).toContain("/ws/ssh/1");
      expect(client.reconnectAttempts).toBe(3);
      expect(client.reconnectInterval).toBe(5000);
    });
  });

  describe("createInstanceLogStream", () => {
    it("应该创建实例日志 WebSocket 客户端", () => {
      const options = {
        instanceId: 2,
        onMessage: vi.fn(),
        onOpen: vi.fn(),
        onClose: vi.fn(),
        onError: vi.fn(),
      };

      const client = createInstanceLogStream(options);

      expect(client).toBeInstanceOf(WebSocketClient);
      expect(client.url).toContain("/ws/instance/2/logs");
      expect(client.reconnectAttempts).toBe(5);
      expect(client.reconnectInterval).toBe(3000);
    });
  });

  describe("createInstanceConsole", () => {
    it("应该创建实例控制台 WebSocket 客户端", () => {
      const options = {
        instanceId: 3,
        onMessage: vi.fn(),
        onOpen: vi.fn(),
        onClose: vi.fn(),
        onError: vi.fn(),
      };

      const client = createInstanceConsole(options);

      expect(client).toBeInstanceOf(WebSocketClient);
      expect(client.url).toContain("/ws/instance/3/console");
      expect(client.reconnectAttempts).toBe(3);
      expect(client.reconnectInterval).toBe(5000);
    });
  });
});
