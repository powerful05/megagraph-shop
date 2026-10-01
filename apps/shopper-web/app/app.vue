<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type {
  MockPaymentResult,
  OrderItem,
  PublicProduct,
  PublicSku,
  SalesOrder,
  ShopCapabilities,
  ShoppingCart,
} from '@megagraph/domain-types'
import {
  needsShopperOrderPolling,
  nextOrderPollingDelay,
} from '@megagraph/domain-types'
import { cancelShopperRequests, shopperApi, type ShopperCredentials } from './api'

type CommercePanel = 'cart' | 'checkout' | 'orders'

const config = useRuntimeConfig()
const apiBase = config.public.apiBase
const isDevelopment = import.meta.dev
const { data: products, pending, error: catalogError, refresh } = await useFetch<PublicProduct[]>(
  `${apiBase}/api/shop/v1/products`,
  { default: () => [] },
)
const { data: capabilities } = await useFetch<ShopCapabilities>(`${apiBase}/api/shop/v1/capabilities`, {
  default: () => ({ mockPaymentAvailable: false }),
})
const mockPaymentAvailable = computed(() => capabilities.value?.mockPaymentAvailable === true)

const credentials = reactive<ShopperCredentials>({
  username: isDevelopment ? 'shopper@megagraph.local' : '',
  password: isDevelopment ? 'shopper123' : '',
})
const inviteCode = ref('')
const displayName = ref('')
const registering = ref(false)
const catalogOpen = ref(false)
const checkout = reactive({
  recipientName: isDevelopment ? '示例顾客' : '',
  recipientPhone: isDevelopment ? '13800138000' : '',
  shippingAddress: isDevelopment ? '深圳市盐田区海景路 1 号' : '',
})
const connected = ref(false)
const loginOpen = ref(false)
const loginDialog = ref<HTMLElement | null>(null)
const commerceDialog = ref<HTMLElement | null>(null)
let dialogTrigger: HTMLElement | null = null
const activePanel = ref<CommercePanel | null>(null)
const busyAction = ref('')
const ordersRefreshBusy = ref(false)
const errorMessage = ref('')
const notice = ref('')
let noticeTimer: ReturnType<typeof setTimeout> | null = null
watch(notice, value => {
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = value ? setTimeout(() => { notice.value = ''; noticeTimer = null }, 6000) : null
})
const pollingMessage = ref('')
const pollingTimedOut = ref(false)
const cart = ref<ShoppingCart>({ cartId: 0, currency: null, estimatedTotal: 0, items: [] })
const orders = ref<SalesOrder[]>([])
const selectedSkuByProduct = reactive<Record<number, number>>({})
const pendingGuideCart = ref<{ productId: number; skuId: number } | null>(null)
watch(loginOpen, value => { if (!value && !connected.value) pendingGuideCart.value = null })
const checkoutKey = ref<string | null>(null)
watch(connected, value => { if (!value) checkoutKey.value = null })
watch(() => JSON.stringify(checkout), () => { checkoutKey.value = null })
const cartMerchantGroups = computed(() => [...new Set(cart.value.items.map(item => item.merchantId))].map(merchantId => {
  const items = cart.value.items.filter(item => item.merchantId === merchantId)
  return { merchantId, name: products.value.find(product => product.evidence.merchantId === merchantId)?.evidence.merchantName ?? `商家 #${merchantId}`,
    items, total: items.reduce((sum, item) => sum + item.lineAmount, 0) }
}))
function merchantName(merchantId: number) {
  return products.value.find(product => product.evidence.merchantId === merchantId)?.evidence.merchantName ?? `商家 #${merchantId}`
}
function orderSupplyItems(order: SalesOrder) { return order.items.filter(item => item.supplyDetails) }
function sizeRowsForItem(item: OrderItem) { return item.supplyDetails?.sizeChart.filter(row => row.skuCode === item.skuCode) ?? [] }
const refundReasons = reactive<Record<number, string>>({})
const submittedPayments = reactive<Record<number, boolean>>({})

let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollStartedAt: number | null = null
let ordersRequest: Promise<SalesOrder[]> | null = null
let orderMutationVersion = 0

const cartCount = computed(() => cart.value.items.reduce((total, item) => total + item.quantity, 0))
const actionableOrderCount = computed(() => orders.value
  .filter(order => order.status === 'PENDING_PAYMENT'
    || order.status === 'SHIPPED'
    || order.afterSale?.status === 'REQUESTED'
    || order.afterSale?.status === 'REFUND_PROCESSING'
    || order.afterSale?.status === 'REFUND_FAILED').length)
const hasPollingOrders = computed(() => orders.value.some(needsShopperOrderPolling))

function imageUrl(path: string | null): string {
  return path ? `${apiBase}${path}` : ''
}

function formatPrice(price: number, currency = 'CNY'): string {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(price)
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function chosenSku(product: PublicProduct): PublicSku | undefined {
  const selectedId = selectedSkuByProduct[product.id]
  return product.skus.find(sku => sku.id === selectedId) ?? product.skus[0]
}

function orderStatusLabel(status: SalesOrder['status']): string {
  return {
    PENDING_PAYMENT: '等待支付',
    PAID: '待发货',
    SHIPPED: '运输中',
    COMPLETED: '已完成',
    REFUNDED: '已退款',
    PAYMENT_FAILED: '支付失败',
    CANCELLED: '已取消',
    EXPIRED: '已超时',
  }[status]
}

function paymentStatusLabel(order: SalesOrder): string {
  if (order.payment?.reconciliationRequired) return '需人工核查'
  return {
    PENDING: '等待支付',
    PROCESSING: '支付处理中',
    SUCCEEDED: '支付成功',
    REFUNDED: '已退款',
    FAILED: '支付失败',
    CANCELLED: '支付已取消',
  }[order.payment?.status ?? 'PENDING']
}

function afterSaleStatusLabel(order: SalesOrder): string {
  const status = order.afterSale?.status
  if (!status) return '—'
  return {
    REQUESTED: '等待商家审核',
    REFUND_PROCESSING: '退款处理中',
    REFUND_FAILED: '退款失败，等待商家处理',
    REFUNDED: '退款成功',
    REJECTED: '退款申请已驳回',
  }[status]
}

function refundStatusLabel(order: SalesOrder): string {
  const status = order.afterSale?.refund?.status
  if (!status) return '—'
  return {
    PENDING: '等待渠道受理',
    PROCESSING: '渠道处理中',
    SUCCEEDED: '退款成功',
    FAILED: '退款失败',
  }[status]
}

function paymentIsAwaitingResult(order: SalesOrder): boolean {
  return order.payment?.status === 'PROCESSING'
    || (order.payment?.status === 'PENDING' && submittedPayments[order.id] === true)
}

function reservationSummary(order: SalesOrder): string {
  return order.reservations.map(reservation => reservation.status).join(' · ')
}

async function execute(action: string, task: () => Promise<void>) {
  busyAction.value = action
  errorMessage.value = ''
  notice.value = ''
  try {
    await task()
  } catch (reason) {
    if (!(reason instanceof DOMException && reason.name === 'AbortError')) {
      errorMessage.value = reason instanceof Error ? reason.message : '操作失败，请稍后重试'
    }
  } finally {
    busyAction.value = ''
  }
}

async function connectShopper() {
  await execute('login', async () => {
    await shopperApi.login(apiBase, credentials)
    credentials.password = ''
    await loadPrivateState()
    notice.value = '顾客会话已建立'
    const pending = pendingGuideCart.value
    pendingGuideCart.value = null
    if (pending) {
      const current = await shopperApi.product(apiBase, pending.productId)
      await addToCart(current, pending.skuId)
    }
  })
}

async function loadPrivateState() {
    const [nextCart, nextOrders] = await Promise.all([
      shopperApi.cart(apiBase, credentials),
      shopperApi.orders(apiBase, credentials),
    ])
    cart.value = nextCart
    setOrders(nextOrders)
    connected.value = true
    loginOpen.value = false
    activePanel.value = nextCart.items.length > 0 ? 'cart' : null
    schedulePolling(true)
}

async function registerShopper() {
  await execute('register', async () => {
    await shopperApi.register(apiBase, { ...credentials, inviteCode: inviteCode.value, displayName: displayName.value })
    inviteCode.value = ''
    registering.value = false
    notice.value = '账号已创建，请登录'
  })
}

async function logoutShopper() {
  await execute('logout', async () => {
    await shopperApi.logout(apiBase)
    cancelShopperRequests()
    clearPollTimer()
    orderMutationVersion += 1
    ordersRequest = null
    connected.value = false
    pendingGuideCart.value = null
    cart.value = { cartId: 0, currency: null, estimatedTotal: 0, items: [] }
    orders.value = []
    activePanel.value = null
    credentials.password = ''
    notice.value = '已退出登录'
  })
}

function openPanel(panel: CommercePanel) {
  if (!connected.value) {
    loginOpen.value = true
    return
  }
  activePanel.value = panel
  if (panel === 'orders') void recoverOrderState()
}

async function addToCart(product: PublicProduct, skuId?: number) {
  if (!connected.value) {
    loginOpen.value = true
    return
  }
  const sku = skuId === undefined ? chosenSku(product) : product.skus.find(item => item.id === skuId)
  if (!sku) { errorMessage.value = '所选规格当前不可售，请重新查询'; return }
  const existing = cart.value.items.find(item => item.skuId === sku.id)
  const nextQuantity = (existing?.quantity ?? 0) + 1
  await execute(`add-${sku.id}`, async () => {
    cart.value = await shopperApi.setCartItem(apiBase, credentials, sku.id, nextQuantity)
    checkoutKey.value = null
    activePanel.value = 'cart'
    notice.value = `${product.title} 已加入购物车`
  })
}

function addGuideProduct(product: PublicProduct, skuId: number) {
  if (busyAction.value) return
  if (!connected.value) {
    pendingGuideCart.value = { productId: product.id, skuId }
    loginOpen.value = true
    return
  }
  void addToCart(product, skuId)
}

async function changeQuantity(skuId: number, quantity: number) {
  if (quantity < 1) return removeCartItem(skuId)
  await execute(`quantity-${skuId}`, async () => {
    cart.value = await shopperApi.setCartItem(apiBase, credentials, skuId, quantity)
    checkoutKey.value = null
  })
}

async function removeCartItem(skuId: number) {
  await execute(`remove-${skuId}`, async () => {
    cart.value = await shopperApi.removeCartItem(apiBase, credentials, skuId)
    checkoutKey.value = null
  })
}

async function createOrder() {
  await execute('checkout', async () => {
    checkoutKey.value ??= crypto.randomUUID()
    const group = await shopperApi.createCheckout(
      apiBase,
      credentials,
      checkoutKey.value,
      checkout,
    )
    orderMutationVersion += 1
    orders.value = [...group.orders, ...orders.value.filter(item => !group.orders.some(order => order.id === item.id))]
    checkoutKey.value = null
    cart.value = await shopperApi.cart(apiBase, credentials)
    activePanel.value = 'orders'
    notice.value = `下单组 ${group.groupNo} 已创建，共 ${group.orders.length} 个试用子订单，库存分别预占`
    await refresh()
    schedulePolling(true)
  })
}

async function applyPayment(order: SalesOrder, result: MockPaymentResult) {
  await execute(`payment-${order.id}-${result}`, async () => {
    const updated = await shopperApi.mockPayment(
      apiBase,
      credentials,
      order.id,
      crypto.randomUUID(),
      result,
    )
    submittedPayments[order.id] = true
    replaceOrder(updated)
    notice.value = needsShopperOrderPolling(updated)
      ? (result === 'SUCCESS'
          ? 'Mock 支付请求已提交，正在等待渠道回调'
          : 'Mock 渠道结果已提交，正在等待后端确认终态')
      : `后端已确认：${paymentStatusLabel(updated)}`
    if (['PAYMENT_FAILED', 'CANCELLED', 'EXPIRED'].includes(updated.status)) {
      await refreshInventoryFacts()
    }
    schedulePolling(true)
  })
}

async function cancelOrder(order: SalesOrder) {
  await execute(`cancel-${order.id}`, async () => {
    const updated = await shopperApi.cancelOrder(apiBase, credentials, order.id)
    replaceOrder(updated)
    notice.value = '订单已取消，预占库存已释放'
    await refreshInventoryFacts()
  })
}

async function confirmReceipt(order: SalesOrder) {
  await execute(`receipt-${order.id}`, async () => {
    const updated = await shopperApi.confirmReceipt(apiBase, credentials, order.id)
    replaceOrder(updated)
    notice.value = '已确认收货，订单已完成'
  })
}

async function requestRefund(order: SalesOrder) {
  const reason = refundReasons[order.id]?.trim() ?? ''
  if (!reason) {
    errorMessage.value = '请填写退款原因'
    return
  }
  await execute(`refund-${order.id}`, async () => {
    const updated = await shopperApi.requestRefund(
      apiBase,
      credentials,
      order.id,
      crypto.randomUUID(),
      reason,
    )
    replaceOrder(updated)
    notice.value = '退款申请已提交，发货已暂停'
  })
}

function replaceOrder(updated: SalesOrder) {
  orderMutationVersion += 1
  orders.value = orders.value.map(order => order.id === updated.id ? updated : order)
  if (updated.payment && !needsShopperOrderPolling(updated)) {
    delete submittedPayments[updated.id]
  }
}

function setOrders(nextOrders: SalesOrder[]) {
  orderMutationVersion += 1
  orders.value = nextOrders
  nextOrders.forEach((order) => {
    if (order.payment && !needsShopperOrderPolling(order)) delete submittedPayments[order.id]
  })
}

function inventoryStateChanged(previous: SalesOrder[], next: SalesOrder[]): boolean {
  const previousById = new Map(previous.map(order => [order.id, order]))
  return next.some((order) => {
    const before = previousById.get(order.id)
    if (!before) return false
    const enteredInventoryTerminal = ['PAYMENT_FAILED', 'CANCELLED', 'EXPIRED', 'REFUNDED'].includes(order.status)
      && before.status !== order.status
    const refundSucceeded = order.afterSale?.refund?.status === 'SUCCEEDED'
      && before.afterSale?.refund?.status !== 'SUCCEEDED'
    return enteredInventoryTerminal || refundSucceeded
  })
}

async function refreshInventoryFacts() {
  const [, nextCart] = await Promise.all([
    refresh(),
    shopperApi.cart(apiBase, credentials),
  ])
  cart.value = nextCart
}

async function refreshOrders(options: { background?: boolean; resetPolling?: boolean } = {}) {
  if (!connected.value) return
  if (ordersRequest) {
    try {
      await ordersRequest
    } catch (reason) {
      if (!options.background) throw reason
    }
    if (options.resetPolling) schedulePolling(true)
    return
  }

  const versionAtStart = orderMutationVersion
  const previous = orders.value
  if (!options.background) ordersRefreshBusy.value = true
  ordersRequest = shopperApi.orders(apiBase, credentials)
  try {
    const nextOrders = await ordersRequest
    if (versionAtStart === orderMutationVersion) {
      const inventoryChanged = inventoryStateChanged(previous, nextOrders)
      setOrders(nextOrders)
      if (inventoryChanged) await refreshInventoryFacts()
    }
    pollingMessage.value = ''
  } catch (reason) {
    const message = reason instanceof Error ? reason.message : '订单状态刷新失败'
    if (options.background) pollingMessage.value = `自动刷新失败：${message}`
    else throw reason
  } finally {
    ordersRequest = null
    if (!options.background) ordersRefreshBusy.value = false
  }

  if (options.resetPolling) schedulePolling(true)
}

function clearPollTimer() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function schedulePolling(resetWindow = false) {
  clearPollTimer()
  if (!connected.value || !hasPollingOrders.value) {
    pollStartedAt = null
    pollingTimedOut.value = false
    return
  }
  if (import.meta.client && document.visibilityState !== 'visible') return

  if (resetWindow || pollStartedAt == null) {
    pollStartedAt = Date.now()
    pollingTimedOut.value = false
  }
  const delay = nextOrderPollingDelay(pollStartedAt, Date.now())
  if (delay == null) {
    pollingTimedOut.value = true
    return
  }
  pollTimer = setTimeout(runPollingCycle, delay)
}

async function runPollingCycle() {
  pollTimer = null
  await refreshOrders({ background: true })
  schedulePolling(false)
}

async function manualRefreshOrders() {
  await execute('refresh-orders', async () => {
    await refreshOrders({ resetPolling: true })
    notice.value = '订单事实已刷新'
  })
}

async function recoverOrderState() {
  if (!connected.value) return
  await refreshOrders({ background: true })
  schedulePolling(true)
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') void recoverOrderState()
  else clearPollTimer()
}

function handleOnline() {
  void recoverOrderState()
}

watch([loginOpen, () => activePanel.value !== null], async ([login, commerce], [wasLogin, wasCommerce]) => {
  if (login || commerce) {
    if (!wasLogin && !wasCommerce) dialogTrigger = document.activeElement as HTMLElement | null
    await nextTick()
    const dialog = login ? loginDialog.value : commerceDialog.value
    const first = dialog?.querySelector<HTMLElement>(login ? 'input' : 'button')
    const focusTarget = first ?? dialog
    focusTarget?.focus()
  } else {
    dialogTrigger?.focus()
    dialogTrigger = null
  }
})

function handleDialogKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    if (loginOpen.value) loginOpen.value = false
    else activePanel.value = null
    return
  }
  if (event.key !== 'Tab') return
  const dialog = event.currentTarget as HTMLElement
  const controls = [...dialog.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href]',
  )].filter(element => element.getClientRects().length > 0)
  const first = controls[0]
  const last = controls.at(-1)
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}

onMounted(() => {
  shopperApi.onSessionExpired(() => {
    if (!connected.value) return
    cancelShopperRequests()
    clearPollTimer()
    orderMutationVersion += 1
    ordersRequest = null
    connected.value = false
    activePanel.value = null
    orders.value = []
    cart.value = { cartId: 0, currency: null, estimatedTotal: 0, items: [] }
    notice.value = '会话已过期，请重新登录'
  })
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('online', handleOnline)
  void shopperApi.me(apiBase).then(() => loadPrivateState()).catch(() => {})
})

onBeforeUnmount(() => {
  if (noticeTimer) clearTimeout(noticeTimer)
  shopperApi.onSessionExpired(null)
  clearPollTimer()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('online', handleOnline)
})
</script>

<template>
  <main>
    <nav class="nav-shell agent-nav">
      <a class="brand" href="#top" aria-label="MegaGraph Shop 首页">
        <span class="brand-mark" />
        MEGAGRAPH
      </a>
      <div class="nav-links">
        <button type="button" :aria-pressed="catalogOpen" @click="catalogOpen = !catalogOpen">
          {{ catalogOpen ? '返回对话' : '商品目录' }}
        </button>
        <button type="button" @click="openPanel('orders')">
          订单<span v-if="actionableOrderCount">{{ actionableOrderCount }}</span>
        </button>
        <button class="cart-trigger" type="button" @click="openPanel('cart')">
          购物袋<span>{{ cartCount }}</span>
        </button>
        <button class="account-trigger" type="button" @click="connected ? logoutShopper() : (loginOpen = true)">
          {{ connected ? '退出' : '登录' }}
        </button>
      </div>
    </nav>

    <AgentExperience
      v-show="!catalogOpen"
      id="top"
      :model-available="capabilities?.guideAvailable === true"
      :demo-catalog-available="capabilities?.demoCatalogAvailable === true"
      :model-name="capabilities?.guideModelName ?? ''"
      :cart-busy="Boolean(busyAction)"
      @add-to-cart="addGuideProduct"
    />

    <section v-if="catalogOpen" id="collection" class="collection-section live-catalog">
      <header class="section-header">
        <div>
          <p class="eyebrow">LIVE CATALOG</p>
          <h2>当前可售</h2>
        </div>
        <button type="button" :disabled="pending" @click="refresh">{{ pending ? '同步中' : '刷新商品' }}</button>
      </header>

      <div v-if="catalogError" class="state-panel">
        <strong>商品服务暂时不可用</strong>
        <p>请确认后端已在 8080 端口启动。</p>
      </div>
      <div v-else-if="pending" class="state-panel">正在验证商品发布状态与库存…</div>
      <div v-else-if="products.length === 0" class="state-panel empty">
        <span>0</span>
        <strong>还没有已发布商品</strong>
        <p>在商家工作台完成草稿确认和发布后，商品会出现在这里。</p>
      </div>
      <div v-else class="product-grid">
        <article v-for="(product, index) in products" :key="product.id" class="product-card">
          <div class="product-media">
            <img v-if="product.mainImageUrl" :src="imageUrl(product.mainImageUrl)" :alt="product.title" />
            <div v-else class="image-placeholder">NO IMAGE</div>
            <span class="index">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="stock-chip">{{ product.availableStock }} READY</span>
          </div>
          <div class="product-meta">
            <div>
              <h3>{{ product.title }}</h3>
              <p>{{ product.subtitle }}</p>
            </div>
            <div class="price-block">
              <strong>{{ formatPrice(product.priceFrom, product.currency) }}</strong>
              <span>{{ product.availableStock }} 件可售</span>
            </div>
          </div>
          <div class="product-actions">
            <label v-if="product.skus.length > 1">
              <span>选择规格</span>
              <select v-model.number="selectedSkuByProduct[product.id]">
                <option v-for="sku in product.skus" :key="sku.id" :value="sku.id">
                  {{ sku.name }} · {{ formatPrice(sku.price, sku.currency) }}
                </option>
              </select>
            </label>
            <div v-else class="single-sku">
              <span>规格</span>
              <strong>{{ product.skus[0]?.name ?? '标准款' }}</strong>
            </div>
            <button
              type="button"
              :disabled="!product.skus.length || busyAction === `add-${chosenSku(product)?.id}`"
              @click="addToCart(product)"
            >
              {{ busyAction === `add-${chosenSku(product)?.id}` ? '加入中…' : '加入购物袋' }}
              <span>＋</span>
            </button>
          </div>
          <details class="product-evidence-details"><summary>商品资料与尺码</summary><ProductSupplyEvidence :product="product" /></details>
        </article>
      </div>
    </section>

    <Transition name="fade">
      <div v-if="loginOpen" class="modal-layer" @click.self="loginOpen = false">
        <section ref="loginDialog" class="login-card" role="dialog" aria-modal="true" aria-labelledby="shopper-login-title" tabindex="-1" @keydown="handleDialogKeydown">
          <button class="close-button" type="button" aria-label="关闭" @click="loginOpen = false">×</button>
          <p class="eyebrow">SHOPPER SESSION</p>
          <h2 id="shopper-login-title">{{ registering ? '邀请注册' : '登录购物会话' }}</h2>
          <p>{{ registering ? '首批试用采用受控邀请。邮箱形式账号目前不代表邮箱已验证。' : '登录后可恢复你的购物袋与订单状态。' }}</p>
          <form @submit.prevent="registering ? registerShopper() : connectShopper()">
            <label v-if="registering">邀请码<input v-model="inviteCode" autocomplete="off" required /></label>
            <label v-if="registering">显示名称<input v-model="displayName" autocomplete="name" required /></label>
            <label>账号<input v-model="credentials.username" type="email" autocomplete="username" required /></label>
            <label>密码<input v-model="credentials.password" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" required /></label>
            <button type="submit" :disabled="busyAction === 'login' || busyAction === 'register'">
              {{ registering ? '创建账号' : '登录并读取购物袋' }}
            </button>
          </form>
          <button class="auth-switch" type="button" @click="registering = !registering">
            {{ registering ? '已有账号？返回登录' : '收到邀请码？创建账号' }}
          </button>
        </section>
      </div>
    </Transition>

    <Transition name="drawer">
      <div v-if="activePanel" class="drawer-layer" @click.self="activePanel = null">
        <aside ref="commerceDialog" class="commerce-drawer" role="dialog" aria-modal="true" aria-label="购物与订单" tabindex="-1" @keydown="handleDialogKeydown">
          <header class="drawer-header">
            <div>
              <p class="eyebrow">TRANSACTION DESK</p>
              <h2>{{ activePanel === 'cart' ? '购物袋' : activePanel === 'checkout' ? '确认结算' : '我的订单' }}</h2>
            </div>
            <button class="close-button" type="button" aria-label="关闭" @click="activePanel = null">×</button>
          </header>

          <div class="drawer-tabs">
            <button :class="{ active: activePanel === 'cart' }" type="button" @click="activePanel = 'cart'">购物袋 {{ cartCount }}</button>
            <button :class="{ active: activePanel === 'orders' }" type="button" @click="activePanel = 'orders'">订单 {{ orders.length }}</button>
          </div>

          <section v-if="activePanel === 'cart'" class="drawer-content cart-content">
            <div v-if="cart.items.length === 0" class="drawer-empty">
              <span>EMPTY / 00</span>
              <h3>购物袋还是空的</h3>
              <p>还没有选中的商品。</p>
            </div>
            <template v-else>
              <section v-for="group in cartMerchantGroups" :key="group.merchantId" class="cart-merchant-group">
                <h3>{{ group.name }} <small>{{ formatPrice(group.total, cart.currency ?? 'CNY') }}</small></h3>
              <article v-for="item in group.items" :key="item.skuId" class="cart-line">
                <div>
                  <span>{{ item.skuCode }}</span>
                  <h3>{{ item.productTitle }}</h3>
                  <p>{{ item.skuName }} · {{ formatPrice(item.unitPrice, item.currency) }}</p>
                </div>
                <div class="quantity-control">
                  <button type="button" :disabled="Boolean(busyAction)" @click="changeQuantity(item.skuId, item.quantity - 1)">−</button>
                  <span>{{ item.quantity }}</span>
                  <button type="button" :disabled="Boolean(busyAction) || item.quantity >= item.availableStock" @click="changeQuantity(item.skuId, item.quantity + 1)">＋</button>
                </div>
                <strong>{{ formatPrice(item.lineAmount, item.currency) }}</strong>
                <button class="text-button" type="button" @click="removeCartItem(item.skuId)">移除</button>
              </article>
              </section>
              <footer class="cart-summary">
                <div><span>预计合计</span><strong>{{ formatPrice(cart.estimatedTotal, cart.currency ?? 'CNY') }}</strong></div>
                <p>提交订单时，后端会重新读取价格和库存；这里的金额不是交易事实。</p>
                <p>{{ cartMerchantGroups.length }} 家商家，各自生成独立试用订单。</p>
              <button v-if="mockPaymentAvailable" type="button" @click="activePanel = 'checkout'">进入结算 <span>→</span></button>
              <p v-else>当前环境只开放选品与购物袋；订单提交和 Mock 支付暂未开放。</p>
              </footer>
            </template>
          </section>

          <section v-else-if="activePanel === 'checkout'" class="drawer-content checkout-content">
            <button class="back-button" type="button" @click="activePanel = 'cart'">← 返回购物袋</button>
            <div class="checkout-total">
              <span>{{ cartCount }} 件商品</span>
              <strong>{{ formatPrice(cart.estimatedTotal, cart.currency ?? 'CNY') }}</strong>
            </div>
            <section v-for="group in cartMerchantGroups" :key="group.merchantId" class="checkout-merchant-group">
              <h3>{{ group.name }} <small>{{ formatPrice(group.total, cart.currency ?? 'CNY') }}</small></h3>
              <p v-for="item in group.items" :key="item.skuId">{{ item.productTitle }} · {{ item.skuName }} × {{ item.quantity }}</p>
              <small>实物 · 独立子订单 · Mock 支付 · 不发货</small>
            </section>
            <form @submit.prevent="createOrder">
              <label>收件人<input v-model="checkout.recipientName" required maxlength="80" /></label>
              <label>联系电话<input v-model="checkout.recipientPhone" required maxlength="32" /></label>
              <label>收货地址<textarea v-model="checkout.shippingAddress" required maxlength="500" rows="4" /></label>
              <div class="checkout-note">
                <span>MOCK PAYMENT</span>
                <p>此阶段不会调用真实支付渠道。仅在本地 Mock 环境可以创建订单并模拟支付结果。</p>
              </div>
              <button type="submit" :disabled="!mockPaymentAvailable || busyAction === 'checkout' || cart.items.length === 0">
                {{ busyAction === 'checkout' ? '正在锁定库存…' : `创建 ${cartMerchantGroups.length} 个试用子订单` }}
              </button>
            </form>
          </section>

          <section v-else class="drawer-content orders-content">
            <div class="orders-refresh-bar">
              <div>
                <span v-if="hasPollingOrders && !pollingTimedOut">LIVE / 每 2 秒同步处理中订单</span>
                <span v-else-if="pollingTimedOut">仍在处理，自动刷新已暂停</span>
                <span v-else>订单状态来自后端事实</span>
                <small v-if="pollingMessage">{{ pollingMessage }}</small>
              </div>
              <button type="button" :disabled="ordersRefreshBusy || Boolean(busyAction)" @click="manualRefreshOrders">
                {{ ordersRefreshBusy || busyAction === 'refresh-orders' ? '刷新中…' : '手动刷新' }}
              </button>
            </div>
            <div v-if="orders.length === 0" class="drawer-empty">
              <span>ORDER / 00</span>
              <h3>还没有订单</h3>
              <p>完成一次结算后，订单及其支付、库存预占状态会显示在这里。</p>
            </div>
            <article v-for="order in orders" v-else :key="order.id" class="order-card">
              <header>
                <div>
                  <span>{{ order.orderNo }}</span>
                  <small>{{ formatDate(order.createdAt) }}</small>
                  <small>{{ merchantName(order.merchantId) }}<template v-if="order.checkoutGroupId"> · 下单组 #{{ order.checkoutGroupId }}</template><template v-if="order.testOrder"> · 试用子订单</template></small>
                </div>
                <strong :class="`status-${order.status.toLowerCase()}`">{{ order.testOrder && order.status === 'PAID' ? '模拟支付成功 · 不发货' : orderStatusLabel(order.status) }}</strong>
              </header>
              <div class="order-items">
                <p v-for="item in order.items" :key="item.skuId">
                  <span>{{ item.productTitle }} · {{ item.skuName }} × {{ item.quantity }}</span>
                  <strong>{{ formatPrice(item.lineAmount, item.currency) }}</strong>
                </p>
              </div>
              <details v-for="item in orderSupplyItems(order)" :key="`evidence-${item.skuId}`" class="order-supply-snapshot">
                <summary>{{ item.productTitle }} · 下单资料版本 {{ item.productVersion }}</summary>
                <p>{{ item.supplyDetails?.color }} · {{ item.supplyDetails?.fabric }} · {{ item.supplyDetails?.fit }}</p>
                <p v-for="row in sizeRowsForItem(item)" :key="row.skuCode">{{ row.sizeLabel }} · 成衣胸围 {{ row.chestCm }} cm · 衣长 {{ row.lengthCm }} cm</p>
                <p>{{ item.supplyDetails?.measurementNote }}</p>
              </details>
              <div class="order-facts">
                <span>支付 / {{ paymentStatusLabel(order) }}</span>
                <span>库存 / {{ reservationSummary(order) }}</span>
              </div>
              <div v-if="order.payment?.reconciliationRequired" class="reconciliation-card">
                <strong>支付状态需要人工核查</strong>
                <p>{{ order.payment.reconciliationReason || '渠道事件与当前订单状态冲突，系统没有自动覆盖订单事实。' }}</p>
              </div>
              <div v-if="order.shipment" class="tracking-card">
                <div>
                  <span>FULFILLMENT / {{ order.shipment.status }}</span>
                  <strong>{{ order.shipment.carrierName }}</strong>
                </div>
                <div>
                  <span>物流单号</span>
                  <strong>{{ order.shipment.trackingNo }}</strong>
                </div>
                <small v-if="order.shipment.deliveredAt">签收于 {{ formatDate(order.shipment.deliveredAt) }}</small>
                <small v-else>发货于 {{ formatDate(order.shipment.shippedAt) }}</small>
              </div>
              <div v-if="order.afterSale" :class="`customer-after-sale ${order.afterSale.status.toLowerCase()}`">
                <header>
                  <span>REFUND / {{ afterSaleStatusLabel(order) }}</span>
                  <small>{{ formatDate(order.afterSale.requestedAt) }}</small>
                </header>
                <p>{{ order.afterSale.reason }}</p>
                <div v-if="order.afterSale.merchantNote">
                  <span>商家处理</span>
                  <strong>{{ order.afterSale.merchantNote }}</strong>
                </div>
                <div v-if="order.afterSale.refund">
                  <span>退款状态</span>
                  <strong>{{ formatPrice(order.afterSale.refund.amount, order.afterSale.refund.currency) }} · {{ refundStatusLabel(order) }}</strong>
                </div>
                <div v-if="order.afterSale.status === 'REFUND_FAILED'" class="failure-summary">
                  <span>失败摘要</span>
                  <strong>{{ order.afterSale.refund?.failureMessage || order.afterSale.refund?.failureCode || '退款未完成，商家需要重试或人工处理。' }}</strong>
                </div>
              </div>
              <div class="order-total">
                <span>订单合计</span>
                <strong>{{ formatPrice(order.totalAmount, order.currency) }}</strong>
              </div>
              <div
                v-if="mockPaymentAvailable && order.status === 'PENDING_PAYMENT' && !paymentIsAwaitingResult(order)"
                class="payment-simulator"
              >
                <p>开发支付回调模拟器</p>
                <div>
                  <button type="button" :disabled="Boolean(busyAction)" @click="applyPayment(order, 'SUCCESS')">模拟成功</button>
                  <button type="button" :disabled="Boolean(busyAction)" @click="applyPayment(order, 'FAILURE')">模拟失败</button>
                  <button type="button" :disabled="Boolean(busyAction)" @click="applyPayment(order, 'CANCELLED')">模拟渠道取消</button>
                  <button type="button" :disabled="Boolean(busyAction)" @click="cancelOrder(order)">主动取消订单</button>
                </div>
                <small>按钮只提交 Mock 渠道结果；页面会等待后端回调处理后的终态。</small>
              </div>
              <div v-else-if="paymentIsAwaitingResult(order)" class="processing-card">
                <strong>支付处理中</strong>
                <p>请求已受理，支付成功与否仍以后端查询结果为准。关闭或刷新页面不会中断处理。</p>
              </div>
              <div v-else-if="order.status === 'SHIPPED'" class="receipt-action">
                <div>
                  <span>DELIVERY CONFIRMATION</span>
                  <p>收到商品并检查无误后，再确认收货。</p>
                </div>
                <button type="button" :disabled="Boolean(busyAction)" @click="confirmReceipt(order)">
                  {{ busyAction === `receipt-${order.id}` ? '确认中…' : '确认收货' }}
                </button>
              </div>
              <form
                v-else-if="order.status === 'PAID' && !order.afterSale"
                class="refund-request"
                @submit.prevent="requestRefund(order)"
              >
                <label>
                  <span>REFUND BEFORE SHIPMENT</span>
                  <textarea
                    v-model="refundReasons[order.id]"
                    maxlength="500"
                    rows="2"
                    placeholder="填写退款原因"
                    required
                  />
                </label>
                <button type="submit" :disabled="Boolean(busyAction)">
                  {{ busyAction === `refund-${order.id}` ? '提交中…' : '申请退款' }}
                </button>
              </form>
            </article>
          </section>
        </aside>
      </div>
    </Transition>

    <Transition name="toast">
      <p v-if="errorMessage" class="toast error" role="alert">{{ errorMessage }}</p>
      <p v-else-if="notice" class="toast success" role="status">{{ notice }}</p>
    </Transition>
  </main>
</template>
