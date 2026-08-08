<script setup>
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getPluginList,
  startPlugin,
  stopPlugin,
  reloadPlugin,
} from "@/api/plugin";
import { getInstanceList } from "@/api/instance";

const router = useRouter();

// 加载状态
const loading = ref(false);

// 插件列表
const pluginList = ref([]);

// 操作加载状态映射
const opLoading = ref({});

// 实例选择对话框
const instanceDialogVisible = ref(false);
const instanceLoading = ref(false);
const instanceList = ref([]);
const currentPluginForInstance = ref(null);

/**
 * 获取插件列表
 */
async function fetchPluginList() {
  loading.value = true;
  try {
    const data = await getPluginList();
    pluginList.value = Array.isArray(data) ? data : [];
  } catch (error) {
    console.error("Failed to fetch plugin list:", error);
    ElMessage.error("获取插件列表失败");
    pluginList.value = [];
  } finally {
    loading.value = false;
  }
}

/**
 * 从插件ID提取游戏编码
 */
function extractGameCode(pluginId) {
  if (!pluginId) return "";
  if (pluginId.startsWith("plugin-")) {
    return pluginId.substring("plugin-".length);
  }
  return "";
}

/**
 * 点击"进入管理"：打开实例选择对话框
 */
async function handleEnterPlugin(row) {
  if (!row.running) {
    ElMessage.warning("插件未运行，请先启动");
    return;
  }
  const gameCode = extractGameCode(row.pluginId);
  if (!gameCode) {
    ElMessage.error("无法识别插件游戏编码");
    return;
  }
  currentPluginForInstance.value = { row, gameCode };
  instanceDialogVisible.value = true;
  instanceLoading.value = true;
  instanceList.value = [];
  try {
    const data = await getInstanceList({
      gameCode: gameCode,
      current: 1,
      size: 100,
    });
    instanceList.value = data?.records || [];
    if (instanceList.value.length === 0) {
      ElMessage.warning(`未找到游戏编码 ${gameCode} 的实例，请先部署对应游戏`);
    }
  } catch (error) {
    ElMessage.error("获取实例列表失败：" + (error.message || ""));
  } finally {
    instanceLoading.value = false;
  }
}

/**
 * 选择实例后跳转到插件管理页面
 */
function handleSelectInstance(instance) {
  if (!instance || !currentPluginForInstance.value) {
    return;
  }
  const gameCode = currentPluginForInstance.value.gameCode;
  instanceDialogVisible.value = false;
  router.push({
    path: `/plugin/${gameCode}`,
    query: {
      instanceId: instance.id,
      instanceName: instance.instanceName || "",
      hostId: instance.hostId || 0,
      hostIp: instance.hostIp || "",
      deployPath: instance.installPath || "",
      ports: instance.portConfig ? JSON.stringify(instance.portConfig) : "{}",
    },
  });
}

/**
 * 启动插件
 */
async function handleStart(row) {
  const key = `start-${row.pluginId}`;
  opLoading.value[key] = true;
  try {
    await startPlugin(row.pluginId);
    ElMessage.success(`插件 ${row.pluginId} 启动成功`);
    await fetchPluginList();
  } catch (error) {
    ElMessage.error(error.message || "启动失败");
  } finally {
    opLoading.value[key] = false;
  }
}

/**
 * 停止插件
 */
async function handleStop(row) {
  try {
    await ElMessageBox.confirm(
      `确定要停止插件「${row.pluginId}」吗？该插件管理的所有功能将不可用。`,
      "停止插件",
      { type: "warning" }
    );
  } catch {
    return; // 用户取消
  }
  const key = `stop-${row.pluginId}`;
  opLoading.value[key] = true;
  try {
    await stopPlugin(row.pluginId);
    ElMessage.success(`插件 ${row.pluginId} 已停止`);
    await fetchPluginList();
  } catch (error) {
    ElMessage.error(error.message || "停止失败");
  } finally {
    opLoading.value[key] = false;
  }
}

/**
 * 重新加载插件
 */
async function handleReload(row) {
  try {
    await ElMessageBox.confirm(
      `确定要重新加载插件「${row.pluginId}」吗？该操作会重建插件 Spring 子容器，期间相关功能将短暂不可用。`,
      "重新加载插件",
      { type: "warning" }
    );
  } catch {
    return;
  }
  const key = `reload-${row.pluginId}`;
  opLoading.value[key] = true;
  try {
    await reloadPlugin(row.pluginId);
    ElMessage.success(`插件 ${row.pluginId} 已重新加载`);
    await fetchPluginList();
  } catch (error) {
    ElMessage.error(error.message || "重新加载失败");
  } finally {
    opLoading.value[key] = false;
  }
}

/**
 * 获取状态标签类型
 */
function getStateType(state) {
  switch (state) {
    case "STARTED":
      return "success";
    case "STOPPED":
      return "warning";
    case "DISABLED":
      return "info";
    case "CREATED":
      return "info";
    case "RESOLVED":
      return "info";
    default:
      return "info";
  }
}

/**
 * 获取实例运行状态标签类型
 */
function getInstanceStatusType(runStatus) {
  switch (runStatus) {
    case 1:
      return "success";
    case 0:
      return "info";
    case 2:
      return "danger";
    case 5:
      return "warning";
    default:
      return "info";
  }
}

/**
 * 获取实例运行状态文本
 */
function getInstanceStatusText(runStatus) {
  switch (runStatus) {
    case 1:
      return "运行中";
    case 0:
      return "已停止";
    case 2:
      return "异常";
    case 5:
      return "部署中";
    default:
      return "未知";
  }
}

onMounted(() => {
  fetchPluginList();
});
</script>

<template>
  <div class="plugin-list-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-icon><Connection /></el-icon>
            <span>插件管理</span>
          </div>
          <el-button type="primary" :icon="'Refresh'" @click="fetchPluginList" :loading="loading">
            刷新
          </el-button>
        </div>
      </template>

      <el-alert
        class="info-alert"
        type="info"
        :closable="false"
        show-icon
      >
        <template #title>
          已加载的插件列表。点击"进入管理"打开插件管理页面；启动/停止/重载操作会立即生效。
        </template>
      </el-alert>

      <el-table
        :data="pluginList"
        v-loading="loading"
        stripe
        table-layout="fixed"
        style="margin-top: 16px"
        empty-text="暂无已加载插件"
      >
        <el-table-column prop="pluginId" label="插件ID" width="160" show-overflow-tooltip />
        <el-table-column prop="pluginName" label="插件名称" width="160" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="90">
          <template #default="{ row }">
            <el-tag size="small">v{{ row.version }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="provider" label="提供者" width="110" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStateType(row.state)" size="small">
              {{ row.stateDesc || row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="300" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              type="primary"
              size="small"
              link
              :disabled="!row.running"
              @click="handleEnterPlugin(row)"
            >
              进入管理
            </el-button>
            <el-button
              v-if="!row.running"
              type="success"
              size="small"
              link
              :loading="opLoading[`start-${row.pluginId}`]"
              @click="handleStart(row)"
            >
              启动
            </el-button>
            <el-button
              v-else
              type="warning"
              size="small"
              link
              :loading="opLoading[`stop-${row.pluginId}`]"
              @click="handleStop(row)"
            >
              停止
            </el-button>
            <el-button
              type="primary"
              size="small"
              link
              :loading="opLoading[`reload-${row.pluginId}`]"
              @click="handleReload(row)"
            >
              重载
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 实例选择对话框 -->
    <el-dialog
      v-model="instanceDialogVisible"
      title="选择要管理的实例"
      width="720px"
      destroy-on-close
    >
      <el-alert
        v-if="currentPluginForInstance"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      >
        <template #title>
          请选择要使用「{{ currentPluginForInstance.row.pluginName }}」管理的实例
        </template>
      </el-alert>

      <el-table
        :data="instanceList"
        v-loading="instanceLoading"
        stripe
        max-height="400"
        empty-text="暂无对应游戏实例，请先在实例管理中部署"
        @row-dblclick="handleSelectInstance"
      >
        <el-table-column prop="instanceName" label="实例名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="hostName" label="主机" width="120" show-overflow-tooltip />
        <el-table-column prop="hostIp" label="主机IP" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getInstanceStatusType(row.runStatus)" size="small">
              {{ getInstanceStatusText(row.runStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="installPath" label="部署路径" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              size="small"
              link
              @click="handleSelectInstance(row)"
            >
              进入管理
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="instanceDialogVisible = false">取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.plugin-list-page {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .header-left {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 16px;
      font-weight: 600;
    }
  }

  .info-alert {
    margin-top: 0;
  }
}
</style>
