import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  base: '/console-vue/',
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts')) return 'vendor-echarts'
          if (id.includes('element-plus')) return 'vendor-element-plus'
          if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) return 'vendor-vue'
          return 'vendor-misc'
        }
      }
    }
  },
  server: {
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8081',
        changeOrigin: true
      }
    }
  }
})
