import { describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
import {
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElRadioGroup,
  ElRadioButton,
  ElSelect,
  ElOption,
  ElButton,
  ElAlert,
  ElDivider,
} from "element-plus";
import BackupForm from "@/components/BackupForm.vue";

// Mock Element Plus icons
vi.mock("@element-plus/icons-vue", () => ({
  DataLine: { name: "DataLine" },
  Document: { name: "Document" },
  Folder: { name: "Folder" },
}));

describe("BackupForm Component", () => {
  const createWrapper = (props = {}) => {
    return mount(BackupForm, {
      props: {
        visible: true,
        instanceId: 1,
        loading: false,
        ...props,
      },
      global: {
        components: {
          ElDialog,
          ElForm,
          ElFormItem,
          ElInput,
          ElRadioGroup,
          ElRadioButton,
          ElSelect,
          ElOption,
          ElButton,
          ElAlert,
          ElDivider,
        },
      },
    });
  };

  describe("Rendering", () => {
    it("should render dialog when visible is true", () => {
      const wrapper = createWrapper({ visible: true });
      expect(wrapper.findComponent(ElDialog).exists()).toBe(true);
    });

    it("should render backup type selection", () => {
      const wrapper = createWrapper();
      const radioGroup = wrapper.findComponent(ElRadioGroup);
      expect(radioGroup.exists()).toBe(true);
    });

    it("should render name input field", () => {
      const wrapper = createWrapper();
      const inputs = wrapper.findAllComponents(ElInput);
      const nameInput = inputs.find((input) =>
        input.props("placeholder")?.includes("备份名称"),
      );
      expect(nameInput).toBeDefined();
    });

    it("should render description textarea", () => {
      const wrapper = createWrapper();
      const textarea = wrapper.find("textarea");
      expect(textarea.exists()).toBe(true);
    });
  });

  describe("Form Validation", () => {
    it("should require backup type", async () => {
      const wrapper = createWrapper();
      const form = wrapper.findComponent(ElForm);

      // Try to submit without type
      await wrapper.vm.handleSubmit();

      // Form should not emit submit event
      expect(wrapper.emitted("submit")).toBeFalsy();
    });

    it("should require backup name", async () => {
      const wrapper = createWrapper();

      // Set type but no name
      wrapper.vm.formData.type = "database";
      wrapper.vm.formData.name = "";

      await wrapper.vm.handleSubmit();

      expect(wrapper.emitted("submit")).toBeFalsy();
    });

    it("should validate name length", async () => {
      const wrapper = createWrapper();

      wrapper.vm.formData.type = "database";
      wrapper.vm.formData.name = "a"; // Too short

      await wrapper.vm.handleSubmit();

      expect(wrapper.emitted("submit")).toBeFalsy();
    });

    it("should emit submit with valid data", async () => {
      const wrapper = createWrapper();

      wrapper.vm.formData.type = "database";
      wrapper.vm.formData.name = "Test Backup";
      wrapper.vm.formData.description = "Test description";

      // Mock form validation
      wrapper.vm.$refs.formRef = {
        validate: vi.fn().mockResolvedValue(true),
      };

      await wrapper.vm.handleSubmit();

      expect(wrapper.emitted("submit")).toBeTruthy();
      expect(wrapper.emitted("submit")[0]).toEqual([
        {
          type: "database",
          name: "Test Backup",
          description: "Test description",
        },
      ]);
    });
  });

  describe("Backup Type Selection", () => {
    it("should default to database backup type", () => {
      const wrapper = createWrapper();
      expect(wrapper.vm.formData.type).toBe("database");
    });

    it("should show file backup options when type is files", async () => {
      const wrapper = createWrapper();

      wrapper.vm.formData.type = "files";
      await wrapper.vm.$nextTick();

      // Should show advanced options for file backup
      expect(wrapper.vm.isFileBackup).toBe(true);
    });

    it("should generate default name on type change", async () => {
      const wrapper = createWrapper();

      wrapper.vm.formData.name = "";
      wrapper.vm.handleTypeChange();

      expect(wrapper.vm.formData.name).toContain("数据库备份");
    });
  });

  describe("File Backup Options", () => {
    it("should show include/exclude paths when type is files", async () => {
      const wrapper = createWrapper();

      wrapper.vm.formData.type = "files";
      await wrapper.vm.$nextTick();

      const selects = wrapper.findAllComponents(ElSelect);
      expect(selects.length).toBeGreaterThanOrEqual(2);
    });

    it("should include paths in submit data for file backup", async () => {
      const wrapper = createWrapper();

      wrapper.vm.formData.type = "files";
      wrapper.vm.formData.name = "Files Backup";
      wrapper.vm.formData.includePaths = ["config/", "world/"];
      wrapper.vm.formData.excludePaths = ["logs/"];

      // Mock form validation
      wrapper.vm.$refs.formRef = {
        validate: vi.fn().mockResolvedValue(true),
      };

      await wrapper.vm.handleSubmit();

      expect(wrapper.emitted("submit")[0]).toEqual([
        {
          type: "files",
          name: "Files Backup",
          description: "",
          includePaths: ["config/", "world/"],
          excludePaths: ["logs/"],
        },
      ]);
    });
  });

  describe("Buttons", () => {
    it("should disable submit button when loading", () => {
      const wrapper = createWrapper({ loading: true });
      const submitButton = wrapper
        .findAllComponents(ElButton)
        .find((btn) => btn.text().includes("开始备份"));
      expect(submitButton?.props("loading")).toBe(true);
    });

    it("should disable submit button when name is too short", () => {
      const wrapper = createWrapper();
      wrapper.vm.formData.name = "a";
      expect(wrapper.vm.canSubmit).toBe(false);
    });

    it("should emit cancel when cancel button clicked", async () => {
      const wrapper = createWrapper();
      const cancelButton = wrapper
        .findAllComponents(ElButton)
        .find((btn) => btn.text().includes("取消"));
      await cancelButton?.trigger("click");
      expect(wrapper.emitted("cancel")).toBeTruthy();
    });
  });

  describe("Dialog Visibility", () => {
    it("should update visible prop on dialog close", async () => {
      const wrapper = createWrapper({ visible: true });
      const dialog = wrapper.findComponent(ElDialog);

      // Simulate dialog close
      await dialog.vm.$emit("update:modelValue", false);

      expect(wrapper.emitted("update:visible")).toBeTruthy();
    });

    it("should reset form when dialog closes", async () => {
      const wrapper = createWrapper();

      wrapper.vm.formData.name = "Test Name";
      wrapper.vm.formData.description = "Test Description";

      wrapper.vm.handleClose();

      expect(wrapper.vm.formData.name).toBe("");
      expect(wrapper.vm.formData.description).toBe("");
    });
  });

  describe("Default Name Generation", () => {
    it("should generate default name with current date", () => {
      const wrapper = createWrapper();
      const name = wrapper.vm.generateDefaultName();

      expect(name).toMatch(/\d{4}-\d{2}-\d{2}/); // Date pattern
    });

    it("should include backup type in default name", () => {
      const wrapper = createWrapper();
      wrapper.vm.formData.type = "database";

      const name = wrapper.vm.generateDefaultName();
      expect(name).toContain("数据库备份");
    });
  });
});
