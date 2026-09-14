import { describe, it, expect, vi, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { nextTick } from "vue";
import { ElDialog, ElButton, ElRadioGroup, ElSelect } from "element-plus";
import BackupForm from "@/components/BackupForm.vue";

// Mock Element Plus icons
vi.mock("@element-plus/icons-vue", async (importOriginal) => {
  // 全量透传真实图标：element-plus 内部会按名解析任意图标（如 Close）
  return { ...(await importOriginal()) };
});

describe("BackupForm Component", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  // dialog 内容经 teleport 渲染：挂到 body + ElementPlus 全量插件 + 等待打开后的内容就绪
  const createWrapper = async (props = {}) => {
    const wrapper = mount(BackupForm, {
      props: {
        visible: true,
        instanceId: 1,
        loading: false,
        ...props,
      },
      global: {
        plugins: [ElementPlus],
      },
      attachTo: document.body,
    });
    await flushPromises();
    await new Promise((resolve) => setTimeout(resolve, 50));
    return wrapper;
  };

  const nameInput = (wrapper) =>
    wrapper.findAll("input").find((i) => i.attributes("placeholder")?.includes("备份名称"));

  const submitButton = (wrapper) =>
    wrapper
      .findAllComponents(ElButton)
      .find((btn) => btn.text().includes("开始备份"));

  describe("Rendering", () => {
    it("should render dialog when visible is true", async () => {
      const wrapper = await createWrapper({ visible: true });
      expect(wrapper.findComponent(ElDialog).exists()).toBe(true);
    });

    it("should render backup type selection", async () => {
      const wrapper = await createWrapper();
      expect(wrapper.findComponent(ElRadioGroup).exists()).toBe(true);
    });

    it("should render name input field", async () => {
      const wrapper = await createWrapper();
      expect(nameInput(wrapper)).toBeDefined();
    });

    it("should render description textarea", async () => {
      const wrapper = await createWrapper();
      expect(wrapper.find("textarea").exists()).toBe(true);
    });
  });

  describe("Form Validation", () => {
    it("should not emit submit when name is empty", async () => {
      const wrapper = await createWrapper();
      await nameInput(wrapper).setValue("");
      await submitButton(wrapper).trigger("click");
      await flushPromises();

      expect(wrapper.emitted("submit")).toBeFalsy();
    });

    it("should not emit submit when name is too short", async () => {
      const wrapper = await createWrapper();
      await nameInput(wrapper).setValue("a");
      await submitButton(wrapper).trigger("click");
      await flushPromises();

      expect(wrapper.emitted("submit")).toBeFalsy();
    });

    it("should emit submit with valid data", async () => {
      const wrapper = await createWrapper();
      await nameInput(wrapper).setValue("Test Backup");
      await wrapper.find("textarea").setValue("Test description");
      await submitButton(wrapper).trigger("click");
      await flushPromises();

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
    it("should default to database backup type", async () => {
      const wrapper = await createWrapper();
      const firstRadio = wrapper.find('input[type="radio"]');
      expect(firstRadio.element.checked).toBe(true);
    });

    it("should show file backup options when type is files", async () => {
      const wrapper = await createWrapper();
      const radios = wrapper.findAll('input[type="radio"]');
      await radios[1].setValue(true);
      await nextTick();

      // 文件备份显示包含/排除两个路径选择器
      const selects = wrapper.findAllComponents(ElSelect);
      expect(selects.length).toBeGreaterThanOrEqual(2);
    });

    it("should generate default name on type change", async () => {
      const wrapper = await createWrapper();
      await nameInput(wrapper).setValue("");
      const radios = wrapper.findAll('input[type="radio"]');
      await radios[1].setValue(true);
      await nextTick();

      expect(nameInput(wrapper).element.value).toContain("文件备份");
    });
  });

  describe("File Backup Options", () => {
    it("should submit with files type when file backup selected", async () => {
      const wrapper = await createWrapper();
      const radios = wrapper.findAll('input[type="radio"]');
      await radios[1].setValue(true);
      await nameInput(wrapper).setValue("Files Backup");
      await submitButton(wrapper).trigger("click");
      await flushPromises();

      expect(wrapper.emitted("submit")).toBeTruthy();
      expect(wrapper.emitted("submit")[0][0].type).toBe("files");
      // 路径为空时不携带 includePaths/excludePaths（由组件契约决定）
      expect(wrapper.emitted("submit")[0][0].includePaths).toBeUndefined();
      expect(wrapper.emitted("submit")[0][0].excludePaths).toBeUndefined();
    });
  });

  describe("Buttons", () => {
    it("should show loading on submit button when loading", async () => {
      const wrapper = await createWrapper({ loading: true });
      expect(submitButton(wrapper)?.props("loading")).toBe(true);
    });

    it("should disable submit button when name is too short", async () => {
      const wrapper = await createWrapper();
      await nameInput(wrapper).setValue("a");
      await nextTick();
      expect(submitButton(wrapper)?.props("disabled")).toBe(true);
    });

    it("should emit cancel when cancel button clicked", async () => {
      const wrapper = await createWrapper();
      const cancelButton = wrapper
        .findAllComponents(ElButton)
        .find((btn) => btn.text().includes("取消"));
      await cancelButton?.trigger("click");
      expect(wrapper.emitted("cancel")).toBeTruthy();
    });
  });

  describe("Dialog Visibility", () => {
    it("should update visible prop on dialog close", async () => {
      const wrapper = await createWrapper({ visible: true });
      const dialog = wrapper.findComponent(ElDialog);

      await dialog.vm.$emit("update:modelValue", false);

      expect(wrapper.emitted("update:visible")).toBeTruthy();
    });

    it("should reset form via exposed resetForm", async () => {
      const wrapper = await createWrapper();
      await nameInput(wrapper).setValue("Test Name");
      await wrapper.find("textarea").setValue("Test Description");

      wrapper.vm.resetForm();
      await nextTick();

      expect(nameInput(wrapper).element.value).toBe("");
    });
  });

  describe("Default Name Generation", () => {
    it("should generate default name with current date", async () => {
      const wrapper = await createWrapper();
      const name = wrapper.vm.generateDefaultName();

      expect(name).toMatch(/\d{4}-\d{2}-\d{2}/); // Date pattern
    });

    it("should include backup type in default name", async () => {
      const wrapper = await createWrapper();

      const name = wrapper.vm.generateDefaultName();
      expect(name).toContain("数据库备份");
    });
  });
});
