<template>
  <div class="admins-page">
    <div class="page-header">
      <h1 class="page-title">管理员管理</h1>
        <div class="header-actions">
          <el-button type="primary" @click="showAddDialog = true">
            <el-icon><Plus /></el-icon>
            添加管理员
          </el-button>
          <el-button @click="refreshAdmins" :loading="loading">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </div>

      <!-- 管理员列表 -->
      <el-card shadow="hover" class="admins-card">
        <el-table
          :data="admins"
          style="width: 100%"
          v-loading="loading"
        >
          <el-table-column prop="name" label="名称" min-width="150" />
          
          <el-table-column prop="steamId" label="Steam ID" min-width="200">
            <template #default="{ row }">
              <el-tag type="info">{{ row.steamId }}</el-tag>
            </template>
          </el-table-column>
          
          <el-table-column prop="flags" label="权限" min-width="200">
            <template #default="{ row }">
              <div class="flags-list">
                <el-tag
                  v-for="flag in parseFlags(row.flags)"
                  :key="flag"
                  size="small"
                  style="margin-right: 4px"
                >
                  {{ flag }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          
          <el-table-column prop="immunity" label="免疫等级" width="120">
            <template #default="{ row }">
              <el-tag :type="getImmunityType(row.immunity)">
                {{ row.immunity }}
              </el-tag>
            </template>
          </el-table-column>
          
          <el-table-column prop="addedAt" label="添加时间" width="180">
            <template #default="{ row }">
              {{ formatDate(row.addedAt) }}
            </template>
          </el-table-column>
          
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" link @click="editAdmin(row)">
                编辑
              </el-button>
              <el-button type="danger" size="small" link @click="deleteAdmin(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 权限说明 -->
      <el-card shadow="hover" class="flags-card" style="margin-top: 20px">
        <template #header>
          <span>权限说明</span>
        </template>
        
        <el-row :gutter="16">
          <el-col
            v-for="(flag, key) in ADMIN_FLAGS"
            :key="key"
            :span="6"
          >
            <div class="flag-item">
              <el-tag type="primary" size="small">{{ flag.flag }}</el-tag>
              <span class="flag-label">{{ flag.label }}</span>
            </div>
          </el-col>
        </el-row>
      </el-card>

      <!-- 添加/编辑管理员对话框 -->
      <el-dialog
        v-model="showAddDialog"
        :title="editingAdmin ? '编辑管理员' : '添加管理员'"
        width="500px"
      >
        <el-form :model="adminForm" label-width="100px">
          <el-form-item label="Steam ID">
            <el-input
              v-model="adminForm.steamId"
              placeholder="STEAM_1:0:12345678"
              :disabled="!!editingAdmin"
            />
          </el-form-item>
          
          <el-form-item label="名称">
            <el-input v-model="adminForm.name" placeholder="管理员名称" />
          </el-form-item>
          
          <el-form-item label="权限">
            <el-checkbox-group v-model="selectedFlags">
              <el-checkbox
                v-for="(flag, key) in ADMIN_FLAGS"
                :key="key"
                :label="flag.flag"
              >
                {{ flag.label }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          
          <el-form-item label="免疫等级">
            <el-slider v-model="adminForm.immunity" :min="0" :max="99" show-input />
          </el-form-item>
        </el-form>
        
        <template #footer>
          <el-button @click="cancelEdit">取消</el-button>
          <el-button type="primary" @click="saveAdmin" :loading="saving">
            保存
          </el-button>
        </template>
      </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'
import { ADMIN_FLAGS } from '@/utils/gameConstants'
import type { AdminInfo } from '@/types'

const pluginStore = usePluginStore()
const loading = ref(false)
const saving = ref(false)
const admins = ref<AdminInfo[]>([])
const showAddDialog = ref(false)
const editingAdmin = ref<AdminInfo | null>(null)
const selectedFlags = ref<string[]>([])

const adminForm = ref({
  steamId: '',
  name: '',
  flags: '',
  immunity: 50
})

// 方法
async function refreshAdmins() {
  loading.value = true
  try {
    admins.value = await adminApi.getList()
  } catch (error) {
    pluginStore.notifyError('获取失败', '无法获取管理员列表')
  } finally {
    loading.value = false
  }
}

function parseFlags(flags: string): string[] {
  return flags.split('').map(f => {
    const flagInfo = Object.values(ADMIN_FLAGS).find(info => info.flag === f)
    return flagInfo?.label || f
  })
}

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

function getImmunityType(immunity: number): TagType {
  if (immunity >= 80) return 'danger'
  if (immunity >= 50) return 'warning'
  return 'info'
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString()
}

function editAdmin(admin: AdminInfo) {
  editingAdmin.value = admin
  adminForm.value = {
    steamId: admin.steamId,
    name: admin.name,
    flags: admin.flags,
    immunity: admin.immunity
  }
  selectedFlags.value = admin.flags.split('')
  showAddDialog.value = true
}

function cancelEdit() {
  showAddDialog.value = false
  editingAdmin.value = null
  adminForm.value = {
    steamId: '',
    name: '',
    flags: '',
    immunity: 50
  }
  selectedFlags.value = []
}

async function saveAdmin() {
  if (!adminForm.value.steamId || !adminForm.value.name) {
    pluginStore.notifyWarning('请填写完整', '请填写 Steam ID 和名称')
    return
  }

  adminForm.value.flags = selectedFlags.value.join('')
  
  saving.value = true
  try {
    if (editingAdmin.value) {
      await adminApi.update(adminForm.value.steamId, {
        flags: adminForm.value.flags,
        immunity: adminForm.value.immunity
      })
      pluginStore.notifySuccess('更新成功', '管理员信息已更新')
    } else {
      await adminApi.add(adminForm.value)
      pluginStore.notifySuccess('添加成功', '管理员已添加')
    }
    
    cancelEdit()
    refreshAdmins()
  } catch (error) {
    pluginStore.notifyError('保存失败', '无法保存管理员信息')
  } finally {
    saving.value = false
  }
}

async function deleteAdmin(admin: AdminInfo) {
  const confirmed = await pluginStore.confirm(
    '确认删除',
    `确定要删除管理员 "${admin.name}" 吗？`
  )
  
  if (!confirmed) return

  try {
    await adminApi.delete(admin.steamId)
    pluginStore.notifySuccess('删除成功', '管理员已删除')
    refreshAdmins()
  } catch (error) {
    pluginStore.notifyError('删除失败', '无法删除管理员')
  }
}

onMounted(() => {
  refreshAdmins()
})
</script>

<style lang="scss" scoped>
.admins-page {
  height: 100%;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-actions {
  display: flex;
  gap: 12px;
}

.flags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.flags-card {
  .flag-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 0;
    
    .flag-label {
      font-size: 14px;
      color: var(--el-text-color-secondary);
    }
  }
}
</style>
