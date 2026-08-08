<template>
  <div class="backup-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>备份管理</span>
          <el-button type="primary" size="small" @click="showCreate = true">创建备份</el-button>
        </div>
      </template>
      <el-table :data="backups" v-loading="loading" stripe>
        <el-table-column prop="name" label="备份名称" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="owner" label="创建人" width="100" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="onRestore(row)">还原</el-button>
            <el-button size="small" @click="onRename(row)">重命名</el-button>
            <el-button size="small" type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="showCreate" title="创建备份" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="备份名称" required>
          <el-input v-model="form.name" placeholder="如：update-20260719" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showRename" title="重命名备份" width="400px">
      <el-form label-width="80px">
        <el-form-item label="新名称" required>
          <el-input v-model="renameForm.newName" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRename = false">取消</el-button>
        <el-button type="primary" :loading="renaming" @click="doRename">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'

interface BackupItem {
  id: string
  name: string
  description?: string
  createdAt?: string
  owner?: string
  status?: string
}

const store = usePluginStore()
const instanceId = computed(() => store.instanceInfo?.instanceId)

const backups = ref<BackupItem[]>([])
const loading = ref(false)
const showCreate = ref(false)
const creating = ref(false)
const form = ref({ name: '', description: '' })

const showRename = ref(false)
const renaming = ref(false)
const renameForm = ref({ backupId: '', newName: '' })

async function loadList() {
  if (!instanceId.value) {
    ElMessage.warning('请先选择实例')
    return
  }
  loading.value = true
  try {
    const data = await backupApi.list(instanceId.value)
    backups.value = Array.isArray(data) ? data : []
  } catch (e: any) {
    ElMessage.error('加载备份列表失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入备份名称')
    return
  }
  if (!instanceId.value) return
  creating.value = true
  try {
    await backupApi.create({
      instanceId: instanceId.value,
      name: form.value.name,
      description: form.value.description
    })
    ElMessage.success('备份已创建')
    showCreate.value = false
    form.value = { name: '', description: '' }
    loadList()
  } catch (e: any) {
    ElMessage.error('创建失败：' + (e?.message || e))
  } finally {
    creating.value = false
  }
}

async function onRestore(row: BackupItem) {
  if (!instanceId.value) return
  try {
    await ElMessageBox.confirm(
      `确认还原备份 "${row.name}"？当前 admins/hostname/motd/host 配置将被覆盖。`,
      '确认还原',
      { type: 'warning', confirmButtonText: '确认还原', cancelButtonText: '取消' }
    )
    await backupApi.restore({ instanceId: instanceId.value, backupId: row.id })
    ElMessage.success('还原成功')
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('还原失败：' + (e?.message || e))
  }
}

function onRename(row: BackupItem) {
  renameForm.value = { backupId: row.id, newName: row.name }
  showRename.value = true
}

async function doRename() {
  if (!renameForm.value.newName.trim()) {
    ElMessage.warning('请输入新名称')
    return
  }
  renaming.value = true
  try {
    await backupApi.rename({
      backupId: renameForm.value.backupId,
      newName: renameForm.value.newName
    })
    ElMessage.success('已重命名')
    showRename.value = false
    loadList()
  } catch (e: any) {
    ElMessage.error('重命名失败：' + (e?.message || e))
  } finally {
    renaming.value = false
  }
}

async function onDelete(row: BackupItem) {
  try {
    await ElMessageBox.confirm(
      `确认删除备份 "${row.name}"？此操作不可恢复。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await backupApi.delete(row.id)
    ElMessage.success('已删除')
    loadList()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || e))
  }
}

function formatTime(t?: string): string {
  if (!t) return '-'
  try {
    return new Date(t).toLocaleString('zh-CN')
  } catch {
    return t
  }
}

onMounted(loadList)
</script>

<style scoped>
.backup-page {
  padding: 12px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
