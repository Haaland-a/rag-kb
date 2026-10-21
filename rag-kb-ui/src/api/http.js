import axios from 'axios'

const TOKEN_KEY = 'rag-kb-token'

/** 读本地 token。登录成功时写入，退出时清掉 */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// 请求带上 token。没有 token 时不加这个头 —— 游客的请求要尽量干净
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 响应拦截器：统一拆掉后端的 R 包装。
 *
 * <p>后端所有 JSON 接口都返回 {@code {code, message, data}}。这里一次性拆掉，
 * 业务层拿到的直接就是 data，不用每个调用点都写 {@code res.data.data}。</p>
 *
 * <p>⚠️ 之所以在拦截器里统一拆而不是各个 api 方法里拆：</p>
 * <ul>
 *   <li>拆漏一个地方就是一个「Cannot read properties of undefined」——
 *       之前 /api/auth/login 就因为这个白屏过</li>
 *   <li>SSE 那条路走原生 fetch（axios 拿不到流），本来就绕过了这里，
 *       所以不会受影响</li>
 * </ul>
 *
 * @returns 直接是 R.data 的内容
 */
http.interceptors.response.use(
  (res) => {
    const body = res.data
    // 非 R 结构（比如某个接口直出了数组）就原样返回，不强行拆
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return body
    }
    if (body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body.data
  },
  (err) => {
    // 401 说明 token 失效了，清掉本地的免得每次都带着一个废 token 发请求。
    // ⚠️ 不在这里做路由跳转：拦截器里拿不到 router，而且 /api/auth/me 是
    // 匿名可调的（返回 null 而不是 401），不应该触发跳转。跳转由页面自己处理。
    if (err?.response?.status === 401) {
      setToken('')
    }
    const msg = err?.response?.data?.message || err.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export default http
