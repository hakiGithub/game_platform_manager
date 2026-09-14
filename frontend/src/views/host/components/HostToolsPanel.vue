<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { getHostTools, installHostTool } from "@/api/host";

const props = defineProps({
  hostId: { type: Number, required: true },
  isOnline: { type: Boolean, default: false },
});

const loading = ref(false);
const installingTool = ref("");
const data = ref(null);

const tools = computed(() => data.value?.tools || []);
const installedCount = computed(
  () => tools.value.filter((item) => item.installed).length,
);

/** displayName 形如 "unzip（zip 解压）"，拆成主名 + 说明两段展示 */
function splitDisplayName(displayName) {
  const text = String(displayName || "");
  const index = text.indexOf("（");
  if (index === -1) return { name: text, desc: "" };
  return {
    name: text.slice(0, index),
    desc: text.slice(index + 1).replace(/）$/, ""),
  };
}

async function refresh() {
  if (!props.isOnline) return;
  loading.value = true;
  try {
    data.value = await getHostTools(props.hostId);
  } catch (error) {
    ElMessage.error("获取环境工具状态失败：" + (error.message || ""));
  } finally {
    loading.value = false;
  }
}

async function handleInstall(tool) {
  installingTool.value = tool;
  try {
    await installHostTool(props.hostId, tool);
    ElMessage.success(`${tool} 安装成功`);
    await refresh();
  } catch (error) {
    ElMessage.error(`安装 ${tool} 失败：` + (error.message || ""));
  } finally {
    installingTool.value = "";
  }
}

onMounted(refresh);
</script>

<template>
  <section class="node-panel host-tools-panel">
    <div class="panel-heading">
      <div>
        <span class="section-kicker">HOST TOOLING</span>
        <h2>环境工具</h2>
      </div>
      <span class="panel-index">03</span>
    </div>

    <div v-if="!isOnline" class="tools-offline">
      <el-icon><WarningFilled /></el-icon>
      <strong>环境探测不可用</strong>
      <span>主机离线，无法探测环境工具</span>
    </div>

    <template v-else>
      <div
        v-loading="loading"
        class="tools-body"
        element-loading-background="transparent"
      >
        <div class="tools-env">
          <div class="env-cell">
            <span class="env-label">包管理器</span>
            <strong
              class="env-value mono"
              :class="{ 'is-unset': !data?.packageManager }"
            >
              {{ data?.packageManager || "未识别" }}
            </strong>
          </div>
          <div class="env-cell">
            <span class="env-label">提权方式</span>
            <strong
              class="env-value"
              :class="data?.sudoNopasswd ? 'is-good' : 'is-unset'"
            >
              {{ data?.sudoNopasswd ? "免密 sudo" : "需密码" }}
            </strong>
          </div>
          <div class="env-cell">
            <span class="env-label">Docker</span>
            <strong
              class="env-value"
              :class="data?.docker ? 'is-good' : 'is-unset'"
            >
              <i class="env-dot"></i>
              {{ data?.docker ? "可用" : "不可用" }}
            </strong>
          </div>
        </div>

        <p v-if="data && !data.packageManager" class="tools-hint">
          <el-icon><WarningFilled /></el-icon>
          未识别到受支持的包管理器（apt/dnf/yum/apk/pacman/zypper），无法自动安装，请通过
          Web 终端手动安装。
        </p>

        <div class="tools-header">
          <span v-if="tools.length" class="tools-count"
            >已安装 {{ installedCount }}/{{ tools.length }}</span
          >
          <span v-else class="tools-count">工具清单</span>
          <el-button text size="small" :loading="loading" @click="refresh">
            <el-icon><Refresh /></el-icon>
            重新探测
          </el-button>
        </div>

        <div v-if="tools.length" class="tools-list">
          <div v-for="item in tools" :key="item.tool" class="tools-item">
            <i
              class="tools-dot"
              :class="item.installed ? 'is-installed' : 'is-missing'"
            ></i>
            <div class="tools-item-main">
              <span class="tools-name">{{
                splitDisplayName(item.displayName).name
              }}</span>
              <small
                v-if="splitDisplayName(item.displayName).desc"
                class="tools-desc"
              >
                {{ splitDisplayName(item.displayName).desc }}
              </small>
            </div>
            <div class="tools-item-side">
              <span v-if="item.installed" class="tools-installed">
                <el-icon><CircleCheckFilled /></el-icon>
                已安装
              </span>
              <el-button
                v-else-if="item.installable"
                size="small"
                type="primary"
                plain
                :loading="installingTool === item.tool"
                @click="handleInstall(item.tool)"
              >
                安装
              </el-button>
              <el-tooltip
                v-else
                content="未识别受支持的包管理器，请通过 Web 终端手动安装"
                placement="top"
              >
                <span class="tools-manual">终端安装</span>
              </el-tooltip>
            </div>
          </div>
        </div>
        <div v-else-if="!loading" class="tools-empty">
          <span>暂无工具探测数据</span>
        </div>
      </div>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.host-tools-panel {
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

.section-kicker {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.14em;
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

.tools-body {
  min-height: 160px;
}

.tools-env {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  padding: 14px 17px 0;
}

.env-cell {
  display: grid;
  gap: 6px;
  padding: 11px 12px;
  border: 1px solid var(--platform-line);
  border-radius: 4px;
  background: var(--platform-surface-0);
}

.env-label {
  color: var(--platform-text-muted);
  font-size: 10px;
  letter-spacing: 0.04em;
}

.env-value {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  color: var(--platform-text-regular);
  font-size: 12px;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.mono {
    font-family: var(--el-font-family-mono);
  }

  &.is-good {
    color: var(--platform-green);
  }

  &.is-unset {
    color: var(--platform-text-muted);
    font-weight: 500;
  }
}

.env-dot {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: currentColor;
}

.tools-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 12px 17px 0;
  padding: 9px 12px;
  border: 1px solid rgba(242, 184, 75, 0.32);
  border-radius: 4px;
  background: rgba(242, 184, 75, 0.06);
  color: var(--platform-amber);
  font-size: 11px;
  line-height: 1.6;

  .el-icon {
    margin-top: 2px;
    flex: 0 0 auto;
  }
}

.tools-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 14px 17px 0;
  padding-bottom: 7px;
  border-bottom: 1px solid rgba(63, 83, 101, 0.46);
}

.tools-count {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
}

.tools-list {
  padding: 0 17px;
}

.tools-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 5px 0;
  border-bottom: 1px solid rgba(63, 83, 101, 0.46);

  &:last-child {
    border-bottom: 0;
  }

  &:hover .tools-name {
    color: var(--platform-text-primary);
  }
}

.tools-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;

  &.is-installed {
    background: var(--platform-green);
    box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.12);
  }

  &.is-missing {
    background: transparent;
    border: 1px solid var(--platform-text-muted);
  }
}

.tools-item-main {
  display: grid;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.tools-name {
  overflow: hidden;
  color: var(--platform-text-regular);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tools-desc {
  overflow: hidden;
  color: var(--platform-text-muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tools-item-side {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
}

.tools-installed {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--platform-green);
  font-size: 11px;

  .el-icon {
    font-size: 13px;
  }
}

.tools-manual {
  color: var(--platform-text-muted);
  cursor: help;
  font-size: 11px;
  text-decoration: underline dotted rgba(104, 120, 138, 0.6);
  text-underline-offset: 3px;
}

.tools-empty {
  display: grid;
  justify-items: center;
  padding: 26px 0 30px;
  color: var(--platform-text-muted);
  font-size: 11px;
}

.tools-offline {
  display: grid;
  justify-items: center;
  gap: 7px;
  padding: 42px 0;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--platform-amber);
    font-size: 22px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 550;
  }

  span {
    font-size: 11px;
  }
}

@media screen and (max-width: 760px) {
  .tools-env {
    grid-template-columns: 1fr;
  }
}
</style>
