<template>
  <section class="node-panel s302-panel">
    <div class="panel-heading">
      <div>
        <span class="section-kicker">STEAM ACCELERATION</span>
        <h2>Steam302 加速</h2>
      </div>
      <span class="panel-index">06</span>
    </div>

    <div class="s302-status" :class="phaseClass">
      <span class="s302-dot"></span>
      <div>
        <strong>{{ status?.message || "未部署" }}</strong>
        <small v-if="status && status.phase !== 'NOT_INSTALLED'">{{ status.image }}</small>
      </div>
    </div>

    <div v-if="status && status.phase !== 'NOT_INSTALLED'" class="s302-metrics">
      <div><span>hosts 劫持</span><strong>{{ status.hostsEntries ?? "-" }} 条</strong></div>
      <div><span>代理域名</span><strong>{{ status.proxiedDomains ?? "-" }} 个</strong></div>
      <div><span>CA 信任</span><strong :class="status.certTrusted ? 'tone-online' : 'tone-offline'">{{ status.certTrusted ? "已信任" : "未信任" }}</strong></div>
    </div>

    <div v-if="status && status.phase !== 'NOT_INSTALLED'" class="s302-share">
      <div class="s302-share-text">
        <span>容器共享加速</span>
        <small>劫持条目指向宿主机 {{ status.targetIp || "127.0.0.1" }}，bridge 容器与宿主机均可走代理</small>
      </div>
      <el-switch
        :model-value="!!status.containerShare"
        :loading="shareSwitching"
        @change="handleContainerShare"
      />
    </div>

    <div class="s302-actions">
      <el-button
        v-if="!status || status.phase === 'NOT_INSTALLED'"
        type="primary"
        :disabled="!isOnline"
        :loading="installing"
        @click="handleInstall"
      >
        安装到主机
      </el-button>
      <template v-else>
        <el-button v-if="status.phase === 'RUNNING'" :loading="acting" @click="handleStop">停止</el-button>
        <el-button v-else type="primary" :loading="acting" @click="handleStart">启动</el-button>
        <el-button @click="dialogVisible = true">服务配置</el-button>
      </template>
      <el-button text :loading="statusLoading" @click="refreshStatus">刷新</el-button>
    </div>

    <!-- 服务配置弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      title="Steam302 服务配置"
      width="640px"
      :close-on-click-modal="false"
      @open="loadConfig"
      @closed="refreshStatus"
    >
      <div v-loading="configLoading" class="s302-dialog-body">
        <el-alert
          v-if="configError"
          :title="configError"
          type="error"
          :closable="false"
          show-icon
        />
        <template v-else>
          <p class="s302-hint">开关修改保存后需<strong>重启服务</strong>才会生效（工具将在重启时改写 hosts 并重建代理路由）。</p>

          <div v-for="group in serviceList.groups" :key="group.key" class="s302-group">
            <div class="s302-group-head">
              <el-checkbox
                :model-value="groupAllEnabled(group)"
                :indeterminate="groupIndeterminate(group)"
                @change="(v) => toggleGroup(group, v)"
              >
                <strong>{{ group.label }}</strong>
                <small class="s302-group-count">{{ group.items.length }} 项</small>
              </el-checkbox>
            </div>
            <div class="s302-items">
              <div v-for="item in group.items" :key="item.key" class="s302-item">
                <div class="s302-item-main">
                  <el-switch
                    v-model="item.enabled"
                    size="small"
                    @change="() => markDirty(item.key, item.enabled)"
                  />
                  <span class="s302-item-name" :class="{ 'is-unknown': !item.known }">{{ item.name }}</span>
                  <el-popover
                    v-if="item.domains.length"
                    placement="left"
                    :width="420"
                    trigger="click"
                  >
                    <template #reference>
                      <el-link type="primary" :underline="false" class="s302-domain-link">
                        {{ item.domains.length }} 个域名
                      </el-link>
                    </template>
                    <div class="s302-domain-list">
                      <div v-for="d in item.domains" :key="d" class="s302-domain">{{ d }}</div>
                    </div>
                  </el-popover>
                </div>
              </div>
            </div>
          </div>

          <el-collapse v-if="serviceList.advanced.length" class="s302-advanced">
            <el-collapse-item :title="`高级设置（${serviceList.advanced.length} 项）`" name="adv">
              <div v-for="adv in serviceList.advanced" :key="adv.key" class="s302-adv-row">
                <span class="s302-adv-key">{{ adv.key }}</span>
                <el-input
                  v-model="adv.value"
                  size="small"
                  class="s302-adv-input"
                  @input="() => markDirty(adv.key, adv.value)"
                />
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>
      </div>

      <template #footer>
        <span v-if="dirtyCount" class="s302-dirty">{{ dirtyCount }} 项未保存</span>
        <el-button @click="dialogVisible = false">关闭</el-button>
        <el-button :disabled="!dirtyCount || configError" :loading="saving" @click="handleSave">保存配置</el-button>
        <el-button
          type="primary"
          :disabled="!dirtyCount"
          :loading="saving || restarting"
          title="保存并重启使配置生效"
          @click="handleSaveAndRestart"
        >
          保存并重启
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getSteam302Config,
  getSteam302Status,
  installSteam302,
  saveSteam302Config,
  setSteam302ContainerShare,
  startSteam302,
  stopSteam302,
} from "@/api/host";
import { buildServiceList } from "@/constants/steam302Services";

const props = defineProps({
  hostId: { type: Number, required: true },
  isOnline: { type: Boolean, default: false },
});

const router = useRouter();

const status = ref(null);
const statusLoading = ref(false);
const installing = ref(false);
const acting = ref(false);
const shareSwitching = ref(false);

const dialogVisible = ref(false);
const configLoading = ref(false);
const saving = ref(false);
const restarting = ref(false);
const configError = ref("");
const serviceList = reactive({ groups: [], advanced: [] });
const dirty = reactive({});

const dirtyCount = computed(() => Object.keys(dirty).length);
const phaseClass = computed(() => {
  const phase = status.value?.phase;
  return phase === "RUNNING" ? "is-running" : phase === "STOPPED" ? "is-stopped" : "is-none";
});

async function refreshStatus() {
  if (!props.hostId) return;
  statusLoading.value = true;
  try {
    status.value = await getSteam302Status(props.hostId);
  } catch (error) {
    ElMessage.error("获取 Steam302 状态失败：" + (error.message || ""));
  } finally {
    statusLoading.value = false;
  }
}

async function handleInstall() {
  const confirmed = await ElMessageBox.confirm(
    "将在主机上部署 Steamcommunity 302 容器（拉取镜像并信任 CA 证书），提交为异步任务执行。是否继续？",
    "安装 Steam302",
    { confirmButtonText: "提交安装任务", cancelButtonText: "取消", type: "info" }
  ).catch(() => false);
  if (!confirmed) return;

  installing.value = true;
  try {
    const taskId = await installSteam302(props.hostId);
    await ElMessageBox.confirm(
      `安装任务已提交（任务ID: ${taskId}），可在任务中心查看进度。`,
      "已提交",
      { confirmButtonText: "前往任务中心", cancelButtonText: "留在本页", type: "success" }
    ).then(() => {
      router.push("/services/tasks/list");
    }).catch(() => {});
    refreshStatus();
  } catch (error) {
    ElMessage.error("提交安装任务失败：" + (error.message || ""));
  } finally {
    installing.value = false;
  }
}

async function handleContainerShare(enabled) {
  shareSwitching.value = true;
  try {
    await setSteam302ContainerShare(props.hostId, !!enabled);
    ElMessage.success(enabled
      ? "容器共享加速已开启：劫持条目已改指宿主机 IP"
      : "容器共享加速已关闭：劫持条目已恢复 127.0.0.1");
    refreshStatus();
  } catch (error) {
    ElMessage.error("切换容器共享加速失败：" + (error.message || ""));
    refreshStatus();
  } finally {
    shareSwitching.value = false;
  }
}

async function handleStart() {
  acting.value = true;
  try {
    await startSteam302(props.hostId);
    ElMessage.success("已启动");
    refreshStatus();
  } catch (error) {
    ElMessage.error("启动失败：" + (error.message || ""));
  } finally {
    acting.value = false;
  }
}

async function handleStop() {
  acting.value = true;
  try {
    await stopSteam302(props.hostId);
    ElMessage.success("已停止");
    refreshStatus();
  } catch (error) {
    ElMessage.error("停止失败：" + (error.message || ""));
  } finally {
    acting.value = false;
  }
}

async function loadConfig() {
  configLoading.value = true;
  configError.value = "";
  Object.keys(dirty).forEach((k) => delete dirty[k]);
  try {
    const config = await getSteam302Config(props.hostId);
    const built = buildServiceList(config || {});
    serviceList.groups = built.groups;
    serviceList.advanced = built.advanced;
  } catch (error) {
    configError.value = "读取配置失败：" + (error.message || "");
    serviceList.groups = [];
    serviceList.advanced = [];
  } finally {
    configLoading.value = false;
  }
}

function markDirty(key, value) {
  dirty[key] = value;
}

function collectChanges() {
  const changes = {};
  for (const group of serviceList.groups) {
    for (const item of group.items) {
      if (item.key in dirty) {
        changes[item.key] = item.enabled ? "1" : "0";
      }
    }
  }
  for (const adv of serviceList.advanced) {
    if (adv.key in dirty) {
      changes[adv.key] = adv.value;
    }
  }
  return changes;
}

async function handleSave() {
  const changes = collectChanges();
  if (!Object.keys(changes).length) return;
  saving.value = true;
  try {
    await saveSteam302Config(props.hostId, changes);
    ElMessage.success("配置已保存，重启服务后生效");
    Object.keys(dirty).forEach((k) => delete dirty[k]);
  } catch (error) {
    ElMessage.error("保存配置失败：" + (error.message || ""));
  } finally {
    saving.value = false;
  }
}

async function handleSaveAndRestart() {
  const changes = collectChanges();
  if (!Object.keys(changes).length) return;
  saving.value = true;
  try {
    await saveSteam302Config(props.hostId, changes);
    Object.keys(dirty).forEach((k) => delete dirty[k]);
    saving.value = false;

    restarting.value = true;
    await stopSteam302(props.hostId);
    await startSteam302(props.hostId);
    ElMessage.success("配置已保存，服务已重启生效");
    dialogVisible.value = false;
  } catch (error) {
    ElMessage.error("操作失败：" + (error.message || ""));
  } finally {
    saving.value = false;
    restarting.value = false;
  }
}

function groupAllEnabled(group) {
  return group.items.length > 0 && group.items.every((i) => i.enabled);
}

function groupIndeterminate(group) {
  const enabled = group.items.filter((i) => i.enabled).length;
  return enabled > 0 && enabled < group.items.length;
}

function toggleGroup(group, value) {
  group.items.forEach((i) => {
    if (i.enabled !== value) {
      i.enabled = value;
      markDirty(i.key, value);
    }
  });
}

onMounted(refreshStatus);
</script>

<style lang="scss" scoped>
.s302-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 6px;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .section-kicker {
    font-size: 11px;
    letter-spacing: 0.14em;
    color: var(--platform-text-tertiary, #8a97a5);
  }

  h2 {
    margin: 4px 0 0;
    font-size: 16px;
    color: var(--platform-text-primary, #dde5ec);
  }

  .panel-index {
    font-size: 12px;
    color: var(--platform-text-tertiary, #8a97a5);
  }
}

.s302-status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--platform-line, rgba(63, 83, 101, 0.68));
  border-radius: 6px;
  background: var(--platform-surface-2, rgba(255, 255, 255, 0.02));

  strong {
    display: block;
    font-size: 14px;
    color: var(--platform-text-primary, #dde5ec);
  }

  small {
    color: var(--platform-text-tertiary, #8a97a5);
  }

  .s302-dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: #8a97a5;
    flex-shrink: 0;
  }

  &.is-running .s302-dot {
    background: #52cf82;
    box-shadow: 0 0 8px rgba(82, 207, 130, 0.6);
  }

  &.is-stopped .s302-dot {
    background: #e6a23c;
  }
}

.s302-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;

  > div {
    padding: 8px 10px;
    border: 1px solid var(--platform-line, rgba(63, 83, 101, 0.68));
    border-radius: 6px;

    span {
      display: block;
      font-size: 11px;
      color: var(--platform-text-tertiary, #8a97a5);
    }

    strong {
      font-size: 13px;
      color: var(--platform-text-primary, #dde5ec);
    }

    .tone-online {
      color: #52cf82;
    }

    .tone-offline {
      color: #e6a23c;
    }
  }
}

.s302-share {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--platform-line, rgba(63, 83, 101, 0.68));
  border-radius: 6px;
  background: var(--platform-surface-2, rgba(255, 255, 255, 0.02));

  .s302-share-text {
    span {
      display: block;
      font-size: 13px;
      color: var(--platform-text-primary, #dde5ec);
    }

    small {
      font-size: 11px;
      color: var(--platform-text-tertiary, #8a97a5);
    }
  }
}

.s302-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.s302-dialog-body {
  max-height: 60vh;
  overflow-y: auto;
}

.s302-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--platform-text-secondary, #a8b4bf);

  strong {
    color: #e6a23c;
  }
}

.s302-group {
  margin-bottom: 14px;
  border: 1px solid var(--platform-line, rgba(63, 83, 101, 0.68));
  border-radius: 6px;

  .s302-group-head {
    padding: 8px 12px;
    border-bottom: 1px solid var(--platform-line, rgba(63, 83, 101, 0.4));
    background: var(--platform-surface-2, rgba(255, 255, 255, 0.02));

    .s302-group-count {
      margin-left: 8px;
      font-size: 11px;
      color: var(--platform-text-tertiary, #8a97a5);
    }
  }
}

.s302-items {
  padding: 4px 0;
}

.s302-item {
  padding: 4px 12px;

  &:hover {
    background: var(--platform-surface-2, rgba(255, 255, 255, 0.02));
  }
}

.s302-item-main {
  display: flex;
  align-items: center;
  gap: 10px;

  .s302-item-name {
    flex: 1;
    font-size: 13px;
    color: var(--platform-text-primary, #dde5ec);

    &.is-unknown {
      color: var(--platform-text-tertiary, #8a97a5);
      font-style: italic;
    }
  }

  .s302-domain-link {
    font-size: 12px;
  }
}

.s302-domain-list {
  max-height: 280px;
  overflow-y: auto;
}

.s302-domain {
  padding: 2px 0;
  font-family: monospace;
  font-size: 12px;
  color: var(--platform-text-secondary, #a8b4bf);
}

.s302-advanced {
  margin-top: 4px;

  .s302-adv-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 4px 0;

    .s302-adv-key {
      width: 220px;
      font-family: monospace;
      font-size: 12px;
      color: var(--platform-text-secondary, #a8b4bf);
    }

    .s302-adv-input {
      flex: 1;
    }
  }
}

.s302-dirty {
  margin-right: 12px;
  font-size: 12px;
  color: #e6a23c;
}
</style>
