export default defineNuxtConfig({
  compatibilityDate: '2026-09-01',
  devtools: { enabled: true },
  css: ['~/assets/main.css'],
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE ?? 'http://localhost:8080',
    },
  },
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: 'MegaGraph Shop — 让商品自己讲述为什么适合你',
      meta: [
        { name: 'description', content: '由商品事实、语义和关系驱动的智能商品探索体验。' },
      ],
    },
  },
})

