<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getTaskList,
  getTaskTypes,
  cancelTask,
  retryTask,
  deleteTask,
} from "@/api/task";

const router = useRouter();

// ==================== 状态 ====================

const loading = ref(false);
const taskList = ref([]);
const total = ref(0);
const taskTypes = ref([]);

// 查询参数
const query = reactive({
  source: "",
  taskType: "",
  status: "",
  keyword: "",
  page: 1,
  size: 20,
});

// 操作加载状态
const opLoading = ref({});

// 自动刷新定时器（仅当有未完成任务时启动）
let refreshTimer = null;

// ==================== 状态选项 ====================

const statusOptions = [
  { label: "全部", value: "" },
  { label: "等待中", value: "PENDING" },
  { label: "运行中", value: "RUNNING" },
  { label: "已完成", value: "COMPLETED" },
  { label: "失败", value: "FAILED" },
  { label: "已取消", value: "CANCELLED" },
];

// 来源选项（从任务类型列表动态构建）
const sourceOptions = computed(() => {
  const sources = new Set(taskTypes.value.map((t) => t.source));
  return [{ label: "全部", value: "" }, ...[...sources].map((s) => ({ label: s, value: s }))];
});

// 类型选项（根据来源过滤）
const typeOptions = computed(() => {
  const filtered = query.source
    ? taskTypes.value.filter((t) => t.source === query.source)
    : taskTypes.value;
  return [{ label: "全部", value: "" }, ...filtered.map((t) => ({ label: t.displayName, value: t.taskType }))];
});

// ==================== 数据加载 ====================

async function fetchTaskList() {
  loading.value = true;
  try {
    const params = { ...query };
    // 清空空字符串参数
    Object.keys(params).forEach((k) => {
      if (params[k] === "" || params[k] === null) delete params[k];
    });
    const data = await getTaskList(params);
    taskList.value = data?.records || [];
    total.value = data?.total || 0;
    // 根据是否有未完成任务决定是否启动自动刷新
    updateAutoRefresh();
  } catch (error) {
    ElMessage.error("获取任务列表失败：" + (error.message || ""));
    taskList.value = [];
  } finally {
    loading.value = false;
  }
}

async function fetchTaskTypes() {
  try {
    const data = await getTaskTypes();
    taskTypes.value = Array.isArray(data) ? data : [];
  } catch (error) {
    console.error("Failed to fetch task types:", error);
  }
}

// ==================== 自动刷新 ====================

function hasUnfinishedTasks() {
  return taskList.value.some(
    (t) => t.status === "PENDING" || t.status === "RUNNING"
  );
}

function updateAutoRefresh() {
  if (hasUnfinishedTasks()) {
    if (!refreshTimer) {
      refreshTimer = setInterval(() => {
        fetchTaskList();
      }, 5000);
    }
  } else {
    stopAutoRefresh();
  }
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

// ==================== 操作 ====================

function handleSearch() {
  query.page = 1;
  fetchTaskList();
}

function handleReset() {
  query.source = "";
  query.taskType = "";
  query.status = "";
  query.keyword = "";
  query.page = 1;
  fetchTaskList();
}

function handlePageChange(page) {
  query.page = page;
  fetchTaskList();
}

function handleSizeChange(size) {
  query.size = size;
  query.page = 1;
  fetchTaskList();
}

function handleViewDetail(row) {
  router.push(`/task/detail/${row.id}`);
}

async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(
      `确定要取消任务「${row.taskTypeName || row.taskType}」吗？运行中的任务将等待 Handler 优雅退出。`,
      "取消任务",
      { type: "warning" }
    );
  } catch {
    return;
  }
  const key = `cancel-${row.id}`;
  opLoading.value[key] = true;
  try {
    await cancelTask(row.id);
    ElMessage.success("取消请求已发送");
    await fetchTaskList();
  } catch (error) {
    ElMessage.error(error.message || "取消失败");
  } finally {
    opLoading.value[key] = false;
  }
}

async function handleRetry(row) {
  try {
    await ElMessageBox.confirm(
      `确定要重试任务「${row.taskTypeName || row.taskType}」吗？将基于原参数创建新任务。`,
      "重试任务",
      { type: "info" }
    );
  } catch {
    return;
  }
  const key = `retry-${row.id}`;
  opLoading.value[key] = true;
  try {
    const newTaskId = await retryTask(row.id);
    ElMessage.success(`重试已提交，新任务ID: ${newTaskId}`);
    await fetchTaskList();
  } catch (error) {
    ElMessage.error(error.message || "重试失败");
  } finally {
    opLoading.value[key] = false;
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除任务「${row.taskTypeName || row.taskType}」的记录吗？此操作不可恢复。`,
      "删除任务",
      { type: "error" }
    );
  } catch {
    return;
  }
  const key = `delete-${row.id}`;
  opLoading.value[key] = true;
  try {
    await deleteTask(row.id);
    ElMessage.success("任务已删除");
    await fetchTaskList();
  } catch (error) {
    ElMessage.error(error.message || "删除失败");
  } finally {
    opLoading.value[key] = false;
  }
}

// ==================== 工具方法 ====================

function getStatusType(status) {
  switch (status) {
    case "PENDING":
      return "info";
    case "RUNNING":
      return "warning";
    case "COMPLETED":
      return "success";
    case "FAILED":
      return "danger";
    case "CANCELLED":
      return "info";
    default:
      return "info";
  }
}

function getStatusLabel(status) {
  switch (status) {
    case "PENDING":
      return "等待中";
    case "RUNNING":
      return "运行中";
    case "COMPLETED":
      return "已完成";
    case "FAILED":
      return "失败";
    case "CANCELLED":
      return "已取消";
    default:
      return status;
  }
}

function formatDuration(ms) {
  if (!ms || ms < 0) return "-";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`;
  return `${Math.floor(ms / 3_600_000)}h${Math.floor((ms % 3_600_000) / 60_000)}m`;
}

function formatTime(time) {
  if (!time) return "-";
  return time.replace("T", " ").substring(0, 19);
}

function isTerminal(status) {
  return ["COMPLETED", "FAILED", "CANCELLED"].includes(status);
}

function canCancel(status) {
  return ["PENDING", "RUNNING"].includes(status);
}

// ==================== 生命周期 ====================

onMounted(() => {
  fetchTaskTypes();
  fetchTaskList();
});

onUnmounted(() => {
  stopAutoRefresh();
});
</script>

<template>
  <div class="task-list-page">
    <!-- 筛选区 -->
    <el-card class="filter-card" shadow="never">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="来源">
          <el-select
            v-model="query.source"
            placeholder="全部"
            clearable
            style="width: 140px"
            @change="handleSearch"
          >
            <el-option
              v-for="opt in sourceOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select
            v-model="query.taskType"
            placeholder="全部"
            clearable
            style="width: 160px"
            @change="handleSearch"
          >
            <el-option
              v-for="opt in typeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            placeholder="全部"
            clearable
            style="width: 120px"
            @change="handleSearch"
          >
            <el-option
              v-for="opt in statusOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            placeholder="搜索类型/作用域/错误信息"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 任务列表 -->
    <el-card class="table-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="taskList"
        style="width: 100%"
        table-layout="fixed"
        @row-click="handleViewDetail"
      >
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务类型" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.taskTypeName || row.taskType }}</span>
            <div class="task-type-sub">{{ row.source }} / {{ row.taskType }}</div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.source }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="作用域" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.scopeName || row.scopeKey || "-" }}
          </template>
        </el-table-column>
        <el-table-column label="进度" width="170">
          <template #default="{ row }">
            <el-progress
              v-if="row.status === 'RUNNING' || row.status === 'PENDING'"
              :percentage="row.progress || 0"
              :status="row.status === 'RUNNING' ? undefined : 'warning'"
              :stroke-width="14"
              :text-inside="true"
            />
            <span v-else>{{ row.progress || 0 }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="结果/错误" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.status === 'FAILED'" class="text-danger">
              {{ row.errorMessage }}
            </span>
            <span v-else>{{ row.resultSummary || "-" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="center">
          <template #default="{ row }">
            {{ formatDuration(row.durationMs) }}
          </template>
        </el-table-column>
        <el-table-column label="重试" width="70" align="center">
          <template #default="{ row }">
            <span v-if="row.retryCount > 0">{{ row.retryCount }}/{{ row.maxRetryCount }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="handleViewDetail(row)">
              详情
            </el-button>
            <el-button
              v-if="canCancel(row.status)"
              link
              type="warning"
              size="small"
              :loading="opLoading[`cancel-${row.id}`]"
              @click.stop="handleCancel(row)"
            >
              取消
            </el-button>
            <el-button
              v-if="row.retryable && isTerminal(row.status)"
              link
              type="primary"
              size="small"
              :loading="opLoading[`retry-${row.id}`]"
              @click.stop="handleRetry(row)"
            >
              重试
            </el-button>
            <el-button
              v-if="isTerminal(row.status)"
              link
              type="danger"
              size="small"
              :loading="opLoading[`delete-${row.id}`]"
              @click.stop="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无任务记录" />
        </template>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.task-list-page {
  padding: var(--spacing-md);

  .filter-card {
    margin-bottom: var(--spacing-md);

    :deep(.el-card__body) {
      padding: var(--spacing-md) var(--spacing-lg);
    }
  }

  .table-card {
    :deep(.el-card__body) {
      padding: 0;
    }
  }

  .task-type-sub {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    margin-top: 2px;
  }

  .text-danger {
    color: var(--el-color-danger);
  }

  .pagination-wrapper {
    display: flex;
    justify-content: flex-end;
    padding: var(--spacing-md) var(--spacing-lg);
  }

  :deep(.el-table) {
    cursor: pointer;
  }
}
</style>
