<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MENU_GROUPS } from './menu'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(localStorage.getItem('meat_trace_menu_collapsed') === '1')

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('meat_trace_menu_collapsed', collapsed.value ? '1' : '0')
}

const activePath = computed(() => route.path)

const breadcrumb = computed(() => {
  for (const group of MENU_GROUPS) {
    const hit = group.items.find((i) => i.path === route.path)
    if (hit) return [group.title, hit.title]
  }
  return ['', (route.meta.title as string) || '']
})

function openPublicTrace() {
  window.open('/trace', '_blank', 'noopener')
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录，确认退出吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  auth.logout()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="shell">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <div class="logo">
          <el-icon :size="20"><Connection /></el-icon>
        </div>
        <div v-show="!collapsed" class="brand-text">
          <div class="name">食堂肉类溯源</div>
          <div class="sub">FISCO BCOS 联盟链</div>
        </div>
      </div>

      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="activePath"
          :collapse="collapsed"
          :collapse-transition="false"
          router
          background-color="transparent"
          text-color="rgba(255,255,255,.72)"
          active-text-color="#ffffff"
          class="side-menu"
        >
          <template v-for="group in MENU_GROUPS" :key="group.title">
            <div v-if="!collapsed" class="group-title">{{ group.title }}</div>
            <div v-else class="group-divider" />
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>

      <div class="side-foot">
        <el-menu
          :collapse="collapsed"
          :collapse-transition="false"
          background-color="transparent"
          text-color="rgba(255,255,255,.72)"
          active-text-color="#fff"
          class="side-menu"
        >
          <el-menu-item index="trace" @click="openPublicTrace">
            <el-icon><View /></el-icon>
            <template #title>公开溯源页</template>
          </el-menu-item>
        </el-menu>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <el-button text class="collapse-btn" @click="toggleCollapse">
          <el-icon :size="18">
            <component :is="collapsed ? 'Expand' : 'Fold'" />
          </el-icon>
        </el-button>

        <el-breadcrumb separator="/" class="crumb">
          <el-breadcrumb-item v-if="breadcrumb[0]">{{ breadcrumb[0] }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ breadcrumb[1] }}</el-breadcrumb-item>
        </el-breadcrumb>

        <div class="top-right">
          <el-tooltip content="对外免登录溯源页，二维码扫出来就是它" placement="bottom">
            <el-button text @click="openPublicTrace">
              <el-icon><View /></el-icon>
              <span class="btn-text">公开溯源</span>
            </el-button>
          </el-tooltip>

          <el-divider direction="vertical" />

          <el-dropdown trigger="click" @command="(cmd: string) => cmd === 'logout' && handleLogout()">
            <span class="user-chip">
              <el-avatar :size="26" class="avatar">{{ auth.displayName.slice(0, 1) }}</el-avatar>
              <span class="user-meta">
                <span class="uname">{{ auth.displayName }}</span>
                <span class="urole">{{ auth.roleLabel }}</span>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  账号：{{ auth.user?.username }}
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ---------------- 侧边栏 ---------------- */
.sidebar {
  display: flex;
  flex-direction: column;
  width: 216px;
  flex-shrink: 0;
  background: linear-gradient(180deg, var(--app-sidebar-bg) 0%, var(--app-sidebar-bg-2) 100%);
  transition: width 0.18s ease;
}

.sidebar.collapsed {
  width: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 58px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 8px;
  color: #d7f5e8;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.14);
}

.brand-text .name {
  font-size: 15px;
  font-weight: 600;
  color: #f2fbf7;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.brand-text .sub {
  margin-top: 2px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
  white-space: nowrap;
}

.menu-scroll {
  flex: 1;
  padding: 6px 0;
}

.group-title {
  padding: 14px 20px 6px;
  font-size: 11px;
  letter-spacing: 1.5px;
  color: rgba(255, 255, 255, 0.34);
}

.group-divider {
  height: 1px;
  margin: 8px 14px;
  background: rgba(255, 255, 255, 0.1);
}

.side-menu {
  border-right: none;
}

.side-menu :deep(.el-menu-item) {
  height: 42px;
  line-height: 42px;
  margin: 2px 8px;
  border-radius: 7px;
  font-size: 13.5px;
}

.side-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.07) !important;
}

.side-menu :deep(.el-menu-item.is-active) {
  background: rgba(255, 255, 255, 0.14) !important;
  font-weight: 600;
}

.side-foot {
  padding: 6px 0 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

/* ---------------- 主区 ---------------- */
.main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 58px;
  padding: 0 18px 0 10px;
  flex-shrink: 0;
  background: #fff;
  border-bottom: 1px solid var(--app-border);
}

.collapse-btn {
  color: #6b7785;
}

.crumb {
  font-size: 13px;
}

.top-right {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.btn-text {
  margin-left: 4px;
  font-size: 13px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 8px;
  cursor: pointer;
  outline: none;
}

.user-chip:hover {
  background: #f4f6f8;
}

.avatar {
  background: var(--el-color-primary);
  color: #fff;
  font-size: 13px;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.uname {
  font-size: 13px;
  color: var(--app-text-main);
}

.urole {
  font-size: 11px;
  color: var(--app-text-sub);
}

.content {
  flex: 1;
  overflow-y: auto;
  background: var(--app-content-bg);
}

/* 路由切换动画：轻微位移，不喧哗 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.fade-slide-leave-to {
  opacity: 0;
}
</style>
