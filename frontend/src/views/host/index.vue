<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getHostList,
  getHostResources,
  createHost,
  updateHost,
  deleteHost,
  testHostConnection,
  previewHostsRefresh,
  refreshHosts,
} from "@/api/host";

const router = useRouter();

// 加载状态
const loading = ref(false);
const testLoading = ref(false);

// 搜索表单
const searchForm = reactive({
  name: "",
  ip: "",
  status: "",
});

// 表格数据
const tableData = ref([]);
const pagination = reactive({
  current: 1,
  size: 10,
  total: 0,
});
const lastRefreshAt = ref("等待同步");
const resourceMetrics = [
  { key: "cpu", label: "CPU" },
  { key: "memory", label: "内存" },
  { key: "disk", label: "磁盘" },
];

const onlineHostCount = computed(
  () => tableData.value.filter((host) => host.status === 1).length,
);
const offlineHostCount = computed(
  () => tableData.value.filter((host) => host.status === 0).length,
);
const lanHostCount = computed(
  () => tableData.value.filter((host) => host.isLanHost).length,
);
const attentionHostCount = computed(
  () =>
    tableData.value.filter(
      (host) => host.status === 0 || getHostPeakUsage(host) >= 80,
    ).length,
);

// 弹窗相关
const dialogVisible = ref(false);
const dialogType = ref("add"); // add | edit
const formRef = ref(null);
const submitLoading = ref(false);

// 表单数据
const hostForm = reactive({
  id: null,
  name: "",
  ip: "",
  sshPort: 22,
  sshUsername: "root",
  sshPassword: "",
  sshPrivateKey: "",
  authType: "password", // password | key
  tags: "",
  remark: "",
  isLanHost: false, // 是否局域网主机（平台代劳硬开关，详见 ADR-0004）
});

// 表单验证规则
const hostRules = {
  name: [
    { required: true, message: "请输入主机名称", trigger: "blur" },
    { min: 1, max: 50, message: "主机名称长度为1-50个字符", trigger: "blur" },
  ],
  ip: [
    { required: true, message: "请输入IP地址", trigger: "blur" },
    {
      pattern:
        /^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$/,
      message: "请输入有效的IPv4地址",
      trigger: "blur",
    },
  ],
  sshPort: [
    { required: true, message: "请输入SSH端口", trigger: "blur" },
    {
      type: "number",
      min: 1,
      max: 65535,
      message: "端口范围为1-65535",
      trigger: "blur",
    },
  ],
  sshUsername: [
    { required: true, message: "请输入用户名", trigger: "blur" },
    { min: 1, max: 50, message: "用户名长度为1-50个字符", trigger: "blur" },
  ],
  sshPassword: [
    {
      required: computed(() => hostForm.authType === "password"),
      message: "请输入密码",
      trigger: "blur",
    },
  ],
  sshPrivateKey: [
    {
      required: computed(() => hostForm.authType === "key"),
      message: "请输入SSH私钥",
      trigger: "blur",
    },
  ],
  remark: [{ max: 200, message: "备注最多200个字符", trigger: "blur" }],
};

// hosts 刷新弹窗
const hostsDialogVisible = ref(false);
const hostsPreviewLoading = ref(false);
const hostsRefreshLoading = ref(false);
const hostsPreview = ref(null);
const hostsSudoPassword = ref("");
const hostsTargetHost = ref(null);
// 用户选中的待改域名（默认全部勾选，用户可取消不需要的，如广告屏蔽条目）
const hostsSelectedDomains = ref([]);
const hostsDomainFilter = ref("");

// 全选/取消全选
const hostsAllSelected = computed(
  () =>
    hostsPreview.value?.domainsToRefresh?.length > 0 &&
    hostsSelectedDomains.value.length ===
      hostsPreview.value.domainsToRefresh.length,
);

// 按过滤词展示的域名
const hostsFilteredDomains = computed(() => {
  const list = hostsPreview.value?.domainsToRefresh || [];
  const kw = hostsDomainFilter.value.trim().toLowerCase();
  if (!kw) return list;
  return list.filter((d) => d.toLowerCase().includes(kw));
});

function toggleAllHostsDomains(val) {
  if (val) {
    hostsSelectedDomains.value = [
      ...(hostsPreview.value?.domainsToRefresh || []),
    ];
  } else {
    hostsSelectedDomains.value = [];
  }
}

// 打开 hosts 刷新弹窗
async function handleRefreshHosts(row) {
  if (row.status !== 1) {
    ElMessage.warning("主机离线，无法刷新 hosts");
    return;
  }
  hostsTargetHost.value = row;
  hostsDialogVisible.value = true;
  hostsPreviewLoading.value = true;
  hostsPreview.value = null;
  hostsSudoPassword.value = "";
  hostsSelectedDomains.value = [];
  hostsDomainFilter.value = "";

  try {
    const data = await previewHostsRefresh(row.id);
    hostsPreview.value = data;
    // 默认全部勾选
    hostsSelectedDomains.value = [...(data.domainsToRefresh || [])];
  } catch (error) {
    ElMessage.error("预检失败：" + (error.message || "未知错误"));
    hostsDialogVisible.value = false;
  } finally {
    hostsPreviewLoading.value = false;
  }
}

// 确认刷新 hosts
async function confirmRefreshHosts() {
  if (!hostsPreview.value) return;

  // 需要密码但未输入
  if (hostsPreview.value.needsSudoPassword && !hostsSudoPassword.value) {
    ElMessage.warning("请输入 sudo 密码");
    return;
  }

  // 无待改域名
  if (
    !hostsPreview.value.domainsToRefresh ||
    hostsPreview.value.domainsToRefresh.length === 0
  ) {
    ElMessage.info("无需刷新，hosts 文件已是目标状态");
    hostsDialogVisible.value = false;
    return;
  }

  // 未选中任何域名
  if (hostsSelectedDomains.value.length === 0) {
    ElMessage.warning("请至少选择一个要刷新的域名");
    return;
  }

  hostsRefreshLoading.value = true;
  try {
    const result = await refreshHosts(
      hostsTargetHost.value.id,
      hostsPreview.value.needsSudoPassword ? hostsSudoPassword.value : null,
      hostsSelectedDomains.value,
    );
    if (result.success) {
      ElMessage.success(
        `已修改 ${result.refreshedDomains?.length || 0} 个域名，备份路径：${result.backupPath || "(无)"}`,
      );
      hostsDialogVisible.value = false;
    } else {
      ElMessage.error(result.errorMessage || "刷新失败");
    }
  } catch (error) {
    ElMessage.error("刷新失败：" + (error.message || "未知错误"));
  } finally {
    hostsRefreshLoading.value = false;
  }
}

// 删除确认弹窗
const deleteDialogVisible = ref(false);
const deleteConfirmName = ref("");
const deleteTarget = ref(null);

// 获取列表
async function fetchData() {
  loading.value = true;
  try {
    const data = await getHostList({
      current: pagination.current,
      size: pagination.size,
      ...searchForm,
    });
    tableData.value = (data.records || []).map((item) => ({
      ...item,
      resources: null,
      resourcesLoading: false,
    }));
    pagination.total = data.total || 0;

    // 加载在线主机的资源使用情况
    await loadResourcesForOnlineHosts();
    lastRefreshAt.value = formatRefreshTime();
  } catch (error) {
    console.error("Failed to fetch host list:", error);
  } finally {
    loading.value = false;
  }
}

// 加载在线主机的资源使用情况
async function loadResourcesForOnlineHosts() {
  const onlineHosts = tableData.value.filter((h) => h.status === 1);
  for (const host of onlineHosts) {
    try {
      host.resourcesLoading = true;
      const resources = await getHostResources(host.id).catch(() => null);
      host.resources = resources;
    } catch (e) {
      // ignore
    } finally {
      host.resourcesLoading = false;
    }
  }
}

// 搜索
function handleSearch() {
  pagination.current = 1;
  fetchData();
}

// 重置
function handleReset() {
  searchForm.name = "";
  searchForm.ip = "";
  searchForm.status = "";
  handleSearch();
}

// 新增
function handleAdd() {
  dialogType.value = "add";
  resetForm();
  dialogVisible.value = true;
}

// 编辑
function handleEdit(row) {
  dialogType.value = "edit";
  resetForm();
  Object.assign(hostForm, {
    id: row.id,
    name: row.name,
    ip: row.ip,
    sshPort: row.sshPort || 22,
    sshUsername: row.sshUsername || "root",
    sshPassword: "",
    sshPrivateKey: "",
    authType: row.sshPassword ? "password" : "key",
    tags: row.tags || "",
    remark: row.remark || "",
    isLanHost: !!row.isLanHost,
  });
  dialogVisible.value = true;
}

// 重置表单
function resetForm() {
  Object.assign(hostForm, {
    id: null,
    name: "",
    ip: "",
    sshPort: 22,
    sshUsername: "root",
    sshPassword: "",
    sshPrivateKey: "",
    authType: "password",
    tags: "",
    remark: "",
    isLanHost: false,
  });
  if (formRef.value) {
    formRef.value.resetFields();
  }
}

// 测试连接
async function handleTestConnection() {
  if (!hostForm.ip || !hostForm.sshPort) {
    ElMessage.warning("请先填写IP地址和端口");
    return;
  }

  testLoading.value = true;
  try {
    // 如果是编辑模式且有ID，使用现有主机测试
    if (hostForm.id) {
      const result = await testHostConnection(hostForm.id);
      if (result.connected) {
        ElMessage.success(`连接测试成功: ${result.message}`);
      } else {
        ElMessage.error(`连接测试失败: ${result.message}`);
      }
    } else {
      // 新增模式，需要先保存才能测试
      ElMessage.info("新增主机请先保存后再测试连接");
    }
  } catch (error) {
    ElMessage.error("连接测试失败：" + (error.message || "网络错误"));
  } finally {
    testLoading.value = false;
  }
}

// 提交表单
async function handleSubmit() {
  if (!formRef.value) return;

  await formRef.value.validate(async (valid) => {
    if (valid) {
      submitLoading.value = true;
      try {
        const data = {
          name: hostForm.name,
          ip: hostForm.ip,
          sshPort: hostForm.sshPort,
          sshUsername: hostForm.sshUsername,
          tags: hostForm.tags,
          remark: hostForm.remark,
          isLanHost: hostForm.isLanHost,
        };

        // 根据认证类型设置密码或私钥
        if (hostForm.authType === "password") {
          data.sshPassword = hostForm.sshPassword;
        } else {
          data.sshPrivateKey = hostForm.sshPrivateKey;
        }

        if (dialogType.value === "add") {
          await createHost(data);
          ElMessage.success("新增成功");
        } else {
          await updateHost(hostForm.id, data);
          ElMessage.success("更新成功");
        }
        dialogVisible.value = false;
        fetchData();
      } catch (error) {
        console.error("Failed to save host:", error);
      } finally {
        submitLoading.value = false;
      }
    }
  });
}

// 删除
function handleDelete(row) {
  deleteTarget.value = row;
  deleteConfirmName.value = "";
  deleteDialogVisible.value = true;
}

// 确认删除
async function confirmDelete() {
  if (deleteConfirmName.value !== deleteTarget.value.name) {
    ElMessage.warning("请输入正确的主机名称");
    return;
  }

  try {
    await deleteHost(deleteTarget.value.id);
    ElMessage.success("删除成功");
    deleteDialogVisible.value = false;
    fetchData();
  } catch (error) {
    console.error("Failed to delete host:", error);
  }
}

// 终端
function handleTerminal(row) {
  if (row.status !== 1) {
    ElMessage.warning("主机离线，无法打开终端");
    return;
  }
  router.push({
    path: `/host/terminal/${row.id}`,
    query: { name: row.name, ip: row.ip },
  });
}

// 主机详情
function handleDetail(row) {
  router.push(`/host/detail/${row.id}`);
}

// 测试连接（表格行）
async function handleTest(row) {
  try {
    const result = await testHostConnection(row.id);
    if (result.connected) {
      ElMessage.success(`连接测试成功: ${result.message}`);
    } else {
      ElMessage.error(`连接测试失败: ${result.message}`);
    }
  } catch (error) {
    ElMessage.error("连接测试失败");
  }
}

// 分页变化
function handlePageChange(page) {
  pagination.current = page;
  fetchData();
}

function handleSizeChange(size) {
  pagination.size = size;
  pagination.current = 1;
  fetchData();
}

// 获取状态图标
function getStatusIcon(status) {
  // 状态: 0-离线，1-在线
  return status === 1 ? "CircleCheck" : "CircleClose";
}

// 获取状态颜色
function getStatusColor(status) {
  return status === 1
    ? "var(--platform-status-running)"
    : "var(--platform-status-stopped)";
}

// 获取进度条颜色
function getProgressColor(value) {
  if (value >= 80) return "var(--el-color-danger)";
  if (value >= 60) return "var(--el-color-warning)";
  return "var(--el-color-success)";
}

function getHostPeakUsage(row) {
  return Math.max(
    getResourceUsage(row, "cpu"),
    getResourceUsage(row, "memory"),
    getResourceUsage(row, "disk"),
  );
}

function getResourceUsage(row, key) {
  return formatUsage(row.resources?.[key]?.usage);
}

function getResourceTone(value) {
  if (value >= 80) return "danger";
  if (value >= 60) return "warning";
  return "normal";
}

function formatRefreshTime() {
  return new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

// 统一格式化资源使用率，保留 1 位小数
function formatUsage(value) {
  const num = Number(value);
  if (Number.isNaN(num)) return 0;
  return Math.round(num * 10) / 10;
}

// 表格行样式
function tableRowClassName({ row }) {
  if (row.status === 0) return "row-stopped";
  return "";
}

onMounted(() => {
  fetchData();
});
</script>

<template>
  <div class="host-container host-operations-page">
    <section class="host-hero">
      <div class="hero-copy">
        <span class="section-kicker">HOST CONTROL / HOST INVENTORY</span>
        <h1>主机工作台</h1>
        <p>集中查看连接健康、资源水位和 SSH 运维入口，先确认主机状态，再进入具体处置。</p>
      </div>
      <div class="hero-actions">
        <div class="hero-status">
          <span class="live-pulse" aria-hidden="true"></span>
          <div>
            <strong>连接面正常</strong>
            <small>上次同步 {{ lastRefreshAt }}</small>
          </div>
        </div>
        <el-button type="primary" @click="handleAdd">
          <el-icon><Plus /></el-icon>
          纳管主机
        </el-button>
      </div>
    </section>

    <section class="host-situation" aria-label="主机运行态势">
      <div class="situation-intro">
        <span class="section-kicker">HOST SITUATION</span>
        <strong>当前纳管态势</strong>
        <small>资源和连接状态来自最近一次同步</small>
      </div>
      <div class="situation-stat">
        <span>在线主机</span>
        <strong>{{ onlineHostCount }}/{{ tableData.length }}</strong>
      </div>
      <div class="situation-stat">
        <span>离线主机</span>
        <strong :class="{ 'is-warning': offlineHostCount > 0 }">{{ offlineHostCount }}</strong>
      </div>
      <div class="situation-stat">
        <span>资源告警</span>
        <strong :class="{ 'is-warning': attentionHostCount > 0 }">{{ attentionHostCount }}</strong>
      </div>
      <div class="situation-stat">
        <span>局域网主机</span>
        <strong>{{ lanHostCount }}</strong>
      </div>
      <div class="situation-action">
        <el-button link @click="fetchData">
          <el-icon><Refresh /></el-icon>
          刷新数据
        </el-button>
      </div>
    </section>

    <section class="host-filter-panel" aria-label="主机筛选">
      <div class="panel-heading filter-heading">
        <div>
          <span class="section-kicker">FILTER / CONNECTION</span>
          <h2>筛选主机</h2>
        </div>
        <span class="filter-hint">按名称、地址或连接状态定位资产</span>
      </div>
      <el-form class="host-filter-form" :model="searchForm" inline>
        <el-form-item label="主机名称">
          <el-input
            v-model="searchForm.name"
            placeholder="例如：ubuntu-01"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="IP地址">
          <el-input
            v-model="searchForm.ip"
            placeholder="例如：10.0.0.12"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="连接状态">
          <el-select v-model="searchForm.status" placeholder="全部状态" clearable>
            <el-option label="在线" :value="1" />
            <el-option label="离线" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            应用筛选
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon>
            清空
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="host-table-panel" aria-label="主机清单">
      <div class="panel-heading table-heading">
        <div>
          <span class="section-kicker">MANAGED HOSTS</span>
          <h2>主机清单</h2>
          <p>{{ pagination.total }} 个纳管资产 · 连接与资源状态实时可见</p>
        </div>
        <div class="table-actions">
          <el-button @click="fetchData">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            新增主机
          </el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        class="host-table"
        :data="tableData"
        style="width: 100%"
        :row-class-name="tableRowClassName"
      >
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <div class="host-status-cell" :class="row.status === 1 ? 'is-online' : 'is-offline'">
              <span class="status-dot" aria-hidden="true"></span>
              <span>{{ row.status === 1 ? "在线" : "离线" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="主机" min-width="170">
          <template #default="{ row }">
            <div class="host-identity">
              <span class="host-icon"><el-icon><Monitor /></el-icon></span>
              <div>
                <button class="host-name-button" type="button" @click="handleEdit(row)">
                  {{ row.name }}
                </button>
                <div class="host-tags">
                  <el-tag v-if="row.isLanHost" size="small" type="success" effect="plain">局域网</el-tag>
                  <el-tag v-if="row.osType || row.os" size="small" type="info" effect="plain">{{ row.osType || row.os }}</el-tag>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="连接" min-width="150">
          <template #default="{ row }">
            <div class="connection-cell">
              <strong>{{ row.ip }}</strong>
              <span>SSH {{ row.sshPort }} · {{ row.sshUsername || "root" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="资源水位" min-width="220">
          <template #default="{ row }">
            <div v-if="row.status === 1 && row.resources" class="resource-stack">
              <div v-for="metric in resourceMetrics" :key="metric.key" class="resource-line" :class="`is-${getResourceTone(getResourceUsage(row, metric.key))}`">
                <span>{{ metric.label }}</span>
                <el-progress
                  :percentage="getResourceUsage(row, metric.key)"
                  :stroke-width="5"
                  :show-text="false"
                  :color="getProgressColor(getResourceUsage(row, metric.key))"
                />
                <strong>{{ getResourceUsage(row, metric.key) }}%</strong>
              </div>
            </div>
            <span v-else-if="row.status === 0" class="resource-offline">资源不可用 · 主机离线</span>
            <span v-else class="resource-loading"><el-icon class="is-loading"><Loading /></el-icon> 正在同步</span>
          </template>
        </el-table-column>
        <el-table-column label="环境" min-width="130">
          <template #default="{ row }">
            <div class="host-context">
              <strong>{{ row.os || row.osType || "Linux 主机" }}</strong>
              <span>{{ row.remark || (row.isLanHost ? "局域网接入" : "远程接入") }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <div class="host-actions">
              <el-button link size="small" @click="handleDetail(row)">详情</el-button>
              <el-button link size="small" :disabled="row.status !== 1" @click="handleTerminal(row)">
                <el-icon><Monitor /></el-icon>
                终端
              </el-button>
              <el-button link size="small" @click="handleTest(row)">测试连接</el-button>
              <el-button link size="small" class="hosts-action" :disabled="row.status !== 1" @click="handleRefreshHosts(row)">hosts</el-button>
              <el-button link size="small" @click="handleEdit(row)">编辑</el-button>
              <el-button link size="small" type="danger" @click="handleDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="host-empty-state">
            <el-icon><Monitor /></el-icon>
            <strong>暂无匹配主机</strong>
            <span>调整筛选条件或新增一台纳管主机</span>
          </div>
        </template>
      </el-table>

      <div class="table-footer">
        <span class="table-footer-note"><i class="live-pulse" aria-hidden="true"></i> 资源状态已接入</span>
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </section>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogType === 'add' ? '新增主机' : '编辑主机'"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="hostForm"
        :rules="hostRules"
        label-width="100px"
      >
        <el-form-item label="主机名称" prop="name">
          <el-input
            v-model="hostForm.name"
            placeholder="请输入主机名称"
            maxlength="50"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="IP地址" prop="ip">
          <el-input v-model="hostForm.ip" placeholder="请输入IP地址" />
        </el-form-item>
        <el-form-item label="局域网主机" prop="isLanHost">
          <el-switch
            v-model="hostForm.isLanHost"
            active-text="是（平台可代劳推送补丁）"
            inactive-text="否"
          />
          <div class="form-tip">
            勾选后，平台可向该主机跨网代劳下载/解压/推送补丁（含容器场景）；
            不勾选时，目标主机必须能自治（curl/wget
            +解压工具齐全），否则补丁安装将报错。详见 ADR-0004。
          </div>
        </el-form-item>
        <el-form-item label="SSH端口" prop="sshPort">
          <el-input-number
            v-model="hostForm.sshPort"
            :min="1"
            :max="65535"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="用户名" prop="sshUsername">
          <el-input
            v-model="hostForm.sshUsername"
            placeholder="请输入用户名"
            maxlength="50"
          />
        </el-form-item>
        <el-form-item label="认证方式" prop="authType">
          <el-radio-group v-model="hostForm.authType">
            <el-radio value="password">密码</el-radio>
            <el-radio value="key">SSH密钥</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          v-if="hostForm.authType === 'password'"
          label="密码"
          prop="sshPassword"
        >
          <el-input
            v-model="hostForm.sshPassword"
            type="password"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>
        <el-form-item v-else label="SSH私钥" prop="sshPrivateKey">
          <el-input
            v-model="hostForm.sshPrivateKey"
            type="textarea"
            :rows="6"
            placeholder="请粘贴SSH私钥内容"
          />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="hostForm.remark"
            type="textarea"
            :rows="3"
            placeholder="请输入备注（可选）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="testLoading" @click="handleTestConnection">
            测试连接
          </el-button>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="submitLoading"
            @click="handleSubmit"
          >
            确定
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- hosts 刷新弹窗 -->
    <el-dialog
      v-model="hostsDialogVisible"
      title="刷新宿主机 hosts（反向代理）"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div v-loading="hostsPreviewLoading" class="hosts-refresh-content">
        <template v-if="hostsPreview">
          <div class="hosts-info-row">
            <span class="label">宿主机 IP：</span>
            <span class="value">{{ hostsPreview.hostLanIp }}</span>
          </div>
          <div class="hosts-info-row">
            <span class="label">主机名：</span>
            <span class="value">{{ hostsPreview.hostname }}</span>
          </div>

          <el-divider content-position="left">
            待改域名清单（{{ hostsPreview.domainsToRefresh?.length || 0 }} 个）
          </el-divider>

          <div v-if="hostsPreview.domainsToRefresh?.length > 0">
            <p class="hosts-tip">
              检测到以下域名指向 127.0.0.1，勾选后将被改为
              <strong>{{ hostsPreview.hostLanIp }}</strong
              >。若 /etc/hosts 中包含广告屏蔽条目（如 StevenBlack/hosts），请只勾选需要反向代理的域名。
            </p>

            <div class="domains-toolbar">
              <el-checkbox
                :model-value="hostsAllSelected"
                @change="toggleAllHostsDomains"
              >
                全选
              </el-checkbox>
              <span class="selected-count">
                已选 {{ hostsSelectedDomains.length }} /
                {{ hostsPreview.domainsToRefresh.length }}
              </span>
              <el-input
                v-model="hostsDomainFilter"
                placeholder="过滤域名"
                size="small"
                clearable
                class="domain-filter"
              />
            </div>

            <el-checkbox-group
              v-model="hostsSelectedDomains"
              class="domains-list"
            >
              <el-checkbox
                v-for="d in hostsFilteredDomains"
                :key="d"
                :value="d"
                :label="d"
                class="domain-item"
              >
                {{ d }}
              </el-checkbox>
            </el-checkbox-group>
          </div>
          <div v-else>
            <el-alert
              title="无需刷新，hosts 文件已是目标状态"
              type="success"
              :closable="false"
              show-icon
            />
          </div>

          <el-divider content-position="left"> sudo 权限 </el-divider>

          <div class="sudo-status">
            <el-tag
              v-if="hostsPreview.sudoAvailable"
              type="success"
              >免密 sudo 可用</el-tag
            >
            <el-tag v-else type="warning">需要 sudo 密码</el-tag>
          </div>

          <el-form-item
            v-if="hostsPreview.needsSudoPassword"
            label="sudo 密码"
            label-width="90px"
            class="sudo-pwd-form"
          >
            <el-input
              v-model="hostsSudoPassword"
              type="password"
              placeholder="请输入 sudo 密码"
              show-password
            />
          </el-form-item>

          <el-alert
            v-if="hostsSelectedDomains.length > 0"
            type="warning"
            :closable="false"
            show-icon
            class="backup-tip"
          >
            <template #title>
              将备份原 /etc/hosts 到 /etc/hosts.bak.{timestamp}，并刷新 DNS
              缓存（resolvectl/systemd-resolve/nscd）
            </template>
          </el-alert>
        </template>
      </div>
      <template #footer>
        <el-button @click="hostsDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="hostsRefreshLoading"
          :disabled="
            !hostsPreview ||
            !hostsPreview.domainsToRefresh?.length ||
            hostsSelectedDomains.length === 0 ||
            (hostsPreview.needsSudoPassword && !hostsSudoPassword)
          "
          @click="confirmRefreshHosts"
        >
          确认刷新（{{ hostsSelectedDomains.length }} 个）
        </el-button>
      </template>
    </el-dialog>

    <!-- 删除确认弹窗 -->
    <el-dialog
      v-model="deleteDialogVisible"
      title="删除确认"
      width="420px"
      :close-on-click-modal="false"
    >
      <div class="delete-confirm-content">
        <el-icon class="warning-icon" :size="32" color="#E6A23C"
          ><Warning
        /></el-icon>
        <p class="confirm-text">
          确定要删除主机「{{ deleteTarget?.name }}」吗？
        </p>
        <p class="confirm-desc">
          删除后将移除该主机下所有实例的管理权限，实例不会被卸载。此操作不可恢复。
        </p>
        <div class="confirm-input">
          <p>请输入主机名称以确认删除：</p>
          <el-input v-model="deleteConfirmName" placeholder="请输入主机名称" />
        </div>
      </div>
      <template #footer>
        <el-button @click="deleteDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="deleteConfirmName !== deleteTarget?.name"
          @click="confirmDelete"
        >
          确定删除
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.host-container {
  .search-card {
    margin-bottom: 16px;

    :deep(.el-card__body) {
      padding-bottom: 0;
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

.status-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.host-name-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;

  .host-name {
    font-weight: var(--platform-font-weight-medium);
  }
}

.form-tip {
  font-size: var(--platform-font-size-xs);
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 4px;
}

.resources-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;

  .resource-item {
    display: flex;
    align-items: center;
    gap: 8px;

    .resource-label {
      width: 32px;
      font-size: var(--platform-font-size-xs);
      color: var(--el-text-color-secondary);
    }

    .el-progress {
      flex: 1;
    }
  }
}

.resource-offline {
  color: var(--el-text-color-secondary);
  font-size: var(--platform-font-size-sm);
}

.resource-loading {
  color: var(--el-color-primary);
}

// 表格行样式
:deep(.row-stopped) {
  opacity: 0.7;
}

// 弹窗样式
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.delete-confirm-content {
  text-align: center;

  .warning-icon {
    margin-bottom: 16px;
  }

  .confirm-text {
    font-size: var(--platform-font-size-base);
    color: var(--el-text-color-primary);
    margin-bottom: 8px;
  }

  .confirm-desc {
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-secondary);
    margin-bottom: 16px;
    line-height: 1.6;
  }

  .confirm-input {
    text-align: left;

    p {
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-regular);
      margin-bottom: 8px;
    }
  }
}

// hosts 刷新弹窗
.hosts-refresh-content {
  .hosts-info-row {
    display: flex;
    margin-bottom: 8px;
    font-size: var(--platform-font-size-sm);

    .label {
      width: 80px;
      color: var(--el-text-color-secondary);
    }

    .value {
      color: var(--el-text-color-primary);
      font-weight: var(--platform-font-weight-medium);
    }
  }

  .hosts-tip {
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-regular);
    margin-bottom: 8px;
    line-height: 1.6;
  }

  .domains-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 8px;

    .selected-count {
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-secondary);
    }

    .domain-filter {
      width: 200px;
      margin-left: auto;
    }
  }

  .domains-list {
    background: var(--el-fill-color-lighter);
    border-radius: 4px;
    padding: 12px 16px;
    margin: 0 0 12px 0;
    max-height: 280px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;

    .domain-item {
      margin-right: 0;
      margin-bottom: 4px;
      font-family: monospace;
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-primary);
      line-height: 1.8;

      :deep(.el-checkbox__label) {
        font-family: monospace;
      }
    }
  }

  .sudo-status {
    margin-bottom: 12px;
  }

  .sudo-pwd-form {
    margin-bottom: 12px;
  }

  .backup-tip {
    margin-top: 8px;
  }
}

// 响应式适配
@media screen and (max-width: 1366px) {
  .resources-cell {
    .resource-item {
      .resource-label {
        display: none;
      }
    }
  }
}
</style>

<style lang="scss" scoped>
.host-operations-page {
  --host-gap: 14px;
  padding: 4px 2px 24px;
  color: var(--platform-text-primary);
}

.host-hero,
.host-situation,
.host-filter-panel,
.host-table-panel {
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-1);
}

.host-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  min-height: 138px;
  padding: 24px 26px;
  border-radius: 6px;
  background:
    linear-gradient(115deg, rgba(39, 181, 243, 0.12), transparent 43%),
    var(--platform-surface-1);
  box-shadow: 0 14px 28px rgba(0, 0, 0, 0.14);
}

.section-kicker {
  display: block;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  line-height: 1.4;
}

.hero-copy h1 {
  margin: 8px 0 7px;
  color: var(--platform-text-primary);
  font-size: clamp(24px, 2.4vw, 32px);
  font-weight: 700;
  letter-spacing: -0.03em;
}

.hero-copy p {
  max-width: 620px;
  margin: 0;
  color: var(--platform-text-secondary);
  font-size: 13px;
}

.hero-actions,
.hero-status,
.situation-action,
.panel-heading,
.table-actions,
.host-identity,
.host-status-cell,
.host-actions,
.table-footer,
.filter-heading {
  display: flex;
  align-items: center;
}

.hero-actions {
  gap: 20px;
}

.hero-status {
  gap: 10px;

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 13px;
  }

  small {
    margin-top: 3px;
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.live-pulse,
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--platform-green);
  box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.12);
}

.host-situation {
  display: grid;
  grid-template-columns: minmax(210px, 1.45fr) repeat(4, minmax(88px, 0.65fr)) auto;
  align-items: center;
  gap: 0;
  min-height: 78px;
  margin-top: var(--host-gap);
  padding: 12px 18px;
  border-radius: 5px;
  background: var(--platform-surface-2);
}

.situation-intro {
  display: grid;
  gap: 3px;

  strong {
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 600;
  }

  small {
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.situation-stat {
  display: grid;
  gap: 4px;
  padding: 0 14px;
  border-left: 1px solid var(--platform-line);

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  strong {
    color: var(--platform-text-primary);
    font-size: 17px;
    font-weight: 600;

    &.is-warning {
      color: var(--platform-amber);
    }
  }
}

.situation-action {
  justify-content: flex-end;
  padding-left: 10px;
}

.host-filter-panel,
.host-table-panel {
  margin-top: var(--host-gap);
  border-radius: 5px;
  overflow: hidden;
}

.host-filter-panel {
  padding: 17px 18px 5px;
  background: var(--platform-surface-1);
}

.panel-heading {
  justify-content: space-between;
  gap: 16px;
}

.panel-heading h2 {
  margin: 5px 0 0;
  color: var(--platform-text-primary);
  font-size: 16px;
  font-weight: 650;
}

.filter-hint,
.table-heading p {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.host-filter-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 13px;

  :deep(.el-form-item) {
    margin-right: 0;
    margin-bottom: 10px;
  }

  :deep(.el-form-item__label) {
    padding-bottom: 5px;
    color: var(--platform-text-muted);
    font-size: 11px;
    line-height: 1.3;
  }

  :deep(.el-input),
  :deep(.el-select) {
    width: 190px;
  }

  :deep(.el-select) {
    width: 150px;
  }

  .filter-actions {
    margin-left: auto;
  }
}

.host-table-panel {
  background: var(--platform-surface-1);
}

.table-heading {
  padding: 17px 18px 15px;
  border-bottom: 1px solid var(--platform-line);
}

.table-heading p {
  margin: 5px 0 0;
}

.table-actions {
  gap: 8px;
}

.host-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(39, 181, 243, 0.06);
  --el-table-header-bg-color: rgba(255, 255, 255, 0.015);
  --el-table-border-color: var(--platform-line);
  --el-table-text-color: var(--platform-text-regular);
  --el-table-header-text-color: var(--platform-text-muted);

  :deep(.el-table__inner-wrapper::before) {
    display: none;
  }

  :deep(.el-table__header-wrapper th.el-table__cell) {
    height: 42px;
    background: rgba(255, 255, 255, 0.015);
    color: var(--platform-text-muted);
    font-size: 11px;
    font-weight: 500;
  }

  :deep(.el-table__body-wrapper td.el-table__cell) {
    height: 78px;
    padding: 10px 0;
    border-bottom-color: var(--platform-line);
  }

  :deep(.el-table__body tr:last-child td.el-table__cell) {
    border-bottom: 0;
  }

  :deep(.el-table__body tr.row-stopped) {
    opacity: 0.68;
  }
}

.host-status-cell {
  gap: 8px;
  color: var(--platform-text-secondary);
  font-size: 12px;

  &.is-online {
    color: var(--platform-green);
  }

  &.is-offline {
    color: var(--platform-text-muted);

    .status-dot {
      background: var(--platform-text-muted);
      box-shadow: none;
    }
  }
}

.host-identity {
  gap: 10px;
  min-width: 0;
}

.host-icon {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgba(39, 181, 243, 0.45);
  border-radius: 5px;
  color: var(--platform-cyan);
  background: rgba(39, 181, 243, 0.06);
}

.host-name-button {
  max-width: 145px;
  padding: 0;
  overflow: hidden;
  border: 0;
  color: var(--platform-text-primary);
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;

  &:hover,
  &:focus-visible {
    color: var(--platform-cyan);
    outline: none;
  }
}

.host-tags {
  display: flex;
  gap: 5px;
  margin-top: 5px;

  :deep(.el-tag) {
    height: 18px;
    border-radius: 3px;
    font-size: 10px;
    line-height: 16px;
  }
}

.connection-cell,
.host-context {
  display: grid;
  gap: 4px;
  min-width: 0;

  strong {
    overflow: hidden;
    color: var(--platform-text-primary);
    font-family: var(--el-font-family-mono);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    overflow: hidden;
    color: var(--platform-text-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.host-context {
  strong {
    font-family: inherit;
    font-weight: 550;
  }
}

.resource-stack {
  display: grid;
  gap: 6px;
  min-width: 165px;
}

.resource-line {
  display: grid;
  grid-template-columns: 36px minmax(50px, 1fr) 35px;
  align-items: center;
  gap: 7px;

  > span,
  > strong {
    font-size: 10px;
  }

  > span {
    color: var(--platform-text-muted);
  }

  > strong {
    color: var(--platform-text-secondary);
    font-family: var(--el-font-family-mono);
    font-weight: 500;
    text-align: right;
  }

  :deep(.el-progress-bar__outer) {
    background: var(--platform-surface-3);
  }
}

.resource-offline,
.resource-loading {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.resource-loading {
  color: var(--platform-cyan);
}

.host-actions {
  flex-wrap: wrap;
  gap: 2px 7px;

  :deep(.el-button) {
    margin-left: 0;
    padding: 2px 0;
    color: var(--platform-cyan);
    font-size: 11px;
  }

  :deep(.el-button:hover:not(.is-disabled)) {
    color: var(--platform-text-primary);
  }

  :deep(.el-button.is-disabled) {
    color: var(--platform-text-muted);
  }

  :deep(.hosts-action) {
    color: var(--platform-amber);
  }

  :deep(.el-button--danger) {
    color: var(--platform-red);
  }
}

.table-footer {
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 10px 18px;
  border-top: 1px solid var(--platform-line);
}

.table-footer-note {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--platform-text-muted);
  font-size: 11px;
}

.table-footer-note .live-pulse {
  width: 7px;
  height: 7px;
}

.host-empty-state {
  display: grid;
  justify-items: center;
  gap: 7px;
  padding: 36px 0;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--platform-cyan);
    font-size: 26px;
  }

  strong {
    color: var(--platform-text-primary);
    font-size: 13px;
  }

  span {
    font-size: 11px;
  }
}

@media screen and (max-width: 1180px) {
  .host-situation {
    grid-template-columns: minmax(180px, 1.3fr) repeat(4, minmax(72px, 0.7fr));
  }

  .situation-action {
    display: none;
  }

  .host-filter-form {
    flex-wrap: wrap;

    .filter-actions {
      margin-left: 0;
    }
  }

  .host-table {
    :deep(.el-table__body-wrapper),
    :deep(.el-table__header-wrapper) {
      overflow-x: auto;
    }
  }
}

@media screen and (max-width: 780px) {
  .host-hero,
  .panel-heading,
  .table-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .host-hero {
    gap: 18px;
    padding: 20px;
  }

  .hero-actions {
    width: 100%;
    justify-content: space-between;
  }

  .host-situation {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px 0;
    padding: 16px;
  }

  .situation-intro {
    grid-column: 1 / -1;
  }

  .situation-stat {
    padding: 0 12px;

    &:nth-of-type(2n) {
      border-left: 0;
    }
  }

  .host-filter-panel {
    padding: 16px 14px 4px;
  }

  .filter-hint {
    display: none;
  }

  .host-filter-form {
    display: grid;
    grid-template-columns: 1fr 1fr;

    :deep(.el-input),
    :deep(.el-select) {
      width: 100%;
    }

    .filter-actions {
      grid-column: 1 / -1;
    }
  }

  .table-heading {
    padding: 16px 14px;
  }

  .table-actions {
    width: 100%;
  }
}
</style>
