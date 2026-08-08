<script setup>
import { ref, reactive, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { getOperationLogs, exportLogs } from "@/api/system";

// 加载状态
const loading = ref(false);

// 搜索表单
const searchForm = reactive({
  keyword: "",
  operator: "",
  operationType: "",
  result: "",
  timeRange: [],
});

// 操作类型选项
const operationTypes = [
  { value: "host", label: "主机管理" },
  { value: "instance", label: "实例管理" },
  { value: "plugin", label: "插件管理" },
  { value: "system", label: "系统设置" },
  { value: "user", label: "用户管理" },
  { value: "backup", label: "备份还原" },
];

// 结果选项
const resultOptions = [
  { value: "success", label: "成功" },
  { value: "fail", label: "失败" },
];

// 表格数据
const tableData = ref([]);
const pagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
});

// 获取列表
async function fetchData() {
  loading.value = true;
  try {
    const params = {
      ...searchForm,
      page: pagination.current,
      pageSize: pagination.pageSize,
    };

    // 处理时间范围
    if (searchForm.timeRange && searchForm.timeRange.length === 2) {
      params.startTime = searchForm.timeRange[0];
      params.endTime = searchForm.timeRange[1];
    }
    delete params.timeRange;

    const data = await getOperationLogs(params);
    tableData.value = data.records || [];
    pagination.total = data.total || 0;
  } catch (error) {
    console.error("Failed to fetch operation logs:", error);
  } finally {
    loading.value = false;
  }
}

// 搜索
function handleSearch() {
  pagination.current = 1;
  fetchData();
}

// 重置
function handleReset() {
  searchForm.keyword = "";
  searchForm.operator = "";
  searchForm.operationType = "";
  searchForm.result = "";
  searchForm.timeRange = [];
  handleSearch();
}

// 导出
async function handleExport() {
  try {
    ElMessage.info("正在导出...");
    const params = { ...searchForm };
    if (searchForm.timeRange && searchForm.timeRange.length === 2) {
      params.startTime = searchForm.timeRange[0];
      params.endTime = searchForm.timeRange[1];
    }
    delete params.timeRange;

    const response = await exportLogs(params);
    downloadBlob(
      response.data,
      `operation-logs-${new Date().toISOString().slice(0, 10)}.csv`
    );
    ElMessage.success("导出成功");
  } catch (error) {
    console.warn("Backend export failed, falling back to client-side CSV:", error);
    exportClientSideCsv();
  }
}

// 下载后端返回的 Blob 文件
function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

// 客户端 CSV 导出降级（当后端 /system/logs/export 不可用时使用）
function exportClientSideCsv() {
  if (!tableData.value || tableData.value.length === 0) {
    ElMessage.warning("当前无数据可导出");
    return;
  }

  const headers = ["操作时间", "操作人", "操作类型", "操作对象", "操作内容", "结果", "IP地址", "客户端"];
  const rows = tableData.value.map((row) => [
    row.time || "",
    row.operator || "",
    getOperationTypeText(row.operationType),
    row.target || "",
    row.content || "",
    getResultText(row.result),
    row.ip || "",
    row.userAgent || "",
  ]);

  const escapeCsv = (value) => {
    const str = String(value ?? "");
    if (str.includes(",") || str.includes('"') || str.includes("\n")) {
      return `"${str.replace(/"/g, '""')}"`;
    }
    return str;
  };

  const csvContent = [headers.map(escapeCsv).join(","), ...rows.map((r) => r.map(escapeCsv).join(","))].join("\n");
  const blob = new Blob(["\uFEFF" + csvContent], { type: "text/csv;charset=utf-8;" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `operation-logs-${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(link.href);
  ElMessage.success("已导出当前页数据（CSV）");
}

// 分页变化
function handlePageChange(page) {
  pagination.current = page;
  fetchData();
}

function handleSizeChange(size) {
  pagination.pageSize = size;
  pagination.current = 1;
  fetchData();
}

// 获取结果标签类型
function getResultType(result) {
  return result === "success" ? "success" : "danger";
}

// 获取结果文本
function getResultText(result) {
  return result === "success" ? "成功" : "失败";
}

// 获取操作类型文本
function getOperationTypeText(type) {
  const item = operationTypes.find((t) => t.value === type);
  return item ? item.label : type;
}

// 快捷时间选择
function handleQuickTime(type) {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());

  switch (type) {
    case "today":
      searchForm.timeRange = [today, now];
      break;
    case "yesterday":
      const yesterday = new Date(today.getTime() - 24 * 60 * 60 * 1000);
      searchForm.timeRange = [yesterday, today];
      break;
    case "week":
      const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
      searchForm.timeRange = [weekAgo, now];
      break;
    case "month":
      const monthAgo = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
      searchForm.timeRange = [monthAgo, now];
      break;
  }
  handleSearch();
}

onMounted(() => {
  fetchData();
});
</script>

<template>
  <div class="logs-container">
    <!-- 搜索区域 -->
    <el-card class="search-card" shadow="never">
      <el-form :model="searchForm" inline>
        <el-form-item label="关键词">
          <el-input
            v-model="searchForm.keyword"
            placeholder="操作内容"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="操作人">
          <el-input
            v-model="searchForm.operator"
            placeholder="操作人"
            clearable
            style="width: 120px"
          />
        </el-form-item>
        <el-form-item label="操作类型">
          <el-select
            v-model="searchForm.operationType"
            placeholder="全部"
            clearable
            style="width: 120px"
          >
            <el-option
              v-for="item in operationTypes"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select
            v-model="searchForm.result"
            placeholder="全部"
            clearable
            style="width: 100px"
          >
            <el-option
              v-for="item in resultOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="searchForm.timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            搜索
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 快捷时间选择 -->
      <div class="quick-time">
        <el-button size="small" @click="handleQuickTime('today')"
          >今天</el-button
        >
        <el-button size="small" @click="handleQuickTime('yesterday')"
          >昨天</el-button
        >
        <el-button size="small" @click="handleQuickTime('week')"
          >近7天</el-button
        >
        <el-button size="small" @click="handleQuickTime('month')"
          >近30天</el-button
        >
      </div>
    </el-card>

    <!-- 表格区域 -->
    <el-card class="table-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">操作日志</span>
          <div class="header-actions">
            <el-button @click="fetchData">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
            <el-button type="primary" @click="handleExport">
              <el-icon><Download /></el-icon>
              导出
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="tableData" style="width: 100%">
        <el-table-column prop="time" label="操作时间" width="180" show-overflow-tooltip />
        <el-table-column prop="operator" label="操作人" width="100" show-overflow-tooltip />
        <el-table-column label="操作类型" width="100">
          <template #default="{ row }">
            <el-tag type="info" size="small" effect="plain">
              {{ getOperationTypeText(row.operationType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="target"
          label="操作对象"
          width="150"
          show-overflow-tooltip
        />
        <el-table-column
          prop="content"
          label="操作内容"
          min-width="250"
          show-overflow-tooltip
        />
        <el-table-column label="结果" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="getResultType(row.result)" size="small">
              {{ getResultText(row.result) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP地址" width="140" show-overflow-tooltip />
        <el-table-column
          prop="userAgent"
          label="客户端"
          width="120"
          show-overflow-tooltip
        />
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[20, 50, 100, 200]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.logs-container {
  .search-card {
    margin-bottom: 16px;

    :deep(.el-card__body) {
      padding-bottom: 0;
    }

    .quick-time {
      display: flex;
      gap: 8px;
      margin-bottom: 16px;
    }
  }

  .table-card {
    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;

      .title {
        font-size: var(--platform-font-size-md);
        font-weight: var(--platform-font-weight-bold);
        color: var(--el-text-color-primary);
      }

      .header-actions {
        display: flex;
        gap: 8px;
      }
    }
  }

  .pagination-wrapper {
    display: flex;
    justify-content: flex-end;
    margin-top: 16px;
  }
}

// 响应式适配
@media screen and (max-width: 1366px) {
  .search-card {
    :deep(.el-form-item) {
      margin-bottom: 12px;
    }
  }
}
</style>
