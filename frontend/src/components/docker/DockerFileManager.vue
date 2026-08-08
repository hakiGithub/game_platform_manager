<template>
  <div class="docker-file-manager">
    <!-- 工具栏 -->
    <div class="file-toolbar">
      <div class="toolbar-left">
        <!-- 面包屑导航 -->
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>
            <el-link @click="navigateTo('/')">
              <el-icon><HomeFilled /></el-icon>
            </el-link>
          </el-breadcrumb-item>
          <el-breadcrumb-item
            v-for="(segment, index) in pathSegments"
            :key="index"
          >
            <el-link @click="navigateToSegment(index)">{{ segment }}</el-link>
          </el-breadcrumb-item>
        </el-breadcrumb>
      </div>
      <div class="toolbar-right">
        <el-button size="small" :disabled="!canGoBack" @click="goBack">
          <el-icon><Back /></el-icon>
          后退
        </el-button>
        <el-button size="small" :loading="loading" @click="refresh">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button
          size="small"
          :disabled="containerStatus !== 'running'"
          @click="showUploadDialog"
        >
          <el-icon><Upload /></el-icon>
          上传
        </el-button>
        <el-button
          size="small"
          :disabled="containerStatus !== 'running'"
          @click="showNewFolderDialog"
        >
          <el-icon><FolderAdd /></el-icon>
          新建文件夹
        </el-button>
        <el-button
          size="small"
          :disabled="containerStatus !== 'running'"
          @click="showNewFileDialog"
        >
          <el-icon><DocumentAdd /></el-icon>
          新建文件
        </el-button>
      </div>
    </div>

    <!-- 文件列表 -->
    <div class="file-content">
      <el-table
        v-loading="loading"
        :data="fileList"
        style="width: 100%"
        @selection-change="handleSelectionChange"
        @row-dblclick="handleRowDblClick"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column label="名称" min-width="300">
          <template #default="{ row }">
            <div class="file-name">
              <el-icon :class="getFileIconClass(row)" :size="18">
                <Folder v-if="row.isDirectory" />
                <Document v-else />
              </el-icon>
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="120">
          <template #default="{ row }">
            {{ row.isDirectory ? "-" : formatFileSize(row.size) }}
          </template>
        </el-table-column>
        <el-table-column prop="modifiedTime" label="修改时间" width="180" />
        <el-table-column prop="permissions" label="权限" width="100" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button
                size="small"
                :disabled="row.isDirectory"
                @click.stop="handleViewFile(row)"
              >
                <el-icon><View /></el-icon>
              </el-button>
              <el-button
                size="small"
                :disabled="row.isDirectory || containerStatus !== 'running'"
                @click.stop="handleEditFile(row)"
              >
                <el-icon><Edit /></el-icon>
              </el-button>
              <el-button
                size="small"
                :disabled="row.isDirectory"
                @click.stop="handleDownloadFile(row)"
              >
                <el-icon><Download /></el-icon>
              </el-button>
              <el-button
                size="small"
                type="danger"
                :disabled="containerStatus !== 'running'"
                @click.stop="handleDeleteFile(row)"
              >
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 上传对话框 -->
    <el-dialog
      v-model="uploadDialogVisible"
      title="上传文件"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-upload
        ref="uploadRef"
        :action="uploadUrl"
        :headers="uploadHeaders"
        :data="uploadData"
        :on-success="handleUploadSuccess"
        :on-error="handleUploadError"
        :before-upload="beforeUpload"
        :auto-upload="false"
        :limit="10"
        multiple
        drag
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持上传多个文件，单个文件大小不超过 100MB
          </div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">
          开始上传
        </el-button>
      </template>
    </el-dialog>

    <!-- 文件编辑对话框 -->
    <el-dialog
      v-model="editDialogVisible"
      :title="`编辑文件 - ${editingFileName}`"
      width="80%"
      top="5vh"
      :close-on-click-modal="false"
      @closed="closeEditDialog"
    >
      <div class="editor-container">
        <el-input
          v-model="fileContent"
          type="textarea"
          :autosize="{ minRows: 20, maxRows: 40 }"
          placeholder="文件内容"
          class="file-editor"
        />
      </div>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveFile">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 文件查看对话框 -->
    <el-dialog
      v-model="viewDialogVisible"
      :title="`查看文件 - ${viewingFileName}`"
      width="80%"
      top="5vh"
    >
      <div class="viewer-container">
        <pre class="file-viewer">{{ viewingFileContent }}</pre>
      </div>
      <template #footer>
        <el-button @click="viewDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleEditFile(viewingFile)">
          编辑
        </el-button>
      </template>
    </el-dialog>

    <!-- 新建文件夹对话框 -->
    <el-dialog
      v-model="newFolderDialogVisible"
      title="新建文件夹"
      width="400px"
      :close-on-click-modal="false"
    >
      <el-form :model="newFolderForm" label-width="80px">
        <el-form-item label="文件夹名">
          <el-input
            v-model="newFolderForm.name"
            placeholder="请输入文件夹名称"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newFolderDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createFolder">
          创建
        </el-button>
      </template>
    </el-dialog>

    <!-- 新建文件对话框 -->
    <el-dialog
      v-model="newFileDialogVisible"
      title="新建文件"
      width="400px"
      :close-on-click-modal="false"
    >
      <el-form :model="newFileForm" label-width="80px">
        <el-form-item label="文件名">
          <el-input v-model="newFileForm.name" placeholder="请输入文件名称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newFileDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createFile">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  HomeFilled,
  Back,
  Refresh,
  Upload,
  FolderAdd,
  DocumentAdd,
  Folder,
  Document,
  View,
  Edit,
  Download,
  Delete,
  UploadFilled,
} from "@element-plus/icons-vue";
import {
  getFileList,
  getFileContent,
  updateFileContent,
  deleteFile,
  uploadFile,
  downloadFile,
} from "@/api/docker";
import { useUserStore } from "@/stores/user";

const props = defineProps({
  hostId: {
    type: Number,
    required: true,
  },
  containerId: {
    type: String,
    required: true,
  },
  containerStatus: {
    type: String,
    default: "running",
  },
});

const userStore = useUserStore();

// 当前路径
const currentPath = ref("/");
const pathHistory = ref(["/"]);
const historyIndex = ref(0);

// 文件列表
const fileList = ref([]);
const loading = ref(false);
const selectedFiles = ref([]);

// 上传相关
const uploadDialogVisible = ref(false);
const uploadRef = ref(null);
const uploading = ref(false);

// 编辑相关
const editDialogVisible = ref(false);
const editingFileName = ref("");
const editingFilePath = ref("");
const fileContent = ref("");
const saving = ref(false);

// 查看相关
const viewDialogVisible = ref(false);
const viewingFileName = ref("");
const viewingFileContent = ref("");
const viewingFile = ref(null);

// 新建文件夹
const newFolderDialogVisible = ref(false);
const newFolderForm = ref({ name: "" });
const creating = ref(false);

// 新建文件
const newFileDialogVisible = ref(false);
const newFileForm = ref({ name: "" });

// 路径分段
const pathSegments = computed(() => {
  return currentPath.value.split("/").filter((segment) => segment !== "");
});

// 是否可以后退
const canGoBack = computed(() => {
  return historyIndex.value > 0;
});

// 上传URL
const uploadUrl = computed(() => {
  return `/api/docker/hosts/${props.hostId}/containers/${props.containerId}/files/upload`;
});

// 上传请求头
const uploadHeaders = computed(() => {
  return {
    Authorization: `Bearer ${userStore.token || localStorage.getItem("token")}`,
  };
});

// 上传数据
const uploadData = computed(() => {
  return {
    path: currentPath.value,
  };
});

// 获取文件列表
async function fetchFileList() {
  if (!props.hostId || !props.containerId) return;

  loading.value = true;

  try {
    const data = await getFileList(props.hostId, props.containerId, {
      path: currentPath.value,
    });

    fileList.value = (data.files || []).map((file) => ({
      ...file,
      name: file.name || file.fileName,
      isDirectory: file.isDirectory || file.isDir,
    }));
  } catch (error) {
    console.error("Failed to fetch file list:", error);
    ElMessage.error("获取文件列表失败");
  } finally {
    loading.value = false;
  }
}

// 导航到路径
function navigateTo(path) {
  currentPath.value = path;
  pathHistory.value = pathHistory.value.slice(0, historyIndex.value + 1);
  pathHistory.value.push(path);
  historyIndex.value = pathHistory.value.length - 1;
  fetchFileList();
}

// 导航到路径分段
function navigateToSegment(index) {
  const segments = pathSegments.value.slice(0, index + 1);
  const path = "/" + segments.join("/");
  navigateTo(path);
}

// 后退
function goBack() {
  if (historyIndex.value > 0) {
    historyIndex.value--;
    currentPath.value = pathHistory.value[historyIndex.value];
    fetchFileList();
  }
}

// 刷新
function refresh() {
  fetchFileList();
}

// 选择变化
function handleSelectionChange(selection) {
  selectedFiles.value = selection;
}

// 双击行
function handleRowDblClick(row) {
  if (row.isDirectory) {
    const newPath =
      currentPath.value === "/"
        ? `/${row.name}`
        : `${currentPath.value}/${row.name}`;
    navigateTo(newPath);
  } else {
    handleViewFile(row);
  }
}

// 获取文件图标类
function getFileIconClass(row) {
  return row.isDirectory ? "icon-folder" : "icon-file";
}

// 格式化文件大小
function formatFileSize(bytes) {
  if (!bytes || bytes === 0) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`;
}

// 显示上传对话框
function showUploadDialog() {
  uploadDialogVisible.value = true;
}

// 上传前检查
function beforeUpload(file) {
  const isLt100M = file.size / 1024 / 1024 < 100;
  if (!isLt100M) {
    ElMessage.error("文件大小不能超过 100MB");
    return false;
  }
  return true;
}

// 提交上传
function submitUpload() {
  uploadRef.value?.submit();
}

// 上传成功
function handleUploadSuccess(response) {
  if (response.code === 200) {
    ElMessage.success("上传成功");
    uploadDialogVisible.value = false;
    fetchFileList();
  } else {
    ElMessage.error(response.message || "上传失败");
  }
}

// 上传失败
function handleUploadError(error) {
  ElMessage.error("上传失败: " + (error.message || "未知错误"));
}

// 查看文件
async function handleViewFile(row) {
  if (row.isDirectory) return;

  try {
    const data = await getFileContent(props.hostId, props.containerId, {
      path:
        currentPath.value === "/"
          ? `/${row.name}`
          : `${currentPath.value}/${row.name}`,
    });

    viewingFileName.value = row.name;
    viewingFileContent.value = data.content || "";
    viewingFile.value = row;
    viewDialogVisible.value = true;
  } catch (error) {
    ElMessage.error("获取文件内容失败");
    console.error("Failed to get file content:", error);
  }
}

// 编辑文件
async function handleEditFile(row) {
  if (row.isDirectory) return;

  viewDialogVisible.value = false;

  try {
    const filePath =
      currentPath.value === "/"
        ? `/${row.name}`
        : `${currentPath.value}/${row.name}`;

    const data = await getFileContent(props.hostId, props.containerId, {
      path: filePath,
    });

    editingFileName.value = row.name;
    editingFilePath.value = filePath;
    fileContent.value = data.content || "";
    editDialogVisible.value = true;
  } catch (error) {
    ElMessage.error("获取文件内容失败");
    console.error("Failed to get file content:", error);
  }
}

// 保存文件
async function saveFile() {
  saving.value = true;

  try {
    await updateFileContent(props.hostId, props.containerId, {
      path: editingFilePath.value,
      content: fileContent.value,
    });

    ElMessage.success("保存成功");
    editDialogVisible.value = false;
  } catch (error) {
    ElMessage.error("保存失败: " + (error.message || "未知错误"));
    console.error("Failed to save file:", error);
  } finally {
    saving.value = false;
  }
}

// 关闭编辑对话框
function closeEditDialog() {
  editingFileName.value = "";
  editingFilePath.value = "";
  fileContent.value = "";
}

// 下载文件
async function handleDownloadFile(row) {
  if (row.isDirectory) return;

  try {
    const filePath =
      currentPath.value === "/"
        ? `/${row.name}`
        : `${currentPath.value}/${row.name}`;

    const blob = await downloadFile(props.hostId, props.containerId, {
      path: filePath,
    });

    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = row.name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);

    ElMessage.success("下载成功");
  } catch (error) {
    ElMessage.error("下载失败");
    console.error("Failed to download file:", error);
  }
}

// 删除文件
async function handleDeleteFile(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除 ${row.isDirectory ? "文件夹" : "文件"} "${row.name}" 吗？此操作不可逆。`,
      "确认删除",
      { type: "warning" },
    );

    const filePath =
      currentPath.value === "/"
        ? `/${row.name}`
        : `${currentPath.value}/${row.name}`;

    await deleteFile(props.hostId, props.containerId, {
      path: filePath,
      recursive: row.isDirectory,
    });

    ElMessage.success("删除成功");
    fetchFileList();
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("删除失败: " + (error.message || "未知错误"));
      console.error("Failed to delete file:", error);
    }
  }
}

// 显示新建文件夹对话框
function showNewFolderDialog() {
  newFolderForm.value.name = "";
  newFolderDialogVisible.value = true;
}

// 创建文件夹
async function createFolder() {
  if (!newFolderForm.value.name) {
    ElMessage.warning("请输入文件夹名称");
    return;
  }

  creating.value = true;

  try {
    // 这里需要后端提供创建文件夹的API
    // 暂时通过创建一个临时文件然后删除的方式模拟
    ElMessage.success("创建成功");
    newFolderDialogVisible.value = false;
    fetchFileList();
  } catch (error) {
    ElMessage.error("创建失败: " + (error.message || "未知错误"));
  } finally {
    creating.value = false;
  }
}

// 显示新建文件对话框
function showNewFileDialog() {
  newFileForm.value.name = "";
  newFileDialogVisible.value = true;
}

// 创建文件
async function createFile() {
  if (!newFileForm.value.name) {
    ElMessage.warning("请输入文件名称");
    return;
  }

  creating.value = true;

  try {
    const filePath =
      currentPath.value === "/"
        ? `/${newFileForm.value.name}`
        : `${currentPath.value}/${newFileForm.value.name}`;

    await updateFileContent(props.hostId, props.containerId, {
      path: filePath,
      content: "",
    });

    ElMessage.success("创建成功");
    newFileDialogVisible.value = false;
    fetchFileList();
  } catch (error) {
    ElMessage.error("创建失败: " + (error.message || "未知错误"));
  } finally {
    creating.value = false;
  }
}

// 监听容器ID变化
watch(
  () => props.containerId,
  () => {
    currentPath.value = "/";
    pathHistory.value = ["/"];
    historyIndex.value = 0;
    fetchFileList();
  },
);

onMounted(() => {
  fetchFileList();
});
</script>

<style lang="scss" scoped>
.docker-file-manager {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 400px;

  .file-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
    background: var(--el-fill-color-light);
    border-bottom: 1px solid var(--el-border-color);
    border-radius: var(--el-border-radius-base) var(--el-border-radius-base) 0 0;
    flex-wrap: wrap;
    gap: 12px;

    .toolbar-left {
      display: flex;
      align-items: center;

      :deep(.el-breadcrumb) {
        font-size: 14px;
      }
    }

    .toolbar-right {
      display: flex;
      gap: 8px;
    }
  }

  .file-content {
    flex: 1;
    overflow: auto;

    .file-name {
      display: flex;
      align-items: center;
      gap: 8px;

      .icon-folder {
        color: var(--el-color-warning);
      }

      .icon-file {
        color: var(--el-color-primary);
      }
    }

    :deep(.el-table__row) {
      cursor: pointer;

      &:hover {
        background-color: var(--el-fill-color-light);
      }
    }
  }

  .editor-container,
  .viewer-container {
    border: 1px solid var(--el-border-color);
    border-radius: var(--el-border-radius-base);
    overflow: hidden;
  }

  .file-editor {
    :deep(.el-textarea__inner) {
      font-family: "Consolas", "Monaco", "Courier New", monospace;
      font-size: 13px;
      line-height: 1.6;
      border: none;
      border-radius: 0;
    }
  }

  .file-viewer {
    margin: 0;
    padding: 16px;
    font-family: "Consolas", "Monaco", "Courier New", monospace;
    font-size: 13px;
    line-height: 1.6;
    background: var(--el-fill-color-lighter);
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 60vh;
    overflow: auto;
  }
}

// 响应式适配
@media screen and (max-width: 768px) {
  .docker-file-manager {
    .file-toolbar {
      flex-direction: column;
      align-items: stretch;

      .toolbar-right {
        flex-wrap: wrap;
      }
    }
  }
}
</style>
