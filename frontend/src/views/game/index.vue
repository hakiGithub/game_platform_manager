<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { getGameList, getGamePage, getGameDetail, deleteGame } from "@/api/game";
import {
  getInstancesByGameId,
  startInstance,
  stopInstance,
} from "@/api/instance";
import { statusType } from "@/utils/instanceStatus";

const router = useRouter();

// 加载状态
const loading = ref(false);

// 搜索表单
const searchForm = reactive({
  keyword: "",
});

// 游戏列表
const gameList = ref([]);

// 分页状态
const pagination = reactive({
  current: 1,
  size: 10,
  total: 0,
});
const lastRefreshAt = ref("等待目录同步");

const catalogGameCount = computed(() => pagination.total || gameList.value.length);
const deployableGameCount = computed(
  () => gameList.value.filter((game) => game.supportedDeployTypes?.length > 0).length,
);
const multiRuntimeGameCount = computed(
  () => gameList.value.filter((game) => (game.supportedDeployTypes?.length || 0) > 1).length,
);
const templateReadyGameCount = computed(
  () =>
    gameList.value.filter(
      (game) =>
        game.deployConfig && Object.keys(game.deployConfig).length > 0,
    ).length,
);

// 详情抽屉
const drawerVisible = ref(false);
const drawerLoading = ref(false);
const currentGame = ref(null);

const currentGameInstances = computed(() => currentGame.value?.attachedInstances || []);
const currentGameRunningCount = computed(
  () => currentGameInstances.value.filter((instance) => instance.status === "running").length,
);

// 实例展开缓存：{ [gameId]: { loading, loaded, list } }
const instanceMap = ref({});

// 获取游戏列表
async function fetchGameList() {
  loading.value = true;
  try {
    const data = await getGamePage({
      current: pagination.current,
      size: pagination.size,
      keyword: searchForm.keyword || undefined,
    });
    gameList.value = data.records || [];
    pagination.total = data.total || 0;
    lastRefreshAt.value = formatRefreshTime();
    // 重置实例缓存
    instanceMap.value = {};
  } catch (error) {
    console.error("Failed to fetch game list:", error);
    ElMessage.error("获取游戏列表失败");
  } finally {
    loading.value = false;
  }
}

// 分页变化
function handlePageChange(page) {
  pagination.current = page;
  fetchGameList();
}

// 每页条数变化
function handleSizeChange(size) {
  pagination.size = size;
  pagination.current = 1;
  fetchGameList();
}

// 展开行时懒加载实例列表
async function handleExpandChange(row, expandedRows) {
  const isExpanded = expandedRows.some((r) => r.id === row.id);
  if (!isExpanded) return;
  // 已加载则跳过
  const cached = instanceMap.value[row.id];
  if (cached && cached.loaded) return;
  await loadGameInstances(row.id);
}

// 加载某游戏的实例列表
async function loadGameInstances(gameId) {
  instanceMap.value[gameId] = {
    loading: true,
    loaded: false,
    list: [],
  };
  try {
    const data = await getInstancesByGameId(gameId);
    instanceMap.value[gameId] = {
      loading: false,
      loaded: true,
      list: data || [],
    };
  } catch (error) {
    console.error("Failed to fetch instances by game id:", error);
    instanceMap.value[gameId] = {
      loading: false,
      loaded: true,
      list: [],
    };
  }
}

// 跳转到实例详情
function handleViewInstance(instance) {
  router.push(`/instance/detail/${instance.id}`);
}

// 启动实例
async function handleStartInstance(instance, gameId = instance.gameId) {
  try {
    await startInstance(instance.id);
    ElMessage.success("启动成功");
    if (gameId) {
      await loadGameInstances(gameId);
      refreshCurrentGameInstances(gameId);
    }
  } catch (error) {
    ElMessage.error("启动失败: " + (error.message || "未知错误"));
  }
}

// 停止实例
async function handleStopInstance(instance, gameId = instance.gameId) {
  try {
    await ElMessageBox.confirm(
      `确定要停止实例「${instance.instanceName}」吗？`,
      "确认操作",
      { type: "warning" },
    );
    await stopInstance(instance.id);
    ElMessage.success("停止成功");
    if (gameId) {
      await loadGameInstances(gameId);
      refreshCurrentGameInstances(gameId);
    }
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error("停止失败: " + (error.message || "未知错误"));
    }
  }
}

// 部署新实例
function handleDeployGame(row) {
  router.push({ path: "/instance/deploy", query: { gameId: row.id } });
}

// 搜索
function handleSearch() {
  pagination.current = 1;
  fetchGameList();
}

// 重置
function handleReset() {
  searchForm.keyword = "";
  pagination.current = 1;
  fetchGameList();
}

// 查看详情
async function handleDetail(row) {
  drawerVisible.value = true;
  drawerLoading.value = true;
  currentGame.value = null;
  try {
    const data = await getGameDetail(row.id);
    await loadGameInstances(row.id);
    currentGame.value = {
      ...data,
      attachedInstances: instanceMap.value[row.id]?.list || [],
    };
  } catch (error) {
    console.error("Failed to fetch game detail:", error);
    ElMessage.error("获取游戏详情失败");
    drawerVisible.value = false;
  } finally {
    drawerLoading.value = false;
  }
}

function refreshCurrentGameInstances(gameId) {
  if (!currentGame.value || currentGame.value.id !== gameId) return;
  currentGame.value = {
    ...currentGame.value,
    attachedInstances: instanceMap.value[gameId]?.list || [],
  };
}

// 删除游戏
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除游戏「${row.gameName}」吗？删除后相关实例配置将受影响，此操作不可恢复。`,
      "删除确认",
      {
        confirmButtonText: "确定删除",
        cancelButtonText: "取消",
        type: "warning",
      },
    );

    await deleteGame(row.id);
    ElMessage.success("删除成功");
    fetchGameList();
  } catch (error) {
    if (error !== "cancel") {
      console.error("Failed to delete game:", error);
      ElMessage.error("删除失败");
    }
  }
}

// 获取部署类型标签
function getDeployTypeTag(type) {
  const typeMap = {
    "docker": { label: "Docker", type: "primary" },
    "docker-compose": { label: "Docker Compose", type: "success" },
    "linuxgsm": { label: "LinuxGSM", type: "warning" },
    "linuxgsm-docker": { label: "LinuxGSM Docker", type: "danger" },
    "native": { label: "原生", type: "info" },
  };
  return typeMap[type] || { label: type, type: "info" };
}

// 格式化时间
function formatTime(time) {
  if (!time) return "-";
  return new Date(time).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

// 获取实例主端口（IP:Port 形式）
function getInstanceEndpoint(instance) {
  const port = instance.portConfig?.game || instance.portConfig?.default;
  if (!port) return instance.hostIp || "-";
  return `${instance.hostIp || "-"}:${port}`;
}

// 获取实例运行数
function getRunningCount(gameId) {
  const cached = instanceMap.value[gameId];
  if (!cached || !cached.list) return 0;
  return cached.list.filter((i) => i.status === "running").length;
}

function getRuntimeCountText(row) {
  const cached = instanceMap.value[row.id];
  if (cached?.loaded) {
    return `${getRunningCount(row.id)}/${cached.list.length} 运行`;
  }
  if (Number.isFinite(row.instanceCount)) {
    return `${row.runningInstanceCount || 0}/${row.instanceCount} 运行`;
  }
  return "展开查看";
}

function getCatalogReadiness(row) {
  if (!row.supportedDeployTypes?.length) return "待补充部署方式";
  if (row.deployConfig && Object.keys(row.deployConfig).length > 0) {
    return "部署模板就绪";
  }
  return "可部署 · 模板待加载";
}

function getCatalogReadinessTone(row) {
  return getCatalogReadiness(row) === "部署模板就绪" ? "ready" : "pending";
}

function getDefaultPortCount(row) {
  const ports = row.deployConfig?.defaultPorts;
  if (ports && typeof ports === "object") return Object.keys(ports).length;
  return row.defaultPort ? 1 : 0;
}

function getEntries(value) {
  if (!value || typeof value !== "object") return [];
  return Object.entries(value);
}

function getPortEntries(row) {
  return getEntries(row?.deployConfig?.defaultPorts || (row?.defaultPort ? { game: row.defaultPort } : null));
}

function formatRefreshTime() {
  return new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

onMounted(() => {
  fetchGameList();
});
</script>

<template>
  <div class="game-container catalog-page">
    <section class="catalog-hero">
      <div class="hero-copy">
        <span class="section-kicker">RUNTIME CATALOG / GAME METADATA</span>
        <h1>游戏目录</h1>
        <p>把游戏能力沉淀成可部署的运行时模板，查看支持的部署方式、端口契约和关联实例。</p>
      </div>
      <div class="hero-actions">
        <div class="hero-status">
          <span class="catalog-pulse" aria-hidden="true"></span>
          <div>
            <strong>目录已就绪</strong>
            <small>上次同步 {{ lastRefreshAt }}</small>
          </div>
        </div>
        <el-button @click="fetchGameList">
          <el-icon><Refresh /></el-icon>
          同步目录
        </el-button>
      </div>
    </section>

    <section class="catalog-rail" aria-label="游戏目录概况">
      <div class="rail-intro">
        <span class="section-kicker">GAME CATALOG</span>
        <strong>运行时资产</strong>
        <small>游戏能力与部署模板</small>
      </div>
      <div class="catalog-stat">
        <span>游戏条目</span>
        <strong>{{ catalogGameCount }}</strong>
      </div>
      <div class="catalog-stat is-ready">
        <span>可部署</span>
        <strong>{{ deployableGameCount }}</strong>
      </div>
      <div class="catalog-stat is-multi">
        <span>多运行时</span>
        <strong>{{ multiRuntimeGameCount }}</strong>
      </div>
      <div class="catalog-stat is-template">
        <span>模板就绪</span>
        <strong>{{ templateReadyGameCount }}</strong>
      </div>
    </section>

    <section class="catalog-filter-panel" aria-label="游戏目录筛选">
      <div class="panel-heading filter-heading">
        <div>
          <span class="section-kicker">FILTER / CAPABILITY</span>
          <h2>筛选目录</h2>
        </div>
        <span class="filter-hint">按游戏名称、编码定位运行时能力</span>
      </div>
      <el-form class="catalog-filter-form" :model="searchForm" inline>
        <el-form-item label="游戏名称或编码">
          <el-input v-model="searchForm.keyword" placeholder="例如：minecraft" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" @click="handleSearch">
            <el-icon><Search /></el-icon>
            应用筛选
          </el-button>
          <el-button @click="handleReset">
            <el-icon><Refresh /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="catalog-panel" aria-label="运行时目录清单">
      <div class="panel-heading catalog-heading">
        <div>
          <span class="section-kicker">RUNTIME BLUEPRINTS</span>
          <h2>运行时目录</h2>
          <p>{{ pagination.total }} 个游戏条目 · 展开行查看关联实例</p>
        </div>
        <el-button @click="fetchGameList">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>

      <el-table
        v-loading="loading"
        class="catalog-table"
        :data="gameList"
        style="width: 100%"
        row-key="id"
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand" width="48">
          <template #default="{ row }">
            <div class="runtime-matrix">
              <div class="matrix-heading">
                <div>
                  <span class="section-kicker">ATTACHED INSTANCES</span>
                  <strong>关联实例</strong>
                </div>
                <el-button link size="small" @click="handleDeployGame(row)">
                  <el-icon><Plus /></el-icon>
                  部署新实例
                </el-button>
              </div>
              <div v-loading="instanceMap[row.id]?.loading" class="runtime-list-wrapper">
                <template v-if="instanceMap[row.id]?.loaded">
                  <div v-if="instanceMap[row.id].list.length === 0" class="runtime-empty">
                    <el-icon><Grid /></el-icon>
                    <span>还没有关联实例</span>
                    <el-button type="primary" size="small" @click="handleDeployGame(row)">立即部署</el-button>
                  </div>
                  <div v-else class="runtime-cards">
                    <article v-for="instance in instanceMap[row.id].list" :key="instance.id" class="runtime-card" :class="`is-${instance.status}`">
                      <div class="runtime-card-heading">
                        <div class="runtime-title">
                          <span class="runtime-status-dot" :class="`is-${instance.status}`"></span>
                          <strong>{{ instance.instanceName }}</strong>
                          <el-tag :type="statusType(instance.status)" size="small" effect="plain">{{ instance.runStatusDesc }}</el-tag>
                        </div>
                        <div class="runtime-card-actions">
                          <el-button v-if="instance.status === 'stopped' || instance.status === 'error'" type="success" link size="small" @click="handleStartInstance(instance)">启动</el-button>
                          <el-button v-if="instance.status === 'running'" type="warning" link size="small" @click="handleStopInstance(instance)">停止</el-button>
                          <el-button type="primary" link size="small" @click="handleViewInstance(instance)">详情</el-button>
                        </div>
                      </div>
                      <div class="runtime-card-grid">
                        <div><span>主机</span><strong>{{ instance.hostName || "-" }}</strong></div>
                        <div><span>端点</span><strong class="mono">{{ getInstanceEndpoint(instance) }}</strong></div>
                        <div><span>玩家</span><strong>{{ instance.onlinePlayers || 0 }}</strong></div>
                        <div><span>部署方式</span><strong>{{ getDeployTypeTag(instance.deployType).label }}</strong></div>
                      </div>
                    </article>
                    <button class="runtime-add-card" type="button" @click="handleDeployGame(row)">
                      <el-icon><Plus /></el-icon>
                      <span>部署新实例</span>
                    </button>
                  </div>
                </template>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目录条目" min-width="230">
          <template #default="{ row }">
            <div class="catalog-name-cell">
              <span class="catalog-icon">
                <el-avatar v-if="row.iconUrl" :src="row.iconUrl" :size="32" shape="square" />
                <el-icon v-else><Grid /></el-icon>
              </span>
              <div>
                <strong>{{ row.gameName }}</strong>
                <span>{{ row.gameCode }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="部署能力" min-width="210">
          <template #default="{ row }">
            <div class="deploy-capability">
              <div class="deploy-tags">
                <el-tag v-for="type in row.supportedDeployTypes" :key="type" :type="getDeployTypeTag(type).type" size="small" effect="plain">{{ getDeployTypeTag(type).label }}</el-tag>
                <span v-if="!row.supportedDeployTypes?.length" class="text-muted">未配置</span>
              </div>
              <span>{{ row.supportedDeployTypes?.length || 0 }} 种部署路径</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="端口契约" width="128">
          <template #default="{ row }">
            <div class="port-contract">
              <strong>{{ row.defaultPort || "—" }}</strong>
              <span>{{ getDefaultPortCount(row) }} 个默认端口</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="关联实例" width="140">
          <template #default="{ row }">
            <div class="instance-summary">
              <strong>{{ getRuntimeCountText(row) }}</strong>
              <span>展开查看运行时</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="模板状态" min-width="150">
          <template #default="{ row }">
            <div class="catalog-readiness" :class="`is-${getCatalogReadinessTone(row)}`">
              <span class="readiness-dot"></span>
              <div>
                <strong>{{ getCatalogReadiness(row) }}</strong>
                <span>{{ formatTime(row.updateTime) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目录操作" width="210" fixed="right">
          <template #default="{ row }">
            <div class="catalog-actions">
              <el-button type="success" link size="small" @click="handleDeployGame(row)">部署</el-button>
              <el-button type="primary" link size="small" @click="handleDetail(row)">详情</el-button>
              <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="catalog-empty">
            <el-icon><Grid /></el-icon>
            <strong>暂无游戏目录条目</strong>
            <span>调整关键词或检查后端游戏元数据</span>
          </div>
        </template>
      </el-table>

      <div class="catalog-footer">
        <span><i class="catalog-pulse" aria-hidden="true"></i> 目录能力已接入</span>
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </section>

    <!-- 详情抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      direction="rtl"
      size="560px"
      :with-header="false"
      class="runtime-drawer"
    >
      <div v-loading="drawerLoading" class="runtime-detail">
        <template v-if="currentGame">
          <header class="runtime-detail-hero">
            <div class="drawer-topbar">
              <span class="section-kicker">RUNTIME TEMPLATE / GAME DETAIL</span>
              <el-button text circle aria-label="关闭详情" @click="drawerVisible = false">
                <el-icon><Close /></el-icon>
              </el-button>
            </div>
            <div class="runtime-detail-title">
              <span class="runtime-detail-icon">
                <el-avatar v-if="currentGame.iconUrl" :src="currentGame.iconUrl" :size="42" shape="square" />
                <el-icon v-else><Grid /></el-icon>
              </span>
              <div>
                <h2>{{ currentGame.gameName }}</h2>
                <code>{{ currentGame.gameCode }}</code>
              </div>
              <span class="detail-readiness" :class="`is-${getCatalogReadinessTone(currentGame)}`">
                <i></i>
                {{ getCatalogReadiness(currentGame) }}
              </span>
            </div>
            <p class="runtime-detail-description">{{ currentGame.description || "暂无运行时描述" }}</p>
            <div class="runtime-detail-meta">
              <span>更新于 {{ formatTime(currentGame.updateTime) }}</span>
              <span>默认端口 {{ currentGame.defaultPort || "—" }}</span>
              <span>{{ currentGameInstances.length }} 个关联实例</span>
            </div>
          </header>

          <div class="runtime-detail-actions">
            <el-button type="primary" @click="handleDeployGame(currentGame)">
              <el-icon><Plus /></el-icon>
              部署新实例
            </el-button>
            <span>从此模板创建新的服务实例</span>
          </div>

          <section class="detail-section runtime-overview-section">
            <div class="detail-section-heading">
              <div>
                <span class="section-kicker">TEMPLATE OVERVIEW</span>
                <h3>运行时概况</h3>
              </div>
              <span class="detail-section-index">01</span>
            </div>
            <div class="runtime-overview-grid">
              <div>
                <span>部署路径</span>
                <strong>{{ currentGame.supportedDeployTypes?.length || 0 }}</strong>
                <small>种可选方式</small>
              </div>
              <div>
                <span>关联实例</span>
                <strong>{{ currentGameInstances.length }}</strong>
                <small>{{ currentGameRunningCount }} 个运行中</small>
              </div>
              <div>
                <span>默认端口</span>
                <strong>{{ getDefaultPortCount(currentGame) }}</strong>
                <small>个端口契约</small>
              </div>
            </div>
          </section>

          <section class="detail-section contract-section">
            <div class="detail-section-heading">
              <div>
                <span class="section-kicker">DEPLOYMENT CONTRACT</span>
                <h3>部署契约</h3>
              </div>
              <span class="detail-section-index">02</span>
            </div>
            <div class="contract-block">
              <span class="contract-label">SUPPORT MATRIX</span>
              <div class="deploy-types-box">
                <el-tag
                  v-for="type in currentGame.supportedDeployTypes"
                  :key="type"
                  :type="getDeployTypeTag(type).type"
                  effect="plain"
                >
                  {{ getDeployTypeTag(type).label }}
                </el-tag>
                <span v-if="!currentGame.supportedDeployTypes?.length" class="detail-muted">暂无部署方式</span>
              </div>
            </div>
            <div class="contract-block">
              <span class="contract-label">PORT CONTRACT</span>
              <div v-if="getPortEntries(currentGame).length" class="port-contract-grid">
                <div v-for="entry in getPortEntries(currentGame)" :key="entry[0]" class="port-contract-item">
                  <span>{{ entry[0] }}</span>
                  <strong>{{ entry[1] }}</strong>
                  <small>TCP / 默认暴露</small>
                </div>
              </div>
              <div v-else class="detail-muted">未配置附加端口契约</div>
            </div>
          </section>

          <section class="detail-section dependency-section">
            <div class="detail-section-heading">
              <div>
                <span class="section-kicker">ENVIRONMENT / OPERATIONS</span>
                <h3>环境与能力</h3>
              </div>
              <span class="detail-section-index">03</span>
            </div>
            <div class="dependency-grid">
              <div class="dependency-group">
                <span class="contract-label">RUNTIME DEPENDENCIES</span>
                <div v-if="getEntries(currentGame.environmentDeps).length" class="dependency-list">
                  <div v-for="entry in getEntries(currentGame.environmentDeps)" :key="entry[0]">
                    <span>{{ entry[0] }}</span>
                    <strong>{{ entry[1] }}</strong>
                  </div>
                </div>
                <span v-else class="detail-muted">未配置运行环境依赖</span>
              </div>
              <div class="dependency-group">
                <span class="contract-label">CUSTOM OPERATIONS</span>
                <div v-if="getEntries(currentGame.customOperations).length" class="dependency-list">
                  <div v-for="entry in getEntries(currentGame.customOperations)" :key="entry[0]">
                    <span>{{ entry[0] }}</span>
                    <strong>{{ entry[1] }}</strong>
                  </div>
                </div>
                <span v-else class="detail-muted">未配置扩展操作</span>
              </div>
            </div>
          </section>

          <section class="detail-section attached-section">
            <div class="detail-section-heading">
              <div>
                <span class="section-kicker">ATTACHED INSTANCES</span>
                <h3>关联实例</h3>
              </div>
              <span class="detail-section-index">04</span>
            </div>
            <div v-if="currentGameInstances.length" class="drawer-instance-list">
              <article v-for="instance in currentGameInstances" :key="instance.id" class="drawer-instance-card" :class="`is-${instance.status}`">
                <div class="drawer-instance-heading">
                  <div>
                    <span class="drawer-instance-name"><i class="instance-status-dot" :class="`is-${instance.status}`"></i>{{ instance.instanceName }}</span>
                    <small>{{ instance.hostName || "未绑定主机" }} · {{ getDeployTypeTag(instance.deployType).label }}</small>
                  </div>
                  <el-tag :type="statusType(instance.status)" size="small" effect="plain">{{ instance.runStatusDesc }}</el-tag>
                </div>
                <div class="drawer-instance-meta">
                  <span><b>ENDPOINT</b>{{ getInstanceEndpoint(instance) }}</span>
                  <span><b>PLAYERS</b>{{ instance.onlinePlayers || 0 }} / {{ instance.configInfo?.maxPlayers || "—" }}</span>
                </div>
                <div class="drawer-instance-actions">
                  <el-button v-if="instance.status === 'stopped' || instance.status === 'error'" type="success" link size="small" @click="handleStartInstance(instance, currentGame.id)">启动</el-button>
                  <el-button v-if="instance.status === 'running'" type="warning" link size="small" @click="handleStopInstance(instance, currentGame.id)">停止</el-button>
                  <el-button type="primary" link size="small" @click="handleViewInstance(instance)">打开实例详情</el-button>
                </div>
              </article>
            </div>
            <div v-else class="drawer-empty-instances">
              <el-icon><Grid /></el-icon>
              <strong>还没有关联实例</strong>
              <span>这个运行时模板可以直接用于创建第一台服务</span>
              <el-button type="primary" size="small" @click="handleDeployGame(currentGame)">立即部署</el-button>
            </div>
          </section>

          <section v-if="currentGame.remark" class="detail-section drawer-note-section">
            <span class="contract-label">CATALOG NOTE</span>
            <p>{{ currentGame.remark }}</p>
          </section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.game-container {
  .search-card {
    margin-bottom: 16px;

    :deep(.el-card__body) {
      padding-bottom: 0;
    }
  }

  .table-card {
    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;

      .title {
        font-size: var(--platform-font-size-md);
        font-weight: var(--platform-font-weight-bold);
        color: var(--el-text-color-primary);
      }

      .header-actions {
        display: flex;
        gap: 8px;
      }
    }

    .pagination-wrap {
      display: flex;
      justify-content: flex-end;
      margin-top: 16px;
    }
  }
}

// 实例展开区
.instance-expand {
  padding: 12px 24px 20px 48px;
  background-color: var(--el-fill-color-lighter);
}

.instance-list-wrapper {
  min-height: 80px;
}

.empty-instances {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
}

.instance-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.instance-card {
  display: flex;
  flex-direction: column;
  background-color: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px 14px;
  transition: all 0.2s;

  &:hover {
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
    border-color: var(--el-color-primary-light-5);
  }

  &.status-running {
    border-left: 3px solid var(--el-color-success);
  }

  &.status-stopped {
    border-left: 3px solid var(--el-color-info);
  }

  &.status-error {
    border-left: 3px solid var(--el-color-danger);
  }

  &.status-installing,
  &.status-updating,
  &.status-starting,
  &.status-stopping {
    border-left: 3px solid var(--el-color-warning);
  }

  .instance-card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;

    .instance-name-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      min-width: 0;
    }

    .instance-name {
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .instance-actions {
      display: flex;
      gap: 4px;
      flex-shrink: 0;
    }
  }

  .instance-card-body {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 6px 16px;
  }

  .instance-meta {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: var(--platform-font-size-sm);

    .meta-label {
      color: var(--el-text-color-secondary);
      min-width: 48px;
    }

    .meta-value {
      color: var(--el-text-color-primary);

      &.mono {
        font-family: "Consolas", "Monaco", monospace;
        font-size: var(--platform-font-size-xs);
      }
    }
  }
}

.add-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed var(--el-border-color);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  min-height: 110px;

  &:hover {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary);
    background-color: var(--el-color-primary-light-9);
  }

  .add-icon {
    font-size: 24px;
  }
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;

  &.dot-running {
    background-color: var(--el-color-success);
    box-shadow: 0 0 4px var(--el-color-success);
  }

  &.dot-stopped {
    background-color: var(--el-color-info);
  }

  &.dot-error {
    background-color: var(--el-color-danger);
  }

  &.dot-installing,
  &.dot-updating,
  &.dot-starting,
  &.dot-stopping {
    background-color: var(--el-color-warning);
    animation: pulse 1.5s infinite;
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.instance-count-text {
  font-size: var(--platform-font-size-sm);
}

.instance-badge {
  margin: 0 auto;
}

.game-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;

  .game-info {
    display: flex;
    flex-direction: column;
    gap: 2px;

    .game-name {
      font-weight: var(--platform-font-weight-medium);
      color: var(--el-text-color-primary);
    }

    .game-code {
      font-size: var(--platform-font-size-xs);
      color: var(--el-text-color-secondary);
    }
  }
}

.deploy-types {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.text-muted {
  color: var(--el-text-color-placeholder);
}

.description-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

// 详情抽屉样式
.detail-content {
  padding: 0 16px;
}

.detail-section {
  margin-bottom: 24px;

  .section-title {
    font-size: var(--platform-font-size-base);
    font-weight: var(--platform-font-weight-medium);
    color: var(--el-text-color-primary);
    margin-bottom: 12px;
    padding-left: 8px;
    border-left: 3px solid var(--el-color-primary);
  }
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;

  .detail-item {
    display: flex;
    flex-direction: column;
    gap: 4px;

    .label {
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-secondary);
    }

    .value {
      font-size: var(--platform-font-size-base);
      color: var(--el-text-color-primary);
    }
  }
}

.description-box {
  padding: 12px;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: var(--platform-font-size-sm);
  color: var(--el-text-color-regular);
  line-height: 1.6;
}

.deploy-types-box {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.json-box {
  padding: 12px;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
  overflow-x: auto;

  pre {
    margin: 0;
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-regular);
    white-space: pre-wrap;
    word-break: break-all;
  }
}

// 响应式适配
@media screen and (max-width: 1366px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style lang="scss" scoped>
.catalog-page :deep(.runtime-drawer) {
  --runtime-accent: #e5b45f;
  --runtime-teal: #72d6c4;
  --runtime-line: rgba(63, 83, 101, 0.68);
  background: var(--platform-surface-1);

  .el-drawer__body {
    padding: 0;
  }
}

.runtime-detail {
  min-height: 100%;
  color: var(--platform-text-primary);
  background:
    linear-gradient(180deg, rgba(229, 180, 95, 0.07), transparent 26%),
    var(--platform-surface-1);
}

.runtime-detail-hero {
  padding: 22px 22px 20px;
  border-bottom: 1px solid var(--platform-line);
  background:
    linear-gradient(120deg, rgba(229, 180, 95, 0.15), transparent 60%),
    var(--platform-surface-1);
}

.drawer-topbar,
.runtime-detail-title,
.runtime-detail-meta,
.detail-section-heading,
.runtime-detail-actions,
.drawer-instance-heading,
.drawer-instance-meta,
.drawer-instance-actions {
  display: flex;
  align-items: center;
}

.drawer-topbar,
.detail-section-heading,
.drawer-instance-heading {
  justify-content: space-between;
  gap: 12px;
}

.drawer-topbar :deep(.el-button) {
  margin-right: -7px;
  color: var(--platform-text-secondary);

  &:hover {
    color: var(--runtime-accent);
  }
}

.runtime-detail-title {
  align-items: center;
  gap: 12px;
  margin-top: 18px;

  h2 {
    margin: 0 0 4px;
    color: var(--platform-text-primary);
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.03em;
  }

  code {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.05em;
  }
}

.runtime-detail-icon {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  border: 1px solid rgba(229, 180, 95, 0.45);
  border-radius: 6px;
  background: rgba(229, 180, 95, 0.12);
  color: var(--runtime-accent);
  font-size: 22px;

  :deep(.el-avatar) {
    border-radius: 5px;
  }
}

.detail-readiness {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  margin-left: auto;
  padding: 5px 7px;
  border: 1px solid rgba(229, 180, 95, 0.3);
  border-radius: 3px;
  color: var(--runtime-accent);
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.03em;

  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &.is-ready {
    border-color: rgba(114, 214, 196, 0.3);
    color: var(--runtime-teal);
  }
}

.runtime-detail-description {
  margin: 16px 0 0 58px;
  color: var(--platform-text-secondary);
  font-size: 12px;
  line-height: 1.65;
}

.runtime-detail-meta {
  flex-wrap: wrap;
  gap: 8px 14px;
  margin: 14px 0 0 58px;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
}

.runtime-detail-actions {
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px;
  border-bottom: 1px solid var(--platform-line);
  background: var(--platform-surface-2);

  > span {
    color: var(--platform-text-muted);
    font-size: 10px;
    text-align: right;
  }
}

.runtime-detail .detail-section {
  margin: 0;
  padding: 19px 22px;
  border-bottom: 1px solid var(--platform-line);
}

.detail-section-heading {
  align-items: flex-start;
  margin-bottom: 15px;

  h3 {
    margin: 5px 0 0;
    color: var(--platform-text-primary);
    font-size: 15px;
    font-weight: 650;
  }
}

.detail-section-index,
.contract-label {
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 9px;
  letter-spacing: 0.08em;
}

.runtime-overview-section {
  background: rgba(16, 32, 45, 0.36);
}

.runtime-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border: 1px solid var(--runtime-line);

  > div {
    display: grid;
    gap: 5px;
    min-height: 80px;
    padding: 12px 13px;
    border-right: 1px solid var(--runtime-line);

    &:last-child {
      border-right: 0;
    }
  }

  span {
    color: var(--platform-text-muted);
    font-size: 10px;
  }

  strong {
    color: var(--runtime-accent);
    font-family: var(--el-font-family-mono);
    font-size: 19px;
    font-weight: 600;
  }

  small {
    color: var(--platform-text-muted);
    font-size: 10px;
  }
}

.contract-section {
  background: var(--platform-surface-1);
}

.contract-block + .contract-block {
  margin-top: 18px;
  padding-top: 15px;
  border-top: 1px solid var(--runtime-line);
}

.contract-label {
  display: block;
  margin-bottom: 9px;
}

.deploy-types-box {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;

  :deep(.el-tag) {
    border-color: rgba(229, 180, 95, 0.32);
    background: rgba(229, 180, 95, 0.08);
    color: #f1c979;
    font-size: 10px;
  }
}

.port-contract-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.port-contract-item {
  display: grid;
  gap: 5px;
  padding: 10px;
  border: 1px solid var(--runtime-line);
  border-radius: 3px;
  background: var(--platform-surface-0);

  span,
  small {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 9px;
  }

  strong {
    color: var(--platform-text-primary);
    font-family: var(--el-font-family-mono);
    font-size: 16px;
    font-weight: 600;
  }
}

.dependency-section {
  background: rgba(16, 32, 45, 0.26);
}

.dependency-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.dependency-group {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--runtime-line);
  background: var(--platform-surface-0);
}

.dependency-list {
  display: grid;
  gap: 8px;

  > div {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    padding-bottom: 7px;
    border-bottom: 1px solid rgba(63, 83, 101, 0.42);

    &:last-child {
      padding-bottom: 0;
      border-bottom: 0;
    }
  }

  span {
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
  }

  strong {
    overflow: hidden;
    color: var(--platform-text-regular);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.detail-muted {
  color: var(--platform-text-muted);
  font-size: 10px;
}

.drawer-instance-list {
  display: grid;
  gap: 9px;
}

.drawer-instance-card {
  padding: 12px;
  border: 1px solid var(--runtime-line);
  border-left: 2px solid var(--platform-text-muted);
  background: var(--platform-surface-0);

  &.is-running {
    border-left-color: var(--platform-green);
  }

  &.is-error {
    border-left-color: var(--platform-red);
  }
}

.drawer-instance-heading {
  align-items: flex-start;

  > div {
    min-width: 0;
  }

  .el-tag {
    flex: 0 0 auto;
    font-size: 9px;
  }
}

.drawer-instance-name {
  display: flex;
  align-items: center;
  gap: 7px;
  overflow: hidden;
  color: var(--platform-text-primary);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;

  .instance-status-dot {
    width: 7px;
    height: 7px;
    flex: 0 0 auto;
    border-radius: 50%;
    background: var(--platform-text-muted);

    &.is-running {
      background: var(--platform-green);
      box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.12);
    }

    &.is-error {
      background: var(--platform-red);
      box-shadow: 0 0 0 3px rgba(240, 100, 106, 0.12);
    }
  }
}

.drawer-instance-heading small {
  display: block;
  margin: 5px 0 0 14px;
  color: var(--platform-text-muted);
  font-size: 10px;
}

.drawer-instance-meta {
  flex-wrap: wrap;
  gap: 8px 18px;
  margin: 14px 0 9px 14px;
  color: var(--platform-text-regular);
  font-family: var(--el-font-family-mono);
  font-size: 10px;

  span {
    display: inline-flex;
    gap: 7px;
  }

  b {
    color: var(--platform-text-muted);
    font-size: 9px;
    font-weight: 500;
  }
}

.drawer-instance-actions {
  justify-content: flex-end;
  gap: 4px;
  border-top: 1px solid rgba(63, 83, 101, 0.42);
  padding-top: 8px;
}

.drawer-empty-instances {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 28px 10px 18px;
  color: var(--platform-text-muted);
  text-align: center;

  .el-icon {
    color: var(--runtime-accent);
    font-size: 23px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
  }

  span {
    font-size: 10px;
  }
}

.drawer-note-section {
  border-bottom: 0 !important;

  p {
    margin: 0;
    color: var(--platform-text-secondary);
    font-size: 11px;
    line-height: 1.7;
  }
}

@media screen and (max-width: 600px) {
  .runtime-detail-hero,
  .runtime-detail .detail-section,
  .runtime-detail-actions {
    padding-inline: 16px;
  }

  .runtime-detail-title {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .detail-readiness {
    margin-left: 58px;
  }

  .runtime-detail-description,
  .runtime-detail-meta {
    margin-left: 0;
  }

  .runtime-detail-actions {
    align-items: flex-start;
    flex-direction: column;

    > span {
      text-align: left;
    }
  }

  .runtime-overview-grid,
  .dependency-grid {
    grid-template-columns: 1fr;
  }

  .runtime-overview-grid > div {
    border-right: 0;
    border-bottom: 1px solid var(--runtime-line);

    &:last-child {
      border-bottom: 0;
    }
  }

  .port-contract-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

<style lang="scss" scoped>
.catalog-page {
  --catalog-gap: 14px;
  --catalog-accent: #e5b45f;
  --catalog-accent-soft: rgba(229, 180, 95, 0.12);
  --catalog-teal: #72d6c4;

  padding: 4px 2px 24px;
  color: var(--platform-text-primary);
}

.catalog-hero,
.catalog-rail,
.catalog-filter-panel,
.catalog-panel {
  border: 1px solid var(--platform-line);
  background: var(--platform-surface-1);
}

.catalog-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  min-height: 138px;
  padding: 24px 26px;
  border-radius: 6px;
  background:
    linear-gradient(116deg, rgba(229, 180, 95, 0.16), transparent 44%),
    var(--platform-surface-1);
  box-shadow: 0 14px 28px rgba(0, 0, 0, 0.14);
}

.catalog-page .section-kicker {
  display: block;
  color: var(--platform-text-muted);
  font-family: var(--el-font-family-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  line-height: 1.4;
}

.catalog-page .hero-copy h1 {
  margin: 8px 0 7px;
  color: var(--platform-text-primary);
  font-size: clamp(24px, 2.4vw, 32px);
  font-weight: 700;
  letter-spacing: -0.03em;
}

.catalog-page .hero-copy p {
  max-width: 650px;
  margin: 0;
  color: var(--platform-text-secondary);
  font-size: 13px;
}

.catalog-page .hero-actions,
.catalog-page .hero-status,
.catalog-page .panel-heading,
.catalog-page .catalog-actions,
.catalog-page .runtime-card-heading,
.catalog-page .runtime-title,
.catalog-page .catalog-footer {
  display: flex;
  align-items: center;
}

.catalog-page .hero-actions {
  gap: 20px;
}

.catalog-page .hero-status {
  gap: 10px;

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 13px;
  }

  small {
    margin-top: 3px;
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.catalog-pulse,
.readiness-dot,
.runtime-status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.catalog-pulse {
  background: var(--catalog-accent);
  box-shadow: 0 0 0 3px var(--catalog-accent-soft);
}

.catalog-rail {
  display: grid;
  grid-template-columns: minmax(205px, 1.4fr) repeat(4, minmax(78px, 0.7fr));
  align-items: stretch;
  gap: 0;
  min-height: 78px;
  margin-top: var(--catalog-gap);
  padding: 12px 18px;
  border-radius: 5px;
  background:
    linear-gradient(90deg, rgba(229, 180, 95, 0.05), transparent 52%),
    var(--platform-surface-2);
}

.rail-intro,
.catalog-stat {
  display: grid;
  align-content: center;
  gap: 4px;
}

.rail-intro {
  strong {
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 600;
  }

  small {
    color: var(--platform-text-muted);
    font-size: 11px;
  }
}

.catalog-stat {
  position: relative;
  padding: 0 15px;
  border-left: 1px solid var(--platform-line);

  &::before {
    position: absolute;
    top: 14px;
    left: -1px;
    width: 2px;
    height: 28px;
    background: var(--platform-text-muted);
    content: "";
  }

  > span {
    color: var(--platform-text-muted);
    font-size: 11px;
  }

  > strong {
    color: var(--platform-text-primary);
    font-size: 18px;
    font-weight: 650;
  }

  &.is-ready::before {
    background: var(--catalog-teal);
  }

  &.is-ready > strong {
    color: var(--catalog-teal);
  }

  &.is-multi::before,
  &.is-template::before {
    background: var(--catalog-accent);
  }
}

.catalog-filter-panel,
.catalog-panel {
  margin-top: var(--catalog-gap);
  border-radius: 5px;
  overflow: hidden;
}

.catalog-filter-panel {
  padding: 17px 18px 5px;
}

.catalog-page .panel-heading {
  justify-content: space-between;
  gap: 16px;
}

.catalog-page .panel-heading h2 {
  margin: 5px 0 0;
  color: var(--platform-text-primary);
  font-size: 16px;
  font-weight: 650;
}

.catalog-page .filter-hint,
.catalog-page .catalog-heading p {
  color: var(--platform-text-muted);
  font-size: 11px;
}

.catalog-filter-form {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 13px;

  :deep(.el-form-item) {
    margin-right: 0;
    margin-bottom: 10px;
  }

  :deep(.el-input) {
    width: 240px;
  }

  .filter-actions {
    display: flex;
    gap: 8px;
  }
}

.catalog-heading {
  padding: 17px 18px 15px;
  border-bottom: 1px solid var(--platform-line);
}

.catalog-heading p {
  margin: 5px 0 0;
}

.catalog-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(229, 180, 95, 0.07);
  --el-table-header-bg-color: rgba(20, 39, 53, 0.86);
  --el-table-border-color: var(--platform-line);
  --el-table-text-color: var(--platform-text-regular);
  --el-table-header-text-color: var(--platform-text-secondary);

  :deep(.el-table__inner-wrapper::before) {
    display: none;
  }

  :deep(.el-table__header-wrapper th.el-table__cell) {
    height: 42px;
    background: var(--el-table-header-bg-color);
    color: var(--platform-text-secondary);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.03em;
  }

  :deep(.el-table__body-wrapper td.el-table__cell) {
    height: 76px;
    border-bottom-color: rgba(38, 56, 71, 0.72);
  }

  :deep(.el-table__row) {
    transition: background 0.18s ease;
  }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: rgba(229, 180, 95, 0.07);
  }

  :deep(.el-table__expand-icon) {
    color: var(--catalog-accent);
  }

  :deep(.el-table__expand-icon--expanded) {
    transform: rotate(90deg);
  }

  :deep(.el-button.is-link) {
    font-size: 12px;
  }
}

.catalog-name-cell {
  display: flex;
  align-items: center;
  gap: 11px;

  > div {
    min-width: 0;
  }

  strong,
  span {
    display: block;
  }

  strong {
    overflow: hidden;
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  > div > span {
    margin-top: 4px;
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.06em;
  }
}

.catalog-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border: 1px solid rgba(229, 180, 95, 0.44);
  border-radius: 5px;
  background: var(--catalog-accent-soft);
  color: var(--catalog-accent);
  font-size: 17px;
}

.deploy-capability,
.port-contract,
.instance-summary,
.catalog-readiness {
  display: grid;
  gap: 5px;
}

.deploy-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;

  :deep(.el-tag) {
    border-color: rgba(229, 180, 95, 0.3);
    background: rgba(229, 180, 95, 0.08);
    color: #f1c979;
  }
}

.deploy-capability > span,
.port-contract > span,
.instance-summary > span,
.catalog-readiness span {
  color: var(--platform-text-muted);
  font-size: 10px;
}

.port-contract strong,
.instance-summary strong {
  color: var(--platform-text-primary);
  font-family: var(--el-font-family-mono);
  font-size: 13px;
  font-weight: 600;
}

.catalog-readiness {
  grid-template-columns: 8px minmax(0, 1fr);
  align-items: start;
  column-gap: 8px;

  .readiness-dot {
    margin-top: 3px;
    background: var(--catalog-accent);
    box-shadow: 0 0 0 3px var(--catalog-accent-soft);
  }

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--catalog-accent);
    font-size: 12px;
    font-weight: 600;
  }

  &.is-ready {
    .readiness-dot {
      background: var(--catalog-teal);
      box-shadow: 0 0 0 3px rgba(114, 214, 196, 0.12);
    }

    strong {
      color: var(--catalog-teal);
    }
  }
}

.catalog-actions {
  gap: 5px;
}

.runtime-matrix {
  padding: 16px 24px 18px 48px;
  border-top: 1px solid rgba(229, 180, 95, 0.12);
  background:
    linear-gradient(105deg, rgba(229, 180, 95, 0.06), transparent 42%),
    var(--platform-surface-2);
}

.matrix-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 12px;

  strong {
    display: block;
    margin-top: 4px;
    color: var(--platform-text-primary);
    font-size: 13px;
    font-weight: 600;
  }
}

.runtime-list-wrapper {
  min-height: 78px;
}

.runtime-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 10px;
}

.runtime-card,
.runtime-add-card {
  min-height: 122px;
  border: 1px solid var(--platform-line);
  border-radius: 5px;
  background: rgba(16, 32, 45, 0.76);
}

.runtime-card {
  padding: 13px 14px;
  border-left: 2px solid var(--platform-text-muted);

  &.is-running {
    border-left-color: var(--platform-green);
  }

  &.is-error {
    border-left-color: var(--platform-red);
  }

  &.is-stopped {
    border-left-color: var(--platform-text-muted);
  }
}

.runtime-card-heading {
  justify-content: space-between;
  gap: 10px;
}

.runtime-title {
  min-width: 0;
  gap: 7px;

  strong {
    overflow: hidden;
    color: var(--platform-text-primary);
    font-size: 12px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.runtime-status-dot {
  background: var(--platform-text-muted);

  &.is-running {
    background: var(--platform-green);
    box-shadow: 0 0 0 3px rgba(82, 207, 130, 0.12);
  }

  &.is-error {
    background: var(--platform-red);
    box-shadow: 0 0 0 3px rgba(240, 100, 106, 0.12);
  }
}

.runtime-card-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 2px;
}

.runtime-card-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 14px;
  margin-top: 15px;

  div {
    min-width: 0;
  }

  span,
  strong {
    display: block;
  }

  span {
    color: var(--platform-text-muted);
    font-size: 10px;
  }

  strong {
    overflow: hidden;
    margin-top: 3px;
    color: var(--platform-text-regular);
    font-size: 11px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mono {
    font-family: var(--el-font-family-mono);
  }
}

.runtime-add-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border-style: dashed;
  border-color: rgba(229, 180, 95, 0.42);
  background: rgba(229, 180, 95, 0.04);
  color: var(--catalog-accent);
  cursor: pointer;
  font-size: 12px;
  transition: border-color 0.18s ease, background 0.18s ease;

  &:hover {
    border-color: var(--catalog-accent);
    background: rgba(229, 180, 95, 0.09);
  }
}

.runtime-empty,
.catalog-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 110px;
  color: var(--platform-text-muted);

  .el-icon {
    color: var(--catalog-accent);
    font-size: 22px;
  }

  strong {
    color: var(--platform-text-regular);
    font-size: 12px;
    font-weight: 600;
  }

  span {
    font-size: 11px;
  }
}

.catalog-empty {
  padding: 28px 0;
}

.catalog-footer {
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 0 18px;
  border-top: 1px solid var(--platform-line);

  > span {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    color: var(--platform-text-muted);
    font-family: var(--el-font-family-mono);
    font-size: 10px;
    letter-spacing: 0.04em;
  }

  .catalog-pulse {
    width: 6px;
    height: 6px;
    box-shadow: none;
  }
}

.catalog-page :deep(.el-pagination) {
  --el-pagination-bg-color: transparent;
  --el-pagination-button-bg-color: transparent;
  --el-pagination-hover-color: var(--catalog-accent);
}

.catalog-page :deep(.el-drawer) {
  background: var(--platform-surface-1);
}

.catalog-page :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding: 18px 20px;
  border-bottom: 1px solid var(--platform-line);
  color: var(--platform-text-primary);
}

.catalog-page :deep(.el-drawer__body) {
  padding-top: 18px;
}

@media screen and (max-width: 1180px) {
  .catalog-rail {
    grid-template-columns: minmax(180px, 1.2fr) repeat(4, minmax(72px, 0.7fr));
    padding-inline: 12px;
  }

  .catalog-stat {
    padding-inline: 10px;
  }

  .catalog-table {
    :deep(.el-table__body-wrapper td.el-table__cell),
    :deep(.el-table__header-wrapper th.el-table__cell) {
      padding-inline: 8px;
    }
  }
}

@media screen and (max-width: 780px) {
  .catalog-hero,
  .catalog-page .panel-heading,
  .catalog-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .catalog-hero {
    gap: 18px;
  }

  .catalog-page .hero-actions {
    justify-content: space-between;
    width: 100%;
  }

  .catalog-rail {
    grid-template-columns: repeat(2, 1fr);
    gap: 14px 0;
  }

  .rail-intro {
    grid-column: span 2;
  }

  .catalog-stat:nth-child(2n) {
    border-left: 0;
  }

  .catalog-filter-form {
    align-items: stretch;
    flex-direction: column;

    :deep(.el-input) {
      width: 100%;
    }
  }

  .catalog-page .filter-actions {
    margin-bottom: 10px;
  }

  .catalog-heading {
    gap: 10px;
  }

  .catalog-footer {
    gap: 10px;
    padding-block: 12px;
  }

  .runtime-matrix {
    padding-inline: 16px;
  }
}
</style>
