<script setup>
import { computed, watch, reactive } from "vue";

/**
 * 部署变量表单组件
 * 根据变量元信息（来自 GET /games/{id}/deploy-config/{deployType}）动态渲染表单
 * 用于 docker-compose 类型部署，将变量值收集后写入实例 configInfo.variables
 */
const props = defineProps({
  // 变量定义列表：[{ name, label, type, defaultValue, required, description, hidden }]
  variables: {
    type: Array,
    default: () => [],
  },
  // 表单数据（双向绑定）：{ VAR_NAME: value }
  modelValue: {
    type: Object,
    default: () => ({}),
  },
  // 是否禁用表单（查看模式）
  disabled: {
    type: Boolean,
    default: false,
  },
});

const emit = defineEmits(["update:modelValue"]);

// 可见变量（hidden !== true）
const visibleVariables = computed(() => {
  return (props.variables || []).filter((v) => !v.hidden);
});

// 本地表单数据（响应式）
const formData = reactive({ ...props.modelValue });

// 当 modelValue 外部变化时同步到本地
watch(
  () => props.modelValue,
  (val) => {
    Object.keys(formData).forEach((k) => {
      if (!(k in (val || {}))) delete formData[k];
    });
    Object.assign(formData, val || {});
  },
  { deep: true },
);

// 当变量定义变化时，补齐默认值
watch(
  () => props.variables,
  (vars) => {
    (vars || []).forEach((v) => {
      if (!(v.name in formData)) {
        formData[v.name] = v.defaultValue ?? defaultByType(v.type);
      }
    });
    emitUpdate();
  },
  { immediate: true },
);

// 根据类型返回默认值
function defaultByType(type) {
  switch (type) {
    case "integer":
      return 0;
    case "boolean":
      return false;
    default:
      return "";
  }
}

// 表单变化时向外 emit
function emitUpdate() {
  emit("update:modelValue", { ...formData });
}

// 输入框占位符
function placeholder(v) {
  if (v.defaultValue !== undefined && v.defaultValue !== "") {
    return `默认: ${v.defaultValue}`;
  }
  return `请输入${v.label || v.name}`;
}

// 暴露校验方法供父组件调用
function validate() {
  const missing = [];
  for (const v of props.variables || []) {
    if (v.required && v.hidden) continue; // hidden 变量跳过校验（由后端兜底）
    if (v.required) {
      const val = formData[v.name];
      if (val === undefined || val === null || val === "") {
        missing.push(v.label || v.name);
      }
    }
  }
  if (missing.length > 0) {
    return { valid: false, message: `以下变量为必填: ${missing.join(", ")}` };
  }
  return { valid: true };
}

defineExpose({ validate });
</script>

<template>
  <div class="deploy-variable-form">
    <el-form
      v-if="visibleVariables.length > 0"
      label-width="140px"
      label-position="right"
      :disabled="disabled"
    >
      <el-form-item
        v-for="v in visibleVariables"
        :key="v.name"
        :label="v.label || v.name"
        :required="v.required"
      >
        <!-- 字符串 -->
        <el-input
          v-if="!v.type || v.type === 'string'"
          v-model="formData[v.name]"
          :placeholder="placeholder(v)"
          @input="emitUpdate"
        />

        <!-- 密码 -->
        <el-input
          v-else-if="v.type === 'password'"
          v-model="formData[v.name]"
          type="password"
          show-password
          :placeholder="placeholder(v)"
          @input="emitUpdate"
        />

        <!-- 整数 -->
        <el-input-number
          v-else-if="v.type === 'integer'"
          v-model="formData[v.name]"
          :min="0"
          controls-position="right"
          @change="emitUpdate"
        />

        <!-- 布尔 -->
        <el-switch
          v-else-if="v.type === 'boolean'"
          v-model="formData[v.name]"
          @change="emitUpdate"
        />

        <!-- 描述提示 -->
        <div v-if="v.description" class="var-desc">
          <el-icon><InfoFilled /></el-icon>
          <span>{{ v.description }}</span>
        </div>
      </el-form-item>
    </el-form>

    <el-empty
      v-else
      description="该部署类型无需配置变量"
      :image-size="60"
    />
  </div>
</template>

<style lang="scss" scoped>
.deploy-variable-form {
  .var-desc {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-top: 4px;
    font-size: var(--platform-font-size-xs, 12px);
    color: var(--el-text-color-secondary);

    .el-icon {
      flex-shrink: 0;
    }
  }
}
</style>
