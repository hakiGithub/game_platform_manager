<template>
  <div class="rcon-page">
    <div class="plugin-page-header">
      <div class="header-meta">
        <span class="section-kicker">L4D2 COMMAND / RCON CONSOLE</span>
        <h2>RCON 控制台</h2>
        <p>远程执行服务器命令，快速运维与调试</p>
      </div>
      <div class="header-actions">
        <el-button @click="clearHistory">
          <el-icon><Delete /></el-icon>
          清空历史
        </el-button>
      </div>
    </div>

      <el-row :gutter="20">
        <!-- 命令输入区 -->
        <el-col :span="16">
          <el-card shadow="hover" class="console-card">
            <template #header>
              <div class="card-header">
                <span>控制台</span>
                <el-tag type="info">{{ history.length }} 条历史</el-tag>
              </div>
            </template>
            
            <!-- 输出区域 -->
            <div class="console-output" ref="outputRef">
              <div
                v-for="(item, index) in history"
                :key="index"
                class="console-item"
              >
                <div class="command-line">
                  <span class="prompt">></span>
                  <span class="command">{{ item.command }}</span>
                </div>
                <div class="output-line">
                  <pre>{{ item.output }}</pre>
                </div>
              </div>
            </div>
            
            <!-- 输入区域 -->
            <div class="console-input">
              <el-input
                v-model="command"
                placeholder="输入 RCON 命令..."
                @keyup.enter="executeCommand"
                clearable
              >
                <template #prefix>
                  <span class="prompt">></span>
                </template>
              </el-input>
              <el-button type="primary" @click="executeCommand" :loading="executing">
                执行
              </el-button>
            </div>
          </el-card>
        </el-col>

        <!-- 常用命令 -->
        <el-col :span="8">
          <el-card shadow="hover" class="commands-card">
            <template #header>
              <span>常用命令</span>
            </template>
            
            <div class="command-list">
              <div
                v-for="cmd in COMMON_RCON_COMMANDS"
                :key="cmd.command"
                class="command-item"
                @click="quickCommand(cmd.command)"
              >
                <div class="command-name">{{ cmd.command }}</div>
                <div class="command-desc">{{ cmd.description }}</div>
              </div>
            </div>
          </el-card>

          <el-card shadow="hover" class="tips-card" style="margin-top: 20px">
            <template #header>
              <span>提示</span>
            </template>
            
            <ul class="tips-list">
              <li>使用上下箭头键可以浏览历史命令</li>
              <li>按 Enter 键快速执行命令</li>
              <li>点击常用命令可以快速输入</li>
              <li>部分命令需要管理员权限</li>
            </ul>
          </el-card>
        </el-col>
      </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { rconApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'
import { COMMON_RCON_COMMANDS } from '@/utils/gameConstants'
import type { RconHistory } from '@/types'

const pluginStore = usePluginStore()
const command = ref('')
const executing = ref(false)
const history = ref<RconHistory[]>([])
const outputRef = ref<HTMLElement | null>(null)
const historyIndex = ref(-1)

// 当前实例 ID（用于区分不同实例的历史记录）
const instanceId = computed(() => pluginStore.instanceInfo?.instanceId ?? 0)

// localStorage 历史记录 key（按实例隔离）
const HISTORY_STORAGE_KEY_PREFIX = 'l4d2_rcon_history_'
const MAX_HISTORY_ITEMS = 50

function historyStorageKey() {
  return `${HISTORY_STORAGE_KEY_PREFIX}${instanceId.value}`
}

// 从 localStorage 加载历史记录
function loadHistory() {
  if (!instanceId.value) {
    history.value = []
    return
  }
  try {
    const raw = localStorage.getItem(historyStorageKey())
    if (raw) {
      const items = JSON.parse(raw) as RconHistory[]
      history.value = Array.isArray(items) ? items.slice(-MAX_HISTORY_ITEMS) : []
    } else {
      history.value = []
    }
  } catch (e) {
    console.error('Failed to load RCON history from localStorage:', e)
    history.value = []
  }
}

// 持久化历史记录到 localStorage
function persistHistory() {
  if (!instanceId.value) return
  try {
    const items = history.value.slice(-MAX_HISTORY_ITEMS)
    localStorage.setItem(historyStorageKey(), JSON.stringify(items))
  } catch (e) {
    console.warn('Failed to persist RCON history:', e)
  }
}

// 方法
async function executeCommand() {
  if (!command.value.trim()) return
  if (!instanceId.value) {
    pluginStore.notifyError('执行失败', '未选择实例，请先在仪表盘选择实例')
    return
  }

  executing.value = true
  try {
    const result = await rconApi.execute(instanceId.value, command.value)
    const output = result.success ? (result.output ?? '') : (result.error ?? '执行失败')

    history.value.push({
      id: Date.now().toString(),
      command: command.value,
      output,
      timestamp: Date.now()
    })
    persistHistory()

    command.value = ''
    historyIndex.value = -1

    // 滚动到底部
    await nextTick()
    if (outputRef.value) {
      outputRef.value.scrollTop = outputRef.value.scrollHeight
    }
  } catch (error) {
    pluginStore.notifyError('执行失败', '命令执行失败')
  } finally {
    executing.value = false
  }
}

function quickCommand(cmd: string) {
  command.value = cmd
}

async function clearHistory() {
  const confirmed = await pluginStore.confirm('确认清空', '确定要清空命令历史吗？')
  if (!confirmed) return

  history.value = []
  if (instanceId.value) {
    try {
      localStorage.removeItem(historyStorageKey())
    } catch (e) {
      console.warn('Failed to clear RCON history from localStorage:', e)
    }
  }
  pluginStore.notifySuccess('清空成功', '命令历史已清空')
}

// 键盘事件处理
function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (historyIndex.value < history.value.length - 1) {
      historyIndex.value++
      command.value = history.value[history.value.length - 1 - historyIndex.value].command
    }
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (historyIndex.value > 0) {
      historyIndex.value--
      command.value = history.value[history.value.length - 1 - historyIndex.value].command
    } else if (historyIndex.value === 0) {
      historyIndex.value = -1
      command.value = ''
    }
  }
}

onMounted(() => {
  loadHistory()
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<style lang="scss" scoped>
.rcon-page {
  height: 100%;
  overflow-y: auto;
}

.console-card {
  :deep(.el-card__body) {
    border-radius: 6px;
  }

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .console-output {
    height: 500px;
    overflow-y: auto;
    background-color: var(--platform-surface-0);
    border: 1px solid var(--platform-line);
    border-radius: 6px;
    padding: 16px;
    margin-bottom: 16px;
    font-family: 'Consolas', 'Monaco', monospace;

    .console-item {
      margin-bottom: 16px;

      .command-line {
        color: var(--platform-cyan);
        margin-bottom: 8px;

        .prompt {
          margin-right: 8px;
          color: var(--platform-cyan);
        }

        .command {
          font-weight: 500;
        }
      }

      .output-line {
        color: var(--platform-text-regular);
        padding-left: 16px;

        pre {
          margin: 0;
          white-space: pre-wrap;
          word-wrap: break-word;
        }
      }
    }
  }

  .console-input {
    display: flex;
    gap: 12px;

    .el-input {
      flex: 1;

      .prompt {
        color: var(--platform-cyan);
        font-weight: 500;
      }
    }
  }
}

.commands-card {
  .command-list {
    .command-item {
      padding: 12px;
      border-radius: 6px;
      cursor: pointer;
      transition: background-color 0.2s;

      &:hover {
        background-color: var(--platform-bg-hover);
      }

      .command-name {
        font-family: 'Consolas', 'Monaco', monospace;
        color: var(--platform-cyan);
        font-weight: 500;
        margin-bottom: 4px;
      }

      .command-desc {
        font-size: 12px;
        color: var(--platform-text-secondary);
      }
    }
  }
}

.tips-card {
  .tips-list {
    margin: 0;
    padding-left: 20px;

    li {
      margin-bottom: 8px;
      color: var(--platform-text-secondary);
      font-size: 13px;
    }
  }
}
</style>
