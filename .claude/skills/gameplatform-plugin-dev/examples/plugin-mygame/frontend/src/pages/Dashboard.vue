<template>
  <div class="dashboard-page">
    <div class="page-header">
      <h1 class="page-title">仪表盘</h1>
      <el-tag type="info" size="large">mode: {{ pluginStore.mode }}</el-tag>
    </div>

    <el-card class="status-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>当前实例</span>
          <el-button type="primary" plain size="small" @click="refresh" :loading="loading">刷新</el-button>
        </div>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="实例ID">{{ pluginStore.instanceInfo?.instanceId || '未选中' }}</el-descriptions-item>
        <el-descriptions-item label="实例名">{{ pluginStore.instanceInfo?.instanceName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="主机ID">{{ pluginStore.instanceInfo?.hostId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="游戏">{{ pluginStore.instanceInfo?.gameCode || 'mygame' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="notes-card" shadow="hover">
      <template #header>
        <div class="card-header">
          <span>笔记（ExtensionClient demo）</span>
          <el-button type="success" plain size="small" @click="showCreateDialog = true">+ 新建</el-button>
        </div>
      </template>

      <el-table :data="notes" v-loading="loading" empty-text="暂无笔记，点击右上角新建">
        <el-table-column prop="title" label="标题" min-width="120" />
        <el-table-column prop="content" label="内容" min-width="200" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="80" align="center" />
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="180" align="center">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建对话框 -->
    <el-dialog v-model="showCreateDialog" title="新建笔记" width="500px">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="createForm.content" type="textarea" :rows="4" placeholder="请输入内容" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="create" :loading="creating">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑对话框 -->
    <el-dialog v-model="showEditDialog" title="编辑笔记" width="500px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="editForm.title" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="editForm.content" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="置顶">
          <el-switch v-model="editForm.pinned" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="saveEdit" :loading="editing">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { usePluginStore } from '@/stores/plugin'
import { noteApi, type NoteVO } from '@/api/note'

const pluginStore = usePluginStore()

const notes = ref<NoteVO[]>([])
const loading = ref(false)

const showCreateDialog = ref(false)
const creating = ref(false)
const createForm = ref({ title: '', content: '' })

const showEditDialog = ref(false)
const editing = ref(false)
const editForm = ref<{ id: string; version: number; title: string; content: string; pinned: boolean }>({
  id: '', version: 0, title: '', content: '', pinned: false
})

async function refresh() {
  if (!pluginStore.instanceInfo?.instanceId) {
    ElMessage.warning('未选中实例（Wujie 模式下由主应用下发，dev 模式需手动设置）')
    return
  }
  loading.value = true
  try {
    notes.value = await noteApi.list(pluginStore.instanceInfo.instanceId)
  } catch (e: any) {
    ElMessage.error('加载失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!pluginStore.instanceInfo?.instanceId) {
    ElMessage.warning('未选中实例')
    return
  }
  if (!createForm.value.title.trim()) {
    ElMessage.warning('请输入标题')
    return
  }
  creating.value = true
  try {
    await noteApi.create({
      instanceId: pluginStore.instanceInfo.instanceId,
      title: createForm.value.title,
      content: createForm.value.content
    })
    ElMessage.success('创建成功')
    showCreateDialog.value = false
    createForm.value = { title: '', content: '' }
    await refresh()
  } catch (e: any) {
    ElMessage.error('创建失败: ' + e.message)
  } finally {
    creating.value = false
  }
}

function openEdit(row: NoteVO) {
  editForm.value = {
    id: row.id,
    version: row.version,
    title: row.title,
    content: row.content || '',
    pinned: row.pinned || false
  }
  showEditDialog.value = true
}

async function saveEdit() {
  editing.value = true
  try {
    await noteApi.update(editForm.value.id, {
      version: editForm.value.version,
      title: editForm.value.title,
      content: editForm.value.content,
      pinned: editForm.value.pinned
    })
    ElMessage.success('保存成功')
    showEditDialog.value = false
    await refresh()
  } catch (e: any) {
    ElMessage.error('保存失败: ' + e.message)
  } finally {
    editing.value = false
  }
}

async function remove(row: NoteVO) {
  try {
    await ElMessageBox.confirm(`确定删除笔记「${row.title}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await noteApi.delete(row.id)
    ElMessage.success('删除成功')
    await refresh()
  } catch (e: any) {
    ElMessage.error('删除失败: ' + e.message)
  }
}

onMounted(() => {
  refresh()
})
</script>

<style lang="scss" scoped>
.dashboard-page {
  padding: 20px;

  .page-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20px;

    .page-title {
      margin: 0;
      font-size: 22px;
      font-weight: 600;
    }
  }

  .status-card,
  .notes-card {
    margin-bottom: 20px;

    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
  }
}
</style>
