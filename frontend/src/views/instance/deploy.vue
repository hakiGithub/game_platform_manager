<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createInstance,
  checkEnvironment,
  checkPort,
  getDeployProgress,
} from "@/api/instance";
import { getHostList, getHostResources } from "@/api/host";
import { getGameList, getDeployConfig } from "@/api/game";
import DeployProgress from "@/components/DeployProgress.vue";
import DeployVariableForm from "@/components/DeployVariableForm.vue";

const router = useRouter();
const route = useRoute();

// 加载状态
const loading = ref(false);
const checkingEnv = ref(false);
const checkingPort = ref(false);

// 当前步骤
const currentStep = ref(0);

// 步骤配置
const steps = [
  { title: "选择主机", icon: "Monitor" },
  { title: "选择游戏", icon: "Grid" },
  { title: "配置参数", icon: "Setting" },
  { title: "环境校验", icon: "CircleCheck" },
  { title: "确认部署", icon: "Check" },
];

const stepDescriptions = [
  "锁定一台在线节点，确认资源水位与 SSH 通道可用。",
  "选择运行时模板，并确认部署方式、默认端口和环境依赖。",
  "填写实例标识、端口、资源限制与游戏运行参数。",
  "在真正执行前检查节点环境、端口占用和运行时条件。",
  "复核部署契约，确认后将创建一条可追踪的部署任务。",
];
const activeStepDescription = computed(
  () => stepDescriptions[currentStep.value] || "完成当前阶段后继续。",
);

// 主机列表
const hostList = ref([]);
const selectedHost = ref(null);

// 游戏列表
const gameList = ref([]);
// 游戏搜索关键词（过滤 gameName / gameCode）
const gameKeyword = ref("");
const filteredGameList = computed(() => {
  const kw = gameKeyword.value.trim().toLowerCase();
  if (!kw) return gameList.value;
  return gameList.value.filter(
    (g) =>
      (g.gameName || "").toLowerCase().includes(kw) ||
      (g.gameCode || "").toLowerCase().includes(kw),
  );
});
const selectedGame = ref(null);
const selectedDeployMethod = ref("docker");

// 资源限制仅对 Docker 类部署生效（ADR-0010 D6）：native/linuxgsm 进程部署不消费 resources
const isDockerLikeDeploy = computed(() =>
  ["docker", "docker-compose", "linuxgsm-docker"].includes(selectedDeployMethod.value)
);

// 表单数据
const deployForm = reactive({
  name: "",
  port: 25565,
  autoStart: true,
  config: {},
  // 资源限制
  resources: {
    cpuLimit: 2,
    memoryLimit: 4,
    diskLimit: 10,
  },
  // 环境变量
  envVars: [],
  // 部署路径
  deployPath: "",
  // Docker 选项（仅 docker/docker-compose/linuxgsm-docker 部署类型显示）
  mountHostCerts: false, // 是否挂载宿主机 SSL 证书到容器（用于反向代理场景），默认关闭
  hostCertPath: "/etc/ssl/certs/ca-certificates.crt", // 宿主机 CA 证书路径
  // 附加端口（除主端口 game 之外的端口，如 query/rcon/steam 等）
  // 结构：{ query: 27016, rcon: 27017, steam: 27005 }
  additionalPorts: {},
});

// 表单引用
const formRef = ref(null);

// 表单验证规则
const rules = {
  name: [
    { required: true, message: "请输入实例名称", trigger: "blur" },
    { min: 2, max: 50, message: "实例名称长度为2-50个字符", trigger: "blur" },
  ],
  port: [
    { required: true, message: "请输入端口", trigger: "blur" },
    {
      type: "number",
      min: 1,
      max: 65535,
      message: "端口范围为1-65535",
      trigger: "blur",
    },
  ],
  deployPath: [{ required: true, message: "请输入部署路径", trigger: "blur" }],
};

// 端口可用性
const portAvailable = ref(null);
const portCheckMessage = ref("");

// 附加端口可用性状态
// 结构：{ query: { available: true, message: "端口可用" }, ... }
const additionalPortStatus = ref({});
const checkingAdditionalPorts = ref(false);

// 环境校验结果
const envCheckResult = ref([]);
const envCheckPassed = ref(false);

// 部署进度
const showDeployProgress = ref(false);
const deployTaskId = ref("");

// Docker Compose 变量配置
const deployVariables = ref([]);
const deployVariablesValues = reactive({});
const loadingDeployConfig = ref(false);
const variableFormRef = ref(null);

// 部署方式选项
const deployMethodOptions = [
  {
    value: "docker",
    label: "Docker",
    icon: "Box",
    description: "使用Docker容器部署，隔离性好，易于管理",
  },
  {
    value: "docker-compose",
    label: "Docker Compose",
    icon: "CopyDocument",
    description: "使用Docker Compose部署，适合多容器应用",
  },
  {
    value: "linuxgsm",
    label: "LinuxGSM",
    icon: "Platform",
    description: "使用LinuxGSM脚本部署，适合游戏服务器",
  },
  {
    value: "linuxgsm-docker",
    label: "LinuxGSM Docker",
    icon: "Monitor",
    description:
      "基于gameservermanagers/gameserver镜像，将LinuxGSM封装在Docker容器中运行，支持自动安装与持久化",
  },
  {
    value: "native",
    label: "原生部署",
    icon: "Cpu",
    description: "直接在主机上部署，性能最优",
  },
];

// 获取主机列表
async function fetchHostList() {
  loading.value = true;
  try {
    const data = await getHostList({ pageSize: 100, status: 1 });
    hostList.value = data.records || [];

    // 加载每个主机的资源信息
    for (const host of hostList.value) {
      try {
        const resources = await getHostResources(host.id).catch(() => null);
        host.resources = resources;
      } catch (e) {
        host.resources = null;
      }
    }
  } catch (error) {
    console.error("Failed to fetch host list:", error);
    ElMessage.error("获取主机列表失败");
  } finally {
    loading.value = false;
  }
}

// 获取游戏列表
async function fetchGameList() {
  try {
    const data = await getGameList({ status: 1 });
    gameList.value = data || [];
    // 如果 URL 携带 gameId 参数，自动选中该游戏
    const queryGameId = route.query.gameId;
    if (queryGameId) {
      const target = gameList.value.find(
        (g) => String(g.id) === String(queryGameId),
      );
      if (target) {
        selectGame(target);
      }
    }
  } catch (error) {
    console.error("Failed to fetch game list:", error);
    ElMessage.error("获取游戏列表失败");
  }
}

// 加载部署配置（docker-compose 类型的变量定义）
// 是否为需要 Compose 变量配置的部署类型
function isComposeVariableDeploy() {
  return ["docker-compose", "linuxgsm-docker"].includes(
    selectedDeployMethod.value,
  );
}

async function loadDeployConfig() {
  if (!isComposeVariableDeploy() || !selectedGame.value) {
    deployVariables.value = [];
    return;
  }
  loadingDeployConfig.value = true;
  try {
    const data = await getDeployConfig(
      selectedGame.value.id,
      selectedDeployMethod.value,
    );
    deployVariables.value = data.variables || [];
    // 重置变量值，并回填每个变量的默认值
    // 之前未回填默认值，导致提交时变量值为空，后端 .env 生成缺失关键变量
    Object.keys(deployVariablesValues).forEach(
      (k) => delete deployVariablesValues[k],
    );
    deployVariables.value.forEach((v) => {
      if (v.defaultValue !== undefined && v.defaultValue !== null) {
        deployVariablesValues[v.name] = v.defaultValue;
      }
    });
  } catch (error) {
    console.error("Failed to load deploy config:", error);
    ElMessage.error("加载部署配置失败: " + (error.message || "未知错误"));
    deployVariables.value = [];
  } finally {
    loadingDeployConfig.value = false;
  }
}

// 选择主机
function selectHost(host) {
  selectedHost.value = host;
}

// 选择游戏
function selectGame(game) {
  selectedGame.value = game;
  // 设置默认部署方式（后端字段为 supportedDeployTypes: string[]）
  const supportMethods =
    Array.isArray(game.supportedDeployTypes) && game.supportedDeployTypes.length > 0
      ? game.supportedDeployTypes
      : ["docker"];
  selectedDeployMethod.value = supportMethods[0] || "docker";

  // 设置默认端口
  // 优先使用 deployConfig.defaultPorts 中的 game 端口；其次 defaultPort 字段；最后兜底 25565
  const defaultPortsMap = getDefaultPortsMap(game);
  const mainPort =
    defaultPortsMap.game ||
    game.defaultPort ||
    (Object.keys(defaultPortsMap).length > 0
      ? defaultPortsMap[Object.keys(defaultPortsMap)[0]]
      : 25565);
  deployForm.port = mainPort;

  // 初始化附加端口（除 game 之外的所有默认端口）
  // 用户可在部署向导中编辑这些端口值
  const additionalPorts = {};
  Object.entries(defaultPortsMap).forEach(([key, value]) => {
    if (key !== "game") {
      additionalPorts[key] = value;
    }
  });
  deployForm.additionalPorts = additionalPorts;

  // 设置默认部署路径（使用 SSH 用户家目录，避免 /opt 等目录的权限问题）
  deployForm.deployPath = `~/games/${game.gameCode || game.id}`;

  // 初始化配置字段
  deployForm.config = {};
  if (game.configSchema) {
    try {
      const schema = JSON.parse(game.configSchema);
      schema.forEach((field) => {
        deployForm.config[field.key] =
          field.default !== undefined ? field.default : "";
      });
    } catch (e) {
      console.error("Failed to parse config schema:", e);
    }
  }

  // 资源限制默认值（ADR-0010）：优先取游戏 dependencies 声明（environmentDeps），
  // 解析失败回退全局默认（2 核 / 4GB / 10GB）
  const deps = game.environmentDeps || {};
  deployForm.resources.cpuLimit = parseResourceValue(deps.cpu, 2);
  deployForm.resources.memoryLimit = parseResourceValue(deps.memory, 4);
  deployForm.resources.diskLimit = parseResourceValue(deps.disk, 10);
}

// 解析 dependencies 资源声明为数字（ADR-0010）：
// "1核" → 1，"2G"/"2GB" → 2，"512M" → 0.5；数字原样返回；解析失败返回 fallback
function parseResourceValue(value, fallback) {
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return value;
  }
  if (typeof value !== "string") return fallback;
  const m = value.trim().match(/^(\d+(?:\.\d+)?)\s*(核|G|GB|g|gb|M|MB|m|mb)?$/);
  if (!m) return fallback;
  const num = parseFloat(m[1]);
  const unit = (m[2] || "").toLowerCase();
  if (unit === "m" || unit === "mb") {
    return Math.round((num / 1024) * 10) / 10; // MB → GB（保留 1 位小数）
  }
  return num;
}

// 从游戏的 deployConfig.defaultPorts 提取多端口 Map
// 后端 GameVO.deployConfig 是一个 Map<String, Object>，其中 defaultPorts 为 Map<String, Integer>
function getDefaultPortsMap(game) {
  if (!game) return {};
  // 兼容字段名：deployConfig.defaultPorts（后端标准字段）
  if (game.deployConfig && game.deployConfig.defaultPorts) {
    return game.deployConfig.defaultPorts;
  }
  return {};
}

// 格式化多端口展示文本，如 "game: 27015, query: 27016, rcon: 27017"
function formatDefaultPorts(game) {
  const ports = getDefaultPortsMap(game);
  if (Object.keys(ports).length === 0) {
    return game.defaultPort ? `${game.defaultPort}` : "-";
  }
  return Object.entries(ports)
    .map(([k, v]) => `${k}: ${v}`)
    .join("，");
}

// 检查端口
async function checkPortAvailability() {
  if (!selectedHost.value || !deployForm.port) return;

  checkingPort.value = true;
  portAvailable.value = null;
  portCheckMessage.value = "";

  try {
    const result = await checkPort(selectedHost.value.id, deployForm.port);
    portAvailable.value = result.available;
    portCheckMessage.value = result.available
      ? "端口可用"
      : `端口已被占用${result.usedBy ? " (" + result.usedBy + ")" : ""}`;
  } catch (error) {
    console.error("Failed to check port:", error);
    portAvailable.value = null;
    portCheckMessage.value = "检查失败";
  } finally {
    checkingPort.value = false;
  }
}

// 检查所有附加端口可用性（并发执行）
async function checkAdditionalPortsAvailability() {
  if (!selectedHost.value) return;
  const entries = Object.entries(deployForm.additionalPorts);
  if (entries.length === 0) return;

  checkingAdditionalPorts.value = true;
  // 重置状态
  additionalPortStatus.value = {};

  try {
    const results = await Promise.all(
      entries.map(async ([key, port]) => {
        try {
          const result = await checkPort(selectedHost.value.id, port);
          return {
            key,
            available: result.available,
            message: result.available
              ? "可用"
              : `被占用${result.usedBy ? " (" + result.usedBy + ")" : ""}`,
          };
        } catch (error) {
          return { key, available: false, message: "检查失败" };
        }
      }),
    );
    const statusMap = {};
    results.forEach((r) => {
      statusMap[r.key] = { available: r.available, message: r.message };
    });
    additionalPortStatus.value = statusMap;
  } finally {
    checkingAdditionalPorts.value = false;
  }
}

// 监听端口变化
watch(
  () => deployForm.port,
  () => {
    portAvailable.value = null;
    portCheckMessage.value = "";
  },
);

// 监听部署方式变化：docker-compose / linuxgsm-docker 时加载变量配置
watch(
  () => selectedDeployMethod.value,
  (method) => {
    if (isComposeVariableDeploy() && selectedGame.value) {
      loadDeployConfig();
    } else {
      deployVariables.value = [];
    }
  },
);

// 添加环境变量
function addEnvVar() {
  deployForm.envVars.push({ key: "", value: "" });
}

// 删除环境变量
function removeEnvVar(index) {
  deployForm.envVars.splice(index, 1);
}

// 下一步
async function nextStep() {
  if (currentStep.value === 0) {
    if (!selectedHost.value) {
      ElMessage.warning("请选择目标主机");
      return;
    }
  } else if (currentStep.value === 1) {
    if (!selectedGame.value) {
      ElMessage.warning("请选择游戏");
      return;
    }
  } else if (currentStep.value === 2) {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    // docker-compose / linuxgsm-docker 变量必填校验
    if (
      isComposeVariableDeploy() &&
      variableFormRef.value
    ) {
      const result = variableFormRef.value.validate();
      if (!result.valid) {
        ElMessage.warning(result.message);
        return;
      }
    }

    // 检查端口
    if (portAvailable.value !== true) {
      await checkPortAvailability();
      if (portAvailable.value !== true) {
        ElMessage.warning("请确保端口可用");
        return;
      }
    }
  } else if (currentStep.value === 3) {
    // 环境校验步骤，执行校验
    await performEnvCheck();
    if (!envCheckPassed.value) {
      ElMessage.warning("环境校验未通过，请修复后重试");
      return;
    }
  }

  if (currentStep.value < steps.length - 1) {
    currentStep.value++;
  }
}

// 上一步
function prevStep() {
  if (currentStep.value > 0) {
    currentStep.value--;
  }
}

// 执行环境校验
async function performEnvCheck() {
  checkingEnv.value = true;
  envCheckResult.value = [
    { key: "docker", label: "Docker环境", status: "checking", icon: "Box" },
    { key: "port", label: "端口可用性", status: "checking", icon: "Link" },
    { key: "disk", label: "磁盘空间", status: "checking", icon: "Folder" },
    { key: "memory", label: "内存资源", status: "checking", icon: "Cpu" },
    {
      key: "dependencies",
      label: "依赖检查",
      status: "checking",
      icon: "Collection",
    },
  ];
  envCheckPassed.value = false;

  try {
    // 并发校验所有端口（主端口 + 附加端口）
    // 收集所有需要校验的端口：主端口 + 附加端口
    const allPortsToCheck = [
      { key: "game", port: deployForm.port, label: "主端口" },
      ...Object.entries(deployForm.additionalPorts).map(([k, p]) => ({
        key: k,
        port: p,
        label: k,
      })),
    ];

    const portCheckResults = await Promise.all(
      allPortsToCheck.map(async (p) => {
        try {
          const r = await checkPort(selectedHost.value.id, p.port);
          return {
            key: p.key,
            label: p.label,
            port: p.port,
            available: r.available,
            usedBy: r.usedBy,
          };
        } catch (error) {
          return {
            key: p.key,
            label: p.label,
            port: p.port,
            available: false,
            usedBy: null,
            error: error.message,
          };
        }
      }),
    );

    // 同步更新前端单独的端口检查状态显示
    portAvailable.value = portCheckResults[0]?.available ?? null;
    portCheckMessage.value = portCheckResults[0]
      ? portCheckResults[0].available
        ? "端口可用"
        : `端口已被占用${
            portCheckResults[0].usedBy ? " (" + portCheckResults[0].usedBy + ")" : ""
          }`
      : "";
    const additionalStatus = {};
    portCheckResults.slice(1).forEach((r) => {
      additionalStatus[r.key] = {
        available: r.available,
        message: r.available
          ? "可用"
          : `被占用${r.usedBy ? " (" + r.usedBy + ")" : ""}`,
      };
    });
    additionalPortStatus.value = additionalStatus;

    const result = await checkEnvironment({
      hostId: selectedHost.value.id,
      port: deployForm.port,
      deployMethod: selectedDeployMethod.value,
      gameId: selectedGame.value.id,
    });

    if (result.checks && result.checks.length > 0) {
      envCheckResult.value = result.checks.map((check) => ({
        key: check.key,
        label: check.label,
        status: check.passed ? "success" : "error",
        message: check.message,
        icon: getCheckIcon(check.key),
      }));

      // 用前端实际多端口校验结果覆盖后端的 port 检查项
      const allPortsAvailable = portCheckResults.every((r) => r.available);
      const failedPorts = portCheckResults.filter((r) => !r.available);
      const portIdx = envCheckResult.value.findIndex(
        (item) => item.key === "port",
      );
      if (portIdx >= 0) {
        envCheckResult.value[portIdx].status = allPortsAvailable
          ? "success"
          : "error";
        envCheckResult.value[portIdx].message = allPortsAvailable
          ? `所有端口可用（共 ${portCheckResults.length} 个）`
          : `端口被占用: ${failedPorts
              .map((p) => `${p.label}=${p.port}`)
              .join("，")}`;
      }
    } else {
      // 模拟校验过程
      for (let i = 0; i < envCheckResult.value.length; i++) {
        await new Promise((resolve) => setTimeout(resolve, 400));
        envCheckResult.value[i].status = "success";
        envCheckResult.value[i].message = getEnvCheckMessage(
          envCheckResult.value[i].key,
        );
      }
    }

    envCheckPassed.value =
      result.passed !== false &&
      envCheckResult.value.every((item) => item.status === "success");
  } catch (error) {
    console.error("Environment check failed:", error);
    ElMessage.error("环境校验失败: " + (error.message || "未知错误"));
    envCheckResult.value.forEach((item) => {
      if (item.status === "checking") {
        item.status = "error";
        item.message = "检查失败";
      }
    });
  } finally {
    checkingEnv.value = false;
  }
}

function getCheckIcon(key) {
  const icons = {
    docker: "Box",
    port: "Link",
    disk: "Folder",
    memory: "Cpu",
    dependencies: "Collection",
  };
  return icons[key] || "CircleCheck";
}

function getEnvCheckMessage(key) {
  const messages = {
    docker: "Docker 24.0.5 已安装并运行",
    port: `${deployForm.port} 端口可用`,
    disk: `剩余空间 ${selectedHost.value?.resources?.disk?.free || "50"}GB，满足要求`,
    memory: `可用内存 ${selectedHost.value?.resources?.memory?.free || "2"}GB，满足最低要求`,
    dependencies: "所有依赖已安装",
  };
  return messages[key] || "";
}

// 检查是否可以部署
const canDeploy = computed(() => {
  return envCheckPassed.value && !checkingEnv.value;
});

// 开始部署
async function handleDeploy() {
  if (!canDeploy.value) {
    ElMessage.warning("环境校验未通过，无法部署");
    return;
  }

  try {
    // 构建端口配置：以用户填写的 game 端口覆盖默认 game 端口，合并用户编辑的附加端口
    const defaultPortsMap = { ...getDefaultPortsMap(selectedGame.value) };
    defaultPortsMap.game = deployForm.port;
    // 用用户编辑的附加端口覆盖默认值
    Object.entries(deployForm.additionalPorts).forEach(([k, v]) => {
      defaultPortsMap[k] = v;
    });
    // 判断是否为 docker 类部署（docker/docker-compose/linuxgsm-docker）
    const isDockerDeploy = ["docker", "docker-compose", "linuxgsm-docker"].includes(
      selectedDeployMethod.value,
    );
    await createInstance({
      instanceName: deployForm.name,
      gameId: selectedGame.value.id,
      hostId: selectedHost.value.id,
      deployType: selectedDeployMethod.value,
      installPath: deployForm.deployPath,
      portConfig: defaultPortsMap,
      configInfo: {
        ...deployForm.config,
        resources: deployForm.resources,
        envVars: deployForm.envVars.filter((v) => v.key && v.value),
        autoRestart: deployForm.autoStart ? 1 : 0,
        gameVersion: selectedGame.value.version,
        gameCode: selectedGame.value.gameCode,
        // docker 类部署：注入宿主机证书挂载选项（覆盖 yml 中的默认值）
        ...(isDockerDeploy
          ? {
              mountHostCerts: deployForm.mountHostCerts,
              hostCertPath: deployForm.hostCertPath,
            }
          : {}),
        // docker-compose / linuxgsm-docker 类型：
        // 将每个变量值展开到 configInfo 顶层（而非嵌套在 variables 键），
        // 因为后端 generateEnvFileContent 从 config 顶层读取用户值（config.get(name)）。
        // 注意：不要覆盖 yml 的 variables List，否则后端无法获取 defaultValue/required 等元数据。
        ...(isComposeVariableDeploy()
          ? { ...deployVariablesValues }
          : {}),
      },
    });

    ElMessage.success("部署任务已创建，可在实例列表查看部署进度");
    router.push("/instance/list");
  } catch (error) {
    console.error("Failed to deploy instance:", error);
    ElMessage.error("部署失败: " + (error.message || "未知错误"));
  }
}

// 部署完成回调
function handleDeployComplete(success) {
  if (success) {
    ElMessage.success("部署成功");
    router.push("/instance/list");
  } else {
    ElMessage.error("部署失败");
  }
}

// 取消部署
function handleCancel() {
  ElMessageBox.confirm("确定要取消部署吗？已填写的数据将丢失。", "确认取消", {
    confirmButtonText: "确定",
    cancelButtonText: "继续部署",
    type: "warning",
  })
    .then(() => {
      router.push("/instance/list");
    })
    .catch(() => {});
}

// 获取进度条颜色
function getProgressColor(value) {
  if (value >= 80) return "var(--el-color-danger)";
  if (value >= 60) return "var(--el-color-warning)";
  return "var(--el-color-success)";
}

// 统一格式化资源使用率，保留 1 位小数
function formatUsage(value) {
  const num = Number(value);
  if (Number.isNaN(num)) return 0;
  return Math.round(num * 10) / 10;
}

// 获取部署方式信息
function getDeployMethodInfo(method) {
  return (
    deployMethodOptions.find((m) => m.value === method) ||
    deployMethodOptions[0]
  );
}

// 格式化文件大小
function formatFileSize(bytes) {
  if (!bytes) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  return `${size.toFixed(1)} ${units[unitIndex]}`;
}

onMounted(() => {
  fetchHostList();
  fetchGameList();
});
</script>

<template>
  <div class="deploy-container">
    <el-card shadow="never" class="deploy-shell">
      <template #header>
        <div class="card-header">
          <div class="header-copy">
            <span class="section-kicker">PROVISIONING BAY / INSTANCE DEPLOYMENT</span>
            <span class="title">部署游戏实例</span>
            <p>从节点选择到环境校验，建立一条可回溯的实例部署链路。</p>
          </div>
          <div class="header-context">
            <el-tag v-if="selectedHost" type="info" size="small">
              <el-icon><Monitor /></el-icon>
              {{ selectedHost.name }}
            </el-tag>
            <el-tag v-if="selectedGame" type="primary" size="small">
              <el-icon><Grid /></el-icon>
              {{ selectedGame.gameName }}
            </el-tag>
            <span class="header-status">
              <i></i>
              {{ currentStep + 1 }} / {{ steps.length }} STAGES
            </span>
          </div>
        </div>
      </template>

      <div class="deploy-workbench">
        <aside class="deploy-rail">
          <div class="rail-caption">DEPLOYMENT FLOW</div>
          <div class="rail-context">
            <span>当前目标</span>
            <strong>{{ selectedHost?.name || "待选择主机" }}</strong>
            <small>{{ selectedHost?.ip || "选择在线节点开始" }}</small>
            <div class="rail-divider"></div>
            <strong>{{ selectedGame?.gameName || "待选择游戏" }}</strong>
            <small>{{ selectedGame?.gameCode || "运行时模板未锁定" }}</small>
          </div>
          <el-steps
            :active="currentStep"
            finish-status="success"
            direction="vertical"
            class="steps"
          >
            <el-step
              v-for="(step, index) in steps"
              :key="index"
              :title="step.title"
            >
              <template #icon>
                <el-icon :size="18">
                  <component :is="step.icon" />
                </el-icon>
              </template>
            </el-step>
          </el-steps>
          <div class="rail-note">
            <el-icon><InfoFilled /></el-icon>
            <span>每个阶段都可以返回调整，部署任务只会在最后确认后创建。</span>
          </div>
        </aside>

        <section class="deploy-main">
          <div class="stage-header">
            <div>
              <span class="section-kicker">STAGE {{ String(currentStep + 1).padStart(2, "0") }} / {{ steps[currentStep]?.title }}</span>
              <h2>{{ steps[currentStep]?.title }}</h2>
              <p>{{ activeStepDescription }}</p>
            </div>
            <span class="stage-index">0{{ currentStep + 1 }}</span>
          </div>

          <!-- 步骤内容 -->
          <div class="step-content">
        <!-- 步骤1：选择主机 -->
        <div v-show="currentStep === 0" class="step-panel">
          <div class="step-title">
            <el-icon><Monitor /></el-icon>
            选择要部署游戏的主机
          </div>
          <el-row :gutter="16">
            <el-col
              v-for="host in hostList"
              :key="host.id"
              :xs="24"
              :sm="12"
              :md="8"
              :lg="12"
            >
              <div
                class="host-card"
                :class="{
                  'is-selected': selectedHost?.id === host.id,
                  'is-offline': host.status !== 1,
                }"
                @click="host.status === 1 && selectHost(host)"
              >
                <div class="host-header">
                  <el-icon class="host-icon" :size="24"><Monitor /></el-icon>
                  <div class="host-name">{{ host.name }}</div>
                  <el-tag
                    :type="host.status === 1 ? 'success' : 'danger'"
                    size="small"
                  >
                    {{ host.status === 1 ? "在线" : "离线" }}
                  </el-tag>
                </div>
                <div class="host-info">
                  <div class="info-item">
                    <span class="label">IP地址:</span>
                    <span class="value">{{ host.ip }}</span>
                  </div>
                  <div class="info-item">
                    <span class="label">操作系统:</span>
                    <span class="value">{{ host.os || "-" }}</span>
                  </div>
                  <div class="info-item">
                    <span class="label">SSH端口:</span>
                    <span class="value">{{ host.sshPort || 22 }}</span>
                  </div>
                </div>
                <div v-if="host.resources" class="host-resources">
                  <div class="resource-item">
                    <span class="resource-label">CPU</span>
                    <el-progress
                      :percentage="formatUsage(host.resources.cpu?.usage)"
                      :stroke-width="6"
                      :color="getProgressColor(formatUsage(host.resources.cpu?.usage))"
                      class="resource-progress"
                    />
                    <span class="resource-value"
                      >{{ formatUsage(host.resources.cpu?.usage) }}%</span
                    >
                  </div>
                  <div class="resource-item">
                    <span class="resource-label">内存</span>
                    <el-progress
                      :percentage="formatUsage(host.resources.memory?.usage)"
                      :stroke-width="6"
                      :color="getProgressColor(formatUsage(host.resources.memory?.usage))"
                      class="resource-progress"
                    />
                    <span class="resource-value"
                      >{{ formatUsage(host.resources.memory?.usage) }}%</span
                    >
                  </div>
                  <div class="resource-item">
                    <span class="resource-label">磁盘</span>
                    <el-progress
                      :percentage="formatUsage(host.resources.disk?.usage)"
                      :stroke-width="6"
                      :color="getProgressColor(formatUsage(host.resources.disk?.usage))"
                      class="resource-progress"
                    />
                    <span class="resource-value"
                      >{{ formatUsage(host.resources.disk?.usage) }}%</span
                    >
                  </div>
                </div>
                <div v-else class="host-resources">
                  <el-skeleton :rows="3" animated />
                </div>
                <div v-if="selectedHost?.id === host.id" class="selected-mark">
                  <el-icon><Check /></el-icon>
                </div>
              </div>
            </el-col>
          </el-row>
          <el-empty
            v-if="hostList.length === 0 && !loading"
            description="暂无在线主机"
          />
          <div v-if="loading" class="loading-container">
            <el-skeleton :rows="5" animated />
          </div>
        </div>

        <!-- 步骤2：选择游戏 -->
        <div v-show="currentStep === 1" class="step-panel">
          <el-row :gutter="24">
            <!-- 游戏列表 -->
            <el-col :span="10">
              <div class="step-title">
                <el-icon><Grid /></el-icon>
                选择游戏
                <span class="step-title-count">{{ filteredGameList.length }} / {{ gameList.length }}</span>
              </div>
              <el-input
                v-model="gameKeyword"
                placeholder="搜索游戏名称或编码"
                clearable
                size="small"
                class="game-search"
              >
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <div class="game-list">
                <div
                  v-for="game in filteredGameList"
                  :key="game.id"
                  class="game-item"
                  :class="{ 'is-selected': selectedGame?.id === game.id }"
                  @click="selectGame(game)"
                >
                  <el-avatar
                    :size="40"
                    :src="game.iconUrl"
                    :icon="game.iconUrl ? undefined : 'Box'"
                    class="game-avatar"
                  />
                  <div class="game-info">
                    <div class="game-name">{{ game.gameName }}</div>
                    <div class="game-code">{{ game.gameCode }}</div>
                  </div>
                  <el-icon
                    v-if="selectedGame?.id === game.id"
                    class="check-icon"
                    ><Check
                  /></el-icon>
                </div>
              </div>
            </el-col>

            <!-- 游戏详情 -->
            <el-col :span="14">
              <div v-if="selectedGame" class="game-detail">
                <div class="step-title">
                  <el-icon><InfoFilled /></el-icon>
                  游戏详情
                </div>
                <div class="detail-header">
                  <el-avatar
                    :size="64"
                    :src="selectedGame.iconUrl"
                    :icon="selectedGame.iconUrl ? undefined : 'Box'"
                  />
                  <div class="detail-info">
                    <h3>{{ selectedGame.gameName }}</h3>
                    <p class="game-code">{{ selectedGame.gameCode }}</p>
                    <p class="game-desc">
                      {{ selectedGame.gameDesc || "暂无描述" }}
                    </p>
                  </div>
                </div>

                <el-divider />

                <div class="detail-section">
                  <div class="section-title">
                    <el-icon><SetUp /></el-icon>
                    部署方式
                  </div>
                  <el-radio-group
                    v-model="selectedDeployMethod"
                    class="deploy-method-group"
                  >
                    <el-radio-button
                      v-for="method in (Array.isArray(selectedGame.supportedDeployTypes) && selectedGame.supportedDeployTypes.length > 0 ? selectedGame.supportedDeployTypes : ['docker'])"
                      :key="method"
                      :value="method"
                    >
                      <el-icon>
                        <component :is="getDeployMethodInfo(method).icon" />
                      </el-icon>
                      {{ getDeployMethodInfo(method).label }}
                    </el-radio-button>
                  </el-radio-group>
                  <div class="method-description">
                    {{ getDeployMethodInfo(selectedDeployMethod).description }}
                  </div>
                </div>

                <div class="detail-section">
                  <div class="section-title">
                    <el-icon><Link /></el-icon>
                    默认端口
                  </div>
                  <div v-if="Object.keys(getDefaultPortsMap(selectedGame)).length > 0" class="port-tags">
                    <el-tag
                      v-for="(port, name) in getDefaultPortsMap(selectedGame)"
                      :key="name"
                      :type="name === 'game' ? 'primary' : 'info'"
                      size="large"
                      effect="plain"
                    >
                      {{ name }}: {{ port }}
                    </el-tag>
                  </div>
                  <el-tag v-else size="large" type="info">{{
                    selectedGame.defaultPort || "-"
                  }}</el-tag>
                </div>

                <div class="detail-section">
                  <div class="section-title">
                    <el-icon><Collection /></el-icon>
                    环境依赖
                  </div>
                  <el-space wrap>
                    <el-tag
                      v-for="dep in selectedGame.defaultDependences
                        ? JSON.parse(selectedGame.defaultDependences)
                        : []"
                      :key="dep"
                      type="info"
                      size="small"
                    >
                      {{ dep }}
                    </el-tag>
                    <span
                      v-if="
                        !selectedGame.defaultDependences ||
                        JSON.parse(selectedGame.defaultDependences).length === 0
                      "
                      class="text-secondary"
                    >
                      无特殊依赖
                    </span>
                  </el-space>
                </div>
              </div>
              <el-empty v-else description="请选择游戏查看详情" />
            </el-col>
          </el-row>
        </div>

        <!-- 步骤3：配置参数 -->
        <div v-show="currentStep === 2" class="step-panel">
          <div class="step-title">
            <el-icon><Setting /></el-icon>
            配置实例参数
          </div>
          <el-form
            ref="formRef"
            :model="deployForm"
            :rules="rules"
            label-width="140px"
            class="config-form"
          >
            <!-- 基础配置 -->
            <div class="config-section">
              <div class="section-title">
                <el-icon><InfoFilled /></el-icon>
                基础配置
              </div>
              <el-form-item label="实例名称" prop="name">
                <el-input
                  v-model="deployForm.name"
                  placeholder="请输入实例名称"
                  maxlength="50"
                  show-word-limit
                  style="width: 300px"
                />
              </el-form-item>
              <el-form-item label="部署路径" prop="deployPath">
                <el-input
                  v-model="deployForm.deployPath"
                  placeholder="请输入部署路径，如 ~/games/minecraft（推荐家目录）"
                  style="width: 400px"
                >
                  <template #prefix>
                    <el-icon><Folder /></el-icon>
                  </template>
                </el-input>
              </el-form-item>
              <el-form-item label="端口号" prop="port">
                <el-input-number
                  v-model="deployForm.port"
                  :min="1"
                  :max="65535"
                />
                <el-button
                  :loading="checkingPort"
                  style="margin-left: 12px"
                  @click="checkPortAvailability"
                >
                  <el-icon><Search /></el-icon>
                  检查端口
                </el-button>
                <el-tag
                  v-if="portAvailable !== null"
                  :type="portAvailable ? 'success' : 'danger'"
                  size="small"
                  effect="plain"
                  style="margin-left: 12px"
                >
                  <el-icon
                    ><component
                      :is="portAvailable ? 'CircleCheck' : 'CircleClose'"
                  /></el-icon>
                  {{ portCheckMessage }}
                </el-tag>
              </el-form-item>
              <!-- 附加端口（除主端口 game 之外的端口，如 query/rcon/steam） -->
              <el-form-item
                v-if="Object.keys(deployForm.additionalPorts).length > 0"
                label="附加端口"
              >
                <div class="additional-ports">
                  <div
                    v-for="(port, key) in deployForm.additionalPorts"
                    :key="key"
                    class="additional-port-row"
                  >
                    <el-tag size="small" type="info" effect="plain">
                      {{ key }}
                    </el-tag>
                    <el-input-number
                      v-model="deployForm.additionalPorts[key]"
                      :min="1"
                      :max="65535"
                      size="small"
                      style="width: 130px"
                    />
                    <el-tag
                      v-if="additionalPortStatus[key]"
                      :type="
                        additionalPortStatus[key].available
                          ? 'success'
                          : 'danger'
                      "
                      size="small"
                      effect="plain"
                    >
                      <el-icon
                        ><component
                          :is="
                            additionalPortStatus[key].available
                              ? 'CircleCheck'
                              : 'CircleClose'
                          "
                      /></el-icon>
                      {{ additionalPortStatus[key].message }}
                    </el-tag>
                  </div>
                  <el-button
                    size="small"
                    :loading="checkingAdditionalPorts"
                    @click="checkAdditionalPortsAvailability"
                  >
                    <el-icon><Search /></el-icon>
                    检查所有附加端口
                  </el-button>
                </div>
                <div class="field-description">
                  游戏服务器通常需要多个端口（如 query/rcon/steam），可在此调整
                </div>
              </el-form-item>
              <el-form-item label="自动启动">
                <el-switch v-model="deployForm.autoStart" />
                <span class="form-tip">实例部署完成后自动启动</span>
              </el-form-item>
            </div>

            <!-- 资源限制 -->
            <div class="config-section">
              <div class="section-title">
                <el-icon><Cpu /></el-icon>
                资源限制
              </div>
              <el-alert
                v-if="!isDockerLikeDeploy"
                type="info"
                :closable="false"
                show-icon
                style="margin-bottom: 12px"
                title="资源限制仅对 Docker 类部署生效"
                description="当前为进程（LinuxGSM）部署，CPU / 内存限制不会应用于游戏进程；磁盘占用预估仍用于部署前环境校验。"
              />
              <el-form-item label="CPU限制">
                <el-slider
                  v-model="deployForm.resources.cpuLimit"
                  :max="8"
                  :step="0.5"
                  show-stops
                  style="width: 300px"
                />
                <span class="resource-value"
                  >{{ deployForm.resources.cpuLimit }} 核</span
                >
              </el-form-item>
              <el-form-item label="内存限制">
                <el-slider
                  v-model="deployForm.resources.memoryLimit"
                  :max="32"
                  :step="1"
                  show-stops
                  style="width: 300px"
                />
                <span class="resource-value"
                  >{{ deployForm.resources.memoryLimit }} GB</span
                >
              </el-form-item>
              <el-form-item label="磁盘占用预估">
                <el-slider
                  v-model="deployForm.resources.diskLimit"
                  :max="100"
                  :step="5"
                  show-stops
                  style="width: 300px"
                />
                <span class="resource-value"
                  >{{ deployForm.resources.diskLimit }} GB</span
                >
                <div class="form-tip">
                  用于部署前磁盘水位校验，非容器硬限制（ADR-0010）
                </div>
              </el-form-item>
            </div>

            <!-- Docker 选项（仅 docker 类部署显示） -->
            <div
              v-if="
                ['docker', 'docker-compose', 'linuxgsm-docker'].includes(
                  selectedDeployMethod,
                )
              "
              class="config-section"
            >
              <div class="section-title">
                <el-icon><Box /></el-icon>
                Docker 选项
              </div>
              <el-form-item label="挂载宿主机证书">
                <el-switch v-model="deployForm.mountHostCerts" />
                <span class="form-tip">
                  反向代理场景下启用此项，将宿主机 CA
                  证书及 /etc/hosts 只读挂载到容器，避免 SSL 校验失败与 DNS 解析异常
                </span>
              </el-form-item>
              <el-form-item
                v-if="deployForm.mountHostCerts"
                label="宿主机证书路径"
              >
                <el-input
                  v-model="deployForm.hostCertPath"
                  placeholder="/etc/ssl/certs/ca-certificates.crt"
                  style="width: 400px"
                />
                <div class="field-description">
                  宿主机 CA 证书 bundle
                  路径，会以只读方式挂载到容器同路径
                </div>
              </el-form-item>
            </div>

            <!-- 环境变量 -->
            <div class="config-section">
              <div class="section-title">
                <el-icon><Collection /></el-icon>
                环境变量
                <el-button
                  link
                  type="primary"
                  size="small"
                  style="margin-left: 12px"
                  @click="addEnvVar"
                >
                  <el-icon><Plus /></el-icon>
                  添加变量
                </el-button>
              </div>
              <div
                v-if="deployForm.envVars.length === 0"
                class="empty-env-vars"
              >
                <el-text type="info">暂无环境变量，点击上方按钮添加</el-text>
              </div>
              <div
                v-for="(env, index) in deployForm.envVars"
                :key="index"
                class="env-var-row"
              >
                <el-input
                  v-model="env.key"
                  placeholder="变量名"
                  style="width: 200px"
                />
                <el-input
                  v-model="env.value"
                  placeholder="变量值"
                  style="width: 300px; margin-left: 8px"
                />
                <el-button
                  link
                  type="danger"
                  style="margin-left: 8px"
                  @click="removeEnvVar(index)"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>

            <!-- 游戏配置 -->
            <div v-if="selectedGame?.configSchema" class="config-section">
              <div class="section-title">
                <el-icon><SetUp /></el-icon>
                游戏配置
              </div>
              <el-form-item
                v-for="field in selectedGame.configSchema
                  ? JSON.parse(selectedGame.configSchema)
                  : []"
                :key="field.key"
                :label="field.label"
              >
                <el-input
                  v-if="field.type === 'input' || field.type === 'text'"
                  v-model="deployForm.config[field.key]"
                  :placeholder="field.placeholder || `请输入${field.label}`"
                  :maxlength="field.maxLength"
                  show-word-limit
                  style="width: 300px"
                />
                <el-input
                  v-else-if="field.type === 'password'"
                  v-model="deployForm.config[field.key]"
                  type="password"
                  :placeholder="field.placeholder || `请输入${field.label}`"
                  show-password
                  style="width: 300px"
                />
                <el-input-number
                  v-else-if="field.type === 'number'"
                  v-model="deployForm.config[field.key]"
                  :min="field.min"
                  :max="field.max"
                  :step="field.step || 1"
                />
                <el-select
                  v-else-if="field.type === 'select'"
                  v-model="deployForm.config[field.key]"
                  :placeholder="field.placeholder || `请选择${field.label}`"
                  style="width: 300px"
                >
                  <el-option
                    v-for="option in field.options"
                    :key="option.value || option"
                    :label="option.label || option"
                    :value="option.value || option"
                  />
                </el-select>
                <el-switch
                  v-else-if="
                    field.type === 'switch' || field.type === 'boolean'
                  "
                  v-model="deployForm.config[field.key]"
                />
                <el-slider
                  v-else-if="field.type === 'slider'"
                  v-model="deployForm.config[field.key]"
                  :min="field.min"
                  :max="field.max"
                  :step="field.step || 1"
                  show-stops
                  style="width: 300px"
                />
                <div v-if="field.description" class="field-description">
                  {{ field.description }}
                </div>
              </el-form-item>
            </div>

            <!-- Docker Compose / LinuxGSM Docker 变量配置 -->
            <div
              v-if="isComposeVariableDeploy()"
              class="config-section"
            >
              <div class="section-title">
                <el-icon><SetUp /></el-icon>
                Compose 变量配置
                <el-tag size="small" type="primary" style="margin-left: 8px">
                  .env
                </el-tag>
              </div>
              <div v-loading="loadingDeployConfig">
                <DeployVariableForm
                  ref="variableFormRef"
                  v-model="deployVariablesValues"
                  :variables="deployVariables"
                />
              </div>
            </div>
          </el-form>
        </div>

        <!-- 步骤4：环境校验 -->
        <div v-show="currentStep === 3" class="step-panel">
          <div class="step-title">
            <el-icon><CircleCheck /></el-icon>
            环境校验
          </div>

          <div class="env-check-container">
            <div
              v-for="item in envCheckResult"
              :key="item.key"
              class="env-check-item"
              :class="item.status"
            >
              <div class="check-icon-wrapper">
                <el-icon
                  v-if="item.status === 'checking'"
                  class="is-loading"
                  :size="24"
                  ><Loading
                /></el-icon>
                <el-icon
                  v-else-if="item.status === 'success'"
                  class="success"
                  :size="24"
                  ><CircleCheck
                /></el-icon>
                <el-icon v-else class="error" :size="24"
                  ><CircleClose
                /></el-icon>
              </div>
              <div class="check-content">
                <div class="check-label">
                  <el-icon><component :is="item.icon" /></el-icon>
                  {{ item.label }}
                </div>
                <div class="check-message">
                  {{
                    item.message ||
                    (item.status === "checking" ? "检查中..." : "")
                  }}
                </div>
              </div>
            </div>
          </div>

          <div
            v-if="!checkingEnv && envCheckResult.length > 0"
            class="env-check-summary"
          >
            <el-result
              :icon="envCheckPassed ? 'success' : 'error'"
              :title="envCheckPassed ? '环境校验通过' : '环境校验未通过'"
              :sub-title="
                envCheckPassed ? '可以开始部署' : '请修复上述问题后重试'
              "
            />
          </div>

          <div v-if="!checkingEnv && !envCheckPassed" class="env-check-actions">
            <el-button type="primary" @click="performEnvCheck">
              <el-icon><Refresh /></el-icon>
              重新校验
            </el-button>
          </div>
        </div>

        <!-- 步骤5：确认部署 -->
        <div v-show="currentStep === 4" class="step-panel">
          <div class="step-title">
            <el-icon><Check /></el-icon>
            确认部署
          </div>

          <!-- 配置摘要 -->
          <div class="confirm-section">
            <div class="section-title">
              <el-icon><Document /></el-icon>
              配置摘要
            </div>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="目标主机">
                <el-icon><Monitor /></el-icon>
                {{ selectedHost?.name }} ({{ selectedHost?.ip }})
              </el-descriptions-item>
              <el-descriptions-item label="游戏">
                <el-icon><Grid /></el-icon>
                {{ selectedGame?.gameName }}
              </el-descriptions-item>
              <el-descriptions-item label="部署方式">
                <el-icon
                  ><component
                    :is="getDeployMethodInfo(selectedDeployMethod).icon"
                /></el-icon>
                {{ getDeployMethodInfo(selectedDeployMethod).label }}
              </el-descriptions-item>
              <el-descriptions-item label="实例名称">{{
                deployForm.name
              }}</el-descriptions-item>
              <el-descriptions-item label="部署路径">{{
                deployForm.deployPath
              }}</el-descriptions-item>
              <el-descriptions-item label="端口号">{{
                deployForm.port
              }}</el-descriptions-item>
              <el-descriptions-item label="自动启动">{{
                deployForm.autoStart ? "是" : "否"
              }}</el-descriptions-item>
              <el-descriptions-item label="CPU限制"
                >{{ deployForm.resources.cpuLimit }} 核</el-descriptions-item
              >
              <el-descriptions-item label="内存限制"
                >{{ deployForm.resources.memoryLimit }} GB</el-descriptions-item
              >
              <el-descriptions-item label="磁盘占用预估"
                >{{ deployForm.resources.diskLimit }} GB</el-descriptions-item
              >
              <el-descriptions-item
                v-if="
                  ['docker', 'docker-compose', 'linuxgsm-docker'].includes(
                    selectedDeployMethod,
                  )
                "
                label="挂载宿主机证书"
              >
                <template v-if="deployForm.mountHostCerts">
                  <el-tag type="success" size="small" effect="plain">
                    已启用
                  </el-tag>
                  <span style="margin-left: 8px; color: #909399; font-size: 12px">
                    {{ deployForm.hostCertPath }} + /etc/hosts
                  </span>
                </template>
                <span v-else style="color: #909399">未启用</span>
              </el-descriptions-item>
            </el-descriptions>
          </div>

          <!-- 环境校验结果 -->
          <div class="confirm-section">
            <div class="section-title">
              <el-icon><CircleCheck /></el-icon>
              环境校验结果
            </div>
            <el-space wrap>
              <el-tag
                v-for="item in envCheckResult"
                :key="item.key"
                :type="item.status === 'success' ? 'success' : 'danger'"
                effect="plain"
              >
                <el-icon
                  ><component
                    :is="
                      item.status === 'success' ? 'CircleCheck' : 'CircleClose'
                    "
                /></el-icon>
                {{ item.label }}
              </el-tag>
            </el-space>
          </div>

          <!-- 环境变量 -->
          <div
            v-if="deployForm.envVars.filter((v) => v.key).length > 0"
            class="confirm-section"
          >
            <div class="section-title">
              <el-icon><Collection /></el-icon>
              环境变量
            </div>
            <el-descriptions :column="2" border>
              <el-descriptions-item
                v-for="env in deployForm.envVars.filter((v) => v.key)"
                :key="env.key"
                :label="env.key"
              >
                {{ env.value }}
              </el-descriptions-item>
            </el-descriptions>
          </div>

          <!-- Docker Compose / LinuxGSM Docker 变量摘要 -->
          <div
            v-if="
              isComposeVariableDeploy() &&
              deployVariables.length > 0
            "
            class="confirm-section"
          >
            <div class="section-title">
              <el-icon><SetUp /></el-icon>
              Compose 变量
            </div>
            <el-descriptions :column="2" border>
              <el-descriptions-item
                v-for="v in deployVariables.filter((item) => !item.hidden)"
                :key="v.name"
                :label="v.label || v.name"
              >
                {{
                  v.type === "password"
                    ? "******"
                    : (deployVariablesValues[v.name] ?? v.defaultValue ?? "-")
                }}
              </el-descriptions-item>
            </el-descriptions>
          </div>

          <!-- 警告提示 -->
          <el-alert
            title="部署确认"
            type="warning"
            description="部署过程可能需要几分钟时间，请耐心等待。部署完成后，实例将自动启动。"
            show-icon
            :closable="false"
            style="margin-top: 24px"
          />
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="step-actions">
        <el-button v-if="currentStep > 0" @click="prevStep">
          <el-icon><ArrowLeft /></el-icon>
          上一步
        </el-button>
        <el-button
          v-if="currentStep < steps.length - 1"
          type="primary"
          @click="nextStep"
        >
          下一步
          <el-icon><ArrowRight /></el-icon>
        </el-button>
        <el-button
          v-if="currentStep === steps.length - 1"
          type="primary"
          :disabled="!canDeploy"
          @click="handleDeploy"
        >
          <el-icon><CircleCheck /></el-icon>
          开始部署
        </el-button>
        <el-button @click="handleCancel">取消</el-button>
      </div>
        </section>
      </div>
    </el-card>

    <!-- 部署进度弹窗 -->
    <DeployProgress
      v-model:visible="showDeployProgress"
      :task-id="deployTaskId"
      @complete="handleDeployComplete"
    />
  </div>
</template>

<style lang="scss" scoped>
.deploy-container {
  max-width: 1200px;
  margin: 0 auto;

  .card-header {
    display: flex;
    align-items: center;
    gap: 12px;

    .title {
      font-size: var(--platform-font-size-md);
      font-weight: var(--platform-font-weight-bold);
      color: var(--el-text-color-primary);
    }
  }

  .steps {
    margin-bottom: 32px;
  }

  .step-content {
    min-height: 400px;
    padding: 24px 0;
  }

  .step-panel {
    max-width: 1000px;
    margin: 0 auto;
  }

  .step-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: var(--platform-font-size-base);
    font-weight: var(--platform-font-weight-medium);
    color: var(--el-text-color-primary);
    margin-bottom: 20px;

    .el-icon {
      color: var(--el-color-primary);
    }
  }

  .step-actions {
    display: flex;
    justify-content: center;
    gap: 12px;
    margin-top: 32px;
    padding-top: 24px;
    border-top: 1px solid var(--el-border-color-lighter);
  }
}

// 主机卡片
.host-card {
  position: relative;
  padding: 16px;
  background: var(--el-bg-color);
  border: 2px solid var(--el-border-color);
  border-radius: var(--platform-card-radius);
  cursor: pointer;
  transition: all 0.3s ease;
  margin-bottom: 16px;

  &:hover:not(.is-offline) {
    border-color: var(--el-color-primary-light-5);
    box-shadow: var(--platform-card-shadow);
    transform: translateY(-2px);
  }

  &.is-selected {
    border-color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }

  &.is-offline {
    opacity: 0.6;
    cursor: not-allowed;
    background: var(--el-fill-color-light);
  }

  .host-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;

    .host-icon {
      color: var(--el-color-primary);
    }

    .host-name {
      flex: 1;
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
      font-size: var(--platform-font-size-base);
    }
  }

  .host-info {
    margin-bottom: 12px;

    .info-item {
      display: flex;
      font-size: var(--platform-font-size-sm);
      margin-bottom: 6px;

      .label {
        color: var(--el-text-color-secondary);
        margin-right: 8px;
        min-width: 60px;
      }

      .value {
        color: var(--el-text-color-regular);
        font-family: var(--el-font-family-mono);
      }
    }
  }

  .host-resources {
    .resource-item {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: var(--platform-font-size-xs);
      margin-bottom: 6px;

      .resource-label {
        color: var(--el-text-color-secondary);
        min-width: 30px;
      }

      .resource-progress {
        flex: 1;
      }

      .resource-value {
        color: var(--el-text-color-secondary);
        min-width: 35px;
        text-align: right;
      }
    }
  }

  .selected-mark {
    position: absolute;
    top: 8px;
    right: 8px;
    width: 24px;
    height: 24px;
    background: var(--el-color-primary);
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
  }
}

// 游戏列表
.game-search {
  margin-bottom: 12px;
}

.game-list {
  max-height: 460px;
  overflow-y: auto;

  .game-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    border: 2px solid var(--el-border-color);
    border-radius: var(--border-radius-base);
    cursor: pointer;
    margin-bottom: 8px;
    transition: all 0.3s ease;

    &:hover {
      border-color: var(--el-color-primary-light-5);
      background: var(--el-fill-color-light);
    }

    &.is-selected {
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }

    .game-avatar {
      flex-shrink: 0;
    }

    .game-info {
      flex: 1;

      .game-name {
        font-weight: var(--platform-font-weight-medium);
        color: var(--el-text-color-primary);
        margin-bottom: 2px;
      }

      .game-code {
        font-size: var(--platform-font-size-xs);
        color: var(--el-text-color-secondary);
        font-family: var(--el-font-family-mono);
      }
    }

    .check-icon {
      color: var(--el-color-primary);
      font-size: 20px;
    }
  }
}

// 游戏详情
.game-detail {
  .detail-header {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 20px;
    background: var(--el-fill-color-light);
    border-radius: var(--border-radius-base);
    margin-bottom: 24px;

    .detail-info {
      h3 {
        margin: 0 0 4px;
        font-size: var(--platform-font-size-md);
        color: var(--el-text-color-primary);
      }

      .game-code {
        margin: 0 0 8px;
        font-size: var(--platform-font-size-sm);
        color: var(--el-text-color-secondary);
        font-family: var(--el-font-family-mono);
      }

      .game-desc {
        margin: 0;
        font-size: var(--platform-font-size-sm);
        color: var(--el-text-color-regular);
        line-height: 1.5;
      }
    }
  }

  .detail-section {
    margin-bottom: 20px;

    .section-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
      margin-bottom: 12px;

      .el-icon {
        color: var(--el-color-primary);
      }
    }

    .port-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }

    .deploy-method-group {
      .el-radio-button__inner {
        display: flex;
        align-items: center;
        gap: 4px;
      }
    }

    .method-description {
      margin-top: 12px;
      padding: 12px;
      background: var(--el-fill-color-light);
      border-radius: var(--border-radius-base);
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-secondary);
    }
  }
}

// 配置表单
.config-form {
  .config-section {
    margin-bottom: 32px;
    padding: 20px;
    background: var(--el-fill-color-light);
    border-radius: var(--border-radius-base);

    .section-title {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
      margin-bottom: 20px;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--el-border-color-lighter);

      .el-icon {
        color: var(--el-color-primary);
      }
    }

    .form-tip {
      margin-left: 12px;
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-secondary);
    }

    .resource-value {
      margin-left: 16px;
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-regular);
      min-width: 60px;
    }

    .empty-env-vars {
      padding: 20px;
      text-align: center;
      background: var(--el-bg-color);
      border-radius: var(--border-radius-base);
      border: 1px dashed var(--el-border-color);
    }

    .env-var-row {
      display: flex;
      align-items: center;
      margin-bottom: 12px;
    }

    .field-description {
      margin-top: 4px;
      font-size: var(--platform-font-size-xs);
      color: var(--el-text-color-secondary);
    }

    .additional-ports {
      display: flex;
      flex-direction: column;
      gap: 8px;
      width: 100%;

      .additional-port-row {
        display: flex;
        align-items: center;
        gap: 8px;
      }
    }
  }
}

// 环境校验
.env-check-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 600px;
  margin: 0 auto;

  .env-check-item {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 16px 20px;
    background: var(--el-fill-color-light);
    border-radius: var(--border-radius-base);
    border-left: 4px solid var(--el-border-color);
    transition: all 0.3s ease;

    &.checking {
      border-left-color: var(--el-color-primary);
    }

    &.success {
      border-left-color: var(--el-color-success);
      background: var(--el-color-success-light-9);
    }

    &.error {
      border-left-color: var(--el-color-danger);
      background: var(--el-color-danger-light-9);
    }

    .check-icon-wrapper {
      .el-icon {
        &.success {
          color: var(--el-color-success);
        }

        &.error {
          color: var(--el-color-danger);
        }
      }
    }

    .check-content {
      flex: 1;

      .check-label {
        display: flex;
        align-items: center;
        gap: 6px;
        font-weight: var(--platform-font-weight-medium);
        color: var(--el-text-color-primary);
        margin-bottom: 4px;

        .el-icon {
          color: var(--el-color-primary);
        }
      }

      .check-message {
        font-size: var(--platform-font-size-sm);
        color: var(--el-text-color-secondary);
      }
    }
  }
}

.env-check-summary {
  margin-top: 32px;
}

.env-check-actions {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

// 确认部署
.confirm-section {
  margin-bottom: 24px;

  .section-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: var(--platform-font-weight-medium);
    color: var(--el-text-color-primary);
    margin-bottom: 16px;

    .el-icon {
      color: var(--el-color-primary);
    }
  }
}

.loading-container {
  padding: 20px;
}

// 响应式适配
@media screen and (max-width: 768px) {
  .deploy-container {
    .step-panel {
      padding: 0 8px;
    }

    .step-title {
      flex-wrap: wrap;
    }
  }

  .game-detail {
    .detail-header {
      flex-direction: column;
      text-align: center;
    }
  }

  .config-form {
    .config-section {
      padding: 12px;

      .env-var-row {
        flex-wrap: wrap;

        .el-input {
          width: 100% !important;
          margin-left: 0 !important;
          margin-bottom: 8px;
        }
      }
    }
  }
}

/* Deployment Bay overrides: a staged operations workspace rather than a generic form card. */
.deploy-container {
  max-width: none;
  margin: 0;
  padding: 4px 2px 28px;
  color: var(--platform-text-primary);
}

.deploy-shell {
  overflow: hidden;
  border: 1px solid var(--platform-line) !important;
  border-radius: 6px !important;
  background: var(--platform-surface-1) !important;
  box-shadow: 0 16px 32px rgba(0, 0, 0, 0.14) !important;
}

.deploy-shell :deep(.el-card__header) {
  padding: 20px 24px;
  border-bottom: 1px solid var(--platform-line);
  background:
    linear-gradient(115deg, rgba(67, 184, 232, 0.14), transparent 45%),
    var(--platform-surface-2);
}

.deploy-shell :deep(.el-card__body) {
  padding: 0;
}

.card-header {
  justify-content: space-between;
  gap: 24px;
}

.header-copy {
  display: grid;
  gap: 5px;

  .title {
    color: var(--platform-text-primary) !important;
    font-size: 22px !important;
    font-weight: 700 !important;
    letter-spacing: -0.025em;
  }

  p {
    margin: 0;
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.header-context {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.header-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-left: 4px;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;

  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--platform-accent);
    box-shadow: 0 0 0 4px var(--platform-accent-soft);
  }
}

.deploy-workbench {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  min-height: 620px;
}

.deploy-rail {
  display: flex;
  flex-direction: column;
  padding: 22px 18px 18px;
  border-right: 1px solid var(--platform-line);
  background: var(--platform-surface-0);
}

.rail-caption,
.section-kicker {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.rail-context {
  display: grid;
  gap: 4px;
  margin-top: 18px;
  padding: 12px;
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-2);

  span,
  small {
    color: var(--platform-text-muted);
    font-size: 10px;
  }

  strong {
    overflow: hidden;
    color: var(--platform-text-primary);
    font-size: 12px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    font-family: var(--el-font-family-mono);
  }
}

.rail-divider {
  height: 1px;
  margin: 8px 0 5px;
  background: var(--platform-line);
}

.deploy-rail .steps {
  margin: 24px 0 0 !important;

  :deep(.el-step) {
    min-height: 57px;
  }

  :deep(.el-step__head) {
    width: 22px;
  }

  :deep(.el-step__icon) {
    width: 22px;
    height: 22px;
    border-width: 1px;
    background: var(--platform-surface-0);
  }

  :deep(.el-step__line) {
    top: 23px;
    bottom: -5px;
    background: var(--platform-line);
  }

  :deep(.el-step__main) {
    padding-left: 10px;
  }

  :deep(.el-step__title) {
    color: var(--platform-text-muted);
    font-size: 12px;
    font-weight: 500;
    line-height: 22px;
  }

  :deep(.el-step__title.is-process),
  :deep(.el-step__title.is-success) {
    color: var(--platform-text-primary);
    font-weight: 650;
  }

  :deep(.el-step__head.is-process) {
    color: var(--platform-accent);
    border-color: var(--platform-accent);
  }

  :deep(.el-step__head.is-success) {
    color: var(--platform-green);
    border-color: var(--platform-green);
  }
}

.rail-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: auto;
  padding-top: 16px;
  border-top: 1px solid var(--platform-line);
  color: var(--platform-text-muted);
  font-size: 10px;
  line-height: 1.55;

  .el-icon {
    flex: 0 0 auto;
    margin-top: 1px;
    color: var(--platform-accent);
  }
}

.deploy-main {
  min-width: 0;
  background: var(--platform-surface-1);
}

.stage-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px 18px;
  border-bottom: 1px solid var(--platform-line);
  background: linear-gradient(90deg, rgba(67, 184, 232, 0.06), transparent 58%);

  h2 {
    margin: 6px 0 4px;
    color: var(--platform-text-primary);
    font-size: 20px;
    font-weight: 650;
    letter-spacing: -0.02em;
  }

  p {
    max-width: 560px;
    margin: 0;
    color: var(--platform-text-secondary);
    font-size: 11px;
    line-height: 1.6;
  }
}

.stage-index {
  color: var(--platform-accent);
  font-family: var(--el-font-family-mono);
  font-size: 28px;
  font-weight: 600;
  opacity: 0.7;
}

.step-content {
  min-height: 0 !important;
  padding: 22px 24px 24px !important;
}

.step-panel {
  max-width: none !important;
  margin: 0 !important;
}

.step-content > .step-panel > .step-title {
  display: none;
}

.host-card {
  min-height: 270px;
  margin-bottom: 16px;
  padding: 16px;
  border: 1px solid var(--platform-line);
  border-radius: 4px;
  background: var(--platform-surface-0);

  &:hover:not(.is-offline) {
    border-color: rgba(67, 184, 232, 0.72);
    background: var(--platform-surface-2);
    box-shadow: 0 14px 24px rgba(0, 0, 0, 0.14);
    transform: translateY(-2px);
  }

  &.is-selected {
    border-color: var(--platform-accent);
    background: var(--platform-accent-soft);
    box-shadow: inset 0 0 0 1px rgba(67, 184, 232, 0.18);
  }

  &.is-offline {
    border-color: var(--platform-line);
    background: var(--platform-surface-2);
  }

  .host-header {
    margin-bottom: 14px;

    .host-icon {
      color: var(--platform-accent);
    }

    .host-name {
      color: var(--platform-text-primary);
      font-size: 15px;
      font-weight: 650;
    }
  }

  .host-info {
    padding: 12px 0;
    border-top: 1px solid var(--platform-line);
    border-bottom: 1px solid var(--platform-line);

    .info-item {
      margin-bottom: 7px;
      font-size: 10px;

      &:last-child {
        margin-bottom: 0;
      }

      .label {
        min-width: 55px;
        color: var(--platform-text-muted);
      }

      .value {
        color: var(--platform-text-regular);
      }
    }
  }

  .host-resources {
    margin-top: 14px;

    .resource-item {
      margin-bottom: 9px;

      .resource-label,
      .resource-value {
        color: var(--platform-text-muted);
        font-family: var(--el-font-family-mono);
        font-size: 10px;
      }

      .resource-label {
        min-width: 34px;
      }
    }

    :deep(.el-progress-bar__outer) {
      background: var(--platform-surface-3);
    }
  }

  .selected-mark {
    top: 10px;
    right: 10px;
    width: 22px;
    height: 22px;
    border-radius: 3px;
    background: var(--platform-accent);
  }
}

.game-list {
  max-height: 540px;
  padding-right: 8px;

  .game-item {
    border: 1px solid var(--platform-line);
    border-radius: 4px;
    background: var(--platform-surface-0);

    &:hover,
    &.is-selected {
      border-color: var(--platform-accent);
      background: var(--platform-accent-soft);
    }
  }
}

.game-detail {
  padding: 16px;
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-0);

  .detail-header,
  .method-description {
    background: var(--platform-surface-2);
  }
}

.config-form .config-section,
.confirm-section {
  padding: 17px;
  border: 1px solid var(--platform-line);
  border-radius: 4px;
  background: var(--platform-surface-0);
}

.config-form .config-section {
  margin-bottom: 12px;

  .section-title {
    color: var(--platform-text-primary);
    border-bottom-color: var(--platform-line);
  }

  .empty-env-vars {
    border-color: var(--platform-line);
    background: var(--platform-surface-2);
  }
}

.env-check-container {
  max-width: none;
  margin: 0;

  .env-check-item {
    border: 1px solid var(--platform-line);
    border-left-width: 3px;
    border-radius: 4px;
    background: var(--platform-surface-0);

    &.success {
      background: rgba(82, 207, 130, 0.07);
    }

    &.error {
      background: rgba(240, 100, 106, 0.07);
    }
  }
}

.confirm-section {
  margin-bottom: 12px;

  :deep(.el-descriptions__body),
  :deep(.el-descriptions__table) {
    background: var(--platform-surface-2);
  }

  :deep(.el-descriptions__label.el-descriptions__cell.is-bordered-label) {
    background: var(--platform-surface-3);
  }
}

.step-actions {
  justify-content: flex-end !important;
  gap: 8px;
  margin-top: 0 !important;
  padding: 16px 24px 20px !important;
  border-top: 1px solid var(--platform-line) !important;
  background: var(--platform-surface-2);
}

@media screen and (max-width: 900px) {
  .deploy-workbench {
    grid-template-columns: 190px minmax(0, 1fr);
  }

  .deploy-rail {
    padding-inline: 14px;
  }

  .step-content,
  .stage-header {
    padding-inline: 18px !important;
  }
}

@media screen and (max-width: 680px) {
  .card-header,
  .header-context {
    align-items: flex-start;
    flex-direction: column;
  }

  .header-context {
    justify-content: flex-start;
  }

  .deploy-workbench {
    display: block;
    min-height: 0;
  }

  .deploy-rail {
    border-right: 0;
    border-bottom: 1px solid var(--platform-line);
  }

  .deploy-rail .steps {
    margin-top: 16px !important;

    :deep(.el-step) {
      min-height: 44px;
    }
  }

  .rail-note {
    display: none;
  }

  .stage-header {
    padding-block: 17px !important;
  }

  .stage-index {
    font-size: 22px;
  }
}
</style>
