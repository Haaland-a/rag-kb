import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端 Spring Boot 跑 8080，前端 dev 跑 5173
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  // 产物直接拷到 rag-kb/src/main/resources/static/，由 Spring Boot 托管（不引 nginx）
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    // Element Plus + Vue 运行时全量打进 index chunk，1.2MB 属正常
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  }
})
