import { computed, onUnmounted, ref } from 'vue'
import { logsApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'

const MAX_LOG_LINES = 2000
const POLL_INTERVAL_MS = 2000

/**
 * 日志查看器状态：初始读取和搜索走带鉴权的 HTTP tail 接口，新内容用短轮询增量读取。
 * 事件序号用于丢弃快速切换文件时迟到的旧响应。
 */
export function useLogStream() {
  const store = usePluginStore()
  const instanceId = computed(() => store.instanceInfo?.instanceId)

  const logs = ref<string[]>([])
  const connected = ref(false)
  const paused = ref(false)
  const currentFile = ref<string>('')
  const searchKeyword = ref('')

  let pollTimer: number | null = null
  let requestSeq = 0

  function setLines(text: string) {
    logs.value = text ? text.replace(/\r/g, '').split('\n').filter(line => line.length > 0) : []
    if (logs.value.length > MAX_LOG_LINES) {
      logs.value.splice(0, logs.value.length - MAX_LOG_LINES)
    }
  }

  async function loadCurrent(seq: number) {
    if (!instanceId.value || !currentFile.value) return
    try {
      const keyword = searchKeyword.value.trim()
      const text = keyword
        ? await logsApi.searchContent(instanceId.value, currentFile.value, keyword)
        : await logsApi.readContent(instanceId.value, currentFile.value)
      if (seq !== requestSeq || paused.value) return
      setLines(text)
      connected.value = true
    } catch {
      if (seq !== requestSeq) return
      connected.value = false
      if (!paused.value) {
        setLines('')
      }
    }
  }

  function schedulePoll(seq: number) {
    if (pollTimer !== null) clearTimeout(pollTimer)
    pollTimer = window.setTimeout(async () => {
      pollTimer = null
      if (paused.value || seq !== requestSeq || !currentFile.value) return
      await loadCurrent(seq)
      if (seq === requestSeq && !paused.value) schedulePoll(seq)
    }, POLL_INTERVAL_MS)
  }

  async function start(file: string) {
    if (!instanceId.value || !file) return
    stop()
    currentFile.value = file
    searchKeyword.value = ''
    const seq = ++requestSeq
    await loadCurrent(seq)
    if (seq === requestSeq && !paused.value) schedulePoll(seq)
  }

  function stop() {
    requestSeq++
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
    connected.value = false
  }

  function togglePause() {
    paused.value = !paused.value
    if (paused.value) {
      stop()
      connected.value = false
    } else if (currentFile.value) {
      const seq = ++requestSeq
      void loadCurrent(seq).then(() => {
        if (seq === requestSeq && !paused.value) schedulePoll(seq)
      })
    }
  }

  async function search(keyword: string) {
    searchKeyword.value = keyword
    const seq = ++requestSeq
    await loadCurrent(seq)
    if (seq === requestSeq && !paused.value) schedulePoll(seq)
  }

  function clear() {
    logs.value = []
  }

  onUnmounted(stop)

  return {
    logs,
    connected,
    paused,
    currentFile,
    instanceId,
    searchKeyword,
    start,
    stop,
    togglePause,
    search,
    clear
  }
}
