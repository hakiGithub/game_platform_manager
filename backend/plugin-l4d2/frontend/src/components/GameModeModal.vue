<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    title="选择游戏模式"
    width="600px"
  >
    <div class="game-mode-selector">
      <div
        v-for="(mode, key) in GAME_MODES"
        :key="key"
        class="mode-item"
        :class="{ selected: selectedMode === mode.value }"
        @click="selectMode(mode.value)"
      >
        <div class="mode-icon">
          <el-icon :size="32">
            <component :is="getModeIcon(mode.value)" />
          </el-icon>
        </div>
        <div class="mode-info">
          <div class="mode-name">{{ mode.label }}</div>
          <div class="mode-desc">{{ mode.description }}</div>
        </div>
        <el-icon v-if="selectedMode === mode.value" class="check-icon">
          <Check />
        </el-icon>
      </div>
    </div>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        @click="confirmSelection"
        :disabled="!selectedMode"
      >
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { GAME_MODES } from '@/utils/gameConstants'

const props = defineProps<{
  modelValue: boolean
  currentMode?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'select': [mode: string]
}>()

const selectedMode = ref<string>('')

function selectMode(mode: string) {
  selectedMode.value = mode
}

function confirmSelection() {
  if (selectedMode.value) {
    emit('select', selectedMode.value)
    emit('update:modelValue', false)
  }
}

function getModeIcon(mode: string): string {
  const iconMap: Record<string, string> = {
    coop: 'User',
    versus: 'Sword',
    survival: 'Timer',
    scavenge: 'Box',
    realism: 'View'
  }
  return iconMap[mode] || 'Document'
}

// 初始化选择
watch(() => props.modelValue, (val) => {
  if (val) {
    selectedMode.value = props.currentMode || 'coop'
  }
})
</script>

<style lang="scss" scoped>
.game-mode-selector {
  .mode-item {
    display: flex;
    align-items: center;
    padding: 16px;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;
    margin-bottom: 12px;
    border: 2px solid transparent;
    
    &:hover {
      background-color: var(--el-fill-color-light);
    }
    
    &.selected {
      background-color: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary);
    }
    
    .mode-icon {
      width: 56px;
      height: 56px;
      border-radius: 12px;
      background-color: var(--el-fill-color);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--el-color-primary);
      margin-right: 16px;
    }
    
    .mode-info {
      flex: 1;
      
      .mode-name {
        font-size: 16px;
        font-weight: 600;
        margin-bottom: 4px;
      }
      
      .mode-desc {
        font-size: 13px;
        color: var(--el-text-color-secondary);
      }
    }
    
    .check-icon {
      color: var(--el-color-primary);
      font-size: 24px;
    }
  }
}
</style>
