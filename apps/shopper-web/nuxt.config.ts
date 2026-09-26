const apiUpstream = process.env.NUXT_API_UPSTREAM ?? 'http://127.0.0.1:8080'

export default defineNuxtConfig({
  compatibilityDate: '2026-09-01',
  devtools: { enabled: true },
  css: ['~/assets/main.css'],
  routeRules: {
    '/api/**': { proxy: `${apiUpstream}/api/**` },
  },
  runtimeConfig: {
    public: {
      apiBase: '',
    },
  },
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: 'MegaGraph Shop — 一个入口，发现适合你的穿搭',
      meta: [
        { name: 'description', content: '从一个对话入口发现不同商家的女装基础款。AI 导购仍在开发中，当前可浏览已公开商品。' },
      ],
    },
  },
})
