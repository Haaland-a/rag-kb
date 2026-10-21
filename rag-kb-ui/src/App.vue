<template>
  <div class="app-shell">
    <router-view />
  </div>
</template>

<script setup>
/**
 * 根组件。
 *
 * <p>⚠️ 这里<b>不放</b>导航菜单：主页是给访客看的营销式页面（深色头图 + 问答卡片），
 * 后台是站主的工作台（浅色顶栏 + 侧边导航），两套视觉完全不同。
 * 导航放在各自的布局里（主页写在 HomeView，后台在 AdminLayout）。</p>
 *
 * <p>历史教训：上一版在这里放了一条全局 el-menu，结果登录页也被套上了导航 ——
 * 登录页出现「问答 / 文档管理」两个点了就 401 的按钮，很怪。</p>
 */
</script>

<style>
/* 全局基础样式。组件内部样式都写在各自的 scoped style 里 */
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
}
body {
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: #1e293b;
}
.app-shell {
  min-height: 100%;
}

/* 滚动条细一点，别抢视觉 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-thumb {
  background: #d5dbe5;
  border-radius: 4px;
}
::-webkit-scrollbar-thumb:hover {
  background: #b9c2cf;
}
::-webkit-scrollbar-track {
  background: transparent;
}
</style>
