const apiUpstream = process.env.NUXT_API_UPSTREAM ?? 'http://127.0.0.1:8080'

export default defineNuxtConfig({
  buildDir: process.env.NUXT_BUILD_DIR ?? '.nuxt',
  compatibilityDate: '2026-09-01',
  devtools: { enabled: false },
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
      title: 'MegaGraph Shop — AI 超市',
      meta: [
        { name: 'description', content: '从自然语言开始，与 MegaGraph AI 超市一起寻找适合的商品。' },
      ],
    },
  },
})
