import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import { tokenStorage, userStorage } from '@/api/http'
import type { UserVO } from '@/types/api'

/**
 * 当前登录态。
 *
 * 只存三样东西：token、用户信息、是否已登录。
 * **不存权限判断结果**——权限永远由后端 `@RequireRole` 判定，前端只负责"藏按钮"，
 * 藏了不等于安全，真正的权限校验必须在服务端做。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(tokenStorage.get())
  const user = ref<UserVO | null>(userStorage.get<UserVO>())

  const isLoggedIn = computed(() => Boolean(token.value))
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const displayName = computed(() => user.value?.displayName || user.value?.username || '未登录')
  const roleLabel = computed(() => {
    switch (user.value?.role) {
      case 'ADMIN':
        return '管理员'
      case 'SUPPLIER':
        return '供应商'
      case 'VIEWER':
        return '只读'
      default:
        return '—'
    }
  })

  async function login(username: string, password: string) {
    const data = await authApi.login(username, password)
    token.value = data.token
    user.value = data.user
    return data
  }

  function logout() {
    authApi.logout()
    token.value = ''
    user.value = null
  }

  return { token, user, isLoggedIn, isAdmin, displayName, roleLabel, login, logout }
})
