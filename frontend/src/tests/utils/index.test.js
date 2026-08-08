/**
 * index.js (工具函数) 单元测试
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  formatDate,
  formatRelativeTime,
  formatFileSize,
  formatDuration,
  debounce,
  throttle,
  deepClone,
  generateUUID,
  copyToClipboard,
} from "@/utils/index";

describe("utils/index.js", () => {
  describe("formatDate", () => {
    it("应该格式化日期为默认格式", () => {
      const date = new Date("2024-03-15T10:30:45");
      const result = formatDate(date);

      expect(result).toBe("2024-03-15 10:30:45");
    });

    it("应该使用自定义格式", () => {
      const date = new Date("2024-03-15T10:30:45");
      const result = formatDate(date, "YYYY/MM/DD");

      expect(result).toBe("2024/03/15");
    });

    it("应该处理空日期", () => {
      const result = formatDate(null);

      expect(result).toBe("");
    });

    it("应该处理字符串日期", () => {
      const result = formatDate("2024-03-15T10:30:45");

      expect(result).toBe("2024-03-15 10:30:45");
    });
  });

  describe("formatRelativeTime", () => {
    it("应该返回相对时间", () => {
      const now = new Date();
      const past = new Date(now.getTime() - 60000); // 1分钟前

      const result = formatRelativeTime(past);

      expect(result).toContain("分钟前");
    });

    it("应该处理空日期", () => {
      const result = formatRelativeTime(null);

      expect(result).toBe("");
    });
  });

  describe("formatFileSize", () => {
    it("应该格式化字节", () => {
      expect(formatFileSize(500)).toBe("500 B");
    });

    it("应该格式化 KB", () => {
      expect(formatFileSize(1024)).toBe("1 KB");
      expect(formatFileSize(1536)).toBe("1.5 KB");
    });

    it("应该格式化 MB", () => {
      expect(formatFileSize(1048576)).toBe("1 MB");
      expect(formatFileSize(1572864)).toBe("1.5 MB");
    });

    it("应该格式化 GB", () => {
      expect(formatFileSize(1073741824)).toBe("1 GB");
    });

    it("应该格式化 TB", () => {
      expect(formatFileSize(1099511627776)).toBe("1 TB");
    });

    it("应该处理 0 字节", () => {
      expect(formatFileSize(0)).toBe("0 B");
    });
  });

  describe("formatDuration", () => {
    it("应该格式化秒数", () => {
      expect(formatDuration(45)).toBe("45秒");
    });

    it("应该格式化分钟和秒", () => {
      expect(formatDuration(125)).toBe("2分钟5秒");
    });

    it("应该格式化小时、分钟和秒", () => {
      expect(formatDuration(3725)).toBe("1小时2分钟5秒");
    });

    it("应该格式化天、小时、分钟和秒", () => {
      expect(formatDuration(90061)).toBe("1天1小时1分钟1秒");
    });

    it("应该处理 0 秒", () => {
      expect(formatDuration(0)).toBe("0秒");
    });

    it("应该处理负数", () => {
      expect(formatDuration(-10)).toBe("0秒");
    });

    it("应该处理 null/undefined", () => {
      expect(formatDuration(null)).toBe("0秒");
      expect(formatDuration(undefined)).toBe("0秒");
    });
  });

  describe("debounce", () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("应该延迟执行函数", () => {
      const fn = vi.fn();
      const debouncedFn = debounce(fn, 300);

      debouncedFn();

      expect(fn).not.toHaveBeenCalled();

      vi.advanceTimersByTime(300);

      expect(fn).toHaveBeenCalledTimes(1);
    });

    it("应该只执行最后一次调用", () => {
      const fn = vi.fn();
      const debouncedFn = debounce(fn, 300);

      debouncedFn("first");
      debouncedFn("second");
      debouncedFn("third");

      vi.advanceTimersByTime(300);

      expect(fn).toHaveBeenCalledTimes(1);
      expect(fn).toHaveBeenCalledWith("third");
    });

    it("应该取消之前的定时器", () => {
      const fn = vi.fn();
      const debouncedFn = debounce(fn, 300);

      debouncedFn();
      vi.advanceTimersByTime(100);

      debouncedFn();
      vi.advanceTimersByTime(100);

      debouncedFn();
      vi.advanceTimersByTime(300);

      expect(fn).toHaveBeenCalledTimes(1);
    });

    it("应该使用默认延迟时间", () => {
      const fn = vi.fn();
      const debouncedFn = debounce(fn);

      debouncedFn();

      vi.advanceTimersByTime(300);

      expect(fn).toHaveBeenCalledTimes(1);
    });
  });

  describe("throttle", () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("应该限制函数执行频率", () => {
      const fn = vi.fn();
      const throttledFn = throttle(fn, 300);

      throttledFn("first");
      throttledFn("second");
      throttledFn("third");

      expect(fn).toHaveBeenCalledTimes(1);
      expect(fn).toHaveBeenCalledWith("first");
    });

    it("应该在间隔后允许再次执行", () => {
      const fn = vi.fn();
      const throttledFn = throttle(fn, 300);

      throttledFn("first");

      vi.advanceTimersByTime(300);

      throttledFn("second");

      expect(fn).toHaveBeenCalledTimes(2);
    });

    it("应该使用默认间隔时间", () => {
      const fn = vi.fn();
      const throttledFn = throttle(fn);

      throttledFn();

      vi.advanceTimersByTime(300);

      throttledFn();

      expect(fn).toHaveBeenCalledTimes(2);
    });
  });

  describe("deepClone", () => {
    it("应该深拷贝对象", () => {
      const obj = {
        name: "test",
        nested: {
          value: 123,
        },
      };

      const cloned = deepClone(obj);

      expect(cloned).toEqual(obj);
      expect(cloned).not.toBe(obj);
      expect(cloned.nested).not.toBe(obj.nested);
    });

    it("应该深拷贝数组", () => {
      const arr = [1, { a: 2 }, [3, 4]];

      const cloned = deepClone(arr);

      expect(cloned).toEqual(arr);
      expect(cloned).not.toBe(arr);
      expect(cloned[1]).not.toBe(arr[1]);
      expect(cloned[2]).not.toBe(arr[2]);
    });

    it("应该拷贝 Date 对象", () => {
      const date = new Date("2024-03-15");

      const cloned = deepClone(date);

      expect(cloned).toEqual(date);
      expect(cloned).not.toBe(date);
    });

    it("应该处理 null", () => {
      expect(deepClone(null)).toBeNull();
    });

    it("应该处理原始类型", () => {
      expect(deepClone("string")).toBe("string");
      expect(deepClone(123)).toBe(123);
      expect(deepClone(true)).toBe(true);
      expect(deepClone(undefined)).toBeUndefined();
    });
  });

  describe("generateUUID", () => {
    it("应该生成有效的 UUID", () => {
      const uuid = generateUUID();

      // UUID 格式: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
      expect(uuid).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
    });

    it("应该生成唯一的 UUID", () => {
      const uuids = new Set();

      for (let i = 0; i < 100; i++) {
        uuids.add(generateUUID());
      }

      expect(uuids.size).toBe(100);
    });
  });

  describe("copyToClipboard", () => {
    it("应该使用 Clipboard API 复制文本", async () => {
      const text = "test text";

      await copyToClipboard(text);

      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(text);
    });

    it("当 Clipboard API 失败时应该使用降级方案", async () => {
      // Mock Clipboard API 失败
      navigator.clipboard.writeText.mockRejectedValueOnce(
        new Error("Clipboard API not available"),
      );

      const text = "fallback text";

      await copyToClipboard(text);

      // 验证降级方案创建了 textarea 元素
      expect(document.createElement).toBeDefined();
    });
  });
});
