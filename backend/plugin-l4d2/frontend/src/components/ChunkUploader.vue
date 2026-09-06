<template>
  <div class="chunk-uploader">
    <el-upload
      ref="uploadRef"
      :auto-upload="false"
      :accept="accept"
      :show-file-list="false"
      :on-change="handleFileChange"
    >
      <el-button type="primary" :loading="uploading">
        {{ uploading ? '上传中...' : '选择文件上传' }}
      </el-button>
    </el-upload>

    <!-- 进度弹窗：HTTP 上传段（0-40%）+ 服务端任务处理段（40-100%），等待直到处理完成 -->
    <TaskProgressDialog
      v-model:visible="dialogVisible"
      title="上传地图"
      :phase="dialogPhase"
      :poll-fn="mainTaskApi.detail"
      :task-id="taskId"
      :external-percent="externalPercent"
      :external-message="externalMessage"
      :task-range="[40, 100]"
      :cancellable="canCancel"
      @completed="onTaskCompleted"
      @failed="onTaskFailed"
      @cancel="onDialogCancel"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { mapApi, MapUploadSubmit, chunkUploadApi, mainTaskApi } from '@/api'
import TaskProgressDialog from '@/components/TaskProgressDialog.vue'

const props = withDefaults(defineProps<{
  instanceId: number
  targetPath?: string
  accept?: string
}>(), {
  accept: '.vpk,.zip,.rar,.7z',
  targetPath: undefined,
})

const emit = defineEmits<{
  success: [result: any]
  error: [error: any]
  progress: [percent: number]
}>()

const CHUNK_THRESHOLD = 100 * 1024 * 1024 // 100MB
const CHUNK_SIZE = 5 * 1024 * 1024 // 5MB

const uploadRef = ref()
const uploading = ref(false)
const cancelled = ref(false)
const currentUploadId = ref<string | null>(null)
const dialogVisible = ref(false)
const dialogPhase = ref<'external' | 'task'>('external')
const externalPercent = ref(0)
const externalMessage = ref('')
const taskId = ref('')

const canCancel = computed(() => currentUploadId.value !== null)
/** 已提交到执行队列的任务信息（弹窗成功事件透传给父组件） */
const lastSubmitted = ref<MapUploadSubmit | null>(null)

async function handleFileChange(file: UploadFile) {
  if (!file.raw) return
  await uploadFile(file.raw)
}

async function uploadFile(file: File) {
  uploading.value = true
  cancelled.value = false
  dialogPhase.value = 'external'
  externalPercent.value = 0
  externalMessage.value = '准备上传...'
  taskId.value = ''
  dialogVisible.value = true

  let submitted: MapUploadSubmit | null = null
  try {
    if (file.size > CHUNK_THRESHOLD) {
      submitted = await uploadByChunks(file)
    } else {
      submitted = await uploadDirect(file)
    }
    if (cancelled.value) return
    if (!submitted?.taskId) {
      // 兼容：后端未返回 taskId（无任务语义），直接按提交成功处理
      finishSuccess()
      return
    }
    // HTTP 上传完成（0-40%），切换到任务轮询段（40-100%）
    lastSubmitted.value = submitted
    taskId.value = submitted.taskId
    dialogPhase.value = 'task'
  } catch (e: any) {
    showError('上传失败: ' + (e?.message || e), e)
  }
}

/** HTTP 传输进度映射到弹窗 0-40% 段 */
function setExternalProgress(p: number, message: string) {
  externalPercent.value = p * 0.4
  externalMessage.value = message
  emit('progress', p)
}

async function uploadDirect(file: File): Promise<MapUploadSubmit> {
  externalMessage.value = '上传中...'
  // 注意：HTTP 层完成 = 文件暂存并提交执行队列；VPK 解析/写入服务器由任务异步处理
  return await mapApi.upload(file, props.instanceId, (p: number) => {
    setExternalProgress(p, `上传中 ${p}%...`)
  })
}

async function uploadByChunks(file: File): Promise<MapUploadSubmit> {
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
  externalMessage.value = `初始化分片上传（共 ${totalChunks} 片）...`

  const initResp = await chunkUploadApi.init({
    instanceId: props.instanceId,
    filename: file.name,
    totalSize: file.size,
    totalChunks,
    targetPath: props.targetPath,
  })
  const uploadId = initResp.uploadId
  currentUploadId.value = uploadId

  // 串行上传每个分片
  for (let i = 0; i < totalChunks; i++) {
    if (currentUploadId.value === null) {
      throw new Error('上传已取消')
    }
    const start = i * CHUNK_SIZE
    const end = Math.min(start + CHUNK_SIZE, file.size)
    const chunk = file.slice(start, end)
    // 包装为 File 以满足 upload 函数签名
    const chunkFile = new File([chunk], `${file.name}.chunk-${i}`, {
      type: 'application/octet-stream',
    })

    setExternalProgress((i / totalChunks) * 100, `上传分片中（${i + 1} / ${totalChunks}）...`)
    await chunkUploadApi.uploadChunk(uploadId, i, chunkFile)
    setExternalProgress(((i + 1) / totalChunks) * 100, `上传分片中（${i + 1} / ${totalChunks}）...`)
  }

  if (currentUploadId.value === null) {
    throw new Error('上传已取消')
  }

  externalMessage.value = '合并分片中...'
  const completeResp = await chunkUploadApi.complete(uploadId)
  currentUploadId.value = null
  // 压缩包合并后提交 map-upload 任务，后续进度走任务轮询段
  return {
    taskId: completeResp?.taskId || '',
    filename: file.name,
    size: file.size,
  }
}

function finishSuccess() {
  dialogVisible.value = false
  uploading.value = false
  ElMessage.success('地图上传完成')
  emit('success', lastSubmitted.value)
}

function onTaskCompleted() {
  finishSuccess()
}

function onTaskFailed(st: { status: string; errorMessage?: string; resultSummary?: string }) {
  dialogVisible.value = false
  uploading.value = false
  ElMessage.error('地图处理失败：' + (st.errorMessage || st.resultSummary || st.status))
  emit('error', new Error(st.errorMessage || st.status))
}

function showError(msg: string, e: any) {
  dialogVisible.value = false
  uploading.value = false
  ElMessage.error(msg)
  emit('error', e)
}

async function onDialogCancel() {
  if (!currentUploadId.value) return
  try {
    await ElMessageBox.confirm('确定取消上传？已上传的分片将被清理', '取消上传', {
      type: 'warning',
    })
    await chunkUploadApi.cancel(currentUploadId.value)
    currentUploadId.value = null
    cancelled.value = true
    uploading.value = false
    dialogVisible.value = false
    ElMessage.info('上传已取消')
  } catch {
    // 用户点了取消按钮
  }
}
</script>

<style scoped>
.chunk-uploader {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
