/**
 * 实例状态展示工具单测（ADR-0005）
 * 锁定前端唯一的状态 → UI 元数据映射，防止漂移。
 * 文本权威在后端（runStatusDesc），前端不维护文本映射。
 */
import { describe, it, expect } from "vitest";
import { statusType, statusIcon, statusColor, ACTIVE_STATUSES } from "@/utils/instanceStatus";

describe("instanceStatus 工具", () => {
  it("statusType 映射与 InstanceStatus wireKey 一致", () => {
    expect(statusType("stopped")).toBe("info");
    expect(statusType("running")).toBe("success");
    expect(statusType("starting")).toBe("warning");
    expect(statusType("stopping")).toBe("warning");
    expect(statusType("error")).toBe("danger");
    expect(statusType("installing")).toBe("warning");
    expect(statusType("updating")).toBe("warning");
    expect(statusType("not_installed")).toBe("info");
    expect(statusType("unknown")).toBe("info");
    expect(statusType("不存在的键")).toBe("info");
  });

  it("statusIcon 覆盖全部 wireKey", () => {
    expect(statusIcon("running")).toBe("CircleCheck");
    expect(statusIcon("stopped")).toBe("CircleClose");
    expect(statusIcon("starting")).toBe("Loading");
    expect(statusIcon("installing")).toBe("Loading");
    expect(statusIcon("updating")).toBe("Loading");
    expect(statusIcon("error")).toBe("Warning");
  });

  it("statusColor 覆盖全部 wireKey 并回退默认色", () => {
    expect(statusColor("running")).toBe("var(--platform-status-running)");
    expect(statusColor("error")).toBe("var(--platform-status-error)");
    expect(statusColor("not_installed")).toBe("var(--platform-status-stopped)");
    expect(statusColor("unknown")).toBe("var(--platform-status-stopped)");
  });

  it("ACTIVE_STATUSES 为四个过渡态（活跃期轮询依据）", () => {
    expect(ACTIVE_STATUSES).toEqual(["installing", "updating", "starting", "stopping"]);
  });
});
