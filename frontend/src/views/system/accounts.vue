<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>云盘账号</span>
          <el-button type="primary" :loading="loading" @click="openCreate">新增账号</el-button>
        </div>
      </template>

      <el-table :data="accounts" v-loading="loading" stripe>
        <el-table-column prop="name" label="账号标识" min-width="120" />
        <el-table-column prop="displayName" label="展示名" min-width="120" />
        <el-table-column prop="providerType" label="网盘" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ row.providerType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'HEALTHY' ? 'success' : 'danger'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="credentialHint" label="凭证" width="110" />
        <el-table-column prop="mountPath" label="挂载点" min-width="160" show-overflow-tooltip />
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleVerify(row)" :loading="row._verifying">探活</el-button>
            <el-button size="small" @click="openRootDir(row)">起始目录</el-button>
            <el-button size="small" @click="openCredential(row)">重贴凭证</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无云盘账号" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增账号 -->
    <el-dialog v-model="createVisible" title="新增云盘账号" width="560px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="网盘" prop="providerType">
          <el-select v-model="createForm.providerType" placeholder="选择网盘" style="width: 100%"
                     @change="onProviderChange">
            <el-option v-for="p in providers" :key="p.type" :value="p.type" :label="p.displayName || p.type" />
          </el-select>
        </el-form-item>
        <el-form-item label="账号标识" prop="name">
          <el-input v-model="createForm.name" placeholder="小写字母/数字/连字符，如 quark-main" />
        </el-form-item>
        <el-form-item label="展示名" prop="displayName">
          <el-input v-model="createForm.displayName" placeholder="可空，缺省用账号标识" />
        </el-form-item>
        <el-form-item v-for="f in currentFields" :key="f.name" :label="f.label"
                      :required="f.required">
          <el-input v-model="createForm.settings[f.name]" :type="f.secret ? 'password' : 'text'"
                    show-password autocomplete="new-password" :placeholder="f.hint || f.label" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" placeholder="可空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">保存并验证</el-button>
      </template>
    </el-dialog>

    <!-- 重贴凭证（仅凭证类字段；起始目录等其他字段由后端保留原值） -->
    <el-dialog v-model="credentialVisible" title="重贴凭证" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
                :title="`账号 ${credentialTarget?.name}（${credentialTarget?.providerType}）凭证过期后在此重新粘贴，起始目录保持不变`" />
      <el-form label-width="90px">
        <el-form-item v-for="f in credentialFields" :key="f.name" :label="f.label" :required="f.required">
          <el-input v-model="credentialForm[f.name]" :type="f.secret ? 'password' : 'text'"
                    show-password autocomplete="new-password" :placeholder="f.hint || f.label" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCredential">保存并验证</el-button>
      </template>
    </el-dialog>

    <!-- 起始目录（懒加载目录树，ADR-0024 增补） -->
    <el-dialog v-model="rootDirVisible" title="选择起始目录" width="560px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px"
                title="切换后路径命名空间随之变化，该账号已转存的产物需按新目录重新寻址/转存" />
      <el-form label-width="90px">
        <el-form-item label="目录">
          <el-cascader v-model="rootDirSelection" :props="rootDirProps" style="width: 100%"
                       placeholder="逐级选择，可选任意层级" clearable />
        </el-form-item>
        <el-form-item label="当前值">
          <el-input v-model="rootDirValue" placeholder="（选择目录后自动回填，也可手输）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rootDirVisible = false">取消</el-button>
        <el-button type="primary" :loading="rootDirSubmitting" @click="handleApplyRootDir">应用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  listCloudAccounts,
  createCloudAccount,
  replaceCloudCredential,
  deleteCloudAccount,
  verifyCloudAccount,
  listCloudProviders,
  listAccountFolders,
  setAccountRootDir,
} from "@/api/cloud";

const loading = ref(false);
const submitting = ref(false);
const accounts = ref([]);
const providers = ref([]);

const createVisible = ref(false);
const createFormRef = ref(null);
const createForm = reactive({ providerType: "", name: "", displayName: "", remark: "", settings: {} });
const createRules = {
  name: [{ required: true, message: "请输入账号标识", trigger: "blur" }],
  providerType: [{ required: true, message: "请选择网盘", trigger: "change" }],
};

const credentialVisible = ref(false);
const credentialTarget = ref(null);
const credentialForm = reactive({});

const currentFields = computed(
  () => providers.value.find((p) => p.type === createForm.providerType)?.fields || []
);
const credentialFields = computed(
  () => (providers.value.find((p) => p.type === credentialTarget.value?.providerType)?.fields || [])
    .filter((f) => f.secret)
);

// ===== 起始目录（懒加载级联，ADR-0024 增补） =====
const rootDirVisible = ref(false);
const rootDirSubmitting = ref(false);
const rootDirTarget = ref(null);
const rootDirSelection = ref([]);
const rootDirValue = ref("");
const rootDirIdBased = ref(false);

const rootDirProps = {
  lazy: true,
  checkStrictly: true,
  lazyLoad: async (node, resolve) => {
    try {
      const path = node.level === 0 ? "/" : node.data.path;
      const res = await listAccountFolders(rootDirTarget.value?.name, path);
      rootDirIdBased.value = !!res.idBased;
      const items = (res.folders || []).map((f) => ({
        value: f.value,
        label: f.name,
        path: f.path,
        leaf: false,
      }));
      if (node.level === 0) {
        // 根节点自身可选（整盘）：idBased → -11，路径语义 → /
        items.unshift({ value: rootDirIdBased.value ? "-11" : "/", label: "（根目录/整盘）", path: "/", leaf: true });
      }
      resolve(items);
    } catch (e) {
      ElMessage.error("目录浏览失败（凭证可能失效）：" + (e.message || e));
      resolve([]);
    }
  },
};

function openRootDir(row) {
  rootDirTarget.value = row;
  rootDirSelection.value = [];
  rootDirValue.value = "";
  rootDirVisible.value = true;
}

async function handleApplyRootDir() {
  const sel = rootDirSelection.value;
  const finalValue = Array.isArray(sel) ? sel[sel.length - 1] : (sel || rootDirValue.value);
  if (!finalValue || !String(finalValue).trim()) {
    ElMessage.warning("请选择目录或手输起始目录值");
    return;
  }
  rootDirSubmitting.value = true;
  try {
    await setAccountRootDir(rootDirTarget.value.name, String(finalValue).trim());
    ElMessage.success("起始目录已应用，默认挂载已重建");
    rootDirVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e.message || "应用失败");
  } finally {
    rootDirSubmitting.value = false;
  }
}

async function load() {
  loading.value = true;
  try {
    const [accRes, provRes] = await Promise.all([listCloudAccounts(), listCloudProviders()]);
    // 主前端 request 拦截器已解包 Result.data，直接就是数组
    accounts.value = accRes || [];
    providers.value = provRes || [];
  } catch (e) {
    ElMessage.error(e.message || "加载失败");
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  createForm.providerType = "";
  createForm.name = "";
  createForm.displayName = "";
  createForm.remark = "";
  createForm.settings = {};
  createVisible.value = true;
}

function onProviderChange() {
  createForm.settings = {};
}

function buildSettingsJson(fields, form) {
  const obj = {};
  for (const f of fields) {
    const v = form[f.name];
    if (v !== undefined && v !== "") obj[f.name] = v;
  }
  return JSON.stringify(obj);
}

async function handleCreate() {
  await createFormRef.value?.validate();
  submitting.value = true;
  try {
    await createCloudAccount({
      name: createForm.name.trim(),
      providerType: createForm.providerType,
      settingsJson: buildSettingsJson(currentFields.value, createForm.settings),
      displayName: createForm.displayName || undefined,
      remark: createForm.remark || undefined,
    });
    ElMessage.success("账号已添加并验证通过");
    createVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e.message || "添加失败（凭证无效或网盘不可达）");
  } finally {
    submitting.value = false;
  }
}

function openCredential(row) {
  credentialTarget.value = row;
  Object.keys(credentialForm).forEach((k) => delete credentialForm[k]);
  credentialVisible.value = true;
}

async function handleCredential() {
  submitting.value = true;
  try {
    await replaceCloudCredential(
      credentialTarget.value.name,
      buildSettingsJson(credentialFields.value, credentialForm)
    );
    ElMessage.success("凭证已更新");
    credentialVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e.message || "更新失败");
  } finally {
    submitting.value = false;
  }
}

async function handleVerify(row) {
  row._verifying = true;
  try {
    await verifyCloudAccount(row.name);
    ElMessage.success("探活通过");
    await load();
  } catch (e) {
    ElMessage.error(e.message || "探活失败");
  } finally {
    row._verifying = false;
  }
}

async function handleDelete(row) {
  await ElMessageBox.confirm(`确定删除云盘账号「${row.displayName || row.name}」？`, "删除确认", {
    type: "warning",
  });
  await deleteCloudAccount(row.name);
  ElMessage.success("已删除");
  await load();
}

onMounted(load);
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
