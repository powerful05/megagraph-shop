<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'

type DemoProduct = {
  id: string
  name: string
  note: string
  image: string
  source: string
}

type Message = {
  id: number
  role: 'user' | 'assistant'
  text: string
  showProducts?: boolean
}

type Conversation = {
  id: number
  title: string
  messages: Message[]
}

const demoProducts: DemoProduct[] = [
  {
    id: 'blue-shirt',
    name: '浅蓝衬衫',
    note: '清爽、利落的演示方向',
    image: '/demo-products/merchant-a-blue-shirt.jpg',
    source: '演示商家 A',
  },
  {
    id: 'ivory-knit',
    name: '米白针织上衣',
    note: '柔和、轻松的演示方向',
    image: '/demo-products/merchant-b-ivory-knit.jpg',
    source: '演示商家 B',
  },
  {
    id: 'gray-cardigan',
    name: '灰色开衫',
    note: '适合叠穿的演示方向',
    image: '/demo-products/merchant-c-gray-cardigan.jpg',
    source: '演示商家 C',
  },
]

let nextId = 1
let responseTimer: ReturnType<typeof setTimeout> | null = null
let progressTimer: ReturnType<typeof setTimeout> | null = null
const conversations = ref<Conversation[]>([{ id: nextId++, title: '新对话', messages: [] }])
const activeId = ref(1)
const draft = ref('')
const pendingId = ref<number | null>(null)
const progress = ref('正在阅读你的描述…')
const sidebarOpen = ref(false)
const previewProduct = ref<DemoProduct | null>(null)
const previewClose = ref<HTMLButtonElement | null>(null)
const messageList = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
let previewTrigger: HTMLElement | null = null

const activeConversation = computed(() => conversations.value.find(item => item.id === activeId.value)!)
const hasMessages = computed(() => activeConversation.value.messages.length > 0)

function startConversation() {
  const current = activeConversation.value
  if (current.messages.length === 0) {
    draft.value = ''
    composer.value?.focus()
    sidebarOpen.value = false
    return
  }
  const conversation = { id: nextId++, title: '新对话', messages: [] }
  conversations.value.unshift(conversation)
  activeId.value = conversation.id
  draft.value = ''
  sidebarOpen.value = false
  nextTick(() => composer.value?.focus())
}

function selectConversation(id: number) {
  activeId.value = id
  sidebarOpen.value = false
  nextTick(scrollToEnd)
}

function scrollToEnd() {
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

function demoAnswer(query: string): Message {
  const virtualOrService = /虚拟|数字|课程|咨询|预约|服务|软件|会员|游戏|设计/.test(query)
  const apparel = /穿|衣|衫|裙|裤|外套|针织|开衫|搭配|通勤|上班|见客户|面试|约会|出门|风格|服装|服饰|春|秋/.test(query)
  if (virtualOrService) {
    return {
      id: nextId++, role: 'assistant',
      text: '我收到了你的需求。当前演示只准备了三张服饰示例图，还没有虚拟商品或服务的可核验数据，因此不会把服饰图片当作你的搜索结果。后续接入真实商家目录与模型后，才能继续匹配。',
    }
  }
  if (!apparel) {
    return {
      id: nextId++, role: 'assistant',
      text: '我收到了你的描述。这个版本演示对话流程，但尚未接入理解需求的模型或实时商品检索；现有三张服饰图片不能证明与你的需求匹配。你可以换一种服饰场景试试看，或打开真实商品目录浏览。',
    }
  }
  return {
    id: nextId++, role: 'assistant',
    text: '我理解你正在描述一个穿搭场景。下面用三张生成的服饰图片演示商家图片如何进入对话；它们不是实时检索结果，也没有经过价格、库存或尺码核验。',
    showProducts: true,
  }
}

function submit() {
  const query = draft.value.trim()
  if (!query || pendingId.value !== null) return
  const conversation = activeConversation.value
  conversation.messages.push({ id: nextId++, role: 'user', text: query })
  if (conversation.title === '新对话') conversation.title = query.length > 24 ? `${query.slice(0, 24)}…` : query
  draft.value = ''
  pendingId.value = conversation.id
  progress.value = '正在阅读你的描述…'
  nextTick(scrollToEnd)
  progressTimer = setTimeout(() => {
    progress.value = '正在整理演示结果…'
  }, 650)
  responseTimer = setTimeout(() => {
    conversation.messages.push(demoAnswer(query))
    pendingId.value = null
    progressTimer = null
    responseTimer = null
    nextTick(scrollToEnd)
  }, 1300)
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

function openPreview(product: DemoProduct) {
  previewTrigger = document.activeElement as HTMLElement | null
  previewProduct.value = product
  nextTick(() => previewClose.value?.focus())
}

function closePreview() {
  previewProduct.value = null
  nextTick(() => previewTrigger?.focus())
}

function handlePreviewKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closePreview()
  if (event.key === 'Tab') {
    event.preventDefault()
    previewClose.value?.focus()
  }
}

onBeforeUnmount(() => {
  if (progressTimer) clearTimeout(progressTimer)
  if (responseTimer) clearTimeout(responseTimer)
})
</script>

<template>
  <section class="agent-app" aria-label="AI 超市演示对话">
    <div v-if="sidebarOpen" class="sidebar-scrim" @click="sidebarOpen = false" />
    <aside class="agent-sidebar" :class="{ 'is-open': sidebarOpen }" aria-label="对话历史">
      <button class="new-chat" type="button" @click="startConversation"><span aria-hidden="true">＋</span> 新对话</button>
      <div class="history-label">本次会话</div>
      <div class="history-list">
        <button
          v-for="conversation in conversations"
          :key="conversation.id"
          type="button"
          :class="{ active: conversation.id === activeId }"
          :aria-current="conversation.id === activeId ? 'page' : undefined"
          @click="selectConversation(conversation.id)"
        >
          <span class="history-icon" aria-hidden="true">☰</span>
          <span class="history-title">{{ conversation.title }}</span>
        </button>
      </div>
      <div class="sidebar-foot">对话仅保存在当前页面，不会提交给真实模型。</div>
    </aside>

    <div class="agent-main">
      <div class="conversation-topline">
        <button class="mobile-history" type="button" aria-label="打开对话历史" @click="sidebarOpen = true">☰</button>
        <span>AI 超市 <small>演示模式</small></span>
        <span class="not-connected">未接入实时模型</span>
      </div>

      <div ref="messageList" class="message-scroll" role="log" aria-live="polite" aria-relevant="additions">
        <div v-if="!hasMessages" class="empty-conversation">
          <div class="agent-symbol" aria-hidden="true"><span>✳</span></div>
          <h1>你在找什么？</h1>
          <p>用自己的话描述想法。这里先演示对话和商品图片的呈现。</p>
        </div>
        <div v-else class="message-stack">
          <template v-for="message in activeConversation.messages" :key="message.id">
            <div v-if="message.role === 'user'" class="user-row">
              <p class="user-bubble">{{ message.text }}</p>
            </div>
            <article v-else class="assistant-row">
              <div class="assistant-avatar" aria-hidden="true">✳</div>
              <div class="assistant-content">
                <div class="assistant-name">MegaGraph <span>演示回复</span></div>
                <p>{{ message.text }}</p>
                <div v-if="message.showProducts" class="demo-results">
                  <div class="results-heading"><strong>图片示例</strong><span>生成素材 · 非真实商家上架商品</span></div>
                  <div class="demo-grid">
                    <button v-for="product in demoProducts" :key="product.id" type="button" class="demo-product" @click="openPreview(product)">
                      <img :src="product.image" :alt="`${product.name}，AI 生成的演示商品图`" />
                      <span class="demo-product-meta"><small>{{ product.source }} · 生成示例</small><strong>{{ product.name }}</strong><span>{{ product.note }}</span></span>
                    </button>
                  </div>
                  <p class="results-footnote">图片仅用于界面演示，不提供价格、库存和购买入口。</p>
                </div>
              </div>
            </article>
          </template>
          <div v-if="pendingId === activeId" class="assistant-row thinking-row" role="status">
            <div class="assistant-avatar" aria-hidden="true">✳</div>
            <div class="assistant-content"><div class="assistant-name">MegaGraph <span>演示处理中</span></div><p><span class="thinking-dot" />{{ progress }}</p></div>
          </div>
        </div>
      </div>

      <div class="composer-zone">
        <form class="composer" @submit.prevent="submit">
          <label class="sr-only" for="agent-prompt">描述你想找的商品或服务</label>
          <textarea
            id="agent-prompt"
            ref="composer"
            v-model="draft"
            rows="2"
            maxlength="2000"
            placeholder="说说你想找什么，场景、感受、预算都可以…"
            :disabled="pendingId !== null"
            @keydown="handleComposerKeydown"
          />
          <div class="composer-actions">
            <span>演示对话 · Enter 发送，Shift + Enter 换行</span>
            <button type="submit" aria-label="发送消息" title="发送消息" :disabled="!draft.trim() || pendingId !== null">↑</button>
          </div>
        </form>
        <p class="composer-disclaimer">演示回复不代表真实 AI 推荐；交易信息以商家发布和后端数据为准。</p>
      </div>
    </div>

    <div v-if="previewProduct" class="preview-layer" @click.self="closePreview">
      <section class="preview-dialog" role="dialog" aria-modal="true" :aria-label="`${previewProduct.name}演示图片`" tabindex="-1" @keydown="handlePreviewKeydown">
        <button ref="previewClose" class="preview-close" type="button" aria-label="关闭图片详情" title="关闭" @click="closePreview">×</button>
        <img :src="previewProduct.image" :alt="`${previewProduct.name}，AI 生成的演示商品图`" />
        <div><small>{{ previewProduct.source }} · AI 生成图片</small><h2>{{ previewProduct.name }}</h2><p>{{ previewProduct.note }}</p><p>此图不是商家上传的商品，也无法确认材质、规格、价格或库存。</p></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.agent-app { display: grid; grid-template-columns: 248px minmax(0, 1fr); height: calc(100svh - 68px); min-height: 560px; color: #242927; background: #fff; }
.agent-sidebar { display: flex; flex-direction: column; min-width: 0; padding: 20px 12px 18px; border-right: 1px solid #e7e9e7; background: #f7f8f6; }
.new-chat { display: flex; align-items: center; gap: 10px; width: 100%; height: 43px; padding: 0 13px; border: 1px solid #d8ded9; border-radius: 6px; background: #fff; text-align: left; font-size: 13px; font-weight: 650; cursor: pointer; }
.new-chat span { font-size: 21px; font-weight: 400; }
.new-chat:hover, .history-list button:hover { background: #edf3ee; }
.history-label { margin: 30px 12px 10px; color: #8a948e; font-size: 11px; }
.history-list { display: grid; align-content: start; gap: 3px; overflow: auto; }
.history-list button { display: flex; align-items: center; gap: 9px; min-width: 0; height: 38px; padding: 0 11px; border: 0; border-radius: 5px; background: transparent; text-align: left; cursor: pointer; }
.history-list button.active { background: #e8eee9; }
.history-icon { flex: 0 0 auto; color: #84968c; font-size: 13px; }
.history-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.sidebar-foot { margin-top: auto; padding: 13px 10px 0; border-top: 1px solid #e3e7e2; color: #8a948e; font-size: 11px; line-height: 1.6; }
.agent-main { display: grid; min-width: 0; min-height: 0; grid-template-rows: 54px minmax(0, 1fr) auto; }
.conversation-topline { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 clamp(18px, 3vw, 42px); border-bottom: 1px solid #f0f1ef; font-size: 13px; font-weight: 700; }
.conversation-topline small { margin-left: 7px; padding: 4px 6px; border: 1px solid #cfe7d7; border-radius: 3px; color: #287b51; font-size: 10px; font-weight: 600; }
.not-connected { color: #929a95; font-size: 11px; font-weight: 400; }
.message-scroll { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.empty-conversation { display: flex; height: 100%; min-height: 270px; align-items: center; justify-content: center; flex-direction: column; padding: 20px; text-align: center; }
.agent-symbol { display: grid; width: 46px; height: 46px; place-items: center; border: 1px solid #d1e5d7; border-radius: 12px; color: #388760; background: #edf7f0; font-size: 30px; }
.empty-conversation h1 { margin: 23px 0 10px; font-size: 34px; font-weight: 620; line-height: 1.2; letter-spacing: 0; }
.empty-conversation p { max-width: 360px; margin: 0; color: #88938b; font-size: 13px; line-height: 1.7; }
.message-stack { width: min(900px, 100%); min-height: 100%; margin: 0 auto; padding: 32px 28px 60px; }
.user-row { display: flex; justify-content: flex-end; margin-bottom: 30px; }
.user-bubble { max-width: min(580px, 90%); margin: 0; padding: 13px 17px; border-radius: 8px; color: #303a34; background: #edf2ed; font-size: 14px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.assistant-row { display: flex; align-items: flex-start; gap: 13px; margin-bottom: 34px; }
.assistant-avatar { display: grid; flex: 0 0 29px; width: 29px; height: 29px; place-items: center; border-radius: 7px; color: #fff; background: #267b53; font-size: 19px; }
.assistant-content { min-width: 0; flex: 1; }
.assistant-name { height: 29px; display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 700; }
.assistant-name span { color: #89938b; font-size: 10px; font-weight: 400; }
.assistant-content > p { max-width: 730px; margin: 9px 0 0; color: #454d47; font-size: 14px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.thinking-row .assistant-content > p { color: #85928a; }
.thinking-dot { display: inline-block; width: 7px; height: 7px; margin-right: 9px; border-radius: 50%; background: #45a671; animation: pulse 1s ease-in-out infinite; }
@keyframes pulse { 50% { opacity: .3; } }
.demo-results { margin-top: 22px; }
.results-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.results-heading strong { font-size: 13px; }
.results-heading span, .results-footnote { color: #89938b; font-size: 11px; }
.demo-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.demo-product { display: block; min-width: 0; overflow: hidden; padding: 0; border: 1px solid #e5e8e4; border-radius: 6px; background: #fff; text-align: left; cursor: pointer; }
.demo-product:hover { border-color: #9bc8ab; box-shadow: 0 5px 17px #273c2c12; }
.demo-product img { display: block; width: 100%; aspect-ratio: 4 / 4.1; object-fit: cover; object-position: center 30%; background: #ebede9; }
.demo-product-meta { display: grid; gap: 5px; padding: 11px 12px 13px; }
.demo-product-meta small { color: #80998a; font-size: 10px; }
.demo-product-meta strong { font-size: 13px; }
.demo-product-meta > span { color: #87918a; font-size: 11px; }
.results-footnote { margin: 10px 0 0; line-height: 1.5; }
.composer-zone { width: min(900px, 100%); margin: 0 auto; padding: 0 28px 18px; }
.composer { padding: 13px 14px 10px; border: 1px solid #d8ded9; border-radius: 8px; background: #fff; box-shadow: 0 8px 28px #273c2c0d; }
.composer:focus-within { border-color: #87b99a; }
.composer textarea { display: block; width: 100%; min-height: 48px; max-height: 180px; resize: vertical; padding: 0; border: 0; outline: 0; color: #303a34; background: transparent; font-size: 14px; line-height: 1.5; }
.composer textarea::placeholder { color: #a3aca5; }
.composer-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.composer-actions span { color: #a2aaa3; font-size: 10px; }
.composer-actions button { flex: 0 0 30px; width: 30px; height: 30px; border: 0; border-radius: 6px; color: #fff; background: #267b53; font-size: 20px; cursor: pointer; }
.composer-actions button:disabled { background: #c6d0c9; cursor: not-allowed; }
.composer-disclaimer { margin: 8px 0 0; color: #a2aaa3; text-align: center; font-size: 10px; line-height: 1.4; }
.preview-layer { position: fixed; z-index: 60; inset: 0; display: grid; place-items: center; padding: 20px; background: #17221bcc; }
.preview-dialog { position: relative; display: grid; width: min(760px, 100%); max-height: calc(100svh - 40px); grid-template-columns: minmax(0, 1fr) minmax(220px, .9fr); overflow: auto; border-radius: 8px; background: #fff; }
.preview-dialog img { display: block; width: 100%; height: 100%; max-height: 680px; object-fit: cover; }
.preview-dialog > div { align-self: center; padding: 32px; }
.preview-dialog small { color: #438461; font-size: 11px; }
.preview-dialog h2 { margin: 12px 0 16px; font-size: 25px; font-weight: 650; line-height: 1.2; letter-spacing: 0; }
.preview-dialog p { color: #68746c; font-size: 13px; line-height: 1.7; }
.preview-close { position: absolute; top: 12px; right: 12px; width: 32px; height: 32px; border: 0; border-radius: 5px; background: #fff; font-size: 22px; cursor: pointer; }
.mobile-history, .sidebar-scrim { display: none; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 760px) {
  .agent-app { display: block; height: calc(100svh - 64px); min-height: 480px; }
  .agent-main { height: 100%; }
  .agent-sidebar { position: fixed; z-index: 32; top: 64px; bottom: 0; left: 0; width: min(280px, 80vw); transform: translateX(-100%); transition: transform .2s ease; }
  .agent-sidebar.is-open { transform: translateX(0); }
  .sidebar-scrim { position: fixed; z-index: 31; inset: 64px 0 0; display: block; background: #16231b72; }
  .mobile-history { display: block; width: 30px; height: 30px; margin-right: 6px; border: 0; background: transparent; font-size: 16px; cursor: pointer; }
  .conversation-topline { justify-content: flex-start; padding: 0 15px; }
  .not-connected { margin-left: auto; }
  .message-stack { padding: 24px 15px 36px; }
  .composer-zone { padding: 0 12px 12px; }
  .composer-actions span { font-size: 9px; }
  .demo-grid { gap: 7px; }
  .demo-product-meta { padding: 8px; }
  .demo-product-meta > span { display: none; }
  .results-heading { display: grid; gap: 3px; }
}
@media (max-width: 440px) {
  .not-connected { display: none; }
  .demo-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .preview-dialog { display: block; }
  .preview-dialog img { height: min(55svh, 460px); }
  .preview-dialog > div { padding: 20px; }
}
@media (prefers-reduced-motion: reduce) { .thinking-dot { animation: none; } .agent-sidebar { transition: none; } }
</style>
