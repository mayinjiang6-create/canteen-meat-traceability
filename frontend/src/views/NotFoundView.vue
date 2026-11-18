<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="nf">
    <div class="code mono">404</div>
    <p>这个地址没有对应的页面。可能是批次号输错了，或者链接已失效。</p>
    <div class="btns">
      <el-button @click="router.back()">返回上一页</el-button>
      <el-button type="primary" @click="router.push('/trace')">去公开溯源查询</el-button>
    </div>
  </div>
</template>

<style scoped>
.nf {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100vh;
  gap: 6px;
  background: #f4f6f8;
}

.code {
  font-size: 72px;
  font-weight: 700;
  color: var(--el-color-primary-light-7);
  letter-spacing: 4px;
}

p {
  margin: 0 0 18px;
  font-size: 14px;
  color: var(--app-text-sub);
}
</style>
