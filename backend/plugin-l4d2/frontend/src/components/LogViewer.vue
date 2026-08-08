<template>
  <div class="log-viewer">
    <div class="log-content" ref="contentRef">
      <div v-for="(line, idx) in filteredLogs" :key="idx" class="log-line" :class="{ 'error-line': isErrorLine(line) }">{{ line }}</div>
      <div v-if="!filteredLogs.length" class="empty">暂无日志</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'

const props = defineProps<{
  logs: string[]
  connected?: boolean
  paused?: boolean
  searchText?: string
}>()

const contentRef = ref<HTMLElement>()

const filteredLogs = computed(() => {
  if (!props.searchText) return props.logs
  return props.logs.filter(l => l.includes(props.searchText!))
})

function isErrorLine(line: string): boolean {
  return /error|exception|failed|fatal/i.test(line)
}

watch(() => props.logs.length, () => {
  nextTick(() => {
    if (contentRef.value) contentRef.value.scrollTop = contentRef.value.scrollHeight
  })
})
</script>

<style scoped>
.log-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.log-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px 12px;
  font-family: 'Cascadia Mono', 'Consolas', monospace;
  font-size: 12px;
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 4px;
}
.log-line {
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.6;
}
.error-line {
  color: #f56c6c;
}
.empty {
  color: #888;
  text-align: center;
  padding: 20px;
}
</style>
