<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    title="选择地图"
    width="800px"
  >
    <div class="map-selector">
      <!-- 搜索栏 -->
      <el-input
        v-model="searchKeyword"
        placeholder="搜索地图..."
        clearable
        style="margin-bottom: 16px"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <!-- 地图列表 -->
      <div class="map-list">
        <div
          v-for="map in filteredMaps"
          :key="map.name"
          class="map-item"
          :class="{ selected: selectedMap?.name === map.name }"
          @click="selectMap(map)"
        >
          <div class="map-icon">
            <el-icon :size="32">
              <component :is="getMapTypeIcon(map.type)" />
            </el-icon>
          </div>
          <div class="map-info">
            <div class="map-name">{{ map.displayName }}</div>
            <div class="map-meta">
              <el-tag size="small" :type="getMapTypeTag(map.type)">
                {{ MAP_TYPES[map.type as keyof typeof MAP_TYPES]?.label || map.type }}
              </el-tag>
              <span class="map-size">{{ formatFileSize(map.size) }}</span>
            </div>
          </div>
          <el-icon v-if="selectedMap?.name === map.name" class="check-icon">
            <Check />
          </el-icon>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        @click="confirmSelection"
        :disabled="!selectedMap"
      >
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { MAP_TYPES } from '@/utils/gameConstants'
import type { MapInfo } from '@/types'

const props = defineProps<{
  modelValue: boolean
  maps: MapInfo[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'select': [map: MapInfo]
}>()

const searchKeyword = ref('')
const selectedMap = ref<MapInfo | null>(null)

const filteredMaps = computed(() => {
  if (!searchKeyword.value) return props.maps
  
  const keyword = searchKeyword.value.toLowerCase()
  return props.maps.filter(map =>
    map.name.toLowerCase().includes(keyword) ||
    map.displayName.toLowerCase().includes(keyword)
  )
})

function selectMap(map: MapInfo) {
  selectedMap.value = map
}

function confirmSelection() {
  if (selectedMap.value) {
    emit('select', selectedMap.value)
    emit('update:modelValue', false)
  }
}

function getMapTypeIcon(type: string) {
  return MAP_TYPES[type as keyof typeof MAP_TYPES]?.icon || 'Document'
}

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

function getMapTypeTag(type: string): TagType | undefined {
  const typeMap: Record<string, TagType> = {
    campaign: 'primary',
    versus: 'success',
    survival: 'warning',
    scavenge: 'info'
  }
  return typeMap[type]
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

// 重置选择
watch(() => props.modelValue, (val) => {
  if (!val) {
    selectedMap.value = null
    searchKeyword.value = ''
  }
})
</script>

<style lang="scss" scoped>
.map-selector {
  .map-list {
    max-height: 500px;
    overflow-y: auto;
    
    .map-item {
      display: flex;
      align-items: center;
      padding: 12px;
      border-radius: 8px;
      cursor: pointer;
      transition: all 0.2s;
      margin-bottom: 8px;
      
      &:hover {
        background-color: var(--el-fill-color-light);
      }
      
      &.selected {
        background-color: var(--el-color-primary-light-9);
        border: 1px solid var(--el-color-primary);
      }
      
      .map-icon {
        width: 48px;
        height: 48px;
        border-radius: 8px;
        background-color: var(--el-fill-color);
        display: flex;
        align-items: center;
        justify-content: center;
        margin-right: 12px;
        color: var(--el-color-primary);
      }
      
      .map-info {
        flex: 1;
        
        .map-name {
          font-weight: 500;
          margin-bottom: 4px;
        }
        
        .map-meta {
          display: flex;
          align-items: center;
          gap: 8px;
          
          .map-size {
            font-size: 12px;
            color: var(--el-text-color-secondary);
          }
        }
      }
      
      .check-icon {
        color: var(--el-color-primary);
        font-size: 24px;
      }
    }
  }
}
</style>
