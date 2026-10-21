export default {
  plugins: {
    '@typescript-eslint': {
      // 第 1 周先用 JS 跑通，类型定义第 2 周补，避免过早被类型系统绑住
      enabled: false
    }
  },
  rules: {
    // 本项目统一规范见用户全局前端规范（Vue3 / TypeScript / UI 风格）
    'vue/multi-word-component-names': 'off'
  }
}
