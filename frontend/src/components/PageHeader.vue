<script setup lang="ts">
/**
 * 页面标题区：标题 + 一句话说明 + 右侧操作位。
 * 说明文字不是装饰——它承担"这个页面在业务里干什么"的解释职责，
 * 用户随便点进一个页面，第一眼就能看懂。
 */
defineProps<{
  title: string
  desc?: string
}>()
</script>

<template>
  <div class="page-head">
    <div>
      <h2>{{ title }}</h2>
      <p v-if="desc" class="desc">{{ desc }}</p>
    </div>
    <div class="actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 10px;
}
</style>
