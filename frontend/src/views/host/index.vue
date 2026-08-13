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
    loadResourcesForOnlineHosts();
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
  <div class="host-container">
    <!-- 搜索区域 -->
    <el-card class="search-card" shadow="never">
      <el-form :model="searchForm" inline>
        <el-form-item label="主机名称">
          <el-input
            v-model="searchForm.name"
            placeholder="主机名称"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="IP地址">
          <el-input
            v-model="searchForm.ip"
            placeholder="IP地址"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="searchForm.status"
            placeholder="全部"
            clearable
            style="width: 120px"
          >
            <el-option label="在线" :value="1" />
            <el-option label="离线" :value="0" />
          </el-select>
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
    </el-card>

    <!-- 表格区域 -->
    <el-card class="table-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">主机列表</span>
          <div class="header-actions">
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
      </template>

      <el-table
        v-loading="loading"
        :data="tableData"
        style="width: 100%"
        :row-class-name="tableRowClassName"
      >
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <div class="status-cell">
              <el-icon :color="getStatusColor(row.status)" :size="16">
                <component :is="getStatusIcon(row.status)" />
              </el-icon>
              <span :style="{ color: getStatusColor(row.status) }">
                {{ row.status === 1 ? "在线" : "离线" }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="主机名称" min-width="180">
          <template #default="{ row }">
            <div class="host-name-cell">
              <span class="host-name">{{ row.name }}</span>
              <el-tag
                v-if="row.isLanHost"
                size="small"
                type="success"
                effect="plain"
                >局域网</el-tag
              >
              <el-tag
                v-if="row.osType"
                size="small"
                type="info"
                effect="plain"
                >{{ row.osType }}</el-tag
              >
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP地址" width="140" />
        <el-table-column prop="sshPort" label="SSH端口" width="100" />
        <el-table-column label="资源使用率" min-width="200">
          <template #default="{ row }">
            <div
              v-if="row.status === 1 && row.resources"
              class="resources-cell"
            >
              <div class="resource-item">
                <span class="resource-label">CPU</span>
                <el-progress
                  :percentage="formatUsage(row.resources.cpu?.usage)"
                  :stroke-width="6"
                  :color="getProgressColor(formatUsage(row.resources.cpu?.usage))"
                />
              </div>
              <div class="resource-item">
                <span class="resource-label">内存</span>
                <el-progress
                  :percentage="formatUsage(row.resources.memory?.usage)"
                  :stroke-width="6"
                  :color="getProgressColor(formatUsage(row.resources.memory?.usage))"
                />
              </div>
              <div class="resource-item">
                <span class="resource-label">磁盘</span>
                <el-progress
                  :percentage="formatUsage(row.resources.disk?.usage)"
                  :stroke-width="6"
                  :color="getProgressColor(formatUsage(row.resources.disk?.usage))"
                />
              </div>
            </div>
            <span v-else-if="row.status === 0" class="resource-offline"
              >离线</span
            >
            <span v-else class="resource-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              size="small"
              :disabled="row.status !== 1"
              @click="handleTerminal(row)"
            >
              <el-icon><Monitor /></el-icon>
              终端
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="handleTest(row)"
            >
              测试
            </el-button>
            <el-button
              type="warning"
              link
              size="small"
              :disabled="row.status !== 1"
              @click="handleRefreshHosts(row)"
            >
              hosts
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="handleEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              type="danger"
              link
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
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
    </el-card>

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
