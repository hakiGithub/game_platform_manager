<script setup>
import { computed } from "vue";
import {
  WarningFilled,
  Calendar,
  Document,
  DataLine,
  Folder,
  InfoFilled,
  Timer,
  CircleCheck,
  CircleClose,
} from "@element-plus/icons-vue";

const props = defineProps({
  /**
   * 是否显示对话框
   */
  visible: {
    type: Boolean,
    default: false,
  },
  /**
   * 备份信息
   */
  backup: {
    type: Object,
    default: () => ({}),
  },
  /**
   * 实例信息
   */
  instance: {
    type: Object,
    default: () => ({}),
  },
  /**
   * 还原中状态
   */
  loading: {
    type: Boolean,
    default: false,
  },
  /**
   * 还原进度
   */
  restoreProgress: {
    type: Object,
    default: () => ({
      progress: 0,
      status: "",
      message: "",
      currentStep: "",
      completed: false,
    }),
  },
});

const emit = defineEmits(["update:visible", "confirm", "cancel"]);

// 计算属性：对话框可见性
const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit("update:visible", val),
});

// 计算属性：是否正在还原
const isRestoring = computed(
  () => props.loading || props.restoreProgress.status === "running",
);

// 计算属性：是否已完成还原
const isCompleted = computed(() => props.restoreProgress.completed);

// 计算属性：还原是否成功
const isSuccess = computed(() => {
  return (
    props.restoreProgress.completed && props.restoreProgress.status !== "failed"
  );
});

// 计算属性：备份类型图标
const backupTypeIcon = computed(() => {
  switch (props.backup.type) {
    case "database":
      return DataLine;
    case "files":
      return Folder;
    default:
      return Document;
  }
});

// 计算属性：备份类型文本
const backupTypeText = computed(() => {
  const typeMap = {
    database: "数据库备份",
    files: "文件备份",
    full: "完整备份",
  };
  return typeMap[props.backup.type] || props.backup.type;
});

// 计算属性：备份状态类型
const backupStatusType = computed(() => {
  const typeMap = {
    pending: "info",
    running: "warning",
    completed: "success",
    failed: "danger",
  };
  return typeMap[props.backup.status] || "info";
});

// 计算属性：备份状态文本
const backupStatusText = computed(() => {
  const statusMap = {
    pending: "等待中",
    running: "备份中",
    completed: "成功",
    failed: "失败",
  };
  return statusMap[props.backup.status] || props.backup.status;
});

// 格式化文件大小
function formatFileSize(bytes) {
  if (!bytes || bytes === 0) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`;
}

// 格式化日期
function formatDate(dateStr) {
  if (!dateStr) return "-";
  const date = new Date(dateStr);
  return date.toLocaleString("zh-CN");
}

// 处理确认
function handleConfirm() {
  emit("confirm", props.backup);
}

// 处理取消
function handleCancel() {
  emit("cancel");
}

// 处理关闭
function handleClose() {
  if (!isRestoring.value) {
    emit("cancel");
  }
}
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    title="还原备份"
    width="650px"
    :close-on-click-modal="!isRestoring"
    :close-on-press-escape="!isRestoring"
    :show-close="!isRestoring"
    @close="handleClose"
  >
    <div class="restore-confirm">
      <!-- 危险警告 -->
      <el-alert
        v-if="!isCompleted"
        title="危险操作警告"
        type="error"
        :closable="false"
        show-icon
        class="danger-alert"
      >
        <template #default>
          <div class="danger-content">
            <p>
              还原操作将<strong>覆盖</strong>当前实例的所有数据，此操作不可撤销！
            </p>
            <p>建议在还原前创建当前状态的备份。</p>
          </div>
        </template>
      </el-alert>

      <!-- 还原进度 -->
      <div v-if="isRestoring || isCompleted" class="restore-progress-section">
        <h4>还原进度</h4>
        <div class="progress-wrapper">
          <el-progress
            :percentage="Math.min(Math.max(restoreProgress.progress, 0), 100)"
            :status="
              restoreProgress.status === 'failed'
                ? 'exception'
                : restoreProgress.completed
                  ? 'success'
                  : ''
            "
            :stroke-width="16"
            class="progress-bar"
          />
          <el-tag
            :type="
              restoreProgress.status === 'failed'
                ? 'danger'
                : restoreProgress.completed
                  ? 'success'
                  : 'primary'
            "
            effect="dark"
            size="small"
            class="status-tag"
          >
            <el-icon v-if="!restoreProgress.completed" class="is-loading"
              ><Loading
            /></el-icon>
            <el-icon v-else-if="restoreProgress.status === 'failed'"
              ><CircleClose
            /></el-icon>
            <el-icon v-else><CircleCheck /></el-icon>
            {{
              restoreProgress.status === "running"
                ? "还原中"
                : restoreProgress.status === "failed"
                  ? "还原失败"
                  : "还原完成"
            }}
          </el-tag>
        </div>
        <p v-if="restoreProgress.message" class="progress-message">
          {{ restoreProgress.message }}
        </p>
        <p v-if="restoreProgress.currentStep" class="progress-step">
          <el-icon><InfoFilled /></el-icon>
          当前步骤: {{ restoreProgress.currentStep }}
        </p>
      </div>

      <!-- 备份信息 -->
      <div v-if="!isCompleted" class="backup-info-section">
        <h4>备份信息</h4>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="备份名称" :span="2">
            <el-icon><Document /></el-icon>
            {{ backup.name }}
          </el-descriptions-item>

          <el-descriptions-item label="备份类型">
            <el-icon :size="16">
              <component :is="backupTypeIcon" />
            </el-icon>
            {{ backupTypeText }}
          </el-descriptions-item>

          <el-descriptions-item label="备份状态">
            <el-tag :type="backupStatusType" size="small">
              {{ backupStatusText }}
            </el-tag>
          </el-descriptions-item>

          <el-descriptions-item label="备份大小">
            {{ formatFileSize(backup.size) }}
          </el-descriptions-item>

          <el-descriptions-item label="备份时间">
            <el-icon><Calendar /></el-icon>
            {{ formatDate(backup.createdAt) }}
          </el-descriptions-item>

          <el-descriptions-item
            v-if="backup.description"
            label="备份描述"
            :span="2"
          >
            {{ backup.description }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 实例信息 -->
      <div v-if="!isCompleted" class="instance-info-section">
        <h4>目标实例</h4>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="实例名称">
            {{ instance.name || "-" }}
          </el-descriptions-item>

          <el-descriptions-item label="实例状态">
            <el-tag
              :type="instance.status === 'running' ? 'success' : 'info'"
              size="small"
            >
              {{ instance.status === "running" ? "运行中" : "已停止" }}
            </el-tag>
          </el-descriptions-item>

          <el-descriptions-item label="游戏类型">
            {{ instance.game || "-" }}
          </el-descriptions-item>

          <el-descriptions-item label="主机">
            {{ instance.hostName || "-" }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 还原成功提示 -->
      <el-alert
        v-if="isSuccess"
        title="还原成功"
        type="success"
        :closable="false"
        show-icon
        class="success-alert"
      >
        <p>备份已成功还原到实例。建议立即检查游戏运行状态。</p>
      </el-alert>

      <!-- 还原失败提示 -->
      <el-alert
        v-if="isCompleted && !isSuccess"
        title="还原失败"
        type="error"
        :closable="false"
        show-icon
        class="error-alert"
      >
        <p>
          {{
            restoreProgress.message ||
            "还原过程中发生错误，请查看日志获取详细信息。"
          }}
        </p>
      </el-alert>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="!isRestoring && !isCompleted">
          <el-button @click="handleCancel">取消</el-button>
          <el-button type="danger" :loading="loading" @click="handleConfirm">
            <el-icon><WarningFilled /></el-icon>
            确认还原
          </el-button>
        </template>
        <template v-else-if="isCompleted">
          <el-button type="primary" @click="handleCancel">
            <el-icon><CircleCheck /></el-icon>
            完成
          </el-button>
        </template>
        <template v-else>
          <el-button disabled>
            <el-icon class="is-loading"><Loading /></el-icon>
            还原中...
          </el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.restore-confirm {
  .danger-alert {
    margin-bottom: 20px;

    .danger-content {
      p {
        margin: 4px 0;
        line-height: 1.5;

        strong {
          color: var(--el-color-danger);
        }
      }
    }
  }

  .success-alert,
  .error-alert {
    margin-top: 20px;
  }

  h4 {
    margin: 0 0 12px 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--el-text-color-primary);
    display: flex;
    align-items: center;
    gap: 6px;

    &::before {
      content: "";
      display: inline-block;
      width: 4px;
      height: 16px;
      background-color: var(--el-color-primary);
      border-radius: 2px;
    }
  }

  .restore-progress-section {
    margin-bottom: 20px;
    padding: 16px;
    background-color: var(--el-fill-color-light);
    border-radius: var(--el-border-radius-base);

    .progress-wrapper {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;

      .progress-bar {
        flex: 1;
      }

      .status-tag {
        flex-shrink: 0;

        .el-icon {
          margin-right: 4px;

          &.is-loading {
            animation: rotating 2s linear infinite;
          }
        }
      }
    }

    .progress-message {
      margin: 8px 0;
      font-size: 13px;
      color: var(--el-text-color-regular);
    }

    .progress-step {
      margin: 8px 0 0 0;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      display: flex;
      align-items: center;
      gap: 4px;

      .el-icon {
        font-size: 14px;
      }
    }
  }

  .backup-info-section,
  .instance-info-section {
    margin-bottom: 20px;

    &:last-child {
      margin-bottom: 0;
    }
  }

  :deep(.el-descriptions) {
    .el-descriptions__label {
      width: 100px;
      font-weight: 500;
    }

    .el-icon {
      margin-right: 4px;
      vertical-align: middle;
    }
  }
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

@keyframes rotating {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .restore-confirm {
    .restore-progress-section {
      .progress-wrapper {
        flex-wrap: wrap;

        .progress-bar {
          width: 100%;
          flex: none;
        }
      }
    }

    :deep(.el-descriptions) {
      .el-descriptions__label {
        width: 80px;
      }
    }
  }
}
</style>
