<script setup lang="ts">
import { computed } from 'vue'
import { type DictItem, dictOf } from '@/constants/dict'

/**
 * 业务状态标签：字典驱动，页面不写死颜色和中文。
 * 传 value 就够了，字典由父组件指定（同一个 "PASSED" 在采购和验收里含义不同）。
 */
const props = withDefaults(
  defineProps<{
    value?: string | null
    dict: Record<string, DictItem>
    size?: 'small' | 'default' | 'large'
    effect?: 'dark' | 'light' | 'plain'
  }>(),
  { size: 'small', effect: 'light' },
)

const item = computed(() => dictOf(props.dict, props.value))
</script>

<template>
  <el-tooltip :content="item.hint" :disabled="!item.hint" placement="top" :show-after="400">
    <el-tag :type="item.type" :size="size" :effect="effect" disable-transitions>{{ item.label }}</el-tag>
  </el-tooltip>
</template>
