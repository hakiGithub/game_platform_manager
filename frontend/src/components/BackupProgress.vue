<script setup>
import { computed } from "vue";
import {
  CircleCheck,
  CircleClose,
  Loading,
  Warning,
} from "@element-plus/icons-vue";

const props = defineProps({
  /**
   * 进度百分比 (0-100)
   */
  progress: {
    type: Number,
    default: 0,
  },
  /**
   * 状态: running-进行中, completed-完成, failed-失败, cancelled-已取消
   */
  status: {
    type: String,
    default: "running",
  },
  /**
   * 状态消息
   */
  message: {
    type: String,
    default: "",
  },
  /**
   * 当前步骤
   */
  currentStep: {
    type: String,
    default: "",
  },
  /**
   * 是否已完成
   */
  completed: {
    type: Boolean,
    default: false,
  },
  /**
   * 是否显示取消按钮
   */
  showCancel: {
    type: Boolean,
    default: true,
  },
  /**
   * 进度条高度
   */
  strokeWidth: {
    type: Number,
    default: 12,
  },
  /**
   * 是否显示文字
   */
  showText: {
    type: Boolean,
    default: true,
  },
});

const emit = defineEmits(["cancel"]);

// 计算进度条状态
const progressStatus = computed(() => {
  if (props.status === "failed") return "exception";
  if (props.status === "completed") return "success";
  return "";
});

// 计算进度条颜色
const progressColor = computed(() => {
  if (props.status === "failed") return "#f56c6c";
  if (props.status === "completed") return "#67c23a";
  if (props.status === "cancelled") return "#909399";
  return "#409eff";
});

// 状态图标
const statusIcon = computed(() => {
  switch (props.status) {
    case "completed":
      return CircleCheck;
    case "failed":
      return CircleClose;
    case "cancelled":
      return Warning;
    case "running":
    default:
      return Loading;
  }
});

// 状态文本
const statusText = computed(() => {
  const statusMap = {
    running: "进行中",
    completed: "已完成",
    failed: "失败",
    cancelled: "已取消",
    pending: "等待中",
  };
  return statusMap[props.status] || props.status;
});

// 状态标签类型
const statusType = computed(() => {
  const typeMap = {
    running: "primary",
    completed: "success",
    failed: "danger",
    cancelled: "info",
    pending: "info",
  };
  return typeMap[props.status] || "info";
});

// 是否显示取消按钮
const canCancel = computed(() => {
  return props.showCancel && props.status === "running" && !props.completed;
});

// 处理取消
function handleCancel() {
  emit("cancel");
}
</script>

<template>
  <div class="backup-progress">
    <!-- 进度条 -->
    <div class="progress-section">
      <el-progress
        :percentage="Math.min(Math.max(progress, 0), 100)"
        :status="progressStatus"
        :color="progressColor"
        :stroke-width="strokeWidth"
        :show-text="showText"
        class="progress-bar"
      >
        <template #default="{ percentage }">
          <span class="progress-text">{{ percentage }}%</span>
        </template>
      </el-progress>

      <!-- 状态标签 -->
      <el-tag :type="statusType" effect="dark" size="small" class="status-tag">
        <el-icon
          class="status-icon"
          :class="{ 'is-loading': status === 'running' }"
        >
          <component :is="statusIcon" />
        </el-icon>
        {{ statusText }}
      </el-tag>

      <!-- 取消按钮 -->
      <el-button
        v-if="canCancel"
        type="danger"
        link
        size="small"
        @click="handleCancel"
      >
        <el-icon><CircleClose /></el-icon>
        取消
      </el-button>
    </div>

    <!-- 详细信息 -->
    <div v-if="message || currentStep" class="progress-info">
      <p v-if="message" class="info-message">{{ message }}</p>
      <p v-if="currentStep" class="info-step">
        <el-icon><InfoFilled /></el-icon>
        当前步骤: {{ currentStep }}
      </p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.backup-progress {
  padding: 16px;
  background-color: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);

  .progress-section {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;

    .progress-bar {
      flex: 1;

      :deep(.el-progress__text) {
        min-width: 40px;
        text-align: right;
      }
    }

    .progress-text {
      font-size: 14px;
      font-weight: 500;
      color: var(--el-text-color-primary);
    }

    .status-tag {
      display: flex;
      align-items: center;
      gap: 4px;
      flex-shrink: 0;

      .status-icon {
        font-size: 14px;

        &.is-loading {
          animation: rotating 2s linear infinite;
        }
      }
    }
  }

  .progress-info {
    padding-top: 12px;
    border-top: 1px solid var(--el-border-color-lighter);

    .info-message {
      margin: 0 0 8px 0;
      font-size: 13px;
      color: var(--el-text-color-regular);
      line-height: 1.5;
    }

    .info-step {
      margin: 0;
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
  .backup-progress {
    .progress-section {
      flex-wrap: wrap;

      .progress-bar {
        width: 100%;
        flex: none;
      }
    }
  }
}
</style>
