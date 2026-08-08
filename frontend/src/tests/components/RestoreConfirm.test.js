import { describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
import {
  ElDialog,
  ElAlert,
  ElTag,
  ElButton,
  ElProgress,
  ElDescriptions,
  ElDescriptionsItem,
  ElIcon,
} from "element-plus";
import RestoreConfirm from "@/components/RestoreConfirm.vue";

// Mock Element Plus icons
vi.mock("@element-plus/icons-vue", () => ({
  WarningFilled: { name: "WarningFilled" },
  Calendar: { name: "Calendar" },
  Document: { name: "Document" },
  DataLine: { name: "DataLine" },
  Folder: { name: "Folder" },
  InfoFilled: { name: "InfoFilled" },
  CircleCheck: { name: "CircleCheck" },
  CircleClose: { name: "CircleClose" },
  Loading: { name: "Loading" },
}));

describe("RestoreConfirm Component", () => {
  const mockBackup = {
    id: 1,
    name: "Test Backup",
    type: "database",
    status: "completed",
    size: 1024 * 1024 * 100, // 100MB
    createdAt: "2024-01-15T10:30:00Z",
    description: "Test backup description",
  };

  const mockInstance = {
    id: 1,
    name: "Test Instance",
    status: "running",
    game: "Minecraft",
    hostName: "Test Host",
  };

  const createWrapper = (props = {}) => {
    return mount(RestoreConfirm, {
      props: {
        visible: true,
        backup: mockBackup,
        instance: mockInstance,
        loading: false,
        restoreProgress: {
          progress: 0,
          status: "",
          message: "",
          currentStep: "",
          completed: false,
        },
        ...props,
      },
      global: {
        components: {
          ElDialog,
          ElAlert,
          ElTag,
          ElButton,
          ElProgress,
          ElDescriptions,
          ElDescriptionsItem,
          ElIcon,
        },
      },
    });
  };

  describe("Rendering", () => {
    it("should render dialog when visible", () => {
      const wrapper = createWrapper({ visible: true });
      expect(wrapper.findComponent(ElDialog).exists()).toBe(true);
    });

    it("should render danger warning alert", () => {
      const wrapper = createWrapper();
      const alert = wrapper.findComponent(ElAlert);
      expect(alert.exists()).toBe(true);
      expect(alert.props("type")).toBe("error");
      expect(alert.props("title")).toBe("危险操作警告");
    });

    it("should render backup information", () => {
      const wrapper = createWrapper();
      expect(wrapper.text()).toContain("Test Backup");
      expect(wrapper.text()).toContain("数据库备份");
    });

    it("should render instance information", () => {
      const wrapper = createWrapper();
      expect(wrapper.text()).toContain("Test Instance");
      expect(wrapper.text()).toContain("Minecraft");
    });
  });

  describe("Progress Display", () => {
    it("should show progress section when restoring", () => {
      const wrapper = createWrapper({
        restoreProgress: {
          progress: 50,
          status: "running",
          message: "Restoring...",
          currentStep: "Importing data",
          completed: false,
        },
      });

      const progress = wrapper.findComponent(ElProgress);
      expect(progress.exists()).toBe(true);
      expect(progress.props("percentage")).toBe(50);
    });

    it("should show completed status", () => {
      const wrapper = createWrapper({
        restoreProgress: {
          progress: 100,
          status: "completed",
          message: "Restore completed",
          completed: true,
        },
      });

      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("status")).toBe("success");
    });

    it("should show failed status", () => {
      const wrapper = createWrapper({
        restoreProgress: {
          progress: 50,
          status: "failed",
          message: "Restore failed",
          completed: true,
        },
      });

      const progress = wrapper.findComponent(ElProgress);
      expect(progress.props("status")).toBe("exception");
    });
  });

  describe("Buttons", () => {
    it("should show confirm and cancel buttons initially", () => {
      const wrapper = createWrapper();
      const buttons = wrapper.findAllComponents(ElButton);

      const confirmButton = buttons.find((btn) =>
        btn.text().includes("确认还原"),
      );
      const cancelButton = buttons.find((btn) => btn.text().includes("取消"));

      expect(confirmButton).toBeDefined();
      expect(cancelButton).toBeDefined();
    });

    it("should disable dialog close when restoring", () => {
      const wrapper = createWrapper({
        loading: true,
        restoreProgress: {
          progress: 50,
          status: "running",
          completed: false,
        },
      });

      const dialog = wrapper.findComponent(ElDialog);
      expect(dialog.props("closeOnClickModal")).toBe(false);
      expect(dialog.props("closeOnPressEscape")).toBe(false);
      expect(dialog.props("showClose")).toBe(false);
    });

    it("should emit confirm when confirm button clicked", async () => {
      const wrapper = createWrapper();
      const confirmButton = wrapper
        .findAllComponents(ElButton)
        .find((btn) => btn.text().includes("确认还原"));

      await confirmButton?.trigger("click");

      expect(wrapper.emitted("confirm")).toBeTruthy();
      expect(wrapper.emitted("confirm")[0]).toEqual([mockBackup]);
    });

    it("should emit cancel when cancel button clicked", async () => {
      const wrapper = createWrapper();
      const cancelButton = wrapper
        .findAllComponents(ElButton)
        .find((btn) => btn.text().includes("取消"));

      await cancelButton?.trigger("click");

      expect(wrapper.emitted("cancel")).toBeTruthy();
    });

    it("should show loading state during restore", () => {
      const wrapper = createWrapper({
        loading: true,
        restoreProgress: {
          progress: 50,
          status: "running",
          completed: false,
        },
      });

      const confirmButton = wrapper
        .findAllComponents(ElButton)
        .find((btn) => btn.text().includes("还原中"));
      expect(confirmButton).toBeDefined();
      expect(confirmButton?.props("disabled")).toBe(true);
    });

    it("should show complete button when finished", () => {
      const wrapper = createWrapper({
        restoreProgress: {
          progress: 100,
          status: "completed",
          completed: true,
        },
      });

      const completeButton = wrapper
        .findAllComponents(ElButton)
        .find((btn) => btn.text().includes("完成"));
      expect(completeButton).toBeDefined();
    });
  });

  describe("Success/Error Alerts", () => {
    it("should show success alert when restore completes", () => {
      const wrapper = createWrapper({
        restoreProgress: {
          progress: 100,
          status: "completed",
          completed: true,
        },
      });

      const alerts = wrapper.findAllComponents(ElAlert);
      const successAlert = alerts.find(
        (alert) => alert.props("type") === "success",
      );
      expect(successAlert).toBeDefined();
    });

    it("should show error alert when restore fails", () => {
      const wrapper = createWrapper({
        restoreProgress: {
          progress: 50,
          status: "failed",
          message: "Connection error",
          completed: true,
        },
      });

      const alerts = wrapper.findAllComponents(ElAlert);
      const errorAlert = alerts.find(
        (alert) =>
          alert.props("type") === "error" &&
          alert.props("title") === "还原失败",
      );
      expect(errorAlert).toBeDefined();
    });
  });

  describe("Backup Type Display", () => {
    it("should show database icon for database backup", () => {
      const wrapper = createWrapper({
        backup: { ...mockBackup, type: "database" },
      });

      expect(wrapper.text()).toContain("数据库备份");
    });

    it("should show files icon for files backup", () => {
      const wrapper = createWrapper({
        backup: { ...mockBackup, type: "files" },
      });

      expect(wrapper.text()).toContain("文件备份");
    });
  });

  describe("File Size Formatting", () => {
    it("should format file size correctly", () => {
      const wrapper = createWrapper({
        backup: { ...mockBackup, size: 1024 * 1024 * 100 }, // 100MB
      });

      expect(wrapper.text()).toContain("100.00 MB");
    });

    it("should handle zero size", () => {
      const wrapper = createWrapper({
        backup: { ...mockBackup, size: 0 },
      });

      expect(wrapper.text()).toContain("-");
    });
  });

  describe("Date Formatting", () => {
    it("should format backup date", () => {
      const wrapper = createWrapper();

      // Should contain formatted date
      expect(wrapper.text()).toMatch(/2024/);
    });

    it("should handle missing date", () => {
      const wrapper = createWrapper({
        backup: { ...mockBackup, createdAt: null },
      });

      expect(wrapper.text()).toContain("-");
    });
  });
});
