import { defineStore } from 'pinia'
import { documentApi } from '@/api'

/**
 * 文档 store。
 *
 * ⚠️ 这里拿到的已经是后端 R 里的 data —— http.js 拦截器统一拆过包，
 * 别再写 res.data，会取到 undefined。
 */
export const useDocumentStore = defineStore('documents', {
  state: () => ({
    list: [],
    current: null,
    chunks: [],
    loading: false
  }),
  actions: {
    async fetchAll() {
      this.loading = true
      try {
        this.list = (await documentApi.list()) || []
        return this.list
      } finally {
        this.loading = false
      }
    },
    async fetchDetail(id) {
      this.current = (await documentApi.detail(id)) ?? null
      return this.current
    },
    async fetchChunks(id, page = 1, size = 20) {
      const pageResult = await documentApi.chunks(id, page, size)
      this.chunks = pageResult?.items || []
      return this.chunks
    },
    async upload(file, onProgress) {
      const fd = new FormData()
      fd.append('file', file)
      return documentApi.upload(fd, onProgress)
    },
    async remove(id) {
      await documentApi.remove(id)
      this.list = this.list.filter((d) => d.id !== id)
    },
    async reindex(id) {
      await documentApi.reindex(id)
    }
  }
})
