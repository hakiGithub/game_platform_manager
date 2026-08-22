<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getScheduleList,
  getScheduleHandlers,
  createSchedule,
  updateSchedule,
  enableSchedule,
  disableSchedule,
  deleteSchedule,
  triggerSchedule,
  getScheduleRuns,
  cancelScheduleRun,
  getScheduleRunLogs,
} from "@/api/schedule";

// ==================== 状态 ====================

const loading = ref(false);
const scheduleList = ref([]);
const total = ref(0);
const handlers = ref([]);

const query = reactive({
  source: "",
  keyword: "",
  enabled: "",
  page: 1,
  size: 20,
});

const opLoading = ref({});
const expandLoading = ref({});
const expandRuns = ref({});
const expandTotal = ref({});
const expandQuery = ref({});

// 来源选项（从处理器列表构建）
const sourceOptions = computed(() => {
  const sources = new Set(handlers.value.map((h) => h.source));
  return [{ label: "全部", value: "" }, ...[...sources].map((s) => ({ label: s, value: s }))];
});

// ==================== 创建/编辑对话框 ====================

const dialogVisible = ref(false);
const dialogMode = ref("create");
const dialogLoading = ref(false);
const form = reactive({
  handler: "",
  name: "",
  cron: "",
  payloadText: "",
  enabled: true,
});
const editingId = ref("");

// 处理器选项（value = source:key）
const handlerOptions = computed(() =>
  handlers.value.map((h) => ({
    label: `${h.displayName}（${h.source}）`,
    value: `${h.source}:${h.taskType}`,
  }))
);

// ==================== 日志抽屉 ====================

const logDrawerVisible = ref(false);
const logDrawerLoading = ref(false);
const logDrawerRun = ref(null);
const runLogs = ref([]);

// ==================== 数据加载 ====================

async function fetchSchedules() {
  loading.value = true;
  try {
    const params = { ...query };
    if (params.enabled === "" || params.enabled === null) delete params.enabled;
    Object.keys(params).forEach((k) => {
      if (params[k] === "" || params[k] === null) delete params[k];
    });
    const data = await getScheduleList(params);
    scheduleList.value = data?.records || [];
    total.value = data?.total || 0;
  } catch (error) {
    ElMessage.error("获取定时计划失败：" + (error.message || ""));
  } finally {
    loading.value = false;
  }
}

async function fetchHandlers() {
  try {
    const data = await getScheduleHandlers();
    handlers.value = Array.isArray(data) ? data : [];
  } catch (error) {
    console.error("Failed to fetch schedule handlers:", error);
  }
}

// ==================== 查询操作 ====================

function handleSearch() {
  query.page = 1;
  fetchSchedules();
}

function handleReset() {
  query.source = "";
  query.keyword = "";
  query.enabled = "";
  query.page = 1;
  fetchSchedules();
}

function handlePageChange(page) {
  query.page = page;
  fetchSchedules();
}

function handleSizeChange(size) {
  query.size = size;
  query.page = 1;
  fetchSchedules();
}

// ==================== 计划操作 ====================

function openCreateDialog() {
  dialogMode.value = "create";
  editingId.value = "";
  form.handler = "";
  form.name = "";
  form.cron = "";
  form.payloadText = "";
  form.enabled = true;
  dialogVisible.value = true;
}

function openEditDialog(row) {
  dialogMode.value = "edit";
  editingId.value = row.id;
  form.handler = `${row.source}:${row.handlerKey}`;
  form.name = row.name;
  form.cron = row.cron;
  form.payloadText = row.payload ? JSON.stringify(row.payload, null, 2) : "";
  form.enabled = row.enabled;
  dialogVisible.value = true;
}

function parsePayloadText() {
  const text = form.payloadText.trim();
  if (!text) return {};
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw new Error("payload 必须是 JSON 对象");
    }
    return parsed;
  } catch (e) {
    if (e instanceof SyntaxError) {
      throw new Error("payload 不是合法的 JSON");
    }
    throw e;
  }
}

async function handleSubmit() {
  if (!form.name.trim()) {
    ElMessage.warning("请输入计划名称");
    return;
  }
  if (dialogMode.value === "create" && !form.handler) {
    ElMessage.warning("请选择处理器");
    return;
  }
  if (!form.cron.trim()) {
    ElMessage.warning("请输入 cron 表达式");
    return;
  }
  let payload;
  try {
    payload = parsePayloadText();
  } catch (e) {
    ElMessage.warning(e.message);
    return;
  }

  dialogLoading.value = true;
  try {
    if (dialogMode.value === "create") {
      const [source, handlerKey] = form.handler.split(":");
      await createSchedule({
        source,
        name: form.name.trim(),
        handlerKey,
        cron: form.cron.trim(),
        payload,
        enabled: form.enabled,
      });
      ElMessage.success("计划已创建");
    } else {
      await updateSchedule(editingId.value, {
        name: form.name.trim(),
        cron: form.cron.trim(),
        payload,
      });
      ElMessage.success("计划已更新");
    }
    dialogVisible.value = false;
    await fetchSchedules();
  } catch (error) {
    ElMessage.error(error.message || "保存失败");
  } finally {
    dialogLoading.value = false;
  }
}

async function handleToggleEnabled(row) {
  const key = `toggle-${row.id}`;
  opLoading.value[key] = true;
  try {
    if (row.enabled) {
      await disableSchedule(row.id);
      ElMessage.success("计划已禁用（进行中的触发会执行完毕）");
    } else {
      await enableSchedule(row.id);
      ElMessage.success("计划已启用");
    }
    await fetchSchedules();
  } catch (error) {
    ElMessage.error(error.message || "操作失败");
  } finally {
    opLoading.value[key] = false;
  }
}

async function handleTrigger(row) {
  try {
    await ElMessageBox.confirm(
      `确定要立即触发「${row.name}」吗？将产生一次手动执行记录。`,
      "立即触发",
      { type: "info" }
    );
  } catch {
    return;
  }
  const key = `trigger-${row.id}`;
  opLoading.value[key] = true;
  try {
    await triggerSchedule(row.id);
    ElMessage.success("已触发，展开行可查看执行记录");
    await fetchSchedules();
  } catch (error) {
    ElMessage.error(error.message || "触发失败");
  } finally {
    opLoading.value[key] = false;
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除计划「${row.name}」吗？进行中的触发将被取消，历史触发记录保留。`,
      "删除计划",
      { type: "error" }
    );
  } catch {
    return;
  }
  const key = `delete-${row.id}`;
  opLoading.value[key] = true;
  try {
    await deleteSchedule(row.id);
    ElMessage.success("计划已删除");
    await fetchSchedules();
  } catch (error) {
    ElMessage.error(error.message || "删除失败");
  } finally {
    opLoading.value[key] = false;
  }
}

// ==================== 触发记录（行展开） ====================

async function handleExpandChange(row, expandedRows) {
  const isExpanded = expandedRows.some((r) => r.id === row.id);
  if (isExpanded && !expandRuns.value[row.id]) {
    expandQuery.value[row.id] = { status: "", page: 1, size: 10 };
    await fetchRuns(row.id);
  }
}

async function fetchRuns(scheduleId) {
  expandLoading.value[scheduleId] = true;
  try {
    const q = expandQuery.value[scheduleId] || { status: "", page: 1, size: 10 };
    const params = { page: q.page, size: q.size };
    if (q.status) params.status = q.status;
    const data = await getScheduleRuns(scheduleId, params);
    expandRuns.value[scheduleId] = data?.records || [];
    expandTotal.value[scheduleId] = data?.total || 0;
  } catch (error) {
    ElMessage.error("获取触发记录失败：" + (error.message || ""));
  } finally {
    expandLoading.value[scheduleId] = false;
  }
}

function handleRunsSearch(row) {
  expandQuery.value[row.id].page = 1;
  fetchRuns(row.id);
}

function handleRunsPageChange(row, page) {
  expandQuery.value[row.id].page = page;
  fetchRuns(row.id);
}

async function handleCancelRun(scheduleId, run) {
  const key = `cancel-run-${run.id}`;
  opLoading.value[key] = true;
  try {
    await cancelScheduleRun(run.id);
    ElMessage.success("取消请求已发送");
    await fetchRuns(scheduleId);
  } catch (error) {
    ElMessage.error(error.message || "取消失败");
  } finally {
    opLoading.value[key] = false;
  }
}

async function handleViewLogs(run) {
  logDrawerVisible.value = true;
  logDrawerRun.value = run;
  logDrawerLoading.value = true;
  runLogs.value = [];
  try {
    const data = await getScheduleRunLogs(run.id);
    runLogs.value = Array.isArray(data) ? data : [];
  } catch (error) {
    ElMessage.error("获取执行日志失败：" + (error.message || ""));
  } finally {
    logDrawerLoading.value = false;
  }
}

// ==================== 展示工具 ====================

function getScheduleStatus(row) {
  if (row.paused) {
    return { label: "已暂停", type: "warning" };
  }
  return row.enabled
    ? { label: "已启用", type: "success" }
    : { label: "已禁用", type: "info" };
}

function getRunStatusType(status) {
  switch (status) {
    case "RUNNING":
      return "warning";
    case "SUCCEEDED":
      return "success";
    case "FAILED":
      return "danger";
    case "CANCELLED":
      return "info";
    case "SKIPPED":
      return "info";
    default:
      return "info";
  }
}

function getRunStatusLabel(status) {
  switch (status) {
    case "RUNNING":
      return "运行中";
    case "SUCCEEDED":
      return "成功";
    case "FAILED":
      return "失败";
    case "CANCELLED":
      return "已取消";
    case "SKIPPED":
      return "已跳过";
    default:
      return status;
  }
}

function getLevelTag(level) {
  switch (level) {
    case "ERROR":
      return "danger";
    case "WARN":
      return "warning";
    case "INFO":
      return "info";
    default:
      return "info";
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

// ==================== 生命周期 ====================

onMounted(() => {
  fetchHandlers();
  fetchSchedules();
});

defineExpose({
  refresh: fetchSchedules,
});
</script>

<template>
  <div class="schedule-panel">
    <!-- 筛选 -->
    <section class="schedule-filter-panel">
      <div class="panel-heading filter-heading">
        <div>
          <span class="section-kicker">SCHEDULE / SCOPE</span>
          <h2>筛选定时计划</h2>
        </div>
        <span class="filter-hint">按来源、名称或启用状态定位计划</span>
      </div>
      <el-form class="schedule-filter-form" :inline="true" :model="query" @submit.prevent>
        <el-form-item label="来源">
          <el-select
            v-model="query.source"
            placeholder="全部"
            clearable
            style="width: 120px"
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
        <el-form-item label="状态">
          <el-select
            v-model="query.enabled"
            placeholder="全部"
            clearable
            style="width: 108px"
            @change="handleSearch"
          >
            <el-option label="已启用" :value="true" />
            <el-option label="已禁用" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input
            v-model="query.keyword"
            placeholder="搜索计划名称"
            clearable
            style="width: 190px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            应用筛选
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <!-- 计划列表 -->
    <el-card class="table-card schedule-console-panel" shadow="never">
      <div class="panel-heading console-heading">
        <div>
          <span class="section-kicker">CRON REGISTRY</span>
          <h2>定时计划</h2>
          <p>{{ total }} 个计划 · 展开行查看触发历史</p>
        </div>
        <div class="console-actions">
          <el-button @click="fetchSchedules">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-button type="primary" @click="openCreateDialog">
            <el-icon><Plus /></el-icon>
            新建计划
          </el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        class="schedule-console-table"
        :data="scheduleList"
        style="width: 100%"
        row-key="id"
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="runs-wrap" v-loading="expandLoading[row.id]">
              <div class="runs-toolbar">
                <strong>触发记录</strong>
                <el-select
                  v-model="expandQuery[row.id].status"
                  placeholder="全部状态"
                  clearable
                  size="small"
                  style="width: 120px"
                  @change="handleRunsSearch(row)"
                >
                  <el-option label="运行中" value="RUNNING" />
                  <el-option label="成功" value="SUCCEEDED" />
                  <el-option label="失败" value="FAILED" />
                  <el-option label="已取消" value="CANCELLED" />
                  <el-option label="已跳过" value="SKIPPED" />
                </el-select>
              </div>
              <el-table
                class="runs-table"
                :data="expandRuns[row.id] || []"
                size="small"
                style="width: 100%"
              >
                <el-table-column label="触发方式" width="90" align="center">
                  <template #default="{ row: run }">
                    <el-tag size="small" :type="run.triggerType === 'MANUAL' ? 'warning' : 'info'" effect="plain">
                      {{ run.triggerType === "MANUAL" ? "手动" : "定时" }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="90" align="center">
                  <template #default="{ row: run }">
                    <el-tag size="small" :type="getRunStatusType(run.status)">
                      {{ getRunStatusLabel(run.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="开始时间" width="135">
                  <template #default="{ row: run }">
                    {{ formatTime(run.startedAt || run.createTime) }}
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="80" align="center">
                  <template #default="{ row: run }">
                    {{ formatDuration(run.durationMs) }}
                  </template>
                </el-table-column>
                <el-table-column label="结果/错误" min-width="180" show-overflow-tooltip>
                  <template #default="{ row: run }">
                    <span :class="['run-result', { 'is-error': run.status === 'FAILED' }]">
                      {{ run.errorMessage || "-" }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="130" align="center">
                  <template #default="{ row: run }">
                    <el-button
                      v-if="run.status === 'RUNNING'"
                      link
                      type="warning"
                      size="small"
                      :loading="opLoading[`cancel-run-${run.id}`]"
                      @click="handleCancelRun(row.id, run)"
                    >
                      取消
                    </el-button>
                    <el-button link type="primary" size="small" @click="handleViewLogs(run)">
                      日志
                    </el-button>
                  </template>
                </el-table-column>
                <template #empty>
                  <span class="runs-empty">暂无触发记录</span>
                </template>
              </el-table>
              <div class="runs-pagination" v-if="(expandTotal[row.id] || 0) > 0">
                <el-pagination
                  small
                  background
                  layout="total, prev, pager, next"
                  :total="expandTotal[row.id] || 0"
                  :page-size="expandQuery[row.id]?.size || 10"
                  :current-page="expandQuery[row.id]?.page || 1"
                  @current-change="(p) => handleRunsPageChange(row, p)"
                />
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.paused && row.pauseReason"
              :content="`系统暂停：${row.pauseReason}`"
              placement="top"
            >
              <el-tag size="small" :type="getScheduleStatus(row).type">
                {{ getScheduleStatus(row).label }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else size="small" :type="getScheduleStatus(row).type">
              {{ getScheduleStatus(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="schedule-identity">
              <strong>{{ row.name }}</strong>
              <span>{{ row.source }} / {{ row.handlerKey }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="cron 表达式" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="cron-code">{{ row.cron }}</code>
          </template>
        </el-table-column>
        <el-table-column label="处理器" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="handler-name">
              {{ row.handlerName || row.handlerKey }}
              <el-tag v-if="!row.handlerName" size="small" type="danger" effect="plain" class="handler-missing">
                未注册
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="上次结果" width="120" align="center">
          <template #default="{ row }">
            <div v-if="row.lastRunStatus" class="last-run-cell">
              <el-tag size="small" :type="getRunStatusType(row.lastRunStatus)">
                {{ getRunStatusLabel(row.lastRunStatus) }}
              </el-tag>
              <span>{{ formatTime(row.lastRunTime) }}</span>
            </div>
            <span v-else class="text-muted">从未触发</span>
          </template>
        </el-table-column>
        <el-table-column label="下次触发" width="135" align="center">
          <template #default="{ row }">
            <span v-if="row.nextFireTime">{{ formatTime(row.nextFireTime) }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              link
              type="success"
              size="small"
              :loading="opLoading[`trigger-${row.id}`]"
              @click="handleTrigger(row)"
            >
              触发
            </el-button>
            <el-button
              link
              :type="row.enabled ? 'warning' : 'success'"
              size="small"
              :loading="opLoading[`toggle-${row.id}`]"
              @click="handleToggleEnabled(row)"
            >
              {{ row.enabled ? "禁用" : "启用" }}
            </el-button>
            <el-button link type="primary" size="small" @click="openEditDialog(row)">
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              :loading="opLoading[`delete-${row.id}`]"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="schedule-empty">
            <el-icon><Timer /></el-icon>
            <strong>暂无定时计划</strong>
            <span>点击「新建计划」创建第一个 cron 计划</span>
          </div>
        </template>
      </el-table>

      <div class="pagination-wrapper schedule-console-footer">
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

    <!-- 创建/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建定时计划' : '编辑定时计划'"
      width="560px"
      destroy-on-close
    >
      <el-form :model="form" label-width="96px">
        <el-form-item v-if="dialogMode === 'create'" label="处理器" required>
          <el-select
            v-model="form.handler"
            placeholder="选择定时任务处理器"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="opt in handlerOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="处理器">
          <el-input :model-value="form.handler" disabled />
        </el-form-item>
        <el-form-item label="计划名称" required>
          <el-input v-model="form.name" placeholder="如：每日地图增量爬取" maxlength="64" />
        </el-form-item>
        <el-form-item label="cron 表达式" required>
          <el-input v-model="form.cron" placeholder="如：0 0 4 * * ?（每天凌晨 4 点）" />
          <div class="cron-presets">
            <el-button
              v-for="preset in [
                { label: '每小时', value: '0 0 * * * ?' },
                { label: '每天 4 点', value: '0 0 4 * * ?' },
                { label: '每周一 4 点', value: '0 0 4 ? * MON' },
                { label: '每月 1 日 4 点', value: '0 0 4 1 * ?' },
              ]"
              :key="preset.value"
              size="small"
              text
              type="primary"
              @click="form.cron = preset.value"
            >
              {{ preset.label }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="payload">
          <el-input
            v-model="form.payloadText"
            type="textarea"
            :rows="4"
            placeholder='JSON 对象，如 {"crawlType": "increment"}（可留空）'
          />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" label="创建后启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogLoading" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 执行日志抽屉 -->
    <el-drawer
      v-model="logDrawerVisible"
      :title="`执行日志 - ${logDrawerRun?.scheduleName || ''}`"
      size="520px"
    >
      <div v-loading="logDrawerLoading" class="log-list">
        <div v-if="!logDrawerLoading && runLogs.length === 0" class="log-empty">
          暂无日志
        </div>
        <div v-for="log in runLogs" :key="log.id" class="log-item">
          <div class="log-meta">
            <el-tag size="small" :type="getLevelTag(log.level)">{{ log.level }}</el-tag>
            <span>{{ formatTime(log.createTime) }}</span>
          </div>
          <p class="log-message">{{ log.message }}</p>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.schedule-panel {
  --task-accent: #ee8358;
  --task-cyan: #55c9dc;
}

.schedule-filter-panel,
.schedule-console-panel {
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-1);
}

.schedule-filter-panel {
  padding: 17px 18px 5px;
  border-radius: 5px;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;

  h2 {
    margin: 5px 0 0;
    color: var(--platform-text-primary);
    font-size: 16px;
    font-weight: 650;
  }
}

.section-kicker {
  display: block;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
}

.filter-hint,
.console-heading p {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.schedule-filter-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 13px;

  :deep(.el-form-item) {
    margin-right: 0;
    margin-bottom: 10px;
  }

  .el-form-item:last-child {
    display: flex;
    gap: 8px;
  }
}

.schedule-console-panel {
  margin-top: 14px;
  border-radius: 5px;
  overflow: hidden;

  :deep(.el-card__body) {
    padding: 0;
  }
}

.console-heading {
  min-height: 76px;
  padding: 17px 18px 15px;
  border-bottom: 1px solid var(--platform-line);

  p {
    margin: 5px 0 0;
  }
}

.console-actions {
  display: flex;
  gap: 8px;
}

.schedule-console-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(238, 131, 88, 0.07);
  --el-table-header-bg-color: rgba(20, 39, 53, 0.86);
  --el-table-border-color: var(--platform-line);
  --el-table-text-color: var(--platform-text-regular);
  --el-table-header-text-color: var(--platform-text-secondary);

  :deep(.el-table__header-wrapper th.el-table__cell) {
    height: 42px;
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.03em;
  }

  :deep(.el-table__body-wrapper td.el-table__cell) {
    border-bottom-color: rgba(38, 56, 71, 0.72);
  }

  :deep(.el-button.is-link) {
    font-size: 12px;
  }
}

.schedule-identity {
  display: grid;
  gap: 4px;

  strong {
    overflow: hidden;
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.03em;
  }
}

.cron-code {
  color: var(--task-cyan);
  font-family: var(--el-font-family-mono);
  font-size: 12px;
}

.handler-name {
  color: var(--platform-text-regular);
  font-size: 12px;
}

.handler-missing {
  margin-left: 6px;
}

.last-run-cell {
  display: grid;
  gap: 4px;
  justify-items: center;

  span {
    color: var(--platform-text-muted);
    font-size: 10px;
  }
}

.text-muted {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.runs-wrap {
  padding: 12px 18px 16px 46px;
  background: rgba(15, 28, 38, 0.5);
}

.runs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  strong {
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 600;
  }
}

.runs-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(20, 39, 53, 0.86);
  --el-table-border-color: var(--platform-line);
  --el-table-text-color: var(--platform-text-regular);
}

.run-result {
  color: var(--platform-text-secondary);
  font-size: 12px;

  &.is-error {
    color: var(--platform-red);
  }
}

.runs-empty {
  color: var(--platform-text-muted);
  font-size: 12px;
}

.runs-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.schedule-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 150px;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--task-accent);
    font-size: 22px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 600;
  }

  span {
    font-size: 11px;
  }
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: var(--spacing-md) var(--spacing-lg);
}

.cron-presets {
  display: flex;
  gap: 4px;
  margin-top: 6px;
}

.log-list {
  min-height: 120px;
}

.log-empty {
  padding: 40px 0;
  color: var(--platform-text-muted);
  font-size: 12px;
  text-align: center;
}

.log-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--platform-line);

  .log-meta {
    display: flex;
    align-items: center;
    gap: 10px;

    span {
      color: var(--platform-text-muted);
      font-family: var(--el-font-family-mono);
      font-size: 10px;
    }
  }

  .log-message {
    margin: 6px 0 0;
    color: var(--platform-text-regular);
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
  }
}
</style>
