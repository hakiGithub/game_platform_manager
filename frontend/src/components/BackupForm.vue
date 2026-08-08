<script setup>
import { ref, reactive, computed } from "vue";
import { ElMessage } from "element-plus";
import { DataLine, Document, Folder } from "@element-plus/icons-vue";

const props = defineProps({
  /**
   * 是否显示对话框
   */
  visible: {
    type: Boolean,
    default: false,
  },
  /**
   * 实例ID
   */
  instanceId: {
    type: [String, Number],
    required: true,
  },
  /**
   * 提交中状态
   */
  loading: {
    type: Boolean,
    default: false,
  },
});

const emit = defineEmits(["update:visible", "submit", "cancel"]);

// 表单引用
const formRef = ref(null);

// 表单数据
const formData = reactive({
  type: "database", // database-数据库, files-文件
  name: "",
  description: "",
  includePaths: [],
  excludePaths: [],
});

// 备份类型选项
const backupTypes = [
  {
    value: "database",
    label: "数据库备份",
    icon: DataLine,
    description: "备份游戏数据库数据，包括玩家数据、配置等",
  },
  {
    value: "files",
    label: "文件备份",
    icon: Document,
    description: "备份游戏文件，包括配置文件、地图、插件等",
  },
];

// 表单验证规则
const formRules = {
  type: [{ required: true, message: "请选择备份类型", trigger: "change" }],
  name: [
    { required: true, message: "请输入备份名称", trigger: "blur" },
    { min: 2, max: 50, message: "长度在 2 到 50 个字符", trigger: "blur" },
    {
      pattern: /^[\u4e00-\u9fa5a-zA-Z0-9_\-\s]+$/,
      message: "只能包含中文、字母、数字、下划线、横线和空格",
      trigger: "blur",
    },
  ],
  description: [
    { max: 200, message: "描述不能超过 200 个字符", trigger: "blur" },
  ],
};

// 计算属性：对话框可见性
const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit("update:visible", val),
});

// 计算属性：是否为文件备份
const isFileBackup = computed(() => formData.type === "files");

// 计算属性：是否可提交
const canSubmit = computed(() => {
  return formData.name.trim().length >= 2 && !props.loading;
});

/**
 * 生成默认备份名称
 */
function generateDefaultName() {
  const now = new Date();
  const dateStr = now.toISOString().slice(0, 10);
  const timeStr = now.toTimeString().slice(0, 5).replace(":", "-");
  const typeLabel =
    backupTypes.find((t) => t.value === formData.type)?.label || "备份";
  return `${typeLabel}_${dateStr}_${timeStr}`;
}

/**
 * 处理类型变化
 */
function handleTypeChange() {
  // 如果名称为空或使用的是默认命名格式，则重新生成名称
  if (
    !formData.name ||
    /备份_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}$/.test(formData.name)
  ) {
    formData.name = generateDefaultName();
  }
}

/**
 * 处理提交
 */
async function handleSubmit() {
  if (!formRef.value) return;

  try {
    await formRef.value.validate();

    const submitData = {
      type: formData.type,
      name: formData.name.trim(),
      description: formData.description.trim(),
    };

    // 文件备份时添加路径配置
    if (formData.type === "files") {
      if (formData.includePaths.length > 0) {
        submitData.includePaths = formData.includePaths;
      }
      if (formData.excludePaths.length > 0) {
        submitData.excludePaths = formData.excludePaths;
      }
    }

    emit("submit", submitData);
  } catch (error) {
    console.error("Form validation failed:", error);
    ElMessage.warning("请检查表单填写是否正确");
  }
}

/**
 * 处理取消
 */
function handleCancel() {
  emit("cancel");
  resetForm();
}

/**
 * 处理对话框关闭
 */
function handleClose() {
  resetForm();
}

/**
 * 重置表单
 */
function resetForm() {
  if (formRef.value) {
    formRef.value.resetFields();
  }
  formData.type = "database";
  formData.name = "";
  formData.description = "";
  formData.includePaths = [];
  formData.excludePaths = [];
}

/**
 * 打开对话框时的初始化
 */
function handleOpen() {
  formData.name = generateDefaultName();
}

// 暴露方法给父组件
defineExpose({
  resetForm,
  generateDefaultName,
});
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    title="创建备份"
    width="600px"
    :close-on-click-modal="false"
    @open="handleOpen"
    @close="handleClose"
  >
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="100px"
      class="backup-form"
    >
      <!-- 备份类型选择 -->
      <el-form-item label="备份类型" prop="type">
        <el-radio-group v-model="formData.type" @change="handleTypeChange">
          <el-radio-button
            v-for="type in backupTypes"
            :key="type.value"
            :value="type.value"
          >
            <el-icon class="type-icon">
              <component :is="type.icon" />
            </el-icon>
            {{ type.label }}
          </el-radio-button>
        </el-radio-group>

        <div class="type-description">
          <el-alert
            :title="backupTypes.find((t) => t.value === formData.type)?.label"
            :description="
              backupTypes.find((t) => t.value === formData.type)?.description
            "
            type="info"
            :closable="false"
            show-icon
          />
        </div>
      </el-form-item>

      <!-- 备份名称 -->
      <el-form-item label="备份名称" prop="name">
        <el-input
          v-model="formData.name"
          placeholder="请输入备份名称"
          maxlength="50"
          show-word-limit
          clearable
        >
          <template #prefix>
            <el-icon><Document /></el-icon>
          </template>
        </el-input>
        <div class="form-tip">
          建议使用有意义的名称，如：更新前备份_2024-01-15
        </div>
      </el-form-item>

      <!-- 备份描述 -->
      <el-form-item label="备份描述" prop="description">
        <el-input
          v-model="formData.description"
          type="textarea"
          :rows="3"
          placeholder="请输入备份描述（可选）"
          maxlength="200"
          show-word-limit
          resize="none"
        />
      </el-form-item>

      <!-- 文件备份特有配置 -->
      <template v-if="isFileBackup">
        <el-divider content-position="left">高级选项</el-divider>

        <el-form-item label="包含路径">
          <el-select
            v-model="formData.includePaths"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入要包含的文件路径"
            class="path-select"
          >
            <el-option label="配置文件 (config/)" value="config/" />
            <el-option label="世界数据 (world/)" value="world/" />
            <el-option label="插件 (plugins/)" value="plugins/" />
          </el-select>
          <div class="form-tip">留空表示备份所有文件，可输入自定义路径</div>
        </el-form-item>

        <el-form-item label="排除路径">
          <el-select
            v-model="formData.excludePaths"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入要排除的文件路径"
            class="path-select"
          >
            <el-option label="日志文件 (logs/)" value="logs/" />
            <el-option label="缓存文件 (cache/)" value="cache/" />
            <el-option label="临时文件 (tmp/)" value="tmp/" />
          </el-select>
          <div class="form-tip">这些路径的文件将不会被备份</div>
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button
          type="primary"
          :loading="loading"
          :disabled="!canSubmit"
          @click="handleSubmit"
        >
          <el-icon><Folder /></el-icon>
          开始备份
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.backup-form {
  .type-icon {
    margin-right: 4px;
    vertical-align: middle;
  }

  .type-description {
    margin-top: 12px;

    :deep(.el-alert) {
      padding: 12px 16px;
    }
  }

  .form-tip {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.4;
  }

  .path-select {
    width: 100%;
  }

  :deep(.el-radio-button__inner) {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 12px 20px;
  }
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

// 响应式适配
@media screen and (max-width: 768px) {
  .backup-form {
    :deep(.el-form-item__label) {
      float: none;
      display: block;
      text-align: left;
      margin-bottom: 8px;
    }

    :deep(.el-form-item__content) {
      margin-left: 0 !important;
    }

    :deep(.el-radio-group) {
      display: flex;
      flex-direction: column;
      width: 100%;

      .el-radio-button {
        width: 100%;

        &__inner {
          border-radius: 0;
          border-left: 1px solid var(--el-border-color);
          width: 100%;
          justify-content: center;
        }

        &:first-child &__inner {
          border-radius: var(--el-border-radius-base)
            var(--el-border-radius-base) 0 0;
        }

        &:last-child &__inner {
          border-radius: 0 0 var(--el-border-radius-base)
            var(--el-border-radius-base);
        }
      }
    }
  }
}
</style>
