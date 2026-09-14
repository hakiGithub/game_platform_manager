<template>
  <div class="admins-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / ADMINS</span>
        <h2>管理员管理</h2>
        <p>SourceMod 管理员账号与权限旗标管理</p>
      </div>
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
      <el-card shadow="never" class="page-card admins-card">
        <el-table
          :data="admins"
          style="width: 100%"
          v-loading="loading"
        >
          <el-table-column prop="remark" label="备注/名称" min-width="150">
            <template #default="{ row }">
              {{ row.remark || '-' }}
            </template>
          </el-table-column>

          <el-table-column prop="steamId" label="Steam ID" min-width="200">
            <template #default="{ row }">
              <el-tag type="info">{{ row.steamId }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="adminFlags" label="权限" min-width="200">
            <template #default="{ row }">
              <div class="flags-list">
                <el-tag
                  v-for="flag in parseFlags(row.adminFlags)"
                  :key="flag"
                  size="small"
                  style="margin-right: 4px"
                >
                  {{ flag }}
                </el-tag>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="isActive" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.isActive === false ? 'info' : 'success'" size="small">
                {{ row.isActive === false ? '已禁用' : '激活' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="createTime" label="添加时间" width="180">
            <template #default="{ row }">
              {{ row.createTime ? formatDate(row.createTime) : '-' }}
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
      <el-card shadow="never" class="page-card flags-card" style="margin-top: 20px">
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

          <el-form-item label="备注/名称">
            <el-input v-model="adminForm.remark" placeholder="管理员备注（可选）" />
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
import { ref, computed, onMounted } from 'vue'
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
  remark: '',
  adminFlags: ''
})

/** 当前实例 ID（插件工作区上下文注入） */
const instanceId = computed(() => pluginStore.instanceInfo?.instanceId)

// 方法
async function refreshAdmins() {
  if (!instanceId.value) {
    pluginStore.notifyWarning('实例未就绪', '请先选择实例')
    return
  }
  loading.value = true
  try {
    admins.value = (await adminApi.getList(instanceId.value)) || []
  } catch (error) {
    pluginStore.notifyError('获取失败', '无法获取管理员列表')
  } finally {
    loading.value = false
  }
}

function parseFlags(flags: string): string[] {
  return (flags || '').split('').map(f => {
    const flagInfo = Object.values(ADMIN_FLAGS).find(info => info.flag === f)
    return flagInfo?.label || f
  })
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString()
}

function editAdmin(admin: AdminInfo) {
  editingAdmin.value = admin
  adminForm.value = {
    steamId: admin.steamId,
    remark: admin.remark || '',
    adminFlags: admin.adminFlags || ''
  }
  selectedFlags.value = (admin.adminFlags || '').split('')
  showAddDialog.value = true
}

function cancelEdit() {
  showAddDialog.value = false
  editingAdmin.value = null
  adminForm.value = {
    steamId: '',
    remark: '',
    adminFlags: ''
  }
  selectedFlags.value = []
}

async function saveAdmin() {
  if (!instanceId.value) {
    pluginStore.notifyWarning('实例未就绪', '请先选择实例')
    return
  }
  if (!adminForm.value.steamId) {
    pluginStore.notifyWarning('请填写完整', '请填写 Steam ID')
    return
  }

  adminForm.value.adminFlags = selectedFlags.value.join('')

  saving.value = true
  try {
    if (editingAdmin.value) {
      await adminApi.updateFlags(instanceId.value, adminForm.value.steamId, adminForm.value.adminFlags)
      pluginStore.notifySuccess('更新成功', '管理员权限已更新')
    } else {
      await adminApi.add(instanceId.value, {
        steamId: adminForm.value.steamId,
        adminFlags: adminForm.value.adminFlags,
        remark: adminForm.value.remark
      })
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
  if (!instanceId.value) return
  const confirmed = await pluginStore.confirm(
    '确认删除',
    `确定要删除管理员 "${admin.remark || admin.steamId}" 吗？`
  )

  if (!confirmed) return

  try {
    await adminApi.delete(instanceId.value, admin.steamId)
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

.admins-card,
.flags-card {
  background: var(--platform-surface-1);
  border: 1px solid var(--platform-line);
  border-radius: 6px;
  box-shadow: none;
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
      color: var(--platform-text-regular);
    }
  }
}
</style>
