/**
 * 日期格式化工具函数
 */
import dayjs from "dayjs";
import relativeTime from "dayjs/plugin/relativeTime";
import "dayjs/locale/zh-cn";

dayjs.extend(relativeTime);
dayjs.locale("zh-cn");

/**
 * 格式化日期
 * @param {string|Date} date 日期
 * @param {string} format 格式
 * @returns {string}
 */
export function formatDate(date, format = "YYYY-MM-DD HH:mm:ss") {
  if (!date) return "";
  return dayjs(date).format(format);
}

/**
 * 获取相对时间
 * @param {string|Date} date 日期
 * @returns {string}
 */
export function formatRelativeTime(date) {
  if (!date) return "";
  return dayjs(date).fromNow();
}

/**
 * 格式化文件大小
 * @param {number} bytes 字节数
 * @returns {string}
 */
export function formatFileSize(bytes) {
  if (bytes === 0) return "0 B";

  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));

  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
}

/**
 * 格式化持续时间
 * @param {number} seconds 秒数
 * @returns {string}
 */
export function formatDuration(seconds) {
  if (!seconds || seconds < 0) return "0秒";

  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);

  const parts = [];
  if (days > 0) parts.push(`${days}天`);
  if (hours > 0) parts.push(`${hours}小时`);
  if (minutes > 0) parts.push(`${minutes}分钟`);
  if (secs > 0 || parts.length === 0) parts.push(`${secs}秒`);

  return parts.join("");
}

/**
 * 防抖函数
 * @param {Function} fn 目标函数
 * @param {number} delay 延迟时间
 * @returns {Function}
 */
export function debounce(fn, delay = 300) {
  let timer = null;
  return function (...args) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      fn.apply(this, args);
    }, delay);
  };
}

/**
 * 节流函数
 * @param {Function} fn 目标函数
 * @param {number} interval 间隔时间
 * @returns {Function}
 */
export function throttle(fn, interval = 300) {
  let lastTime = 0;
  return function (...args) {
    const now = Date.now();
    if (now - lastTime >= interval) {
      lastTime = now;
      fn.apply(this, args);
    }
  };
}

/**
 * 深拷贝
 * @param {Object} obj 目标对象
 * @returns {Object}
 */
export function deepClone(obj) {
  if (obj === null || typeof obj !== "object") return obj;

  if (obj instanceof Date) return new Date(obj);
  if (obj instanceof Array) return obj.map((item) => deepClone(item));
  if (obj instanceof Object) {
    const copy = {};
    Object.keys(obj).forEach((key) => {
      copy[key] = deepClone(obj[key]);
    });
    return copy;
  }
}

/**
 * 生成 UUID
 * @returns {string}
 */
export function generateUUID() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * 复制文本到剪贴板
 * @param {string} text 文本内容
 * @returns {Promise<void>}
 */
export async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
    return Promise.resolve();
  } catch (error) {
    // 降级方案
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand("copy");
    document.body.removeChild(textarea);
    return Promise.resolve();
  }
}
