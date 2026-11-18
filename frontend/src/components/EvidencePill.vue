<script setup lang="ts">
import { computed } from 'vue'
import { EVIDENCE_STATUS } from '@/constants/dict'
import type { EvidenceInfo } from '@/types/api'

/**
 * 存证进度胶囊。
 *
 * 关键认知：业务落库与上链是**异步**两件事（TECH-01 §异步存证）。
 * 接口返回 evidence.status = PENDING 是正常状态，页面必须能表达"已入库、正在上链"，
 * 而不是把它当失败弹红字。这条在答辩里是个加分点。
 */
const props = defineProps<{ evidence?: EvidenceInfo | null }>()

const status = computed(() => {
  const s = props.evidence?.status
  if (!s) return 'NONE'
  return s.toUpperCase()
})

const item = computed(() => {
  if (status.value === 'NONE') return { label: '无存证', type: 'info' as const }
  return EVIDENCE_STATUS[status.value] ?? { label: status.value, type: 'info' as const }
})

const tip = computed(() => {
  const id = props.evidence?.eventId
  if (!id) return '该操作不产生链上事件（内部状态流转）'
  return status.value === 'CONFIRMED'
    ? `已上链，事件 ID：${id}`
    : `事件 ID：${id}\n存证任务异步执行，稍后可在溯源页核验`
})
</script>

<template>
  <el-tooltip :content="tip" placement="top" :show-after="300">
    <el-tag :type="item.type" size="small" effect="plain" disable-transitions>{{ item.label }}</el-tag>
  </el-tooltip>
</template>
