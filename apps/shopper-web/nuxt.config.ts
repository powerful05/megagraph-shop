const apiUpstream = process.env.NUXT_API_UPSTREAM ?? 'http://127.0.0.1:8080'

export default defineNuxtConfig({
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
      title: 'MegaGraph Shop — AI 超市对话演示',
      meta: [
        { name: 'description', content: '从自然语言开始体验 AI 超市对话流程。当前为演示界面，未接入真实 AI 推荐。' },
      ],
    },
  },
})
