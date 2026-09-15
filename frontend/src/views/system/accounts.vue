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
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleVerify(row)" :loading="row._verifying">探活</el-button>
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

    <!-- 重贴凭证 -->
    <el-dialog v-model="credentialVisible" title="重贴凭证" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
                :title="`账号 ${credentialTarget?.name}（${credentialTarget?.providerType}）凭证过期后在此重新粘贴`" />
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
  () => providers.value.find((p) => p.type === credentialTarget.value?.providerType)?.fields || []
);

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
