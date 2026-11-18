import { http, tokenStorage, userStorage } from './http'
import type { LoginResponse, UserVO } from '@/types/api'

export const authApi = {
  async login(username: string, password: string): Promise<LoginResponse> {
    const data = await http.post<LoginResponse>('/auth/login', { username, password })
    tokenStorage.set(data.token)
    userStorage.set(data.user)
    return data
  },

  me: () => http.get<UserVO>('/auth/me'),

  logout() {
    tokenStorage.clear()
    userStorage.clear()
  },
}
