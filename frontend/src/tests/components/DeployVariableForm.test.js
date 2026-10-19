import { describe, it, expect, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { InfoFilled } from "@element-plus/icons-vue";
import { defineComponent, h, nextTick, reactive, ref } from "vue";
import DeployVariableForm from "@/components/DeployVariableForm.vue";

vi.mock("@element-plus/icons-vue", async (importOriginal) => {
  return { ...(await importOriginal()) };
});

const variables = [
  {
    name: "MYSQL_PORT",
    label: "MySQL 端口",
    type: "integer",
    defaultValue: "3000",
    required: false,
  },
  {
    name: "DNF_DB_ROOT_PASSWORD",
    label: "数据库 root 密码",
    type: "password",
    defaultValue: "88888888",
    required: true,
  },
  {
    name: "PUBLIC_IP",
    label: "对外 IP",
    type: "string",
    defaultValue: "127.0.0.1",
    required: true,
  },
  {
    name: "GATE_AES_KEY",
    label: "网关 AES Key",
    type: "string",
    defaultValue: "hidden-default",
    required: false,
    hidden: true,
  },
];

function mountForm() {
  return mount(DeployVariableForm, {
    props: {
      variables,
      modelValue: {
        MYSQL_PORT: "3000",
        DNF_DB_ROOT_PASSWORD: "88888888",
        PUBLIC_IP: "127.0.0.1",
        GATE_AES_KEY: "hidden-default",
      },
    },
    global: {
      plugins: [ElementPlus],
      components: { InfoFilled },
    },
  });
}

function latestValues(wrapper) {
  const emitted = wrapper.emitted("update:modelValue") || [];
  return emitted.length ? emitted.at(-1)[0] : wrapper.props("modelValue");
}

describe("DeployVariableForm", () => {
  it("re-emits the latest value after the parent loads variable definitions asynchronously", async () => {
    const Parent = defineComponent({
      setup() {
        const loadedVariables = ref([]);
        const values = reactive({});
        const submitted = ref({});
        const load = () => {
          loadedVariables.value = variables;
        };
        const onUpdate = (nextValues) => {
          Object.keys(values).forEach((key) => delete values[key]);
          Object.assign(values, nextValues);
          submitted.value = { ...nextValues };
        };
        return { loadedVariables, values, submitted, load, onUpdate };
      },
      render() {
        return h("div", [
          h(DeployVariableForm, {
            ref: "form",
            variables: this.loadedVariables,
            modelValue: this.values,
            "onUpdate:modelValue": this.onUpdate,
          }),
          h("pre", { class: "submitted" }, JSON.stringify(this.values)),
        ]);
      },
    });

    const wrapper = mount(Parent, {
      global: {
        plugins: [ElementPlus],
        components: { InfoFilled },
      },
    });
    wrapper.vm.load();
    await nextTick();
    await flushPromises();

    const inputs = wrapper.findAll("input");
    expect(inputs).toHaveLength(3);
    await inputs[1].setValue("custom-root-password");
    await inputs[2].setValue("192.168.111.253");
    await nextTick();

    const submitted = JSON.parse(wrapper.find(".submitted").text());
    expect(submitted.MYSQL_PORT).toBe(3000);
    expect(submitted.PUBLIC_IP).toBe("192.168.111.253");
    expect(submitted.DNF_DB_ROOT_PASSWORD).toBe("custom-root-password");
    expect(submitted.GATE_AES_KEY).toBe("hidden-default");
  });

  it("keeps an explicitly edited port in the latest snapshot", async () => {
    const wrapper = mountForm();
    await flushPromises();

    await wrapper.find(".el-input-number input").setValue("3010");
    await nextTick();

    expect(latestValues(wrapper)).toMatchObject({
      MYSQL_PORT: 3010,
      PUBLIC_IP: "127.0.0.1",
      DNF_DB_ROOT_PASSWORD: "88888888",
      GATE_AES_KEY: "hidden-default",
    });
  });

  it("emits user changes but does not emit a parent snapshot during initialization", async () => {
    const wrapper = mountForm();
    await flushPromises();

    await wrapper.findAll("input")[2].setValue("192.168.111.253");
    await nextTick();

    expect(latestValues(wrapper)).toMatchObject({
      MYSQL_PORT: 3000,
      DNF_DB_ROOT_PASSWORD: "88888888",
      PUBLIC_IP: "192.168.111.253",
      GATE_AES_KEY: "hidden-default",
    });
    expect(wrapper.find(".el-input-number input").element.value).toBe("3000");
  });

  it("validates required values from the latest form state", async () => {
    const wrapper = mountForm();
    await flushPromises();

    await wrapper.findAll("input")[2].setValue("");
    expect(wrapper.vm.validate()).toEqual({
      valid: false,
      message: "以下变量为必填: 对外 IP",
    });
  });
});
