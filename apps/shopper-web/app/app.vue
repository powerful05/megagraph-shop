<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type {
  MockPaymentResult,
  PublicProduct,
  PublicSku,
  SalesOrder,
  ShoppingCart,
} from '@megagraph/domain-types'
import { shopperApi, type ShopperCredentials } from './api'

type CommercePanel = 'cart' | 'checkout' | 'orders'

const config = useRuntimeConfig()
const apiBase = config.public.apiBase
const isDevelopment = import.meta.dev
const { data: products, pending, error: catalogError, refresh } = await useFetch<PublicProduct[]>(
  `${apiBase}/api/shop/v1/products`,
  { default: () => [] },
)

const credentials = reactive<ShopperCredentials>({
  username: isDevelopment ? 'shopper@megagraph.local' : '',
  password: isDevelopment ? 'shopper123' : '',
})
const checkout = reactive({
  recipientName: isDevelopment ? '示例顾客' : '',
  recipientPhone: isDevelopment ? '13800138000' : '',
  shippingAddress: isDevelopment ? '深圳市盐田区海景路 1 号' : '',
})
const connected = ref(false)
const loginOpen = ref(false)
const activePanel = ref<CommercePanel | null>(null)
const busyAction = ref('')
const errorMessage = ref('')
const notice = ref('')
const cart = ref<ShoppingCart>({ cartId: 0, currency: null, estimatedTotal: 0, items: [] })
const orders = ref<SalesOrder[]>([])
const selectedSkuByProduct = reactive<Record<number, number>>({})

const cartCount = computed(() => cart.value.items.reduce((total, item) => total + item.quantity, 0))
const pendingOrderCount = computed(() => orders.value.filter(order => order.status === 'PENDING_PAYMENT').length)

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
    PAID: '支付完成',
    PAYMENT_FAILED: '支付失败',
    CANCELLED: '已取消',
    EXPIRED: '已超时',
  }[status]
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
    errorMessage.value = reason instanceof Error ? reason.message : '操作失败，请稍后重试'
  } finally {
    busyAction.value = ''
  }
}

async function connectShopper() {
  await execute('login', async () => {
    const [nextCart, nextOrders] = await Promise.all([
      shopperApi.cart(apiBase, credentials),
      shopperApi.orders(apiBase, credentials),
    ])
    cart.value = nextCart
    orders.value = nextOrders
    connected.value = true
    loginOpen.value = false
    activePanel.value = nextCart.items.length > 0 ? 'cart' : null
    notice.value = '顾客账户已连接'
  })
}

function openPanel(panel: CommercePanel) {
  if (!connected.value) {
    loginOpen.value = true
    return
  }
  activePanel.value = panel
}

async function addToCart(product: PublicProduct) {
  if (!connected.value) {
    loginOpen.value = true
    return
  }
  const sku = chosenSku(product)
  if (!sku) return
  const existing = cart.value.items.find(item => item.skuId === sku.id)
  const nextQuantity = (existing?.quantity ?? 0) + 1
  await execute(`add-${sku.id}`, async () => {
    cart.value = await shopperApi.setCartItem(apiBase, credentials, sku.id, nextQuantity)
    activePanel.value = 'cart'
    notice.value = `${product.title} 已加入购物车`
  })
}

async function changeQuantity(skuId: number, quantity: number) {
  if (quantity < 1) return removeCartItem(skuId)
  await execute(`quantity-${skuId}`, async () => {
    cart.value = await shopperApi.setCartItem(apiBase, credentials, skuId, quantity)
  })
}

async function removeCartItem(skuId: number) {
  await execute(`remove-${skuId}`, async () => {
    cart.value = await shopperApi.removeCartItem(apiBase, credentials, skuId)
  })
}

async function createOrder() {
  await execute('checkout', async () => {
    const order = await shopperApi.createOrder(
      apiBase,
      credentials,
      crypto.randomUUID(),
      checkout,
    )
    orders.value = [order, ...orders.value.filter(item => item.id !== order.id)]
    cart.value = await shopperApi.cart(apiBase, credentials)
    activePanel.value = 'orders'
    notice.value = `订单 ${order.orderNo} 已创建，库存已预占`
    await refresh()
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
    replaceOrder(updated)
    notice.value = result === 'SUCCESS' ? 'Mock 支付成功，订单已确认' : 'Mock 支付已结束，预占库存已释放'
    await refresh()
  })
}

async function cancelOrder(order: SalesOrder) {
  await execute(`cancel-${order.id}`, async () => {
    const updated = await shopperApi.cancelOrder(apiBase, credentials, order.id)
    replaceOrder(updated)
    notice.value = '订单已取消，预占库存已释放'
    await refresh()
  })
}

function replaceOrder(updated: SalesOrder) {
  orders.value = orders.value.map(order => order.id === updated.id ? updated : order)
}
</script>

<template>
  <main>
    <nav class="nav-shell">
      <a class="brand" href="#top" aria-label="MegaGraph Shop 首页">
        <span class="brand-mark" />
        MEGAGRAPH
      </a>
      <div class="nav-links">
        <a href="#collection">新品</a>
        <a href="#principle">原则</a>
        <button type="button" @click="openPanel('orders')">
          订单<span v-if="pendingOrderCount">{{ pendingOrderCount }}</span>
        </button>
        <button class="cart-trigger" type="button" @click="openPanel('cart')">
          购物袋<span>{{ cartCount }}</span>
        </button>
      </div>
    </nav>

    <section id="top" class="hero">
      <div class="hero-copy">
        <p class="eyebrow">AI-NATIVE PRODUCT DISCOVERY</p>
        <h1>商品不只被搜索。<br><em>它被理解。</em></h1>
        <p class="lede">从商品事实出发，完成一次真实可验证的选择。现在，你可以从已发布商品进入购物车、结算与本地 Mock 支付。</p>
        <div class="hero-actions">
          <a class="primary-link" href="#collection">开始选购 <span>↘</span></a>
          <button type="button" @click="connected ? openPanel('orders') : (loginOpen = true)">
            {{ connected ? '查看我的订单' : '连接顾客账户' }}
          </button>
        </div>
      </div>
      <div class="hero-object" aria-hidden="true">
        <div class="orb orb-a" />
        <div class="orb orb-b" />
        <div class="wireframe" />
        <span class="object-label">COMMERCE OBJECT / 002</span>
      </div>
    </section>

    <section id="collection" class="collection-section">
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
        </article>
      </div>
    </section>

    <section id="principle" class="principle-section">
      <p class="eyebrow">SYSTEM PRINCIPLE</p>
      <p class="manifesto">AI 可以组织语言，<br>但价格、库存与订单状态<br><em>只相信交易事实。</em></p>
      <div class="principle-grid">
        <span>01 / VERIFIED CATALOG</span>
        <span>02 / SERVER-SIDE PRICING</span>
        <span>03 / RESERVED INVENTORY</span>
      </div>
    </section>

    <Transition name="fade">
      <div v-if="loginOpen" class="modal-layer" @click.self="loginOpen = false">
        <section class="login-card" role="dialog" aria-modal="true" aria-labelledby="shopper-login-title">
          <button class="close-button" type="button" aria-label="关闭" @click="loginOpen = false">×</button>
          <p class="eyebrow">SHOPPER SESSION</p>
          <h2 id="shopper-login-title">连接你的购物会话</h2>
          <p>本地 Basic Auth 只用于验证阶段 2A 交易闭环，后续会替换为正式登录会话。</p>
          <form @submit.prevent="connectShopper">
            <label>账号<input v-model="credentials.username" autocomplete="username" /></label>
            <label>密码<input v-model="credentials.password" type="password" autocomplete="current-password" /></label>
            <button type="submit" :disabled="busyAction === 'login'">
              {{ busyAction === 'login' ? '连接中…' : '连接并读取购物袋' }}
            </button>
          </form>
        </section>
      </div>
    </Transition>

    <Transition name="drawer">
      <div v-if="activePanel" class="drawer-layer" @click.self="activePanel = null">
        <aside class="commerce-drawer">
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
              <p>从当前可售商品中选择一件，系统会在结算时重新验证价格与库存。</p>
            </div>
            <template v-else>
              <article v-for="item in cart.items" :key="item.skuId" class="cart-line">
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
              <footer class="cart-summary">
                <div><span>预计合计</span><strong>{{ formatPrice(cart.estimatedTotal, cart.currency ?? 'CNY') }}</strong></div>
                <p>提交订单时，后端会重新读取价格和库存；这里的金额不是交易事实。</p>
                <button type="button" @click="activePanel = 'checkout'">进入结算 <span>→</span></button>
              </footer>
            </template>
          </section>

          <section v-else-if="activePanel === 'checkout'" class="drawer-content checkout-content">
            <button class="back-button" type="button" @click="activePanel = 'cart'">← 返回购物袋</button>
            <div class="checkout-total">
              <span>{{ cartCount }} 件商品</span>
              <strong>{{ formatPrice(cart.estimatedTotal, cart.currency ?? 'CNY') }}</strong>
            </div>
            <form @submit.prevent="createOrder">
              <label>收件人<input v-model="checkout.recipientName" required maxlength="80" /></label>
              <label>联系电话<input v-model="checkout.recipientPhone" required maxlength="32" /></label>
              <label>收货地址<textarea v-model="checkout.shippingAddress" required maxlength="500" rows="4" /></label>
              <div class="checkout-note">
                <span>MOCK PAYMENT</span>
                <p>此阶段不会调用真实支付渠道。订单创建后库存会被预占，你可以模拟成功、失败或取消。</p>
              </div>
              <button type="submit" :disabled="busyAction === 'checkout' || cart.items.length === 0">
                {{ busyAction === 'checkout' ? '正在锁定库存…' : '创建订单并预占库存' }}
              </button>
            </form>
          </section>

          <section v-else class="drawer-content orders-content">
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
                </div>
                <strong :class="`status-${order.status.toLowerCase()}`">{{ orderStatusLabel(order.status) }}</strong>
              </header>
              <div class="order-items">
                <p v-for="item in order.items" :key="item.skuId">
                  <span>{{ item.productTitle }} · {{ item.skuName }} × {{ item.quantity }}</span>
                  <strong>{{ formatPrice(item.lineAmount, item.currency) }}</strong>
                </p>
              </div>
              <div class="order-facts">
                <span>支付 / {{ order.payment?.status ?? '—' }}</span>
                <span>库存 / {{ reservationSummary(order) }}</span>
              </div>
              <div class="order-total">
                <span>订单合计</span>
                <strong>{{ formatPrice(order.totalAmount, order.currency) }}</strong>
              </div>
              <div v-if="order.status === 'PENDING_PAYMENT'" class="payment-simulator">
                <p>开发支付模拟器</p>
                <div>
                  <button type="button" :disabled="Boolean(busyAction)" @click="applyPayment(order, 'SUCCESS')">模拟成功</button>
                  <button type="button" :disabled="Boolean(busyAction)" @click="applyPayment(order, 'FAILURE')">模拟失败</button>
                  <button type="button" :disabled="Boolean(busyAction)" @click="cancelOrder(order)">取消订单</button>
                </div>
              </div>
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
