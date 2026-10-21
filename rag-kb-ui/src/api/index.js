import http, { getToken } from './http'

/**
 * 接口封装。
 *
 * <p>⚠️ 这里所有方法拿到的已经是后端 R 里的 <b>data</b> —— http.js 的响应拦截器
 * 已经把 {@code {code, message, data}} 拆掉了。所以不要再写 {@code r.data}，
 * 那样会取到 undefined。</p>
 */

export const documentApi = {
  list: () => http.get('/documents'),
  detail: (id) => http.get(`/documents/${id}`),
  chunks: (id, page = 1, size = 20) => http.get(`/documents/${id}/chunks`, { params: { page, size } }),
  upload: (formData, onProgress) =>
    http.post('/documents', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => onProgress && onProgress(Math.round((e.loaded * 100) / e.total))
    }),
  remove: (id) => http.delete(`/documents/${id}`),
  reindex: (id) => http.post(`/documents/${id}/reindex`)
}

export const authApi = {
  /** 登录状态查询：未登录时 data 为 null，不报错 */
  me: () => http.get('/auth/me'),
  login: (username, password) => http.post('/auth/login', { username, password }),
  /**
   * 注册。
   *
   * ⚠️ deviceId 由 device.js 算好传进来，后端用它挡「同一设备注册多个账号」。
   * 不传也能注册，那就只剩同 IP 的软限制。
   */
  register: (body) => http.post('/auth/register', body),
  logout: () => http.post('/auth/logout'),
  changePassword: (oldPassword, newPassword) =>
    http.post('/auth/password', { oldPassword, newPassword })
}

/**
 * 留言板 + 动态评论。
 *
 * ⚠️ 可见性是后端按「看的人是谁」算的，前端不做任何过滤 ——
 * 悄悄话、被屏蔽的留言都在 SQL 层就排除了，前端拿到的就是能看的。
 *
 * ⚠️ 游客也能调 public 系列。带 token 时后端会额外返回
 * 「你自己写的被屏蔽的留言」（让你知道被屏蔽了）。
 */
export const commentApi = {
  // ---- 访客 ----
  board: (limit = 20, offset = 0) => http.get('/comments/public', { params: { limit, offset } }),
  momentComments: (momentId, limit = 30, offset = 0) =>
    http.get(`/comments/public/moments/${momentId}`, { params: { limit, offset } }),
  submitToBoard: (body) => http.post('/comments/public', body),
  submitToMoment: (momentId, body) => http.post(`/comments/public/moments/${momentId}`, body),

  // ---- 站主 ----
  adminList: (params = {}) => http.get('/comments', { params }),
  edit: (id, content) => http.put(`/comments/${id}`, { content }),
  remove: (id) => http.delete(`/comments/${id}`),
  setStatus: (id, status) => http.put(`/comments/${id}/status`, { status }),
  unread: () => http.get('/comments/unread'),
  markRead: (id) => http.post('/comments/read', null, { params: { id } })
}

export const siteApi = {
  /** 主页公开信息：站名、文档数、访客数、照片墙、项目、下载列表、当前 IP 剩余额度 */
  info: () => http.get('/site'),
  /** 当前 IP 的问答配额 */
  quota: () => http.get('/chat/quota'),
  /** 记一次下载（纯统计，失败无所谓） */
  hitDownload: (id) => http.post(`/media/public/downloads/${id}/hit`)
}

/**
 * 个人内容管理（照片墙 / 项目 / 简历）。
 *
 * ⚠️ 站主专属接口在 /api/media 下（不带 /public），由 AuthInterceptor 拦。
 * 公开接口在 /api/media/public/**，主页数据已经从 /api/site 一次拿全，
 * 这里的 public 系列只在「主页之外单独刷新某一块」时才用。
 */
export const mediaApi = {
  // ---- 照片墙 ----
  photos: () => http.get('/media/photos'),
  uploadPhoto: (formData, onProgress) =>
    http.post('/media/photos', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => onProgress && onProgress(Math.round((e.loaded * 100) / e.total))
    }),
  updatePhoto: (id, body) => http.put(`/media/photos/${id}`, body),
  removePhoto: (id) => http.delete(`/media/photos/${id}`),

  // ---- 项目 ----
  projects: () => http.get('/media/projects'),
  uploadCover: (formData, onProgress) =>
    http.post('/media/projects/cover', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => onProgress && onProgress(Math.round((e.loaded * 100) / e.total))
    }),
  createProject: (body) => http.post('/media/projects', body),
  updateProject: (id, body) => http.put(`/media/projects/${id}`, body),
  removeProject: (id) => http.delete(`/media/projects/${id}`),

  // ---- 简历 / 作品集 ----
  downloads: () => http.get('/media/downloads'),
  uploadDownload: (formData, onProgress) =>
    http.post('/media/downloads', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => onProgress && onProgress(Math.round((e.loaded * 100) / e.total))
    }),
  updateDownload: (id, body) => http.put(`/media/downloads/${id}`, body),
  removeDownload: (id) => http.delete(`/media/downloads/${id}`)
}

export const visitApi = {
  list: (limit = 50, offset = 0) => http.get('/visit', { params: { limit, offset } }),
  summary: (limit = 50) => http.get('/visit/summary', { params: { limit } }),
  stats: () => http.get('/visit/stats')
}

/**
 * 「关于我」站主资料。
 *
 * ⚠️ 读和写都在站主接口里（/api/site/profile 不在 ADMIN_PATHS，
 * 由 Controller 内部做 requireAdmin 检查）。游客读的是 /api/site
 * 返回的 owner 字段，不会调到这里。
 */
export const profileApi = {
  get: () => http.get('/site/profile'),
  save: (body) => http.put('/site/profile', body),
  reset: () => http.post('/site/profile/reset'),
  /**
   * 上传头像，返回 { url }。
   *
   * ⚠️ 上传不等于保存：拿到 url 之后还要调 save() 才会写进数据库。
   * 这样站主可以连传几张挑一张，中间那些不会短暂出现在主页上。
   *
   * ⚠️ 手动设 multipart 头：axios 虽然能自己识别 FormData，
   * 但拦截器里动过 headers 之后会退化成 JSON 序列化，文件变成空。
   */
  uploadAvatar: (file) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/site/profile/avatar', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

/**
 * 日常动态 + 访客留言。
 *
 * ⚠️ 分两组：
 * - public 系列匿名可访问（/api/moments/public/**），主页和 /moments 页用
 * - 其余是站主接口，未登录会被 Controller 抛 401
 *
 * ⚠️ 留言是「私密的」：访客只能提交，永远拿不到列表。
 * 那个 comments 计数在访客接口里恒为 0，别指望从别处推出来。
 */
export const momentApi = {
  // ---- 访客 ----
  publicList: () => http.get('/moments/public'),

  /**
   * 单条动态 + 它下面访客可见的全部留言。
   *
   * <p>和 `commentApi.momentComments` 的区别：这个同时返回动态本体，
   * 详情页一次请求就能渲染完整页面，不用先查列表再筛出一条。</p>
   */
  publicDetail: (id) => http.get(`/moments/public/${id}`),

  // ⚠️ 这里原有的 submitComment 也删了：动态留言统一走
  //   commentApi.submitToMoment。旧端点 /moments/public/{id}/comments
  //   用的是 SiteContentRequests.CommentSubmit，没有 visibility 字段，
  //   注册用户从那条路发「悄悄话」会被静默存成公开。


  // ---- 站主：动态 ----
  list: (limit = 20, offset = 0) => http.get('/moments', { params: { limit, offset } }),
  upload: (formData, onProgress) =>
    http.post('/moments/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => onProgress && onProgress(Math.round((e.loaded * 100) / e.total))
    }),
  create: (body) => http.post('/moments', body),
  update: (id, body) => http.put(`/moments/${id}`, body),
  syncToPhotos: (id, photoCaption) =>
    http.post(`/moments/${id}/sync-to-photos`, { photoCaption }),
  remove: (id) => http.delete(`/moments/${id}`)

  // ⚠️ 这里原有的 comments / commentsOf / markRead / unread 四个留言方法已删除。
  //   留言只有一套实现在 commentApi 里；这两个对象并存时，前端一半页面走
  //   /api/moments/comments（旧 DTO，没有 visibility/hidden），另一半走
  //   /api/comments（新 DTO），正是「站主看不到悄悄话」那个 bug 的成因。
  //   后端旧路径还留着（@Deprecated 转调），但前端不许再从这里进。
}

export const chatApi = {
  // ---- 站主：问答记录 ----
  /**
   * 问答记录列表。
   *
   * ⚠️ onlyVisitor 默认 true：站主自己调试时问的那些不该混进
   * 「访客都问了什么」的视图里。想看全部就传 false。
   */
  logs: (limit = 20, offset = 0, onlyVisitor = true) =>
    http.get('/chat/logs', { params: { limit, offset, onlyVisitor } }),
  logDetail: (id) => http.get(`/chat/logs/${id}`),
  removeLog: (id) => http.delete(`/chat/logs/${id}`),
  clearLogs: () => http.post('/chat/logs/clear'),

  /**
   * SSE 流式问答，返回取消函数。
   *
   * ⚠️ 用原生 fetch 不用 axios：axios 在浏览器里走 XHR，拿不到流式 body。
   * 手写 SSE 解析器只有 20 行，比引 eventsource-parser 划算。
   *
   * ⚠️ 必须手动带 Authorization 头：这里绕过了 axios 拦截器。
   * 游客没有 token 就不带，服务端按匿名处理。
   */
  stream: ({ question, documentIds, topK }, handlers) => {
    const ctrl = new AbortController()
    const headers = { 'Content-Type': 'application/json' }
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`

    fetch('/api/chat', {
      method: 'POST',
      headers,
      body: JSON.stringify({ question, documentIds, topK }),
      signal: ctrl.signal
    })
      .then(async (res) => {
        if (!res.ok) {
          // 429 是限流，后端把原因放在 message 里，前端直接展示
          let detail = ''
          try {
            const body = await res.json()
            detail = body?.message || ''
          } catch {
            /* 响应不是 JSON，忽略 */
          }
          throw new Error(detail || `HTTP ${res.status}`)
        }
        const reader = res.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          // SSE 以空行分帧
          const frames = buffer.split('\n\n')
          buffer = frames.pop() || ''
          for (const frame of frames) handleFrame(frame, handlers)
        }
      })
      .catch((e) => {
        if (e.name !== 'AbortError') handlers.onError?.(e.message)
      })
    return () => ctrl.abort()
  }
}

/** 解析一个 SSE 帧：event: xxx\ndata: {...} */
function handleFrame(frame, handlers) {
  let event = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return
  let data
  try {
    data = JSON.parse(dataLines.join('\n'))
  } catch {
    data = dataLines.join('\n')
  }
  if (event === 'sources') handlers.onSources?.(data)
  else if (event === 'token') handlers.onToken?.(typeof data === 'string' ? data : data?.text ?? '')
  else if (event === 'done') handlers.onDone?.(data)
  else if (event === 'error') handlers.onError?.(data.message)
}
