<template>
  <div class="restart-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / RESTART CONTROL</span>
        <h2>服务器重启管理</h2>
        <p>RCON 与命令模式的服务器重启控制</p>
      </div>
      <div class="header-actions"></div>
    </div>

    <el-skeleton :loading="loading" :rows="10" animated>
      <template #default>
        <!-- 提示信息 -->
        <el-alert
          title="重启模式说明"
          type="info"
          :closable="false"
          show-icon
        >
          <template #default>
            <div class="alert-line"><strong>RCON 模式：</strong>通过 RCON 协议发送 <code>_restart</code> 命令，需服务器在线。</div>
            <div class="alert-line"><strong>命令模式：</strong>通过 shell 执行 <code>docker restart</code>，需管理端有 shell 权限。</div>
          </template>
        </el-alert>

        <!-- 重启配置卡片 -->
        <el-card shadow="never" class="page-card config-card">
          <template #header>
            <div class="card-header">
              <span>重启配置</span>
              <el-tag
                :type="config.enabled ? 'success' : 'info'"
                size="small"
              >
                {{ config.enabled ? '已启用' : '已停用' }}
              </el-tag>
            </div>
          </template>
          <el-form :model="config" label-width="160px">
            <el-form-item label="启用重启功能">
              <el-switch v-model="config.enabled" />
              <span class="form-tip">关闭后将拒绝所有重启请求</span>
            </el-form-item>

            <el-form-item label="重启方式">
              <el-radio-group v-model="config.byRcon">
                <el-radio :value="true">RCON 模式</el-radio>
                <el-radio :value="false">命令模式</el-radio>
              </el-radio-group>
              <span class="form-tip">AUTO 模式下按此选择</span>
            </el-form-item>

            <el-form-item label="容器名">
              <el-input
                v-model="config.containerName"
                :disabled="config.byRcon"
                placeholder="docker restart 目标容器名"
              />
              <span class="form-tip">仅命令模式下可编辑</span>
            </el-form-item>

            <el-form-item label="自定义命令">
              <el-input
                v-model="config.customCmd"
                :disabled="config.byRcon"
                placeholder="留空则使用 docker restart {containerName}"
              />
              <span class="form-tip">仅命令模式下可编辑，非空时覆盖默认命令</span>
            </el-form-item>

            <el-form-item label="命令超时">
              <el-input-number
                v-model="config.commandTimeoutMs"
                :min="5000"
                :step="1000"
                :disabled="config.byRcon"
              />
              <span class="form-tip">毫秒，仅命令模式生效</span>
            </el-form-item>

            <el-form-item v-if="config.availableModes && config.availableModes.length" label="可用模式">
              <el-tag
                v-for="mode in config.availableModes"
                :key="mode"
                type="info"
                size="small"
                style="margin-right: 6px"
              >
                {{ mode }}
              </el-tag>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="savingConfig" @click="saveConfig">
                <el-icon><Check /></el-icon>
                保存配置
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <!-- 重启操作卡片 -->
        <el-card shadow="never" class="page-card action-card">
          <template #header>
            <div class="card-header">
              <span>重启操作</span>
            </div>
          </template>
          <div class="action-area">
            <el-button
              type="danger"
              size="large"
              :loading="restarting"
              :disabled="!config.enabled"
              @click="handleRestart"
            >
              <el-icon><RefreshRight /></el-icon>
              重启服务器
            </el-button>
            <span v-if="!config.enabled" class="form-tip" style="margin-left: 12px">
              重启功能已停用，无法执行
            </span>
          </div>
        </el-card>

        <!-- 高级选项 -->
        <el-card shadow="never" class="page-card advanced-card">
          <template #header>
            <div class="card-header">
              <span>高级选项</span>
            </div>
          </template>
          <el-collapse>
            <el-collapse-item title="强制指定模式重启" name="advanced">
              <div class="advanced-actions">
                <el-button
                  type="warning"
                  :loading="restartRconLoading"
                  @click="handleRestartByRcon"
                >
                  强制 RCON 模式重启
                </el-button>
                <el-button
                  type="warning"
                  :loading="restartCmdLoading"
                  @click="handleRestartByCommand"
                >
                  强制命令模式重启
                </el-button>
              </div>
              <div class="advanced-tip">
                强制模式将忽略 AUTO 配置直接按指定方式重启，适用于一种模式失败时手动切换。
              </div>
            </el-collapse-item>
          </el-collapse>
        </el-card>
      </template>
    </el-skeleton>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  restartApi,
  type RestartConfigVO,
  type RestartConfigUpdateDTO
} from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

// ===== 配置 =====
const loading = ref(false)
const savingConfig = ref(false)
const config = ref<RestartConfigVO>({
  byRcon: true,
  containerName: '',
  customCmd: '',
  commandTimeoutMs: 30000,
  enabled: true,
  availableModes: ['AUTO', 'RCON', 'COMMAND']
})

async function loadConfig() {
  loading.value = true
  try {
    const data = await restartApi.getConfig()
    config.value = {
      byRcon: Boolean(data.byRcon),
      containerName: data.containerName ?? '',
      customCmd: data.customCmd ?? '',
      commandTimeoutMs: data.commandTimeoutMs ?? 30000,
      enabled: data.enabled !== false,
      availableModes: data.availableModes ?? ['AUTO', 'RCON', 'COMMAND']
    }
  } catch (e: any) {
    ElMessage.error('加载重启配置失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  const payload: RestartConfigUpdateDTO = {
    byRcon: config.value.byRcon,
    containerName: config.value.containerName,
    customCmd: config.value.customCmd,
    commandTimeoutMs: config.value.commandTimeoutMs,
    enabled: config.value.enabled
  }
  savingConfig.value = true
  try {
    await restartApi.setConfig(payload)
    ElMessage.success('重启配置已保存')
  } catch (e: any) {
    ElMessage.error('保存重启配置失败：' + (e?.message || e))
  } finally {
    savingConfig.value = false
  }
}

// ===== 重启操作 =====
const restarting = ref(false)
const restartRconLoading = ref(false)
const restartCmdLoading = ref(false)

async function handleRestart() {
  const id = instanceId.value
  if (!id) {
    ElMessage.warning('请先选择实例')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认要重启服务器吗？将按当前配置（AUTO 模式）执行。',
      '重启确认',
      { type: 'warning', confirmButtonText: '重启', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  restarting.value = true
  try {
    await restartApi.restart({ instanceId: id, mode: 'AUTO' })
    ElMessage.success('重启命令已发送')
  } catch (e: any) {
    ElMessage.error('重启失败：' + (e?.message || e))
  } finally {
    restarting.value = false
  }
}

async function handleRestartByRcon() {
  const id = instanceId.value
  if (!id) {
    ElMessage.warning('请先选择实例')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认强制使用 RCON 模式重启？将通过 RCON 协议发送 _restart 命令。',
      'RCON 模式重启确认',
      { type: 'warning', confirmButtonText: '确认重启', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  restartRconLoading.value = true
  try {
    await restartApi.restartByRcon(id)
    ElMessage.success('RCON 重启命令已发送')
  } catch (e: any) {
    ElMessage.error('RCON 重启失败：' + (e?.message || e))
  } finally {
    restartRconLoading.value = false
  }
}

async function handleRestartByCommand() {
  const id = instanceId.value
  if (!id) {
    ElMessage.warning('请先选择实例')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认强制使用命令模式重启？将通过 shell 执行 docker restart 命令。',
      '命令模式重启确认',
      { type: 'warning', confirmButtonText: '确认重启', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  restartCmdLoading.value = true
  try {
    await restartApi.restartByCommand(id)
    ElMessage.success('命令重启已执行')
  } catch (e: any) {
    ElMessage.error('命令重启失败：' + (e?.message || e))
  } finally {
    restartCmdLoading.value = false
  }
}

onMounted(() => {
  loadConfig()
})
</script>

<style scoped>
.restart-page {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.config-card,
.action-card,
.advanced-card {
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  border-radius: 6px;
  box-shadow: none;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--platform-text-secondary);
}

.alert-line {
  line-height: 1.8;
}

.alert-line code {
  font-family: 'Consolas', 'Monaco', monospace;
  background: var(--platform-surface-2);
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
}

.action-area {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.advanced-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.advanced-tip {
  font-size: 12px;
  color: var(--platform-text-secondary);
  line-height: 1.6;
}
</style>
