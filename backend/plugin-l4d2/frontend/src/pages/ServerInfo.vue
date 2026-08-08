<template>
  <div class="server-info-page">
    <el-row :gutter="20">
      <el-col :span="8">
        <el-card v-loading="loading" class="info-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">服务器名称</span>
              <span class="card-subtitle">hostname</span>
              <el-button type="primary" link size="small" @click="saveHostname" :loading="hostnameSaving">保存</el-button>
            </div>
          </template>
          <el-input
            v-model="hostname"
            maxlength="64"
            show-word-limit
            clearable
            placeholder="服务器在浏览器列表显示的名称"
          />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card v-loading="loading" class="info-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">MOTD</span>
              <span class="card-subtitle">motd.txt</span>
              <el-button type="primary" link size="small" @click="saveMotd" :loading="motdSaving">保存</el-button>
            </div>
          </template>
          <el-input
            v-model="motd"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="玩家加入服务器时显示的公告"
          />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card v-loading="loading" class="info-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">Host</span>
              <span class="card-subtitle">host.txt</span>
              <el-button type="primary" link size="small" @click="saveHost" :loading="hostSaving">保存</el-button>
            </div>
          </template>
          <el-input
            v-model="host"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="服务器主机信息（如官网/联系）"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { serverInfoApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const hostname = ref('')
const motd = ref('')
const host = ref('')

const loading = ref(false)
const hostnameSaving = ref(false)
const motdSaving = ref(false)
const hostSaving = ref(false)

async function loadAll() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await serverInfoApi.get(instanceId.value)
    hostname.value = data?.hostname || ''
    motd.value = data?.motd || ''
    host.value = data?.host || ''
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function saveHostname() {
  if (!instanceId.value) return
  hostnameSaving.value = true
  try {
    await serverInfoApi.update({ instanceId: instanceId.value, hostname: hostname.value })
    ElMessage.success('hostname 已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    hostnameSaving.value = false
  }
}

async function saveMotd() {
  if (!instanceId.value) return
  motdSaving.value = true
  try {
    await serverInfoApi.update({ instanceId: instanceId.value, motd: motd.value })
    ElMessage.success('motd 已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    motdSaving.value = false
  }
}

async function saveHost() {
  if (!instanceId.value) return
  hostSaving.value = true
  try {
    await serverInfoApi.update({ instanceId: instanceId.value, host: host.value })
    ElMessage.success('host 已保存')
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    hostSaving.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.server-info-page {
  padding: 16px;
}

.info-card {
  border-radius: 8px;

  :deep(.el-card__header) {
    padding: 14px 16px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  :deep(.el-card__body) {
    padding: 16px;
  }
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.card-subtitle {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 400;
  flex: 1;
}

:deep(.el-input__wrapper),
:deep(.el-textarea__inner) {
  border-radius: 6px;
}

:deep(.el-input__inner::placeholder),
:deep(.el-textarea__inner::placeholder) {
  color: var(--el-text-color-secondary);
  opacity: 0.7;
}
</style>
