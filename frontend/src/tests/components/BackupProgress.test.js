import { describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { ElProgress, ElTag, ElButton, ElIcon } from "element-plus";
import BackupProgress from "@/components/BackupProgress.vue";

// Mock Element Plus icons
vi.mock("@element-plus/icons-vue", () => ({
  CircleCheck: { name: "CircleCheck" },
  CircleClose: { name: "CircleClose" },
  Loading: { name: "Loading" },
  Warning: { name: "Warning" },
}));

describe("BackupProgress Component", () => {
  const createWrapper = (props = {}) => {
    return mount(BackupProgress, {
      props: {
        progress: 0,
        status: "running",
        message: "",
        currentStep: "",
        completed: false,
        showCancel: true,
        ...props,
      },
      global: {
        components: {
          ElProgress,
          ElTag,
          ElButton,
          ElIcon,
        },
      },
    });
  };

  describe("Rendering", () => {
    it("should render progress bar with correct percentage", () => {
      const wrapper = createWrapper({ progress: 50 });
      const progress = wrapper.findComponent(ElProgress);
      expect(progress.exists()).toBe(true);
      expect(progress.props("percentage")).toBe(50);
    });

    it("should clamp progress between 0 and 100", () => {
      const wrapper = createWrapper({ progress: 150 });
      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("percentage")).toBe(100);
    });

    it("should render status tag", () => {
      const wrapper = createWrapper({ status: "running" });
      const tag = wrapper.findComponent(ElTag);
      expect(tag.exists()).toBe(true);
      expect(tag.text()).toContain("进行中");
    });

    it("should render message when provided", () => {
      const wrapper = createWrapper({
        message: "Backing up database...",
        currentStep: "Exporting tables",
      });
      expect(wrapper.text()).toContain("Backing up database...");
      expect(wrapper.text()).toContain("当前步骤: Exporting tables");
    });
  });

  describe("Status Display", () => {
    it("should display correct status text for running", () => {
      const wrapper = createWrapper({ status: "running" });
      expect(wrapper.text()).toContain("进行中");
    });

    it("should display correct status text for completed", () => {
      const wrapper = createWrapper({ status: "completed" });
      expect(wrapper.text()).toContain("已完成");
    });

    it("should display correct status text for failed", () => {
      const wrapper = createWrapper({ status: "failed" });
      expect(wrapper.text()).toContain("失败");
    });

    it("should display correct status text for cancelled", () => {
      const wrapper = createWrapper({ status: "cancelled" });
      expect(wrapper.text()).toContain("已取消");
    });

    it("should use correct tag type for each status", () => {
      const statuses = [
        { status: "running", type: "primary" },
        { status: "completed", type: "success" },
        { status: "failed", type: "danger" },
        { status: "cancelled", type: "info" },
      ];

      statuses.forEach(({ status, type }) => {
        const wrapper = createWrapper({ status });
        const tag = wrapper.findComponent(ElTag);
        expect(tag.props("type")).toBe(type);
      });
    });
  });

  describe("Progress Bar Status", () => {
    it("should set progress status to exception when failed", () => {
      const wrapper = createWrapper({ status: "failed" });
      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("status")).toBe("exception");
    });

    it("should set progress status to success when completed", () => {
      const wrapper = createWrapper({ status: "completed" });
      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("status")).toBe("success");
    });

    it("should not set progress status when running", () => {
      const wrapper = createWrapper({ status: "running" });
      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("status")).toBe("");
    });
  });

  describe("Cancel Button", () => {
    it("should show cancel button when running and showCancel is true", () => {
      const wrapper = createWrapper({
        status: "running",
        showCancel: true,
        completed: false,
      });
      const cancelButton = wrapper.findComponent(ElButton);
      expect(cancelButton.exists()).toBe(true);
      expect(cancelButton.text()).toContain("取消");
    });

    it("should not show cancel button when completed", () => {
      const wrapper = createWrapper({
        status: "running",
        completed: true,
      });
      const buttons = wrapper.findAllComponents(ElButton);
      expect(buttons.length).toBe(0);
    });

    it("should not show cancel button when showCancel is false", () => {
      const wrapper = createWrapper({
        status: "running",
        showCancel: false,
      });
      const buttons = wrapper.findAllComponents(ElButton);
      expect(buttons.length).toBe(0);
    });

    it("should not show cancel button when status is not running", () => {
      const wrapper = createWrapper({
        status: "completed",
        showCancel: true,
      });
      const buttons = wrapper.findAllComponents(ElButton);
      expect(buttons.length).toBe(0);
    });

    it("should emit cancel event when cancel button clicked", async () => {
      const wrapper = createWrapper({
        status: "running",
        showCancel: true,
      });
      const cancelButton = wrapper.findComponent(ElButton);
      await cancelButton.trigger("click");
      expect(wrapper.emitted("cancel")).toBeTruthy();
    });
  });

  describe("Props", () => {
    it("should accept custom stroke width", () => {
      const wrapper = createWrapper({ strokeWidth: 20 });
      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("strokeWidth")).toBe(20);
    });

    it("should hide text when showText is false", () => {
      const wrapper = createWrapper({ showText: false });
      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("showText")).toBe(false);
    });
  });
});
