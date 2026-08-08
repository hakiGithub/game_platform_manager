<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    title="选择难度"
    width="500px"
  >
    <div class="difficulty-selector">
      <div
        v-for="(diff, key) in DIFFICULTIES"
        :key="key"
        class="difficulty-item"
        :class="{ selected: selectedDifficulty === diff.value }"
        @click="selectDifficulty(diff.value)"
      >
        <div class="difficulty-icon" :style="{ backgroundColor: diff.color }">
          <el-icon :size="32"><Flag /></el-icon>
        </div>
        <div class="difficulty-info">
          <div class="difficulty-name">{{ diff.label }}</div>
          <div class="difficulty-desc">{{ getDifficultyDesc(diff.value) }}</div>
        </div>
        <el-icon v-if="selectedDifficulty === diff.value" class="check-icon">
          <Check />
        </el-icon>
      </div>
    </div>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        @click="confirmSelection"
        :disabled="!selectedDifficulty"
      >
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { DIFFICULTIES } from '@/utils/gameConstants'

const props = defineProps<{
  modelValue: boolean
  currentDifficulty?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'select': [difficulty: string]
}>()

const selectedDifficulty = ref<string>('')

function selectDifficulty(difficulty: string) {
  selectedDifficulty.value = difficulty
}

function confirmSelection() {
  if (selectedDifficulty.value) {
    emit('select', selectedDifficulty.value)
    emit('update:modelValue', false)
  }
}

function getDifficultyDesc(difficulty: string): string {
  const descs: Record<string, string> = {
    easy: '适合新手玩家，感染者伤害较低',
    normal: '标准难度，平衡的游戏体验',
    hard: '进阶难度，感染者伤害适中',
    impossible: '专家难度，感染者伤害极高'
  }
  return descs[difficulty] || ''
}

// 初始化选择
watch(() => props.modelValue, (val) => {
  if (val) {
    selectedDifficulty.value = props.currentDifficulty || 'normal'
  }
})
</script>

<style lang="scss" scoped>
.difficulty-selector {
  .difficulty-item {
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
    
    .difficulty-icon {
      width: 56px;
      height: 56px;
      border-radius: 12px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: white;
      margin-right: 16px;
    }
    
    .difficulty-info {
      flex: 1;
      
      .difficulty-name {
        font-size: 16px;
        font-weight: 600;
        margin-bottom: 4px;
      }
      
      .difficulty-desc {
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
