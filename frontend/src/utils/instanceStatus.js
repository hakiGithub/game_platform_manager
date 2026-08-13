/**
 * 实例状态展示工具（ADR-0005：run_status 词汇表统一后的唯一前端映射）
 *
 * 权威在后端 InstanceStatus 枚举：
 * - 文本：直接消费后端 runStatusDesc（枚举 description 派生），前端不再维护文本映射
 * - 本工具只保留 UI 元数据：el-tag 颜色类型、图标名，以及「活跃状态」集合
 *
 * 键为 InstanceVO.status（枚举 wireKey）：
 * stopped / running / starting / stopping / error / installing / updating / not_installed / unknown
 */

/** 活跃状态（过渡态）集合：处于这些状态时前端需要持续轮询刷新 */
export const ACTIVE_STATUSES = ["installing", "updating", "starting", "stopping"];

const TYPE_MAP = {
  stopped: "info",
  running: "success",
  starting: "warning",
  stopping: "warning",
  error: "danger",
  installing: "warning",
  updating: "warning",
  not_installed: "info",
  unknown: "info",
};

const ICON_MAP = {
  stopped: "CircleClose",
  running: "CircleCheck",
  starting: "Loading",
  stopping: "Loading",
  error: "Warning",
  installing: "Loading",
  updating: "Loading",
  not_installed: "CircleClose",
  unknown: "InfoFilled",
};

const COLOR_MAP = {
  stopped: "var(--platform-status-stopped)",
  running: "var(--platform-status-running)",
  starting: "var(--platform-status-deploying)",
  stopping: "var(--platform-status-deploying)",
  error: "var(--platform-status-error)",
  installing: "var(--platform-status-deploying)",
  updating: "var(--platform-status-deploying)",
  not_installed: "var(--platform-status-stopped)",
};

/** 状态 → el-tag type */
export function statusType(status) {
  return TYPE_MAP[status] || "info";
}

/** 状态 → 图标名（Element Plus 图标组件名） */
export function statusIcon(status) {
  return ICON_MAP[status] || "InfoFilled";
}

/** 状态 → 图标颜色（CSS 变量） */
export function statusColor(status) {
  return COLOR_MAP[status] || "var(--platform-status-stopped)";
}
