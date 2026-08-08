import { ref, computed, onUnmounted } from 'vue'
import { logsApi } from '@/api'
import { usePluginStore } from '@/stores/plugin'

const MAX_LOG_LINES = 2000

export function useLogStream() {
  const store = usePluginStore()
  const instanceId = computed(() => store.instanceInfo?.instanceId)

  const logs = ref<string[]>([])
  const connected = ref(false)
  const paused = ref(false)
  const currentFile = ref<string>('')

  let eventSource: EventSource | null = null
  let reconnectTimer: number | null = null

  function buildUrl(file: string): string {
    const base = logsApi.streamUrl(instanceId.value!, file)
    // Wujie 模式下需要拼宿主 origin
    if (typeof window !== 'undefined' && (window as any).__POWERED_BY_WUJIE__) {
      const origin = (window as any).$wujie?.location?.origin
      if (origin) return origin + base
    }
    return base
  }

  function pushLine(line: string) {
    logs.value.push(line)
    if (logs.value.length > MAX_LOG_LINES) {
      logs.value.splice(0, logs.value.length - MAX_LOG_LINES)
    }
  }

  function start(file: string) {
    if (paused.value) return
    if (!instanceId.value || !file) return
    stop()
    currentFile.value = file
    const url = buildUrl(file)
    eventSource = new EventSource(url, { withCredentials: true })
    eventSource.onopen = () => { connected.value = true }
    eventSource.onmessage = (e) => { pushLine(e.data) }
    eventSource.onerror = () => {
      connected.value = false
      eventSource?.close()
      eventSource = null
      if (!paused.value) {
        reconnectTimer = window.setTimeout(() => start(currentFile.value), 5000)
      }
    }
  }

  function stop() {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
    eventSource?.close()
    eventSource = null
    connected.value = false
  }

  function togglePause() {
    paused.value = !paused.value
    if (paused.value) {
      stop()
    } else if (currentFile.value) {
      start(currentFile.value)
    }
  }

  function clear() {
    logs.value = []
  }

  onUnmounted(stop)

  return { logs, connected, paused, currentFile, instanceId, start, stop, togglePause, clear }
}
