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

    <el-progress
      v-if="uploading || progress > 0"
      :percentage="progress"
      :status="progressStatus"
      :stroke-width="20"
      :text-inside="true"
    />

    <div v-if="uploading || statusText" class="upload-info">
      <span>{{ statusText }}</span>
      <el-button
        v-if="uploading"
        type="danger"
        size="small"
        @click="cancelUpload"
        :disabled="!canCancel"
      >
        取消
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { mapApi, chunkUploadApi } from '@/api'

const props = withDefaults(defineProps<{
  instanceId: number
  targetPath?: string
  accept?: string
}>(), {
  accept: '.vpk',
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
const progress = ref(0)
const currentUploadId = ref<string | null>(null)
const statusText = ref('')
const cancelled = ref(false)

const progressStatus = computed(() => {
  if (progress.value >= 100) return 'success'
  if (progress.value < 0) return 'exception'
  return ''
})

const canCancel = computed(() => currentUploadId.value !== null)

async function handleFileChange(file: UploadFile) {
  if (!file.raw) return
  await uploadFile(file.raw)
}

async function uploadFile(file: File) {
  uploading.value = true
  progress.value = 0
  cancelled.value = false
  statusText.value = '准备上传...'

  try {
    if (file.size > CHUNK_THRESHOLD) {
      await uploadByChunks(file)
    } else {
      await uploadDirect(file)
    }
    if (cancelled.value) return
    progress.value = 100
    statusText.value = '上传完成'
    ElMessage.success('上传成功')
    emit('success', null)
  } catch (e: any) {
    progress.value = -1
    statusText.value = '上传失败: ' + (e?.message || e)
    ElMessage.error('上传失败: ' + (e?.message || e))
    emit('error', e)
  } finally {
    uploading.value = false
    currentUploadId.value = null
    // 重置 upload 组件，允许再次选择同一文件
    if (uploadRef.value) {
      uploadRef.value.clearFiles()
    }
  }
}

async function uploadDirect(file: File) {
  statusText.value = '直接上传中...'
  await mapApi.upload(file, props.instanceId, (p: number) => {
    progress.value = Math.floor(p)
    emit('progress', p)
  })
}

async function uploadByChunks(file: File) {
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
  statusText.value = `初始化分片上传（共 ${totalChunks} 片）...`

  const initResp = await chunkUploadApi.init({
    instanceId: props.instanceId,
    filename: file.name,
    totalSize: file.size,
    totalChunks,
    targetPath: props.targetPath,
  })
  const uploadId = initResp.uploadId
  currentUploadId.value = uploadId

  statusText.value = `上传分片中（0 / ${totalChunks}）...`

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

    statusText.value = `上传分片中（${i + 1} / ${totalChunks}）...`
    await chunkUploadApi.uploadChunk(uploadId, i, chunkFile)
    progress.value = Math.floor(((i + 1) / totalChunks) * 100)
    emit('progress', progress.value)
  }

  if (currentUploadId.value === null) {
    throw new Error('上传已取消')
  }

  statusText.value = '合并分片中...'
  await chunkUploadApi.complete(uploadId)
  currentUploadId.value = null
}

async function cancelUpload() {
  if (!currentUploadId.value) return
  try {
    await ElMessageBox.confirm('确定取消上传？已上传的分片将被清理', '取消上传', {
      type: 'warning',
    })
    await chunkUploadApi.cancel(currentUploadId.value)
    currentUploadId.value = null
    cancelled.value = true
    uploading.value = false
    progress.value = 0
    statusText.value = '已取消'
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
.upload-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
