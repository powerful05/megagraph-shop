<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, toRef, watch } from 'vue'
import { ArrowUp, Columns2, MessageSquare, PanelLeft, Plus, RefreshCw, Square, X } from 'lucide-vue-next'
import type { DeliveryOffer, PublicProduct } from '@megagraph/domain-types'
import { useGuideConversations, type AgentMessage as Message } from '../composables/useGuideConversations'
import ProductComparison from './ProductComparison.vue'
import ProductImage from './ProductImage.vue'
import DigitalOfferCard from './DigitalOfferCard.vue'
import { shopperApi } from '../api'

const props = defineProps<{
  modelAvailable: boolean
  digitalDeliveryAvailable: boolean
  demoCatalogAvailable: boolean
  modelName: string
  cartBusy: boolean
  accountUserId: number | null
  reduceMotion: boolean
  systemReduceMotion: boolean
}>()
const emit = defineEmits<{ addToCart: [product: PublicProduct, skuId: number]; purchaseDigital: [offer: DeliveryOffer]; refreshCapabilities: []; 'update:reduceMotion': [value: boolean] }>()
const apiBase = useRuntimeConfig().public.apiBase

type DemoProduct = {
  id: string
  name: string
  note: string
  image: string
  source: string
  live?: boolean
  price?: string
  product?: PublicProduct
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
const draft = ref('')
const sidebarOpen = ref(false)
const historyPanel = ref<HTMLElement | null>(null)
const historyTrigger = ref<HTMLButtonElement | null>(null)
const newChatButton = ref<HTMLButtonElement | null>(null)
const comparisonProducts = ref<PublicProduct[]>([])
const comparisonOpen = ref(false)
let comparisonTrigger: HTMLElement | null = null
watch(() => props.accountUserId, () => { comparisonProducts.value = []; comparisonOpen.value = false; closePreview(false) })
const previewProduct = ref<DemoProduct | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
let previewRequest: AbortController | null = null
const previewClose = ref<HTMLButtonElement | null>(null)
const previewDialog = ref<HTMLElement | null>(null)
const previewSkuId = ref<number | null>(null)
const previewAllowedSkuIds = ref<number[]>([])
const previewChanged = ref(false)
const messageList = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
let previewTrigger: HTMLElement | null = null

const { conversations, activeId, activeConversation, pendingId, waiting, progress, loading, historyError, canRetry, canSend,
  startConversation: createChat, selectConversation: switchChat, send, stopWaiting, recover, loadHistory } = useGuideConversations(
  apiBase, toRef(props, 'accountUserId'), () => props.modelAvailable || props.digitalDeliveryAvailable || !props.demoCatalogAvailable, demoAnswer,
  () => { if (!props.modelAvailable) emit('refreshCapabilities'); void nextTick(scrollToEnd) },
)
const hasMessages = computed(() => activeConversation.value.messages.length > 0)
const previewSkus = computed(() => previewProduct.value?.product?.skus.filter(sku => previewAllowedSkuIds.value.includes(sku.id)) ?? [])
const previewSku = computed(() => previewSkus.value.find(sku => sku.id === previewSkuId.value))

function basisFor(message: Message, productId: number) {
  return message.recommendationBasis?.filter(item => item.productId === productId) ?? []
}

function startConversation() {
  createChat()
  draft.value = ''
  sidebarOpen.value = false
  nextTick(() => composer.value?.focus())
}

function selectConversation(id: string) {
  switchChat(id)
  draft.value = ''
  sidebarOpen.value = false
  nextTick(() => { scrollToEnd(); composer.value?.focus() })
}

function toggleComparison(product: PublicProduct) {
  if (comparisonProducts.value.some(item => item.id === product.id)) {
    comparisonProducts.value = comparisonProducts.value.filter(item => item.id !== product.id)
  } else if (comparisonProducts.value.length < 3) comparisonProducts.value.push(product)
}

function openComparison(event: MouseEvent) {
  comparisonTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  comparisonOpen.value = true
}

function isCompared(productId: number) {
  return comparisonProducts.value.some(item => item.id === productId)
}

function closeComparison() {
  comparisonOpen.value = false
  nextTick(() => { if (comparisonTrigger?.isConnected) comparisonTrigger.focus() })
}

function viewComparisonDetails(product: PublicProduct) {
  comparisonOpen.value = false
  void openPreview(livePreview(product))
  previewTrigger = comparisonTrigger
}

function openHistory() {
  sidebarOpen.value = true
  nextTick(() => newChatButton.value?.focus())
}

function closeHistory() {
  sidebarOpen.value = false
  nextTick(() => historyTrigger.value?.focus())
}

function handleHistoryKeydown(event: KeyboardEvent) {
  if (!sidebarOpen.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeHistory(); return }
  if (event.key !== 'Tab') return
  const controls = Array.from(historyPanel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)') ?? [])
    .filter(button => button.getClientRects().length && getComputedStyle(button).visibility !== 'hidden')
  const first = controls[0]
  const last = controls.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}

function scrollToEnd() {
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

function demoAnswer(query: string): Message {
  const virtualOrService = /虚拟|数字|课程|咨询|预约|服务|软件|会员|游戏|设计/.test(query)
  const apparel = /穿|衣|衫|裙|裤|外套|针织|开衫|搭配|通勤|上班|见客户|面试|约会|出门|风格|服装|服饰|春|秋/.test(query)
  if (virtualOrService) {
    return {
      id: `demo-${nextId++}`, role: 'assistant',
      text: '我收到了你的需求。当前演示只准备了三张服饰示例图，还没有虚拟商品或服务的可核验数据，因此不会把服饰图片当作你的搜索结果。后续接入真实商家目录与模型后，才能继续匹配。',
    }
  }
  if (!apparel) {
    return {
      id: `demo-${nextId++}`, role: 'assistant',
      text: '我收到了你的描述。这个版本演示对话流程，但尚未接入理解需求的模型或实时商品检索；现有三张服饰图片不能证明与你的需求匹配。你可以换一种服饰场景试试看，或打开真实商品目录浏览。',
    }
  }
  return {
    id: `demo-${nextId++}`, role: 'assistant',
    text: '我理解你正在描述一个穿搭场景。下面用三张生成的服饰图片演示商家图片如何进入对话；它们不是实时检索结果，也没有经过价格、库存或尺码核验。',
    showProducts: true,
  }
}

function submit() {
  const query = draft.value.trim()
  if (!query || !canSend.value) return
  draft.value = ''
  void send(query)
  nextTick(scrollToEnd)
}

function visibleDemoProducts(message: Message) {
  return message.demoIds ? demoProducts.filter(product => message.demoIds!.includes(product.id)) : demoProducts
}

function livePreview(product: PublicProduct): DemoProduct {
  return {
    id: String(product.id), name: product.title, note: product.subtitle ?? '',
    image: product.mainImageUrl ? `${apiBase}${product.mainImageUrl}` : '',
    source: product.evidence.merchantName, live: true, price: formatPrice(product), product,
  }
}

function formatPrice(product: PublicProduct) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: product.currency }).format(product.priceFrom)
}

function retryReply() {
  if (canRetry.value) void send('', true)
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

async function openPreview(product: DemoProduct, event?: MouseEvent) {
  previewRequest?.abort()
  previewRequest = new AbortController()
  const request = previewRequest
  previewTrigger = event?.currentTarget instanceof HTMLElement ? event.currentTarget : document.activeElement as HTMLElement | null
  previewProduct.value = product
  previewLoading.value = !!product.product
  previewError.value = ''
  previewChanged.value = false
  previewAllowedSkuIds.value = product.product?.skus.map(sku => sku.id) ?? []
  previewSkuId.value = null
  nextTick(() => previewClose.value?.focus())
  if (!product.product) return
  try {
    const fresh = await shopperApi.product(apiBase, product.product.id, request.signal)
    if (!request.signal.aborted) {
      previewProduct.value = livePreview(fresh)
      previewSkuId.value = previewSkus.value[0]?.id ?? null
      previewChanged.value = fresh.evidence.productVersion !== product.product.evidence.productVersion
        || fresh.skus.some(sku => {
          const before = product.product!.skus.find(item => item.id === sku.id)
          return before && (before.price !== sku.price || before.name !== sku.name)
        })
    }
  } catch (error) {
    if (!request.signal.aborted) previewError.value = error instanceof Error ? error.message : '商品当前不可用'
  } finally {
    if (!request.signal.aborted) previewLoading.value = false
  }
}

function closePreview(restoreFocus = true) {
  previewRequest?.abort()
  previewProduct.value = null
  if (restoreFocus) nextTick(() => { if (previewTrigger?.isConnected) previewTrigger.focus() })
}

function addPreviewToCart() {
  const product = previewProduct.value?.product
  const sku = previewSku.value
  if (!product || !sku || previewLoading.value || previewError.value || props.cartBusy) return
  closePreview(false)
  emit('addToCart', product, sku.id)
}

function handlePreviewKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.stopPropagation(); closePreview() }
  if (event.key === 'Tab') {
    const controls = Array.from(previewDialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), select:not(:disabled), summary, [tabindex="0"]') ?? [])
      .filter(control => control.getClientRects().length > 0)
    const first = controls[0]
    const last = controls.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}

onBeforeUnmount(() => {
  previewRequest?.abort()
})
</script>

<template>
  <section class="agent-app" aria-label="AI 超市对话">
    <div v-if="sidebarOpen" class="sidebar-scrim" @click="closeHistory" />
    <aside ref="historyPanel" class="agent-sidebar" :class="{ 'is-open': sidebarOpen }" aria-label="对话历史" @keydown="handleHistoryKeydown">
      <button ref="newChatButton" class="new-chat" type="button" @click="startConversation"><Plus :size="19" /> 新对话</button>
      <button class="history-close" type="button" aria-label="关闭对话历史" title="关闭对话历史" @click="closeHistory"><X :size="20" /></button>
      <div class="history-label">{{ accountUserId ? '账号对话' : '本页对话' }}</div>
      <p v-if="loading" role="status">正在恢复对话…</p>
      <div v-if="historyError" class="history-error" role="alert"><p>{{ historyError }}</p><button type="button" @click="activeConversation.saved ? recover() : loadHistory()">刷新状态</button></div>
      <div class="history-list">
        <button
          v-for="conversation in conversations"
          :key="conversation.id"
          type="button"
          :class="{ active: conversation.id === activeId }"
          :aria-current="conversation.id === activeId ? 'page' : undefined"
          @click="selectConversation(conversation.id)"
        >
          <MessageSquare class="history-icon" :size="14" aria-hidden="true" />
          <span class="history-title">{{ conversation.title }}</span>
        </button>
      </div>
      <div class="sidebar-foot">{{ accountUserId ? '对话保存在当前账号，退出后不留在本页。' : '未登录对话仅留在本页，刷新后不保留。' }}</div>
      <label class="motion-preference" :title="systemReduceMotion ? '系统已开启减少动态' : undefined">
        <input type="checkbox" :checked="reduceMotion" :disabled="systemReduceMotion"
          @change="emit('update:reduceMotion', ($event.target as HTMLInputElement).checked)" />减少动态
      </label>
    </aside>

    <div class="agent-main">
      <div class="conversation-topline">
        <button ref="historyTrigger" class="mobile-history" type="button" aria-label="打开对话历史" :aria-expanded="sidebarOpen" @click="openHistory"><PanelLeft :size="18" /></button>
        <span>AI 超市 <small>{{ modelAvailable ? '真实对话' : digitalDeliveryAvailable ? '测试供给' : demoCatalogAvailable ? '演示模式' : '暂不可用' }}</small></span>
        <span class="not-connected">{{ modelAvailable ? modelName : digitalDeliveryAvailable ? '数字文件来自商家确认资料' : demoCatalogAvailable ? '未接入实时模型' : '对话服务未就绪' }}</span>
      </div>

      <div ref="messageList" class="message-scroll" role="log" aria-live="polite" aria-relevant="additions">
        <div v-if="!hasMessages" class="empty-conversation">
          <div class="agent-symbol" aria-hidden="true"><span>✳</span></div>
          <h1>你在找什么？</h1>
          <p>{{ !modelAvailable && digitalDeliveryAvailable ? '描述想找的数字测试文件，核对商家确认资料后再决定是否单独试购。' : demoCatalogAvailable && !modelAvailable ? '用自己的话描述想法。这里先演示对话和商品图片的呈现。' : '想解决什么、喜欢什么，或者还没想清楚，都可以从这里聊起。' }}</p>
        </div>
        <div v-else class="message-stack">
          <template v-for="message in activeConversation.messages" :key="message.id">
            <div v-if="message.role === 'user'" class="user-row">
              <p class="user-bubble">{{ message.text }}</p>
            </div>
            <article v-else class="assistant-row">
              <div class="assistant-avatar" aria-hidden="true">✳</div>
              <div class="assistant-content">
                <div class="assistant-name">MegaGraph <span>{{ message.error ? '回复失败' : message.modelReply ? (message.responseSource === 'JAVA_DIGITAL' ? '已核验测试资料' : message.responseSource === 'NO_MODEL' ? '模型未启用' : modelName || '文本模型回复') : '演示回复' }}</span></div>
                <p>{{ message.text }}</p>
                <div v-if="message.advice" class="model-advice"><span>选购建议</span><p>{{ message.advice }}</p></div>
                <button v-if="message.error && message === activeConversation.messages.at(-1)" class="retry-reply" type="button" :disabled="!canRetry" @click="retryReply">重试</button>
                <div v-if="message.showProducts" class="demo-results">
                  <div class="results-heading"><strong>图片示例</strong><span>生成素材 · 非真实商家上架商品</span></div>
                  <div class="demo-grid">
                    <button v-for="product in visibleDemoProducts(message)" :key="product.id" type="button" class="demo-product" @click="openPreview(product, $event)">
                      <ProductImage :src="product.image" :alt="`${product.name}，AI 生成的演示商品图`" />
                      <span class="demo-product-meta"><small>{{ product.source }} · 生成示例</small><strong>{{ product.name }}</strong><span>{{ product.note }}</span></span>
                    </button>
                  </div>
                  <p class="results-footnote">图片仅用于界面演示，不提供价格、库存和购买入口。</p>
                </div>
                <DigitalOfferCard v-for="offer in message.deliveryOffers" :key="`digital-${offer.id}`" :offer="offer" :busy="cartBusy" :account-user-id="accountUserId" @purchase="emit('purchaseDigital', $event)" />
                <div v-if="message.liveProducts?.length" class="demo-results">
                  <div class="results-heading"><strong>匹配商品</strong><span>返回时已核对资料版本与可售状态</span></div>
                  <div class="demo-grid">
                    <article v-for="product in message.liveProducts" :key="product.id" class="recommendation-item">
                    <button type="button" class="demo-product" @click="openPreview(livePreview(product), $event)">
                      <ProductImage :src="product.mainImageUrl ? `${apiBase}${product.mainImageUrl}` : null" :alt="product.title" />
                      <span class="demo-product-meta"><small>{{ product.evidence.merchantName }} · {{ product.evidence.dataOrigin === 'LOCAL_TEST' ? '测试供给' : '已发布' }}</small><strong>{{ product.title }}</strong><span>{{ formatPrice(product) }} 起 · 资料版本 {{ product.evidence.productVersion }}</span></span>
                    </button>
                    <label class="compare-select"><input type="checkbox" :checked="isCompared(product.id)" :disabled="comparisonProducts.length >= 3 && !isCompared(product.id)" @change="toggleComparison(product)" />加入比较</label>
                    <template v-for="basis in basisFor(message, product.id)" :key="basis.productId">
                      <div class="recommendation-basis">
                        <p>{{ !basis.facts.length ? '资料待补充' : basis.dataOrigin === 'LOCAL_TEST' ? '已确认测试资料' : '商家已确认资料' }}<span v-if="basis.productVersion !== null"> · v{{ basis.productVersion }}</span></p>
                        <dl><template v-for="fact in basis.facts.slice(0, 2)" :key="fact.key"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></template></dl>
                        <details>
                          <summary>更多依据与限制</summary>
                          <dl><template v-for="fact in basis.facts.slice(2)" :key="fact.key"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></template></dl>
                          <ul><li v-for="limitation in basis.limitations" :key="limitation">{{ limitation }}</li></ul>
                        </details>
                      </div>
                    </template>
                    </article>
                  </div>
                </div>
              </div>
            </article>
          </template>
          <div v-if="pendingId === activeId" class="assistant-row thinking-row" role="status">
            <div class="assistant-avatar" aria-hidden="true">✳</div>
            <div class="assistant-content"><div class="assistant-name">MegaGraph <span>{{ !modelAvailable && !digitalDeliveryAvailable && demoCatalogAvailable ? '演示处理中' : '正在回复' }}</span></div><p><span class="thinking-dot" />{{ progress }}</p></div>
          </div>
        </div>
      </div>

      <div class="composer-zone">
        <div v-if="comparisonProducts.length" class="comparison-tray">
          <span>已选 {{ comparisonProducts.length }} 件</span>
          <button type="button" :disabled="comparisonProducts.length < 2" @click="openComparison"><Columns2 :size="16" />比较</button>
          <button type="button" aria-label="清空比较商品" title="清空比较商品" @click="comparisonProducts = []"><X :size="16" /></button>
        </div>
        <form class="composer" @submit.prevent="submit">
          <label class="sr-only" for="agent-prompt">描述你想找的商品或服务</label>
          <textarea
            id="agent-prompt"
            ref="composer"
            v-model="draft"
            rows="2"
            maxlength="2000"
            placeholder="说说你想找什么，场景、感受、预算都可以…"
            :disabled="pendingId !== null || loading"
            @keydown="handleComposerKeydown"
          />
          <div class="composer-actions">
            <span>{{ !modelAvailable && digitalDeliveryAvailable ? '查询已确认测试供给' : !modelAvailable && demoCatalogAvailable ? '演示对话' : '与 AI 超市对话' }}</span>
            <button v-if="waiting" type="button" aria-label="停止等待" title="停止等待" @click="stopWaiting"><Square :size="17" /></button>
            <button v-else-if="pendingId !== null" type="button" aria-label="刷新请求状态" title="刷新请求状态" @click="recover()"><RefreshCw :size="18" /></button>
            <button v-else type="submit" aria-label="发送消息" title="发送消息" :disabled="!draft.trim() || !canSend"><ArrowUp :size="20" /></button>
          </div>
        </form>
        <p class="composer-disclaimer">{{ modelAvailable ? (demoCatalogAvailable ? '当前包含生成的演示图；商品交易信息以商家发布为准。' : 'AI 建议仅供选购参考，商品交易信息以商家发布为准。') : digitalDeliveryAvailable ? '可查询商家已确认的自建测试文件。此为受控 Demo，使用 Mock 支付；支付成功后 24 小时内领取及下载。' : demoCatalogAvailable ? '演示回复不代表真实 AI 推荐；交易信息以商家发布和后端数据为准。' : '对话服务状态暂不可用，商品目录以当前服务端资料为准。' }}</p>
      </div>
    </div>

    <div v-if="previewProduct" class="preview-layer" @click.self="closePreview()">
      <section ref="previewDialog" class="preview-dialog" role="dialog" aria-modal="true" :aria-label="`${previewProduct.name}商品详情`" tabindex="-1" @keydown="handlePreviewKeydown">
        <button ref="previewClose" class="preview-close" type="button" aria-label="关闭图片详情" title="关闭" @click="closePreview()"><X :size="20" /></button>
        <ProductImage class="preview-media" :src="previewProduct.image || null" :alt="previewProduct.name" contain />
        <div><small>{{ previewProduct.source }}{{ previewProduct.live ? '' : ' · AI 生成图片' }}</small><h2>{{ previewProduct.name }}</h2><p>{{ previewProduct.note }}</p><p v-if="previewLoading">正在核对当前资料…</p><p v-else-if="previewError" role="alert">{{ previewError }}</p>
          <template v-else-if="previewProduct.product">
            <p v-if="previewChanged" class="preview-update" role="status">商品资料或规格价格已更新，请核对后再选择。</p>
            <ProductSupplyEvidence :product="previewProduct.product" />
            <div class="preview-purchase">
              <label for="guide-sku">规格</label>
              <select id="guide-sku" v-model="previewSkuId" :disabled="cartBusy || !previewSkus.length">
                <option v-for="sku in previewSkus" :key="sku.id" :value="sku.id">{{ sku.name }}</option>
              </select>
              <p v-if="previewSku">{{ new Intl.NumberFormat('zh-CN', { style: 'currency', currency: previewSku.currency }).format(previewSku.price) }} · 可售 {{ previewSku.availableStock }} 件</p>
              <p v-else>本次推荐的规格当前不可售，请重新查询。</p>
              <button type="button" :disabled="cartBusy || !previewSku" @click="addPreviewToCart">{{ cartBusy ? '处理中…' : '加入购物袋' }}</button>
            </div>
          </template>
          <p v-else>此图不是商家上传的商品，也无法确认材质、规格、价格或库存。</p>
        </div>
      </section>
    </div>
    <ProductComparison v-if="comparisonOpen" :products="comparisonProducts" @close="closeComparison" @details="viewComparisonDetails" />
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
.model-advice { margin-top: 14px; }
.model-advice > span { color: #687a85; font-size: 11px; font-weight: 650; }
.model-advice > p { margin: 5px 0 0; color: #454d47; font-size: 14px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.recommendation-item { min-width: 0; }
.recommendation-item > .demo-product { width: 100%; }
.recommendation-basis { padding: 10px 2px 0; color: #596861; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.recommendation-basis > p { margin: 0 0 6px; color: #49735b; font-size: 10px; font-weight: 650; }
.recommendation-basis dl { display: grid; grid-template-columns: minmax(0, 54px) minmax(0, 1fr); gap: 3px 7px; margin: 0; }
.recommendation-basis dt { color: #87938b; }
.recommendation-basis dd { margin: 0; }
.recommendation-basis details { margin-top: 7px; }
.recommendation-basis summary { color: #687a85; cursor: pointer; }
.recommendation-basis details > dl { margin-top: 8px; }
.recommendation-basis ul { margin: 8px 0 0; padding-left: 14px; }
.recommendation-basis li + li { margin-top: 5px; }
.retry-reply { margin-top: 12px; padding: 6px 12px; border: 1px solid #d8ded9; border-radius: 4px; background: #fff; cursor: pointer; font-size: 12px; }
.missing-image { display: grid; width: 100%; aspect-ratio: 4 / 4.1; place-items: center; color: #7b877e; background: #eef1ec; font-size: 12px; }
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
.preview-layer { position: fixed; z-index: 60; inset: 0; display: grid; place-items: start center; padding: 20px; background: #17221bcc; }
.preview-dialog { position: relative; display: grid; width: min(760px, 100%); max-height: calc(100svh - 40px); grid-template-columns: minmax(0, 1fr) minmax(220px, .9fr); overflow: auto; border-radius: 8px; background: #fff; }
.preview-media { align-self: start; }
.preview-dialog > div:not(.preview-media) { align-self: center; padding: 32px; }
.preview-dialog small { color: #438461; font-size: 11px; }
.preview-dialog h2 { margin: 12px 0 16px; font-size: 25px; font-weight: 650; line-height: 1.2; letter-spacing: 0; }
.preview-dialog p { color: #68746c; font-size: 13px; line-height: 1.7; }
.preview-purchase { display: grid; gap: 8px; margin-top: 20px; padding-top: 16px; border-top: 1px solid #e5e8e4; }
.preview-purchase label { font-size: 12px; font-weight: 650; }
.preview-purchase select { width: 100%; min-width: 0; min-height: 40px; padding: 8px; border: 1px solid #cdd8d0; border-radius: 4px; background: #fff; font-size: 13px; }
.preview-purchase p { margin: 0; }
.preview-purchase button { width: 100%; min-height: 42px; padding: 10px; border: 0; border-radius: 4px; background: #267b53; color: #fff; font-size: 13px; font-weight: 650; cursor: pointer; }
.preview-purchase button:disabled { background: #c6d0c9; cursor: not-allowed; }
.preview-dialog .preview-update { color: #916222; }
.preview-close { position: fixed; z-index: 1; top: 32px; right: max(32px, calc((100vw - 760px) / 2 + 12px)); width: 32px; height: 32px; border: 0; border-radius: 5px; background: #fff; font-size: 22px; cursor: pointer; }
.mobile-history, .sidebar-scrim, .history-close { display: none; }
.history-error { padding: 8px 12px; color: #a83c3c; font-size: 12px; overflow-wrap: anywhere; }
.history-icon { flex-shrink: 0; }
.compare-select { display: flex; align-items: center; gap: 6px; margin: 8px 0; font-size: 12px; color: #526259; }
.comparison-tray { display: flex; justify-content: flex-end; align-items: center; gap: 8px; padding-bottom: 8px; font-size: 12px; }
.comparison-tray button { display: inline-flex; justify-content: center; align-items: center; gap: 5px; min-height: 30px; padding: 5px 8px; border: 1px solid #dce4df; border-radius: 4px; color: #267b53; background: #fff; cursor: pointer; }
.comparison-tray button:disabled { opacity: .5; cursor: not-allowed; }
.composer-actions button, .preview-close { display: inline-grid; place-items: center; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 760px) {
  .agent-app { display: block; height: calc(100svh - 64px); min-height: 480px; }
  .agent-main { height: 100%; }
  .agent-sidebar { position: fixed; z-index: 32; top: 64px; bottom: 0; left: 0; width: min(280px, 80vw); visibility: hidden; transform: translateX(-100%); transition: transform .2s ease; }
  .agent-sidebar.is-open { visibility: visible; transform: translateX(0); }
  .history-close { display: inline-grid; place-items: center; position: absolute; top: 26px; right: 20px; width: 30px; height: 30px; border: 0; background: #fff; font-size: 22px; cursor: pointer; }
  .sidebar-scrim { position: fixed; z-index: 31; inset: 64px 0 0; display: block; background: #16231b72; }
  .mobile-history { display: inline-grid; place-items: center; width: 30px; height: 30px; margin-right: 6px; border: 0; background: transparent; font-size: 16px; cursor: pointer; }
  .conversation-topline { justify-content: flex-start; padding: 0 15px; }
  .not-connected { margin-left: auto; }
  .message-stack { padding: 24px 15px 36px; }
  .composer-zone { padding: 0 12px 12px; }
  .composer-actions span { font-size: 9px; }
  .demo-grid { gap: 7px; }
  .demo-product-meta { padding: 8px; }
  .demo-product-meta > span { display: none; }
  .recommendation-item .demo-product-meta > span { display: block; overflow-wrap: anywhere; }
  .results-heading { display: grid; gap: 3px; }
}
@media (max-width: 440px) {
  .not-connected { display: none; }
  .demo-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .preview-dialog { display: block; }
  .preview-media { height: min(55svh, 460px); aspect-ratio: auto; }
  .preview-dialog > div:not(.preview-media) { padding: 20px; }
}
@media (prefers-reduced-motion: reduce) { .thinking-dot { animation: none; } .agent-sidebar { transition: none; } }
.motion-preference { display: flex; gap: 7px; align-items: center; margin-top: 12px; font-size: 12px; color: #5c6961; }
.motion-preference input { accent-color: #277c53; }
</style>
