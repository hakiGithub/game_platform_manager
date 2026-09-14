<template>
  <el-dialog
    v-model="visible"
    title="识别为游戏实例"
    width="560px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div v-loading="loading">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      >
        <template #title>
          将容器「{{ container?.containerName }}」纳管为平台实例：不重新部署，
          仅创建实例记录并绑定容器，之后可在实例页启停与监控。
        </template>
      </el-alert>

      <el-form :model="form" label-width="90px">
        <el-form-item label="实例名称" required>
          <el-input v-model="form.instanceName" placeholder="同主机内唯一" />
        </el-form-item>

        <el-form-item label="游戏" required>
          <el-select
            v-model="form.gameId"
            filterable
            placeholder="选择游戏元数据"
            style="width: 100%"
            @change="onGameChange"
          >
            <el-option
              v-for="g in games"
              :key="g.id"
              :label="`${g.gameName} (${g.gameCode})`"
              :value="g.id"
            />
          </el-select>
          <div v-if="guessed" class="guess-tip">已按镜像名自动预选，可修改</div>
        </el-form-item>

        <el-form-item label="部署方式">
          <el-tag v-if="resolvedDeployType" size="small">{{ resolvedDeployType }}</el-tag>
          <span v-else class="field-tip">选择游戏后按游戏元数据自动判定</span>
        </el-form-item>

        <template v-if="resolvedDeployType === 'docker-compose'">
          <el-divider content-position="left">Compose 信息</el-divider>
          <el-form-item label="项目名" required>
            <el-input v-model="form.projectName" placeholder="com.docker.compose.project" />
          </el-form-item>
          <el-form-item label="工作目录" required>
            <el-input v-model="form.workDir" placeholder="容器 compose 的工作目录" />
          </el-form-item>
          <el-form-item label="服务名">
            <el-input v-model="form.serviceName" placeholder="可选" />
          </el-form-item>
        </template>

        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="onSubmit">
        认领
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from "vue";
import { ElMessage } from "element-plus";
import { adoptContainer, getContainerDetail } from "@/api/docker";
import { getGameList } from "@/api/game";

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  hostId: { type: Number, default: null },
  container: { type: Object, default: null },
});

const emit = defineEmits(["update:modelValue", "adopted"]);

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit("update:modelValue", v),
});

const loading = ref(false);
const submitting = ref(false);
const games = ref([]);
const containerDetail = ref(null);
const guessed = ref(false);

const form = ref({
  instanceName: "",
  gameId: null,
  projectName: "",
  workDir: "",
  serviceName: "",
  remark: "",
});

/** Docker 类 deployType 探测优先级（与后端 ADR-0023 一致） */
const ADOPTABLE = ["docker", "docker-compose", "linuxgsm-docker"];

const selectedGame = computed(() =>
  games.value.find((g) => g.id === form.value.gameId) || null,
);

const resolvedDeployType = computed(() => {
  const types = selectedGame.value?.supportedDeployTypes;
  if (!types || !types.length) return null;
  return ADOPTABLE.find((t) => types.includes(t)) || null;
});

const canSubmit = computed(
  () =>
    Boolean(form.value.instanceName.trim()) &&
    Boolean(form.value.gameId) &&
    (resolvedDeployType.value !== "docker-compose" ||
      (Boolean(form.value.projectName.trim()) && Boolean(form.value.workDir.trim()))),
);

async function onOpen() {
  form.value = {
    instanceName: props.container?.containerName || "",
    gameId: null,
    projectName: "",
    workDir: "",
    serviceName: "",
    remark: "",
  };
  guessed.value = false;
  containerDetail.value = null;
  loading.value = true;
  try {
    const [gameData, detail] = await Promise.all([
      getGameList({ status: 1 }),
      props.hostId && props.container?.containerId
        ? getContainerDetail(props.hostId, props.container.containerId)
        : Promise.resolve(null),
    ]);
    games.value = Array.isArray(gameData) ? gameData : gameData?.records || [];
    containerDetail.value = detail;

    // 按镜像名猜测游戏预选（猜不准可改）
    const imageName = (props.container?.imageName || detail?.imageName || "").toLowerCase();
    const hit = games.value.find(
      (g) => g.gameCode && imageName.includes(g.gameCode.toLowerCase()),
    );
    if (hit) {
      form.value.gameId = hit.id;
      guessed.value = true;
    }

    // compose labels 预填
    const labels = detail?.labels || {};
    form.value.projectName = labels["com.docker.compose.project"] || "";
    form.value.workDir = labels["com.docker.compose.project.working_dir"] || "";
    form.value.serviceName = labels["com.docker.compose.service"] || "";
  } catch (error) {
    ElMessage.error("加载认领信息失败: " + (error.message || "未知错误"));
  } finally {
    loading.value = false;
  }
}

async function onSubmit() {
  if (!props.hostId || !props.container?.containerId) return;
  submitting.value = true;
  try {
    const payload = {
      instanceName: form.value.instanceName.trim(),
      gameId: form.value.gameId,
      deployType: resolvedDeployType.value || undefined,
      remark: form.value.remark || undefined,
    };
    if (resolvedDeployType.value === "docker-compose") {
      payload.projectName = form.value.projectName.trim();
      payload.workDir = form.value.workDir.trim();
      payload.serviceName = form.value.serviceName.trim() || undefined;
    }
    const instanceId = await adoptContainer(
      props.hostId,
      props.container.containerId,
      payload,
    );
    ElMessage.success(`认领成功，已创建实例 #${instanceId}`);
    visible.value = false;
    emit("adopted", { instanceId, container: props.container });
  } catch (error) {
    ElMessage.error("认领失败: " + (error.message || "未知错误"));
  } finally {
    submitting.value = false;
  }
}

// 用户主动换选游戏后清除猜测提示（onOpen 的程序化预选不触发 change）
function onGameChange() {
  guessed.value = false;
}
</script>

<style scoped>
.guess-tip {
  font-size: 12px;
  color: var(--el-color-primary);
  margin-top: 4px;
}

.field-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
