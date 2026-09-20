<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { Category, MerchantProduct, SalesOrder } from '@megagraph/domain-types'
import { merchantApi, type Credentials } from './api'

const isDevelopment = import.meta.env.DEV
const credentials = reactive<Credentials>({
  username: isDevelopment ? 'merchant@megagraph.local' : '',
  password: isDevelopment ? 'merchant123' : '',
})
const connected = ref(false)
const merchantRole = ref('')
const busy = ref(false)
const error = ref('')
const categories = ref<Category[]>([])
const products = ref<MerchantProduct[]>([])
const orders = ref<SalesOrder[]>([])
const shipmentDrafts = reactive<Record<number, { carrierName: string; trackingNo: string }>>({})
const afterSaleNotes = reactive<Record<number, string>>({})
const activeArea = ref<'products' | 'orders'>('products')
const activeProduct = ref<MerchantProduct | null>(null)
const selectedFile = ref<File | null>(null)
const localPreviewUrl = ref('')
const storedPreviewUrl = ref('')

const form = reactive({
  title: '',
  subtitle: '',
  categoryId: 0,
  code: '',
  name: '默认规格',
  price: 0,
  stock: 0,
})

const leafCategories = computed(() => categories.value.filter((category) => category.level > 1))
const canConfirm = computed(() => activeProduct.value?.draftStatus !== 'CONFIRMED')
const canPublish = computed(() => activeProduct.value?.draftStatus === 'CONFIRMED'
  && activeProduct.value.publishStatus !== 'PUBLISHED')
const canOffShelf = computed(() => activeProduct.value?.publishStatus === 'PUBLISHED')
const readyToShipOrders = computed(() => orders.value.filter(order => order.status === 'PAID').length)
const inTransitOrders = computed(() => orders.value.filter(order => order.status === 'SHIPPED').length)
const refundReviewOrders = computed(() => orders.value
  .filter(order => order.afterSale?.status === 'REQUESTED').length)
const revenueStatuses: SalesOrder['status'][] = ['PAID', 'SHIPPED', 'COMPLETED']
const paidOrders = computed(() => orders.value.filter(order => revenueStatuses.includes(order.status)).length)
const paidRevenue = computed(() => orders.value
  .filter(order => revenueStatuses.includes(order.status))
  .reduce((total, order) => total + order.totalAmount, 0))

async function execute(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '操作失败'
  } finally {
    busy.value = false
  }
}

async function login() {
  await execute(async () => {
    const me = await merchantApi.me(credentials)
    merchantRole.value = me.role
    const [categoryList, productList, orderList] = await Promise.all([
      merchantApi.categories(credentials),
      merchantApi.products(credentials),
      merchantApi.orders(credentials),
    ])
    categories.value = categoryList
    products.value = productList
    setOrders(orderList)
    connected.value = true
    ElMessage.success('商家工作台已连接')
  })
}

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  if (localPreviewUrl.value) URL.revokeObjectURL(localPreviewUrl.value)
  localPreviewUrl.value = selectedFile.value ? URL.createObjectURL(selectedFile.value) : ''
}

async function upload() {
  if (!selectedFile.value) {
    error.value = '请先选择商品图片'
    return
  }
  await execute(async () => {
    const job = await merchantApi.upload(credentials, selectedFile.value!)
    const product = await merchantApi.product(credentials, job.productId)
    await setActiveProduct(product)
    await refreshProducts()
    ElMessage.success('AI 草稿已生成，请补齐发布信息')
  })
}

async function setActiveProduct(product: MerchantProduct) {
  activeProduct.value = product
  const sku = product.skus[0]
  form.title = product.title ?? ''
  form.subtitle = product.subtitle ?? ''
  form.categoryId = product.categoryId ?? leafCategories.value[0]?.id ?? 0
  form.code = sku?.code ?? `SKU-${product.id}`
  form.name = sku?.name ?? '默认规格'
  form.price = sku?.price ?? 0
  form.stock = sku?.stock ?? 0

  if (storedPreviewUrl.value) URL.revokeObjectURL(storedPreviewUrl.value)
  storedPreviewUrl.value = ''
  const image = product.images[0]
  if (image) {
    try {
      storedPreviewUrl.value = await merchantApi.imageUrl(credentials, image.url)
    } catch {
      // 编辑能力不依赖预览成功。
    }
  }
}

async function refreshProducts() {
  products.value = await merchantApi.products(credentials)
}

async function refreshOrders() {
  await execute(async () => {
    setOrders(await merchantApi.orders(credentials))
    ElMessage.success('订单状态已刷新')
  })
}

function setOrders(nextOrders: SalesOrder[]) {
  orders.value = nextOrders
  nextOrders.forEach((order) => {
    shipmentDrafts[order.id] ??= { carrierName: '顺丰速运', trackingNo: '' }
    afterSaleNotes[order.id] ??= ''
  })
}

function shipmentDraft(orderId: number) {
  return shipmentDrafts[orderId] ??= { carrierName: '顺丰速运', trackingNo: '' }
}

function replaceOrder(updated: SalesOrder) {
  orders.value = orders.value.map(order => order.id === updated.id ? updated : order)
}

async function shipOrder(order: SalesOrder) {
  const details = shipmentDraft(order.id)
  if (!details.carrierName.trim() || !details.trackingNo.trim()) {
    error.value = '请填写承运商和物流单号'
    return
  }
  await execute(async () => {
    const updated = await merchantApi.shipOrder(
      credentials,
      order.id,
      crypto.randomUUID(),
      details,
    )
    replaceOrder(updated)
    ElMessage.success(`订单 ${order.orderNo} 已发货`)
  })
}

async function resolveAfterSale(order: SalesOrder, action: 'approve' | 'reject') {
  const note = afterSaleNotes[order.id]?.trim() ?? ''
  if (!note) {
    error.value = '请填写退款处理说明'
    return
  }
  await execute(async () => {
    const updated = await merchantApi.resolveAfterSale(
      credentials,
      order.id,
      action,
      crypto.randomUUID(),
      note,
    )
    replaceOrder(updated)
    ElMessage.success(action === 'approve' ? '退款已完成，库存已回补' : '退款申请已驳回')
  })
}

function formatPrice(price: number, currency = 'CNY') {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(price)
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function orderStatusLabel(status: SalesOrder['status']) {
  return {
    PENDING_PAYMENT: '等待支付',
    PAID: '支付完成',
    SHIPPED: '运输中',
    COMPLETED: '已完成',
    REFUNDED: '已退款',
    PAYMENT_FAILED: '支付失败',
    CANCELLED: '已取消',
    EXPIRED: '已超时',
  }[status]
}

async function saveDraft() {
  if (!activeProduct.value) return
  await execute(async () => {
    const updated = await merchantApi.updateProduct(credentials, activeProduct.value!, form)
    await setActiveProduct(updated)
    await refreshProducts()
    ElMessage.success('草稿已保存')
  })
}

async function confirmDraft() {
  if (!activeProduct.value) return
  await execute(async () => {
    const updated = await merchantApi.confirm(credentials, activeProduct.value!)
    await setActiveProduct(updated)
    await refreshProducts()
    ElMessage.success('当前版本已确认')
  })
}

async function publishProduct() {
  if (!activeProduct.value) return
  await execute(async () => {
    const updated = await merchantApi.publish(credentials, activeProduct.value!)
    await setActiveProduct(updated)
    await refreshProducts()
    ElMessage.success('商品已发布，用户端现在可以看到')
  })
}

async function offShelfProduct() {
  if (!activeProduct.value) return
  await execute(async () => {
    const updated = await merchantApi.offShelf(credentials, activeProduct.value!)
    await setActiveProduct(updated)
    await refreshProducts()
    ElMessage.success('商品已下架')
  })
}

onBeforeUnmount(() => {
  if (localPreviewUrl.value) URL.revokeObjectURL(localPreviewUrl.value)
  if (storedPreviewUrl.value) URL.revokeObjectURL(storedPreviewUrl.value)
})
</script>

<template>
  <main class="shell">
    <header class="topbar">
      <div>
        <p class="eyebrow">MEGAGRAPH / MERCHANT STUDIO</p>
        <h1>从一张图片，到可发布商品。</h1>
      </div>
      <div v-if="connected" class="session-pill">
        <span class="signal" /> 已连接 · {{ merchantRole }}
      </div>
    </header>

    <section v-if="!connected" class="login-panel glass-panel">
      <div>
        <p class="step">阶段 1 / 商家身份</p>
        <h2>进入商品入库工作台</h2>
        <p class="muted">{{ isDevelopment ? '本地开发账号已填入。' : '请输入你的商家账号。' }}</p>
      </div>
      <el-form label-position="top" @submit.prevent="login">
        <el-form-item label="账号">
          <el-input v-model="credentials.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="credentials.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy">连接工作台</el-button>
      </el-form>
    </section>

    <template v-else>
      <nav class="studio-tabs" aria-label="工作台区域">
        <button type="button" :class="{ active: activeArea === 'products' }" @click="activeArea = 'products'">
          商品入库 <span>{{ products.length }}</span>
        </button>
        <button type="button" :class="{ active: activeArea === 'orders' }" @click="activeArea = 'orders'">
          交易订单 <span>{{ orders.length }}</span>
        </button>
      </nav>

      <section v-if="activeArea === 'products'" class="workspace-grid">
        <aside class="glass-panel product-rail">
          <div class="section-heading">
            <div>
              <p class="step">01 / 上传</p>
              <h2>商品源图片</h2>
            </div>
            <span>{{ products.length }} 件</span>
          </div>

          <label class="dropzone">
            <input type="file" accept="image/jpeg,image/png,image/webp" @change="selectFile" />
            <img v-if="localPreviewUrl" :src="localPreviewUrl" alt="待上传商品预览" />
            <div v-else>
              <strong>选择一张商品图</strong>
              <span>JPEG / PNG / WebP · 最大 10MB</span>
            </div>
          </label>
          <el-button type="primary" class="full-button" :loading="busy" @click="upload">生成 AI 草稿</el-button>

          <div class="product-list">
            <button
              v-for="product in products"
              :key="product.id"
              class="product-list-item"
              :class="{ active: activeProduct?.id === product.id }"
              type="button"
              @click="setActiveProduct(product)"
            >
              <span>#{{ product.id }}</span>
              <strong>{{ product.title }}</strong>
              <small>{{ product.publishStatus }}</small>
            </button>
          </div>
        </aside>

        <section class="glass-panel editor-panel">
          <div v-if="!activeProduct" class="empty-state">
            <span class="orb" />
            <h2>上传第一张商品图片</h2>
            <p>系统会创建可编辑草稿。价格、库存和类目必须由你确认。</p>
          </div>

          <template v-else>
            <div class="section-heading">
              <div>
                <p class="step">02 / 确认</p>
                <h2>最短确认页</h2>
              </div>
              <div class="status-stack">
                <el-tag effect="dark">{{ activeProduct.draftStatus }}</el-tag>
                <el-tag :type="activeProduct.publishStatus === 'PUBLISHED' ? 'success' : 'info'">
                  {{ activeProduct.publishStatus }}
                </el-tag>
              </div>
            </div>

            <div class="draft-layout">
              <div class="media-frame">
                <img v-if="storedPreviewUrl || localPreviewUrl" :src="storedPreviewUrl || localPreviewUrl" alt="商品主图" />
                <div v-else class="media-placeholder">IMAGE</div>
                <span>MAIN IMAGE</span>
              </div>

              <el-form class="draft-form" label-position="top">
                <el-form-item label="商品标题（必填）">
                  <el-input v-model="form.title" maxlength="200" show-word-limit />
                </el-form-item>
                <el-form-item label="副标题">
                  <el-input v-model="form.subtitle" maxlength="300" />
                </el-form-item>
                <el-form-item label="末级类目（必填）">
                  <el-select v-model="form.categoryId" placeholder="选择类目" class="full-width">
                    <el-option v-for="category in leafCategories" :key="category.id" :label="category.name" :value="category.id" />
                  </el-select>
                </el-form-item>

                <div class="form-grid">
                  <el-form-item label="SKU 编码">
                    <el-input v-model="form.code" />
                  </el-form-item>
                  <el-form-item label="规格名称">
                    <el-input v-model="form.name" />
                  </el-form-item>
                  <el-form-item label="价格 / CNY">
                    <el-input-number v-model="form.price" :min="0" :precision="2" class="full-width" />
                  </el-form-item>
                  <el-form-item label="可售库存">
                    <el-input-number v-model="form.stock" :min="0" :precision="0" class="full-width" />
                  </el-form-item>
                </div>

                <div class="action-row">
                  <el-button :loading="busy" @click="saveDraft">保存草稿</el-button>
                  <el-button :disabled="!canConfirm" :loading="busy" @click="confirmDraft">确认当前版本</el-button>
                  <el-button type="primary" :disabled="!canPublish" :loading="busy" @click="publishProduct">发布商品</el-button>
                  <el-button type="danger" plain :disabled="!canOffShelf" :loading="busy" @click="offShelfProduct">下架</el-button>
                </div>
              </el-form>
            </div>
          </template>
        </section>
      </section>

      <section v-else class="orders-workspace">
        <div class="order-metrics">
          <article class="glass-panel metric-card">
            <span>ALL ORDERS</span>
            <strong>{{ orders.length }}</strong>
            <p>当前商家全部交易记录</p>
          </article>
          <article class="glass-panel metric-card attention">
            <span>READY TO SHIP</span>
            <strong>{{ readyToShipOrders }}</strong>
            <p>已支付、等待商家发货</p>
          </article>
          <article class="glass-panel metric-card transit">
            <span>IN TRANSIT</span>
            <strong>{{ inTransitOrders }}</strong>
            <p>等待顾客确认收货</p>
          </article>
          <article class="glass-panel metric-card refund">
            <span>REFUND REVIEW</span>
            <strong>{{ refundReviewOrders }}</strong>
            <p>等待审核的未发货退款</p>
          </article>
          <article class="glass-panel metric-card revenue">
            <span>PAID REVENUE</span>
            <strong>{{ formatPrice(paidRevenue) }}</strong>
            <p>{{ paidOrders }} 笔已支付订单</p>
          </article>
        </div>

        <section class="glass-panel order-ledger">
          <header class="ledger-header">
            <div>
              <p class="step">03 / 交易</p>
              <h2>订单事实台账</h2>
              <p>订单金额、支付状态与库存预占均来自后端事实库。</p>
            </div>
            <el-button :loading="busy" @click="refreshOrders">刷新订单</el-button>
          </header>

          <div v-if="orders.length === 0" class="orders-empty">
            <span>00</span>
            <h3>还没有交易订单</h3>
            <p>顾客完成结算后，属于当前商家的订单会显示在这里。</p>
          </div>

          <div v-else class="order-list">
            <article v-for="order in orders" :key="order.id" class="merchant-order-card">
              <header>
                <div class="order-identity">
                  <span>#{{ order.id }}</span>
                  <strong>{{ order.orderNo }}</strong>
                  <small>{{ formatDate(order.createdAt) }}</small>
                </div>
                <div class="order-statuses">
                  <span :class="`order-status ${order.status.toLowerCase()}`">{{ orderStatusLabel(order.status) }}</span>
                  <span class="payment-status">PAYMENT / {{ order.payment?.status ?? '—' }}</span>
                </div>
              </header>

              <div class="merchant-order-body">
                <div class="ordered-products">
                  <p v-for="item in order.items" :key="item.skuId">
                    <span>
                      <strong>{{ item.productTitle }}</strong>
                      <small>{{ item.skuCode }} · {{ item.skuName }}</small>
                    </span>
                    <span>× {{ item.quantity }}</span>
                    <strong>{{ formatPrice(item.lineAmount, item.currency) }}</strong>
                  </p>
                </div>
                <dl class="delivery-facts">
                  <div><dt>收件人</dt><dd>{{ order.recipientName }}</dd></div>
                  <div><dt>电话</dt><dd>{{ order.recipientPhone }}</dd></div>
                  <div><dt>地址</dt><dd>{{ order.shippingAddress }}</dd></div>
                </dl>
              </div>

              <div v-if="order.shipment" class="shipment-facts">
                <div>
                  <span>FULFILLMENT / {{ order.shipment.status }}</span>
                  <strong>{{ order.shipment.carrierName }}</strong>
                </div>
                <div>
                  <span>物流单号</span>
                  <strong>{{ order.shipment.trackingNo }}</strong>
                </div>
                <small v-if="order.shipment.deliveredAt">签收时间 {{ formatDate(order.shipment.deliveredAt) }}</small>
                <small v-else>发货时间 {{ formatDate(order.shipment.shippedAt) }}</small>
              </div>

              <div v-if="order.afterSale" :class="`after-sale-card ${order.afterSale.status.toLowerCase()}`">
                <header>
                  <div>
                    <span>AFTER SALE / {{ order.afterSale.status }}</span>
                    <strong>顾客退款申请</strong>
                  </div>
                  <small>{{ formatDate(order.afterSale.requestedAt) }}</small>
                </header>
                <p>{{ order.afterSale.reason }}</p>
                <form v-if="order.afterSale.status === 'REQUESTED'" @submit.prevent>
                  <el-input
                    v-model="afterSaleNotes[order.id]"
                    maxlength="500"
                    placeholder="填写审核说明"
                  />
                  <el-button type="primary" :loading="busy" @click="resolveAfterSale(order, 'approve')">批准并 Mock 退款</el-button>
                  <el-button :loading="busy" @click="resolveAfterSale(order, 'reject')">驳回申请</el-button>
                </form>
                <div v-else class="after-sale-result">
                  <span>处理说明</span>
                  <strong>{{ order.afterSale.merchantNote }}</strong>
                  <template v-if="order.afterSale.refund">
                    <span>退款结果</span>
                    <strong>{{ formatPrice(order.afterSale.refund.amount, order.afterSale.refund.currency) }} · {{ order.afterSale.refund.status }}</strong>
                  </template>
                </div>
              </div>

              <form
                v-if="order.status === 'PAID' && order.afterSale?.status !== 'REQUESTED'"
                class="shipment-form"
                @submit.prevent="shipOrder(order)"
              >
                <label>
                  <span>承运商</span>
                  <el-input v-model="shipmentDraft(order.id).carrierName" maxlength="80" />
                </label>
                <label>
                  <span>物流单号</span>
                  <el-input v-model="shipmentDraft(order.id).trackingNo" maxlength="80" placeholder="输入可追踪的单号" />
                </label>
                <el-button type="primary" native-type="submit" :loading="busy">确认发货</el-button>
              </form>

              <footer>
                <div class="reservation-facts">
                  <span v-for="reservation in order.reservations" :key="reservation.skuId">
                    SKU {{ reservation.skuId }} / {{ reservation.quantity }} 件 / {{ reservation.status }}
                  </span>
                </div>
                <div class="merchant-order-total">
                  <span>订单金额</span>
                  <strong>{{ formatPrice(order.totalAmount, order.currency) }}</strong>
                </div>
              </footer>
            </article>
          </div>
        </section>
      </section>
    </template>

    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  </main>
</template>
