import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { getToken, setToken } from '@/api/http'

/**
 * 登录状态。
 *
 * <p>核心设计：<b>「有没有 token」和「token 还算不算数」是两件事</b>。
 * token 在 localStorage 里，但可能已过期（服务端重启会清空所有会话）。
 * 所以进页面时先看本地有没有 token，有就调 /api/auth/me 验一次 ——
 * 这个接口匿名可调，未登录返回 null 而不是 401，不会污染控制台。</p>
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    /** 是否已经问过服务端「我是谁」——避免路由守卫在验证完成前就放行/拦截 */
    resolved: false,
    loading: false
  }),

  getters: {
    /** 是否已登录（以服务端确认过为准，不只看本地 token） */
    isLoggedIn: (state) => !!state.user,
    displayName: (state) => state.user?.displayName || state.user?.username || '',
    /**
     * 是不是站主。
     *
     * <p>⚠️ 只用来决定「显示不显示后台入口」，<b>不是安全边界</b>。
     * 真正的拦截在服务端（AuthInterceptor 的路径白名单 + requireAdmin）。
     * 前端就算把这里改成 true，进后台页也只会看到一堆 401。</p>
     */
    isAdmin: (state) => state.user?.admin === true
  },

  actions: {
    /**
     * 向服务端确认登录状态。启动时调一次。
     *
     * @returns 是否已登录
     */
    async resolve() {
      if (this.resolved) return this.isLoggedIn
      this.loading = true
      try {
        // 本地压根没 token 就不用发请求了，省一次往返
        if (!getToken()) {
          this.user = null
          return false
        }
        this.user = await authApi.me()
        if (!this.user) {
          // 服务端说没这个人 —— 本地 token 是废的，清掉
          setToken('')
        }
        return !!this.user
      } catch {
        // 网络错误不当作「未登录」，只是本次没验成。下次导航会再试
        this.user = null
        return false
      } finally {
        this.resolved = true
        this.loading = false
      }
    },

    /**
     * 登录。
     *
     * <p>⚠️ 登录响应里已经带完整身份（token/userId/displayName/admin），
     * 不再多拉一次 /api/auth/me。少一次往返，也没有「有 token 但用户信息
     * 还没到」的中间态 —— 那个中间态会让路由守卫误判成普通用户。</p>
     */
    async login(username, password) {
      const res = await authApi.login(username, password)
      setToken(res.token)
      this.user = {
        id: res.userId,
        username: res.username,
        displayName: res.displayName,
        admin: res.admin === true
      }
      this.resolved = true
      return this.user
    },

    /**
     * 注册一个普通用户。
     *
     * <p>⚠️ 注册用户<b>不是站主</b>。注册成功会直接登录（后端顺手发了 token），
     * 但进不了后台 —— 后端所有 /admin 接口都会 401/403。</p>
     *
     * <p>设备指纹在这里传给后端：它是「同一设备不能注册多账号」的依据。
     * 传空也能注册成功，只是只剩 IP 那道软限制。</p>
     */
    async register({ username, password, displayName, deviceId }) {
      const res = await authApi.register({ username, password, displayName, deviceId })
      if (res?.token) {
        setToken(res.token)
        this.user = {
          id: res.userId,
          username: res.username,
          displayName: res.displayName,
          admin: res.admin === true
        }
        this.resolved = true
      }
      return this.user
    },

    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 登出失败也要清本地：不能因为服务端不通就让用户卡在登录态
      }
      setToken('')
      this.user = null
      this.resolved = true
    }
  }
})
