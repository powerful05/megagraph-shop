import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': process.env.MERCHANT_API_UPSTREAM ?? 'http://localhost:8080',
    },
  },
})
