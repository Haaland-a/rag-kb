import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AdminLayout from '@/layouts/AdminLayout.vue'

/**
 * 路由表。
 *
 * <p>分两层：</p>
 * <ul>
 *   <li><b>游客可见</b>：{@code /} 个人主页（含右下角问答浮窗）、{@code /login}</li>
 *   <li><b>站主可见</b>：{@code /admin/**}，统一套 AdminLayout</li>
 * </ul>
 *
 * <p>⚠️ meta.requiresAuth 只是<b>前端体验</b>层面的拦截 —— 真正的权限检查
 * 在服务端 AuthInterceptor 里（/api/documents、/api/visit 需要 token）。
 * 任何人改 localStorage 都能过前端守卫，但过不了后端。</p>
 *
 * <p>⚠️ 新增路由记得同步 {@code WebConfig.addViewControllers} 的 history 回退，
 * 否则浏览器刷新就 404。</p>
 */
const routes = [
  // ---- 游客可见：个人主页，问答是右下角浮窗 ----
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '首页' }
  },

  // ---- 游客可见：日常动态全页（主页只放最近几条） ----
  {
    path: '/moments',
    name: 'moments',
    component: () => import('@/views/MomentsView.vue'),
    meta: { title: '日常' }
  },

  // ---- 游客可见：单条动态详情（主页/列表页点某条动态进来） ----
  // ⚠️ 路径带数字约束：/moments/abc 不该进详情页，应该落到兜底 404→首页。
  //    同步记得 WebConfig.addViewControllers 里也加了 /moments/{id:[0-9]+}
  {
    path: '/moments/:id(\\d+)',
    name: 'moment-detail',
    component: () => import('@/views/MomentDetailView.vue'),
    meta: { title: '日常' }
  },

  // ---- 游客可见：留言板（不用注册也能留言） ----
  {
    path: '/board',
    name: 'board',
    component: () => import('@/views/BoardView.vue'),
    meta: { title: '留言板' }
  },

  // ---- 注册 ----
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册', guestOnly: true }
  },

  // ---- 登录 ----
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', guestOnly: true }
  },

  // ---- 站主后台：套同一层布局 ----
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAuth: true },
    children: [
      // 登录后的落地页 —— 概览 + 上传入口
      { path: '', redirect: '/admin/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '后台概览', requiresAuth: true }
      },
      {
        path: 'documents',
        name: 'documents',
        component: () => import('@/views/DocumentsView.vue'),
        meta: { title: '文档管理', requiresAuth: true }
      },
      {
        path: 'documents/:id',
        name: 'document-detail',
        component: () => import('@/views/DocumentDetailView.vue'),
        meta: { title: '文档详情', requiresAuth: true }
      },
      {
        path: 'media',
        name: 'media',
        component: () => import('@/views/MediaView.vue'),
        meta: { title: '我的内容', requiresAuth: true }
      },
      {
        path: 'profile',
        name: 'admin-profile',
        component: () => import('@/views/ProfileEditView.vue'),
        meta: { title: '关于我', requiresAuth: true }
      },
      {
        path: 'moments',
        name: 'admin-moments',
        component: () => import('@/views/MomentsAdminView.vue'),
        meta: { title: '日常动态', requiresAuth: true }
      },
      {
        path: 'comments',
        name: 'admin-comments',
        component: () => import('@/views/CommentsView.vue'),
        meta: { title: '访客留言', requiresAuth: true }
      },
      {
        path: 'chats',
        name: 'admin-chats',
        component: () => import('@/views/ChatLogsView.vue'),
        meta: { title: '问答记录', requiresAuth: true }
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/UsersView.vue'),
        meta: { title: '注册用户', requiresAuth: true }
      },
      {
        path: 'visits',
        name: 'visits',
        component: () => import('@/views/VisitsView.vue'),
        meta: { title: '访问记录', requiresAuth: true }
      }
    ]
  },

  // 兜底：不存在的路径回主页，别让人看到一片空白
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 全局前置守卫。
 *
 * <p>关键点：必须<b>先 resolve 一次登录状态</b>再判断。
 * 直接用 store.isLoggedIn（初始 false）会导致刷新页面时「闪一下登录页再跳回来」——
 * 因为 /api/auth/me 还没返回。</p>
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 只在第一次导航时问服务端「我是谁」，之后走内存里的 resolved 标记
  if (!auth.resolved) {
    await auth.resolve()
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  /**
   * ⚠️ 后台是站主专属，普通注册用户进来要挡住。
   *
   * <p>这类用户是「已登录但非站主」—— 不能跳登录页（他已经登录了，
   * 跳过去会被 guestOnly 再弹回后台，死循环）。所以直接回主页，
   * 并在 query 里带一个标记让主页提示一句。</p>
   *
   * <p>注意这仍然只是前端体验层：真正的拦截在服务端，非站主调
   * /api/comments 等端点一律 403。</p>
   */
  if (to.meta.requiresAuth && !auth.isAdmin) {
    return { name: 'home', query: { deny: 'admin' } }
  }

  // 已登录还去登录页 → 站主进后台概览，普通用户回主页
  if (to.meta.guestOnly && auth.isLoggedIn) {
    return auth.isAdmin ? { name: 'dashboard' } : { name: 'home' }
  }

  return true
})

router.afterEach((to) => {
  const base = 'Demo'
  document.title = to.meta.title ? `${to.meta.title} · ${base}` : base
})

export default router
