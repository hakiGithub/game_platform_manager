<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import {
  getHostDetail,
  getHostResources,
  getHostStatus,
  testHostConnection,
} from "@/api/host";

const route = useRoute();
const router = useRouter();
const hostId = computed(() => Number(route.params.id));

const loading = ref(false);
const statusLoading = ref(false);
const connectionLoading = ref(false);
const host = ref(null);
const statusSnapshot = ref(null);
const resources = ref(null);
const connectionResult = ref(null);
let refreshTimer = null;

const currentStatus = computed(() => statusSnapshot.value?.status ?? host.value?.status ?? 0);
const isOnline = computed(() => currentStatus.value === 1);
const statusLabel = computed(() => (isOnline.value ? "在线" : "离线"));
const resourceSignal = computed(() => isOnline.value && !!resources.value);
const resourceCards = computed(() => [
  { key: "cpu", label: "CPU", value: getUsage("cpu"), description: "处理器使用率" },
  { key: "memory", label: "内存", value: getUsage("memory"), description: "系统内存使用率" },
  { key: "disk", label: "磁盘", value: getUsage("disk"), description: "根分区使用率" },
]);
const hostTags = computed(() => parseTags(host.value?.tags));

async function fetchHostDetail() {
  loading.value = true;
  try {
    const [detail, status, resourceData] = await Promise.all([
      getHostDetail(hostId.value),
      getHostStatus(hostId.value),
      getHostResources(hostId.value),
    ]);
    host.value = detail;
    statusSnapshot.value = status;
    resources.value = resourceData;
    connectionResult.value = null;
  } catch (error) {
    host.value = null;
    ElMessage.error("获取主机详情失败：" + (error.message || ""));
  } finally {
    loading.value = false;
  }
}

async function refreshHostTelemetry() {
  if (!hostId.value) return;
  statusLoading.value = true;
  try {
    const [status, resourceData] = await Promise.all([
      getHostStatus(hostId.value),
      getHostResources(hostId.value),
    ]);
    statusSnapshot.value = status;
    resources.value = resourceData;
    ElMessage.success("节点态势已刷新");
  } catch (error) {
    ElMessage.error("刷新节点态势失败：" + (error.message || ""));
  } finally {
    statusLoading.value = false;
  }
}

async function handleTestConnection() {
  connectionLoading.value = true;
  try {
    connectionResult.value = await testHostConnection(hostId.value);
    if (connectionResult.value?.connected) {
      ElMessage.success(connectionResult.value.message || "连接测试成功");
    } else {
      ElMessage.error(connectionResult.value?.message || "主机当前不可达");
    }
  } catch (error) {
    connectionResult.value = { connected: false, message: error.message || "连接测试失败" };
    ElMessage.error("连接测试失败：" + (error.message || ""));
  } finally {
    connectionLoading.value = false;
  }
}

function handleTerminal() {
  if (!isOnline.value) {
    ElMessage.warning("主机离线，无法打开终端");
    return;
  }
  router.push({
    path: `/host/terminal/${hostId.value}`,
    query: { name: host.value?.name, ip: host.value?.ip },
  });
}

function handleBack() {
  router.push("/host/list");
}

function getUsage(key) {
  if (!isOnline.value) return null;
  const directValue = statusSnapshot.value?.[`${key}Usage`];
  const resourceValue = resources.value?.[key]?.usage;
  const value = directValue ?? resourceValue;
  return Number.isFinite(Number(value)) ? Math.round(Number(value)) : null;
}

function getUsageTone(value) {
  if (value === null || value === undefined) return "muted";
  if (value >= 80) return "danger";
  if (value >= 60) return "warning";
  return "normal";
}

function formatUptime(seconds) {
  if (!seconds || seconds < 0) return "-";
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return `${days}天 ${hours}小时 ${minutes}分钟`;
}

function formatRate(value) {
  if (value === null || value === undefined) return "-";
  return `${value} Mbps`;
}

function formatTime(time) {
  if (!time) return "-";
  return String(time).replace("T", " ").substring(0, 19);
}

function parseTags(value) {
  if (Array.isArray(value)) return value;
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [String(value)];
  } catch {
    return String(value)
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean);
  }
}

onMounted(() => {
  fetchHostDetail();
  refreshTimer = setInterval(() => {
    if (isOnline.value) refreshHostTelemetry();
  }, 10000);
});

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});
</script>

<template>
  <div class="host-detail-page node-operations-page" v-loading="loading">
    <template v-if="host">
      <section class="node-hero">
        <div class="node-heading">
          <el-button class="node-back" link @click="handleBack">
            <el-icon><ArrowLeft /></el-icon>
            返回主机清单
          </el-button>
          <div class="node-title-row">
            <div class="node-icon"><el-icon><Monitor /></el-icon></div>
            <div>
              <span class="section-kicker">NODE COMMAND / HOST PROFILE</span>
              <h1>{{ host.name || host.hostname }}</h1>
              <p>{{ host.ip }} · {{ host.os || host.osType || "Linux 主机" }}</p>
            </div>
            <span class="node-status" :class="isOnline ? 'is-online' : 'is-offline'">
              <i></i>
              {{ statusLabel }}
            </span>
          </div>
          <div class="node-id-line">
            <span>HOST ID</span>
            <code>{{ host.id }}</code>
            <span class="separator">·</span>
            <span>SSH {{ host.sshPort || 22 }} / {{ host.sshUsername || "root" }}</span>
          </div>
        </div>
        <div class="node-actions">
          <div class="heartbeat" :class="{ 'is-live': isOnline }">
            <span></span>
            {{ isOnline ? "节点心跳正常" : "节点信号中断" }}
          </div>
          <div class="action-row">
            <el-button type="primary" :disabled="!isOnline" @click="handleTerminal">
              <el-icon><Monitor /></el-icon>
              打开终端
            </el-button>
            <el-button :loading="statusLoading" @click="refreshHostTelemetry">
              <el-icon><Refresh /></el-icon>
              刷新态势
            </el-button>
          </div>
        </div>
      </section>

      <section class="node-rail" aria-label="主机资源概况">
        <div class="rail-intro">
          <span class="section-kicker">NODE TELEMETRY</span>
          <strong>节点态势</strong>
          <small>{{ resourceSignal ? "资源信号实时接入" : "资源信号不可用" }}</small>
        </div>
        <div class="rail-stat" :class="`is-${getUsageTone(getUsage('cpu'))}`">
          <span>CPU</span>
          <strong>{{ getUsage("cpu") === null ? "—" : `${getUsage("cpu")}%` }}</strong>
        </div>
        <div class="rail-stat" :class="`is-${getUsageTone(getUsage('memory'))}`">
          <span>内存</span>
          <strong>{{ getUsage("memory") === null ? "—" : `${getUsage("memory")}%` }}</strong>
        </div>
        <div class="rail-stat" :class="`is-${getUsageTone(getUsage('disk'))}`">
          <span>磁盘</span>
          <strong>{{ getUsage("disk") === null ? "—" : `${getUsage("disk")}%` }}</strong>
        </div>
        <div class="rail-stat">
          <span>负载</span>
          <strong>{{ statusSnapshot?.loadAverage || "—" }}</strong>
        </div>
      </section>

      <div class="node-grid">
        <main class="node-main-column">
          <section class="node-panel resource-panel">
            <div class="panel-heading">
              <div>
                <span class="section-kicker">RESOURCE TELEMETRY</span>
                <h2>资源水位</h2>
              </div>
              <span class="panel-index">01</span>
            </div>
            <div v-if="resourceSignal" class="resource-card-grid">
              <article v-for="card in resourceCards" :key="card.key" class="resource-card" :class="`is-${getUsageTone(card.value)}`">
                <div class="resource-card-heading">
                  <span>{{ card.label }}</span>
                  <strong>{{ card.value }}%</strong>
                </div>
                <el-progress :percentage="card.value || 0" :stroke-width="7" :show-text="false" />
                <small>{{ card.description }}</small>
              </article>
            </div>
            <div v-else class="resource-lost-state">
              <el-icon><WarningFilled /></el-icon>
              <strong>资源信号暂不可用</strong>
              <span>主机离线或当前没有可读取的 telemetry</span>
            </div>
            <div v-if="resourceSignal" class="network-strip">
              <div>
                <span>NETWORK RX</span>
                <strong>{{ formatRate(resources?.network?.rx) }}</strong>
              </div>
              <div>
                <span>NETWORK TX</span>
                <strong>{{ formatRate(resources?.network?.tx) }}</strong>
              </div>
              <div>
                <span>LAST SAMPLE</span>
                <strong>{{ formatTime(statusSnapshot?.sampledAt || statusSnapshot?.updatedAt) }}</strong>
              </div>
            </div>
          </section>

          <section class="node-panel profile-panel">
            <div class="panel-heading">
              <div>
                <span class="section-kicker">NODE PROFILE</span>
                <h2>纳管信息</h2>
              </div>
              <span class="panel-index">02</span>
            </div>
            <div class="profile-list">
              <div><span>主机名称</span><strong>{{ host.name || host.hostname || "-" }}</strong><small>管理标识</small></div>
              <div><span>主机地址</span><strong class="mono">{{ host.ip || "-" }}</strong><small>{{ host.isLanHost ? "局域网接入" : "远程接入" }}</small></div>
              <div><span>操作系统</span><strong>{{ host.os || host.osType || "Linux 主机" }}</strong><small>运行环境</small></div>
              <div><span>SSH 连接</span><strong class="mono">{{ host.sshUsername || "root" }}@{{ host.ip }}:{{ host.sshPort || 22 }}</strong><small>认证信息已托管</small></div>
              <div><span>运行时间</span><strong>{{ formatUptime(statusSnapshot?.uptime) }}</strong><small>节点在线时长</small></div>
              <div><span>最近同步</span><strong>{{ formatTime(statusSnapshot?.updatedAt) }}</strong><small>资源采样时间</small></div>
            </div>
            <div v-if="hostTags.length" class="tag-strip">
              <span>TAG INDEX</span>
              <el-tag v-for="tag in hostTags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
            </div>
            <p v-if="host.remark" class="profile-note">{{ host.remark }}</p>
          </section>
        </main>

        <aside class="node-side-column">
          <section class="node-panel connection-panel">
            <div class="panel-heading">
              <div>
                <span class="section-kicker">SSH CONTROL</span>
                <h2>连接控制</h2>
              </div>
              <span class="panel-index">03</span>
            </div>
            <div class="connection-hero" :class="isOnline ? 'is-online' : 'is-offline'">
              <span class="connection-dot"></span>
              <div>
                <strong>{{ isOnline ? "SSH 通道可用" : "SSH 通道不可用" }}</strong>
                <small>{{ host.ip }}:{{ host.sshPort || 22 }}</small>
              </div>
            </div>
            <div v-if="connectionResult" class="connection-result" :class="connectionResult.connected ? 'is-success' : 'is-failed'">
              <strong>{{ connectionResult.connected ? "连接测试成功" : "连接测试失败" }}</strong>
              <span>{{ connectionResult.message || "-" }}</span>
              <small v-if="connectionResult.latency > 0">延迟 {{ connectionResult.latency }}ms</small>
            </div>
            <el-button class="connection-test" :loading="connectionLoading" @click="handleTestConnection">
              测试连接
            </el-button>
          </section>

          <section class="node-panel signal-panel">
            <div class="panel-heading">
              <div>
                <span class="section-kicker">SCHEDULER SIGNAL</span>
                <h2>调度信号</h2>
              </div>
              <span class="panel-index">04</span>
            </div>
            <div class="signal-list">
              <div><span>节点状态</span><strong :class="isOnline ? 'tone-online' : 'tone-offline'">{{ statusLabel }}</strong></div>
              <div><span>负载均值</span><strong class="mono">{{ statusSnapshot?.loadAverage || "—" }}</strong></div>
              <div><span>局域网接入</span><strong>{{ host.isLanHost ? "已启用" : "未启用" }}</strong></div>
              <div><span>主机系统</span><strong>{{ host.os || host.osType || "Linux" }}</strong></div>
            </div>
          </section>

          <section class="node-panel operations-panel">
            <div class="panel-heading">
              <div>
                <span class="section-kicker">NODE OPERATIONS</span>
                <h2>运维入口</h2>
              </div>
              <span class="panel-index">05</span>
            </div>
            <div class="operation-list">
              <button type="button" :disabled="!isOnline" @click="handleTerminal">
                <span><el-icon><Monitor /></el-icon> Web 终端</span>
                <small>{{ isOnline ? "打开交互式 Shell" : "主机离线" }}</small>
                <el-icon><ArrowRight /></el-icon>
              </button>
              <button type="button" @click="refreshHostTelemetry">
                <span><el-icon><Refresh /></el-icon> 资源采样</span>
                <small>立即刷新节点指标</small>
                <el-icon><ArrowRight /></el-icon>
              </button>
            </div>
          </section>
        </aside>
      </div>
    </template>

    <section v-else-if="!loading" class="node-panel not-found-state">
      <el-empty description="主机不存在或已被删除">
        <el-button type="primary" @click="handleBack">返回主机清单</el-button>
      </el-empty>
    </section>
  </div>
</template>

<style lang="scss" scoped>
.node-operations-page {
  --node-accent: #43b8e8;
  --node-accent-soft: rgba(67, 184, 232, 0.12);
  --node-line: rgba(63, 83, 101, 0.68);

  padding: 4px 2px 28px;
  color: var(--platform-text-primary);
}

.node-hero,
.node-rail,
.node-panel {
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-1);
}

.node-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 26px;
  min-height: 190px;
  padding: 22px 26px 24px;
  border-radius: 6px;
  background:
    linear-gradient(120deg, rgba(67, 184, 232, 0.16), transparent 44%),
    linear-gradient(90deg, rgba(82, 207, 130, 0.05), transparent 68%),
    var(--platform-surface-1);
  box-shadow: 0 16px 32px rgba(0, 0, 0, 0.15);
}

.node-heading {
  min-width: 0;
  flex: 1;
}

.node-back {
  padding: 0;
  color: var(--platform-text-secondary);

  &:hover {
    color: var(--node-accent);
  }
}

.node-title-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 17px;
}

.node-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  border: 1px solid rgba(67, 184, 232, 0.46);
  border-radius: 6px;
  background: var(--node-accent-soft);
  color: var(--node-accent);
  font-size: 21px;
}

.node-title-row h1 {
  margin: 6px 0 5px;
  color: var(--platform-text-primary);
  font-size: clamp(24px, 2.6vw, 34px);
  font-weight: 700;
  letter-spacing: -0.035em;
}

.node-title-row p {
  margin: 0;
  color: var(--platform-text-secondary);
  font-size: 13px;
}

.node-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  align-self: flex-start;
  margin-top: 18px;
  padding: 5px 8px;
  border: 1px solid rgba(82, 207, 130, 0.32);
  border-radius: 3px;
  color: var(--platform-green);
  font-family: var(--el-font-family-mono);
  font-size: 10px;

  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: currentColor;
  }

  &.is-offline {
    border-color: var(--platform-line);
    color: var(--platform-text-muted);
  }
}

.node-id-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 20px 0 0 56px;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.03em;

  code {
    color: var(--platform-text-regular);
    font-family: inherit;
  }

  .separator {
    color: var(--node-line);
  }
}

.node-actions {
  display: grid;
  justify-items: end;
  gap: 16px;
}

.heartbeat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;

  &.is-live {
    color: var(--node-accent);

    > span {
      background: var(--node-accent);
      box-shadow: 0 0 0 4px var(--node-accent-soft);
    }
  }

  > span {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--platform-text-muted);
  }
}

.action-row {
  display: flex;
  gap: 8px;
}

.node-rail {
  display: grid;
  grid-template-columns: minmax(190px, 1.35fr) repeat(4, minmax(90px, 0.7fr));
  min-height: 78px;
  margin-top: 14px;
  padding: 12px 18px;
  border-radius: 5px;
  background: var(--platform-surface-2);
}

.rail-intro,
.rail-stat {
  display: grid;
  align-content: center;
  gap: 4px;
}

.rail-intro {
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

.rail-stat {
  position: relative;
  padding: 0 13px;
  border-left: 1px solid var(--platform-line);

  &::before {
    position: absolute;
    top: 14px;
    left: -1px;
    width: 2px;
    height: 28px;
    background: var(--platform-text-muted);
    content: "";
  }

  > span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  > strong {
    color: var(--platform-text-primary);
    font-family: var(--el-font-family-mono);
    font-size: 17px;
    font-weight: 600;
  }

  &.is-normal::before,
  &.is-normal > strong {
    color: var(--platform-green);
  }

  &.is-warning::before,
  &.is-warning > strong {
    color: var(--platform-amber);
  }

  &.is-danger::before,
  &.is-danger > strong {
    color: var(--platform-red);
  }

  &.is-muted::before {
    background: var(--platform-text-muted);
  }
}

.node-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(300px, 0.82fr);
  gap: 14px;
  margin-top: 14px;
}

.node-main-column,
.node-side-column {
  display: grid;
  align-content: start;
  gap: 14px;
  min-width: 0;
}

.node-panel {
  min-width: 0;
  overflow: hidden;
  border-radius: 5px;
}

.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  min-height: 68px;
  padding: 14px 17px;
  border-bottom: 1px solid var(--platform-line);
}

.panel-heading h2 {
  margin: 5px 0 0;
  color: var(--platform-text-primary);
  font-size: 15px;
  font-weight: 650;
}

.panel-index {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
}

.resource-panel {
  background:
    linear-gradient(130deg, rgba(67, 184, 232, 0.05), transparent 50%),
    var(--platform-surface-1);
}

.resource-card-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  padding: 16px 17px;
}

.resource-card {
  display: grid;
  gap: 9px;
  padding: 13px;
  border: 1px solid var(--node-line);
  background: var(--platform-surface-0);

  &.is-warning {
    border-color: rgba(229, 180, 95, 0.4);
  }

  &.is-danger {
    border-color: rgba(240, 100, 106, 0.46);
  }
}

.resource-card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  strong {
    color: var(--platform-text-primary);
    font-family: var(--el-font-family-mono);
    font-size: 20px;
    font-weight: 600;
  }
}

.resource-card small {
  color: var(--platform-text-muted);
  font-size: 10px;
}

.resource-card :deep(.el-progress-bar__outer) {
  background: var(--platform-surface-3);
}

.resource-card.is-warning :deep(.el-progress-bar__inner) {
  background: var(--platform-amber);
}

.resource-card.is-danger :deep(.el-progress-bar__inner) {
  background: var(--platform-red);
}

.resource-lost-state,
.not-found-state :deep(.el-empty) {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 48px 0;
  color: var(--platform-text-muted);
}

.resource-lost-state {
  .el-icon {
    color: var(--platform-amber);
    font-size: 24px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
  }

  span {
    font-size: 11px;
  }
}

.network-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin: 0 17px 17px;
  padding-top: 14px;
  border-top: 1px solid var(--node-line);

  div {
    display: grid;
    gap: 5px;
  }

  span {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 9px;
  }

  strong {
    color: var(--platform-text-regular);
    font-family: var(--el-font-family-mono);
    font-size: 11px;
    font-weight: 500;
  }
}

.profile-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 3px 17px 8px;

  > div {
    display: grid;
    gap: 5px;
    min-height: 66px;
    padding: 12px 0;
    border-bottom: 1px solid rgba(63, 83, 101, 0.46);

    &:nth-child(odd) {
      padding-right: 15px;
      border-right: 1px solid rgba(63, 83, 101, 0.46);
    }

    &:nth-child(even) {
      padding-left: 15px;
    }
  }

  span {
    color: var(--platform-text-muted);
    font-size: 10px;
  }

  strong {
    overflow: hidden;
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 550;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--platform-text-muted);
    font-size: 10px;
  }

  .mono {
    font-family: var(--el-font-family-mono);
    font-size: 11px;
  }
}

.tag-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 17px;
  padding: 13px 0;
  border-bottom: 1px solid var(--node-line);

  > span {
    margin-right: 3px;
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 9px;
  }

  :deep(.el-tag) {
    border-color: rgba(67, 184, 232, 0.26);
    background: rgba(67, 184, 232, 0.06);
    color: var(--node-accent);
    font-size: 10px;
  }
}

.profile-note {
  margin: 13px 17px 16px;
  color: var(--platform-text-secondary);
  font-size: 11px;
  line-height: 1.65;
}

.connection-hero {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 17px 0;
  padding: 13px;
  border: 1px solid rgba(82, 207, 130, 0.3);
  background: rgba(82, 207, 130, 0.07);

  &.is-offline {
    border-color: var(--node-line);
    background: rgba(63, 83, 101, 0.11);

    .connection-dot {
      background: var(--platform-text-muted);
      box-shadow: none;
    }
  }

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
  }

  small {
    margin-top: 4px;
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
  }
}

.connection-dot {
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--platform-green);
  box-shadow: 0 0 0 4px rgba(82, 207, 130, 0.11);
}

.connection-result {
  display: grid;
  gap: 4px;
  margin: 12px 17px 0;
  padding: 10px 12px;
  border-left: 2px solid var(--platform-green);
  background: rgba(82, 207, 130, 0.07);

  &.is-failed {
    border-left-color: var(--platform-red);
    background: rgba(240, 100, 106, 0.07);
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 11px;
  }

  span,
  small {
    color: var(--platform-text-muted);
    font-size: 10px;
  }
}

.connection-test {
  width: calc(100% - 34px);
  margin: 14px 17px 17px;
}

.signal-list {
  display: grid;
  gap: 0;
  padding: 4px 17px 9px;

  > div {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    min-height: 43px;
    border-bottom: 1px solid rgba(63, 83, 101, 0.46);

    &:last-child {
      border-bottom: 0;
    }
  }

  span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  strong {
    overflow: hidden;
    color: var(--platform-text-regular);
    font-size: 11px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mono {
    font-family: var(--el-font-family-mono);
  }

  .tone-online {
    color: var(--platform-green);
  }

  .tone-offline {
    color: var(--platform-text-muted);
  }
}

.operation-list {
  padding: 3px 17px 8px;

  button {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 3px 10px;
    width: 100%;
    padding: 12px 0;
    border: 0;
    border-bottom: 1px solid rgba(63, 83, 101, 0.46);
    background: transparent;
    color: var(--platform-text-regular);
    cursor: pointer;
    text-align: left;

    &:last-child {
      border-bottom: 0;
    }

    &:hover:not(:disabled) {
      color: var(--node-accent);
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.48;
    }

    > span {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
    }

    > span + small {
      grid-column: 1;
      color: var(--platform-text-muted);
      font-size: 10px;
    }

    > .el-icon {
      grid-column: 2;
      grid-row: 1 / span 2;
      color: var(--platform-text-muted);
    }
  }
}

.not-found-state {
  min-height: 340px;
}

.node-operations-page :deep(.el-button) {
  --el-button-hover-text-color: var(--node-accent);
}

@media screen and (max-width: 1120px) {
  .node-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .node-actions {
    justify-items: start;
    width: 100%;
  }

  .node-grid {
    grid-template-columns: 1fr;
  }
}

@media screen and (max-width: 760px) {
  .node-hero,
  .node-rail {
    padding-inline: 17px;
  }

  .node-title-row {
    flex-wrap: wrap;
  }

  .node-status {
    margin-left: 56px;
  }

  .node-id-line {
    flex-wrap: wrap;
    margin-left: 0;
  }

  .node-rail {
    grid-template-columns: repeat(2, 1fr);
    gap: 14px 0;
  }

  .rail-intro {
    grid-column: span 2;
  }

  .rail-stat:nth-child(2n + 1) {
    border-left: 0;
  }

  .resource-card-grid,
  .profile-list,
  .network-strip {
    grid-template-columns: 1fr;
  }

  .profile-list > div:nth-child(odd),
  .profile-list > div:nth-child(even) {
    padding-right: 0;
    padding-left: 0;
    border-right: 0;
  }

  .network-strip {
    gap: 10px;
  }

  .action-row {
    flex-wrap: wrap;
  }
}
</style>
