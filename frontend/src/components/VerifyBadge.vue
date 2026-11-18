<script setup lang="ts">
import { computed } from 'vue'
import { VERIFY_STATUS } from '@/constants/dict'
import type { VerifyStatus } from '@/types/api'

/**
 * 四类核验状态徽标（BR-12）。
 *
 * 刻意不用 el-tag 的 type：四类状态的语义比「成功/警告/危险」更细，
 * 需要一个稳定的颜色映射表，改成 el-tag 会让 UNREACHABLE 和 MISMATCHED 都变红，
 * 而这两件事的严重程度完全不同——前者是"查不到"，后者是"对不上"。
 */
const props = withDefaults(
  defineProps<{
    status?: VerifyStatus | string | null
    /** 是否显示悬停解释 */
    tip?: boolean
    /** 紧凑模式：只显示圆点 + 短标签，用于密集表格 */
    compact?: boolean
  }>(),
  { tip: true, compact: false },
)

const key = computed(() => (props.status || 'UNCONFIRMED').toUpperCase())

const item = computed(() => VERIFY_STATUS[key.value] ?? { label: key.value, type: 'info', hint: '' })
</script>

<template>
  <el-tooltip :content="item.hint" :disabled="!tip || !item.hint" placement="top" :show-after="300">
    <span class="vc-badge" :class="`vc-${key}`">
      <span class="dot" />
      <span v-if="!compact">{{ item.label }}</span>
    </span>
  </el-tooltip>
</template>
