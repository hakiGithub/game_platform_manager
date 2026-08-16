<script setup>
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getPluginList,
  startPlugin,
  stopPlugin,
  reloadPlugin,
  unloadPlugin,
} from "@/api/plugin";
import { getInstanceList } from "@/api/instance";
import { statusType } from "@/utils/instanceStatus";

const router = useRouter();

const loading = ref(false);
const pluginList = ref([]);
const opLoading = ref({});

const instanceDialogVisible = ref(false);
const instanceLoading = ref(false);
const instanceList = ref([]);
const currentPluginForInstance = ref(null);

const gameLabels = {
  minecraft: "Minecraft",
  l4d2: "Left 4 Dead 2",
  palworld: "幻兽帕鲁",
  rust: "Rust",
};

const pluginStats = computed(() => {
  const total = pluginList.value.length;
  const running = pluginList.value.filter((item) => item.running).length;
  const disabled = pluginList.value.filter((item) => item.state === "DISABLED").length;
  return {
    total,
    running,
    stopped: total - running - disabled,
    disabled,
    entryPoints: pluginList.value.reduce(
      (sum, item) => sum + Number(item.menuCount || item.capabilityCount || 0),
      0
    ),
  };
});

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

function extractGameCode(pluginId) {
  if (!pluginId) return "";
  return pluginId.startsWith("plugin-") ? pluginId.substring("plugin-".length) : "";
}

function getSupportedGames(row) {
  const codes = Array.isArray(row.supportedGames) && row.supportedGames.length
    ? row.supportedGames
    : [extractGameCode(row.pluginId)].filter(Boolean);
  return codes.map((code) => ({ code, label: gameLabels[code] || code.toUpperCase() }));
}

function getEntryPointCount(row) {
  return Number(row.menuCount || row.capabilityCount || 0) || "—";
}

function getPluginStateClass(row) {
  if (row.running) return "is-running";
  if (row.state === "DISABLED") return "is-disabled";
  return "is-stopped";
}

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
    const data = await getInstanceList({ gameCode, current: 1, size: 100 });
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

function handleSelectInstance(instance) {
  if (!instance || !currentPluginForInstance.value) return;
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

async function handleStop(row) {
  try {
    await ElMessageBox.confirm(
      `确定要停止插件「${row.pluginId}」吗？该插件管理的所有功能将不可用。`,
      "停止插件",
      { type: "warning" }
    );
  } catch {
    return;
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

async function handleReload(row) {
  try {
    await ElMessageBox.confirm(
      `确定要重新加载插件「${row.pluginId}」吗？该插件管理的所有功能将短暂不可用。`,
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

async function handleUnload(row) {
  try {
    await ElMessageBox.confirm(
      `确定要卸载插件「${row.pluginId}」吗？卸载后需要重新加载插件资源才能恢复。`,
      "卸载插件",
      { type: "warning" }
    );
  } catch {
    return;
  }
  const key = `unload-${row.pluginId}`;
  opLoading.value[key] = true;
  try {
    await unloadPlugin(row.pluginId);
    ElMessage.success(`插件 ${row.pluginId} 已卸载`);
    await fetchPluginList();
  } catch (error) {
    ElMessage.error(error.message || "卸载失败");
  } finally {
    opLoading.value[key] = false;
  }
}

function getStateType(state) {
  switch (state) {
    case "STARTED":
      return "success";
    case "STOPPED":
      return "warning";
    case "DISABLED":
    case "CREATED":
    case "RESOLVED":
    default:
      return "info";
  }
}

onMounted(fetchPluginList);
</script>

<template>
  <div class="plugin-list-page">
    <section class="plugin-hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="pulse-dot"></span> PF4J EXTENSION CONSOLE</div>
        <h1>插件扩展台</h1>
        <p>把游戏专属能力装配到正确的运行时工作区，集中管理加载状态与扩展入口。</p>
        <div class="hero-meta">
          <span>HOST MODULE / 01</span>
          <span>ROUTING READY</span>
          <span>LAST SYNC · NOW</span>
        </div>
      </div>
      <div class="hero-orbit" aria-hidden="true">
        <div class="orbit-ring orbit-ring--outer"></div>
        <div class="orbit-ring orbit-ring--inner"></div>
        <div class="orbit-core"><el-icon :size="24"><Connection /></el-icon></div>
        <div class="orbit-readout">
          <span>LOADED EXTENSIONS</span>
          <strong>{{ String(pluginStats.total).padStart(2, "0") }}</strong>
          <small>PF4J HOST</small>
        </div>
      </div>
    </section>

    <section class="signal-strip" aria-label="插件运行信号">
      <div class="signal-item">
        <span class="signal-label">LOADED MODULES</span>
        <strong>{{ pluginStats.total }}</strong>
        <small>已加载扩展</small>
      </div>
      <div class="signal-item signal-item--green">
        <span class="signal-label">RUNNING</span>
        <strong>{{ pluginStats.running }}</strong>
        <small>可进入工作区</small>
      </div>
      <div class="signal-item signal-item--amber">
        <span class="signal-label">STANDBY</span>
        <strong>{{ pluginStats.stopped }}</strong>
        <small>等待启用</small>
      </div>
      <div class="signal-item signal-item--blue">
        <span class="signal-label">ENTRY POINTS</span>
        <strong>{{ pluginStats.entryPoints }}</strong>
        <small>扩展入口</small>
      </div>
      <div class="signal-note">
        <el-icon><InfoFilled /></el-icon>
        <span>进入工作区前选择具体游戏实例，路由上下文会自动带入主机与端口信息。</span>
      </div>
    </section>

    <section class="inventory-heading">
      <div>
        <span class="section-kicker">EXTENSION INVENTORY</span>
        <h2>能力模块</h2>
        <p>{{ pluginStats.total }} 个插件已接入主应用运行时，状态与入口在此统一收口。</p>
      </div>
      <el-button class="refresh-button" :loading="loading" @click="fetchPluginList">
        <el-icon><Refresh /></el-icon>
        刷新清单
      </el-button>
    </section>

    <section class="plugin-grid" v-loading="loading">
      <article
        v-for="row in pluginList"
        :key="row.pluginId"
        class="plugin-card"
        :class="getPluginStateClass(row)"
      >
        <div class="plugin-card__head">
          <div class="plugin-mark"><el-icon :size="21"><Connection /></el-icon></div>
          <div class="plugin-identity">
            <span class="plugin-label">PF4J MODULE</span>
            <h3>{{ row.pluginName || row.pluginId }}</h3>
            <code>{{ row.pluginId }}</code>
          </div>
          <el-tag :type="getStateType(row.state)" effect="dark" size="small">
            <span class="status-dot"></span>{{ row.stateDesc || row.state }}
          </el-tag>
        </div>

        <p class="plugin-description">{{ row.description || "暂无插件描述" }}</p>

        <div class="plugin-specs">
          <div>
            <span>VERSION</span>
            <strong>v{{ row.version || "—" }}</strong>
          </div>
          <div>
            <span>PROVIDER</span>
            <strong>{{ row.provider || "—" }}</strong>
          </div>
          <div>
            <span>ENTRY POINTS</span>
            <strong>{{ getEntryPointCount(row) }}</strong>
          </div>
        </div>

        <div class="plugin-route">
          <span>ROUTES TO</span>
          <div class="game-tags">
            <el-tag v-for="game in getSupportedGames(row)" :key="game.code" effect="plain" size="small">
              {{ game.label }}
            </el-tag>
          </div>
        </div>

        <div class="plugin-path">
          <span><el-icon><FolderOpened /></el-icon>{{ row.pluginPath || "runtime/plugins" }}</span>
          <span>{{ row.dependencies || "No external dependencies" }}</span>
        </div>

        <div class="plugin-card__footer">
          <el-button
            class="workspace-button"
            type="primary"
            :disabled="!row.running"
            @click="handleEnterPlugin(row)"
          >
            进入工作区
            <el-icon><Right /></el-icon>
          </el-button>
          <div class="plugin-actions">
            <el-button
              v-if="!row.running"
              class="text-action text-action--green"
              link
              :loading="opLoading[`start-${row.pluginId}`]"
              @click="handleStart(row)"
            >启动</el-button>
            <el-button
              v-else
              class="text-action text-action--amber"
              link
              :loading="opLoading[`stop-${row.pluginId}`]"
              @click="handleStop(row)"
            >停止</el-button>
            <el-button
              class="text-action"
              link
              :loading="opLoading[`reload-${row.pluginId}`]"
              @click="handleReload(row)"
            >重载</el-button>
            <el-button
              class="text-action text-action--red"
              link
              :loading="opLoading[`unload-${row.pluginId}`]"
              @click="handleUnload(row)"
            >卸载</el-button>
          </div>
        </div>
      </article>

      <div v-if="!loading && pluginList.length === 0" class="plugin-empty">
        <div class="empty-mark"><el-icon :size="26"><Connection /></el-icon></div>
        <strong>暂无已加载插件</strong>
        <span>请确认 PF4J 插件目录与运行时配置后刷新清单。</span>
      </div>
    </section>

    <el-dialog
      v-model="instanceDialogVisible"
      class="plugin-instance-dialog"
      width="760px"
      destroy-on-close
    >
      <template #header>
        <div class="dialog-heading">
          <span class="section-kicker">INSTANCE ROUTING</span>
          <strong>选择管理目标</strong>
          <small>将插件能力绑定到一个正在纳管的游戏实例。</small>
        </div>
      </template>

      <div v-if="currentPluginForInstance" class="dialog-context">
        <div class="dialog-context__mark"><el-icon><Connection /></el-icon></div>
        <div>
          <span>ACTIVE EXTENSION</span>
          <strong>{{ currentPluginForInstance.row.pluginName }}</strong>
        </div>
        <code>{{ currentPluginForInstance.row.pluginId }}</code>
      </div>

      <el-alert
        v-if="currentPluginForInstance"
        class="dialog-alert"
        type="info"
        :closable="false"
        show-icon
      >
        <template #title>
          请选择要使用「{{ currentPluginForInstance.row.pluginName }}」管理的实例
        </template>
      </el-alert>

      <el-table
        class="instance-table"
        :data="instanceList"
        v-loading="instanceLoading"
        stripe
        max-height="400"
        empty-text="暂无对应游戏实例，请先在实例管理中部署"
        @row-dblclick="handleSelectInstance"
      >
        <el-table-column prop="instanceName" label="实例名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="hostName" label="主机" width="120" show-overflow-tooltip />
        <el-table-column prop="hostIp" label="主机 IP" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.runStatusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click="handleSelectInstance(row)">
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
  min-height: 100%;
  padding: 24px 28px 40px;
  color: var(--platform-text-primary);
  background:
    radial-gradient(circle at 88% 3%, rgba(0, 183, 255, 0.1), transparent 27%),
    linear-gradient(180deg, rgba(8, 20, 32, 0.98), rgba(7, 17, 28, 0.98));
}

.plugin-hero {
  display: flex;
  min-height: 208px;
  align-items: center;
  justify-content: space-between;
  gap: 36px;
  padding: 28px 36px;
  overflow: hidden;
  border: 1px solid rgba(83, 132, 163, 0.28);
  background:
    linear-gradient(115deg, rgba(22, 43, 58, 0.96), rgba(11, 27, 42, 0.82)),
    radial-gradient(circle at 92% 30%, rgba(0, 194, 255, 0.22), transparent 34%);
  box-shadow: 0 16px 42px rgba(0, 0, 0, 0.22);
  position: relative;
}

.plugin-hero::after {
  position: absolute;
  right: -80px;
  bottom: -110px;
  width: 330px;
  height: 220px;
  border: 1px solid rgba(0, 184, 255, 0.12);
  border-radius: 50%;
  content: "";
  transform: rotate(-18deg);
}

.hero-copy {
  max-width: 690px;
  position: relative;
  z-index: 1;
}

.eyebrow,
.section-kicker,
.plugin-label,
.signal-label,
.dialog-context span {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #8fb7c9;
}

.pulse-dot,
.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--platform-green);
  box-shadow: 0 0 0 4px rgba(48, 207, 116, 0.12), 0 0 14px rgba(48, 207, 116, 0.8);
}

.plugin-hero h1 {
  margin: 15px 0 9px;
  color: #f1f7fb;
  font-size: clamp(28px, 3vw, 40px);
  font-weight: 650;
  letter-spacing: -0.03em;
}

.hero-copy p {
  margin: 0;
  color: #9eb4c1;
  font-size: 14px;
  line-height: 1.8;
}

.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 22px;
  margin-top: 24px;
  color: #688391;
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.09em;
}

.hero-orbit {
  width: 220px;
  height: 150px;
  flex: 0 0 220px;
  position: relative;
  z-index: 1;
}

.orbit-ring {
  position: absolute;
  border: 1px solid rgba(0, 185, 255, 0.25);
  border-radius: 50%;
  transform: rotate(-18deg);
}

.orbit-ring--outer { inset: 0 4px 14px 18px; }
.orbit-ring--inner { inset: 18px 28px 31px 43px; border-color: rgba(83, 206, 255, 0.34); }

.orbit-core {
  position: absolute;
  top: 48px;
  left: 88px;
  display: grid;
  width: 56px;
  height: 56px;
  place-items: center;
  border: 1px solid rgba(93, 211, 255, 0.75);
  border-radius: 18px;
  color: #8ae4ff;
  background: rgba(17, 69, 91, 0.76);
  box-shadow: 0 0 30px rgba(0, 184, 255, 0.2);
}

.orbit-readout {
  position: absolute;
  top: 13px;
  right: -7px;
  display: grid;
  gap: 3px;
  text-align: right;
}

.orbit-readout span,
.orbit-readout small {
  color: #7597a5;
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.12em;
}

.orbit-readout strong {
  color: #e2f8ff;
  font-family: var(--el-font-family-mono);
  font-size: 32px;
  font-weight: 500;
}

.signal-strip {
  display: grid;
  grid-template-columns: 0.9fr 0.9fr 0.9fr 0.9fr 2.2fr;
  margin-top: 16px;
  border: 1px solid var(--platform-line);
  background: rgba(13, 30, 44, 0.88);
}

.signal-item,
.signal-note {
  min-height: 88px;
  padding: 17px 20px;
  border-right: 1px solid rgba(98, 136, 157, 0.16);
}

.signal-item { display: grid; align-content: center; gap: 4px; }

.signal-item strong {
  color: #dcecf2;
  font-family: var(--el-font-family-mono);
  font-size: 25px;
  font-weight: 500;
}

.signal-item small { color: var(--platform-text-muted); font-size: 11px; }
.signal-item--green strong { color: var(--platform-green); }
.signal-item--amber strong { color: var(--platform-amber); }
.signal-item--blue strong { color: var(--platform-accent); }

.signal-note {
  display: flex;
  align-items: center;
  gap: 12px;
  border-right: 0;
  color: #8ca7b5;
  font-size: 12px;
  line-height: 1.7;
}

.signal-note .el-icon { color: var(--platform-accent); }

.inventory-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin: 32px 0 16px;
}

.inventory-heading h2 { margin: 7px 0 4px; color: #eaf5f8; font-size: 22px; font-weight: 600; }
.inventory-heading p { margin: 0; color: var(--platform-text-muted); font-size: 12px; }

.refresh-button {
  height: 36px;
  border-color: rgba(82, 155, 182, 0.4) !important;
  color: #9fdcf0 !important;
  background: rgba(20, 54, 70, 0.55) !important;
}

.refresh-button:hover {
  border-color: var(--platform-accent) !important;
  color: #d8f8ff !important;
  background: rgba(20, 89, 113, 0.65) !important;
}

.plugin-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  min-height: 210px;
  position: relative;
}

.plugin-card {
  display: flex;
  min-height: 330px;
  flex-direction: column;
  padding: 21px 22px 18px;
  border: 1px solid rgba(83, 132, 163, 0.28);
  background: linear-gradient(145deg, rgba(17, 38, 53, 0.98), rgba(10, 25, 38, 0.98));
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.15);
  transition: border-color 180ms ease, transform 180ms ease;
}

.plugin-card:hover { border-color: rgba(43, 189, 237, 0.65); transform: translateY(-2px); }
.plugin-card.is-running { border-top: 2px solid rgba(48, 207, 116, 0.74); }
.plugin-card.is-stopped { border-top: 2px solid rgba(245, 178, 66, 0.7); }
.plugin-card.is-disabled { border-top: 2px solid rgba(136, 159, 171, 0.65); }

.plugin-card__head { display: flex; align-items: flex-start; gap: 13px; }

.plugin-mark {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border: 1px solid rgba(75, 183, 220, 0.35);
  border-radius: 12px;
  color: #8dddf3;
  background: rgba(24, 79, 101, 0.48);
}

.plugin-identity { min-width: 0; flex: 1; }

.plugin-identity h3 {
  overflow: hidden;
  margin: 5px 0 3px;
  color: #e6f2f5;
  font-size: 17px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plugin-identity code,
.plugin-card code,
.dialog-context code {
  color: #6f9aab;
  font-family: var(--el-font-family-mono);
  font-size: 10px;
}

.plugin-card__head :deep(.el-tag) { border: 0; border-radius: 999px; background: rgba(50, 185, 111, 0.15); color: #77e4a1; }
.plugin-card.is-stopped .plugin-card__head :deep(.el-tag) { background: rgba(245, 178, 66, 0.14); color: #f4c776; }
.plugin-card.is-disabled .plugin-card__head :deep(.el-tag) { background: rgba(122, 143, 154, 0.15); color: #a2b2ba; }

.status-dot {
  width: 5px;
  height: 5px;
  margin-right: 5px;
  box-shadow: none;
}

.plugin-card.is-stopped .status-dot { background: var(--platform-amber); }
.plugin-card.is-disabled .status-dot { background: #8c9ca5; }

.plugin-description { min-height: 43px; margin: 19px 0 17px; color: #92a9b5; font-size: 12px; line-height: 1.8; }

.plugin-specs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding: 13px 0;
  border-top: 1px solid rgba(90, 128, 146, 0.16);
  border-bottom: 1px solid rgba(90, 128, 146, 0.16);
}

.plugin-specs div { display: grid; min-width: 0; gap: 5px; }

.plugin-specs span,
.plugin-route > span,
.plugin-path span {
  color: #658493;
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.08em;
}

.plugin-specs strong { overflow: hidden; color: #d6e7ec; font-size: 12px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }

.plugin-route { display: flex; min-height: 47px; align-items: center; gap: 14px; }
.game-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.game-tags :deep(.el-tag) { border-color: rgba(57, 177, 220, 0.3); color: #8ed4e8; background: rgba(29, 87, 108, 0.2); }

.plugin-path { display: flex; justify-content: space-between; gap: 12px; padding: 9px 0 12px; border-top: 1px solid rgba(90, 128, 146, 0.1); }

.plugin-path span { display: inline-flex; min-width: 0; align-items: center; gap: 5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plugin-path span:last-child { color: #6c8997; text-align: right; }

.plugin-card__footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: auto; padding-top: 13px; border-top: 1px solid rgba(90, 128, 146, 0.16); }

.workspace-button { min-width: 130px; border-color: rgba(29, 181, 229, 0.65) !important; color: #a8ebff !important; background: rgba(18, 85, 108, 0.58) !important; }
.workspace-button:hover:not(.is-disabled) { border-color: #42d4ff !important; color: #e2fbff !important; background: rgba(20, 111, 139, 0.8) !important; }
.workspace-button.is-disabled { opacity: 0.4; }

.plugin-actions { display: flex; align-items: center; gap: 3px; }
.text-action { color: #85b8c8 !important; }
.text-action:hover { color: #d6f7ff !important; }
.text-action--green { color: #73dfa0 !important; }
.text-action--amber { color: #edc276 !important; }
.text-action--red { color: #de8585 !important; }

.plugin-empty {
  display: grid;
  min-height: 220px;
  grid-column: 1 / -1;
  place-items: center;
  align-content: center;
  gap: 8px;
  border: 1px dashed rgba(90, 132, 150, 0.35);
  color: var(--platform-text-muted);
}

.empty-mark { display: grid; width: 54px; height: 54px; margin-bottom: 5px; place-items: center; border: 1px solid rgba(75, 183, 220, 0.28); border-radius: 50%; color: #77bad0; background: rgba(24, 79, 101, 0.3); }
.plugin-empty strong { color: #b4cbd3; font-size: 14px; font-weight: 500; }
.plugin-empty span { font-size: 12px; }

:deep(.plugin-instance-dialog) { border: 1px solid rgba(78, 142, 167, 0.42); background: #102636; }
:deep(.plugin-instance-dialog .el-dialog__header) { margin-right: 0; padding: 22px 24px 17px; border-bottom: 1px solid rgba(92, 132, 150, 0.18); }
:deep(.plugin-instance-dialog .el-dialog__body) { padding: 19px 24px 12px; }
:deep(.plugin-instance-dialog .el-dialog__footer) { padding: 12px 24px 19px; border-top: 1px solid rgba(92, 132, 150, 0.18); }

.dialog-heading { display: grid; gap: 6px; }
.dialog-heading strong { color: #e9f7fa; font-size: 19px; font-weight: 600; }
.dialog-heading small { color: #8ca8b4; font-size: 12px; }

.dialog-context { display: flex; align-items: center; gap: 11px; margin-bottom: 14px; padding: 12px 14px; border: 1px solid rgba(78, 155, 182, 0.27); background: rgba(19, 65, 83, 0.3); }
.dialog-context__mark { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 9px; color: #80dafa; background: rgba(26, 112, 145, 0.35); }
.dialog-context div:nth-child(2) { display: grid; gap: 3px; flex: 1; }
.dialog-context strong { color: #d4edf3; font-size: 13px; font-weight: 500; }
.dialog-context code { color: #77afc1; }
.dialog-alert { margin-bottom: 14px; }

.instance-table { overflow: hidden; border: 1px solid rgba(86, 131, 150, 0.23); }

@media (max-width: 1100px) {
  .signal-strip { grid-template-columns: repeat(4, 1fr); }
  .signal-note { grid-column: 1 / -1; border-top: 1px solid rgba(98, 136, 157, 0.16); }
}

@media (max-width: 820px) {
  .plugin-list-page { padding: 16px; }
  .plugin-hero { min-height: 0; padding: 24px; }
  .hero-orbit { display: none; }
  .plugin-grid { grid-template-columns: 1fr; }
}

@media (max-width: 560px) {
  .signal-strip { grid-template-columns: repeat(2, 1fr); }
  .signal-item { border-bottom: 1px solid rgba(98, 136, 157, 0.16); }
  .inventory-heading { align-items: flex-start; flex-direction: column; }
  .plugin-card__footer { align-items: flex-start; flex-direction: column; }
  .plugin-actions { width: 100%; justify-content: space-between; }
}
</style>
