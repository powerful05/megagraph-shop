<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { AuthMe, Category, MerchantProduct, SalesOrder, SupplyDetails } from '@megagraph/domain-types'
import {
  isAfterSaleBlockingShipment,
  needsMerchantOrderPolling,
  nextOrderPollingDelay,
} from '@megagraph/domain-types'
import { cancelMerchantRequests, merchantApi, type Credentials } from './api'

const isDevelopment = import.meta.env.DEV
const credentials = reactive<Credentials>({
  username: isDevelopment ? 'merchant@megagraph.local' : '',
  password: isDevelopment ? 'merchant123' : '',
  merchantId: 0,
})
const identity = ref<AuthMe | null>(null)
let merchantGeneration = 0
const connected = ref(false)
const merchantRole = ref('')
const busy = ref(false)
const ordersRefreshBusy = ref(false)
const error = ref('')
const pollingMessage = ref('')
const pollingTimedOut = ref(false)
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
const supplyDraft = ref<SupplyDetails | null>(null)
const scenarioText = ref('')

function addSupplyDetails() {
  supplyDraft.value = {
    color: '', fabric: '', fit: '', seasons: [], scenarios: [], measurementNote: '', imageSourceNote: '',
    sizeChart: (activeProduct.value?.skus.length ? activeProduct.value.skus : [{ code: form.code, name: form.name }])
      .map(sku => ({ skuCode: sku.code, sizeLabel: sku.name, chestCm: 0, lengthCm: 0, shoulderCm: null, sleeveCm: null })),
  }
  scenarioText.value = ''
}

let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollStartedAt: number | null = null
let ordersRequest: Promise<SalesOrder[]> | null = null
let orderMutationVersion = 0

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
const hasUnsavedDraft = computed(() => {
  const product = activeProduct.value
  if (!product) return false
  const sku = product.skus[0]
  const normalizedDetails = supplyDraft.value ? { ...supplyDraft.value,
    scenarios: scenarioText.value.split(/[,，]/).map(value => value.trim()).filter(Boolean) } : null
  return form.title !== product.title || form.subtitle !== (product.subtitle ?? '')
    || form.categoryId !== product.categoryId || form.code !== sku?.code || form.name !== sku?.name
    || form.price !== sku?.price || form.stock !== sku?.stock
    || JSON.stringify(normalizedDetails) !== JSON.stringify(product.evidence.details)
})
const canEdit = computed(() => merchantRole.value === 'OWNER' || merchantRole.value === 'EDITOR')
const canConfirm = computed(() => ['OWNER', 'EDITOR', 'PUBLISHER'].includes(merchantRole.value)
  && activeProduct.value?.draftStatus !== 'CONFIRMED' && !hasUnsavedDraft.value)
const canPublish = computed(() => ['OWNER', 'PUBLISHER'].includes(merchantRole.value)
  && activeProduct.value?.draftStatus === 'CONFIRMED'
  && activeProduct.value.publishStatus !== 'PUBLISHED' && !hasUnsavedDraft.value)
const canOffShelf = computed(() => ['OWNER', 'PUBLISHER'].includes(merchantRole.value)
  && activeProduct.value?.publishStatus === 'PUBLISHED')
const readyToShipOrders = computed(() => orders.value.filter(canShipOrder).length)
const inTransitOrders = computed(() => orders.value.filter(order => order.status === 'SHIPPED').length)
const refundReviewOrders = computed(() => orders.value
  .filter(order => order.afterSale?.status === 'REQUESTED'
    || order.afterSale?.status === 'REFUND_PROCESSING'
    || order.afterSale?.status === 'REFUND_FAILED').length)
const hasPollingOrders = computed(() => orders.value.some(needsMerchantOrderPolling))
const revenueStatuses: SalesOrder['status'][] = ['PAID', 'SHIPPED', 'COMPLETED']
const paidOrders = computed(() => orders.value.filter(order => !order.testOrder && revenueStatuses.includes(order.status)).length)
const paidRevenue = computed(() => orders.value
  .filter(order => !order.testOrder && revenueStatuses.includes(order.status))
  .reduce((total, order) => total + order.totalAmount, 0))

async function execute(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (reason) {
    if (!(reason instanceof DOMException && reason.name === 'AbortError')) {
      error.value = reason instanceof Error ? reason.message : '操作失败'
    }
  } finally {
    busy.value = false
  }
}

async function login() {
  await execute(async () => {
    const me = await merchantApi.login(credentials)
    credentials.password = ''
    identity.value = me
    const first = me.memberships[0]
    if (!first) throw new Error('当前账号未加入活跃商家')
    if (me.memberships.length === 1) {
      credentials.merchantId = first.merchantId
      await loadMerchant()
      ElMessage.success('商家工作台已连接')
    }
  })
}

async function loadMerchant() {
    const generation = ++merchantGeneration
    const me = await merchantApi.me(credentials)
    const [categoryList, productList, orderList] = await Promise.all([
      merchantApi.categories(credentials),
      merchantApi.products(credentials),
      merchantApi.orders(credentials),
    ])
    if (generation !== merchantGeneration) return
    merchantRole.value = me.role
    categories.value = categoryList
    products.value = productList
    setOrders(orderList)
    connected.value = true
    schedulePolling(true)
}

async function switchMerchant(event: Event) {
  const target = event.target as HTMLSelectElement
  const selected = Number(target.value)
  if (!identity.value?.memberships.some(item => item.merchantId === selected)) return
  merchantGeneration += 1
  cancelMerchantRequests()
  clearPollTimer()
  orderMutationVersion += 1
  ordersRequest = null
  credentials.merchantId = selected
  categories.value = []
  products.value = []
  orders.value = []
  activeProduct.value = null
  if (storedPreviewUrl.value) URL.revokeObjectURL(storedPreviewUrl.value)
  storedPreviewUrl.value = ''
  await execute(loadMerchant)
}

async function logout() {
  await execute(async () => {
    await merchantApi.logout(credentials)
    merchantGeneration += 1
    cancelMerchantRequests()
    clearPollTimer()
    orderMutationVersion += 1
    ordersRequest = null
    connected.value = false
    identity.value = null
    credentials.merchantId = 0
    credentials.password = ''
    categories.value = []
    products.value = []
    orders.value = []
    activeProduct.value = null
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
  const generation = merchantGeneration
  activeProduct.value = product
  const sku = product.skus[0]
  form.title = product.title ?? ''
  form.subtitle = product.subtitle ?? ''
  form.categoryId = product.categoryId ?? leafCategories.value[0]?.id ?? 0
  form.code = sku?.code ?? `SKU-${product.id}`
  form.name = sku?.name ?? '默认规格'
  form.price = sku?.price ?? 0
  form.stock = sku?.stock ?? 0
  supplyDraft.value = product.evidence.details ? JSON.parse(JSON.stringify(product.evidence.details)) : null
  scenarioText.value = supplyDraft.value?.scenarios.join('，') ?? ''

  if (storedPreviewUrl.value) URL.revokeObjectURL(storedPreviewUrl.value)
  storedPreviewUrl.value = ''
  const image = product.images[0]
  if (image) {
    try {
      const previewUrl = await merchantApi.imageUrl(credentials, image.url)
      if (generation === merchantGeneration) storedPreviewUrl.value = previewUrl
      else URL.revokeObjectURL(previewUrl)
    } catch {
      // 编辑能力不依赖预览成功。
    }
  }
}

async function refreshProducts() {
  const generation = merchantGeneration
  const nextProducts = await merchantApi.products(credentials)
  if (generation === merchantGeneration) products.value = nextProducts
}

function setOrders(nextOrders: SalesOrder[]) {
  orderMutationVersion += 1
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
  orderMutationVersion += 1
  orders.value = orders.value.map(order => order.id === updated.id ? updated : order)
}

function canShipOrder(order: SalesOrder): boolean {
  return canEdit.value && !order.testOrder && order.status === 'PAID' && !isAfterSaleBlockingShipment(order.afterSale?.status)
}

function canRetryRefund(order: SalesOrder): boolean {
  return merchantRole.value === 'OWNER' && order.afterSale?.status === 'REFUND_FAILED'
}

async function shipOrder(order: SalesOrder) {
  if (!canShipOrder(order)) {
    error.value = '当前订单存在退款流程，不能发货'
    return
  }
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
    ElMessage.success(action === 'approve'
      ? (needsMerchantOrderPolling(updated)
          ? '退款请求已提交，正在等待渠道结果'
          : `后端退款状态：${refundStatusLabel(updated)}`)
      : '退款申请已驳回')
    schedulePolling(true)
  })
}

async function retryRefund(order: SalesOrder) {
  if (!canRetryRefund(order)) {
    error.value = '只有 OWNER 可以重试失败退款'
    return
  }
  const note = afterSaleNotes[order.id]?.trim()
    || order.afterSale?.merchantNote?.trim()
    || 'OWNER 显式重试失败退款'
  await execute(async () => {
    const updated = await merchantApi.retryRefund(
      credentials,
      order.id,
      crypto.randomUUID(),
      note,
    )
    replaceOrder(updated)
    ElMessage.success(needsMerchantOrderPolling(updated)
      ? '退款重试已提交，正在等待渠道结果'
      : `后端退款状态：${refundStatusLabel(updated)}`)
    schedulePolling(true)
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
    REQUESTED: '等待审核',
    REFUND_PROCESSING: '退款处理中',
    REFUND_FAILED: '退款失败',
    REFUNDED: '退款成功',
    REJECTED: '已驳回',
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

async function saveDraft() {
  if (!activeProduct.value) return
  await execute(async () => {
    if (supplyDraft.value) supplyDraft.value.scenarios = scenarioText.value.split(/[,，]/).map(value => value.trim()).filter(Boolean)
    const updated = await merchantApi.updateProduct(credentials, activeProduct.value!, form, supplyDraft.value)
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
  const generation = merchantGeneration
  if (!options.background) ordersRefreshBusy.value = true
  ordersRequest = merchantApi.orders(credentials)
  try {
    const nextOrders = await ordersRequest
    if (generation === merchantGeneration && versionAtStart === orderMutationVersion) setOrders(nextOrders)
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

async function manualRefreshOrders() {
  await execute(async () => {
    await refreshOrders({ resetPolling: true })
    ElMessage.success('订单状态已刷新')
  })
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
  if (document.visibilityState !== 'visible') return

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

onMounted(() => {
  merchantApi.onSessionExpired(() => {
    if (!connected.value && !identity.value) return
    merchantGeneration += 1
    cancelMerchantRequests()
    clearPollTimer()
    connected.value = false
    identity.value = null
    products.value = []
    orders.value = []
    activeProduct.value = null
    error.value = '会话已过期，请重新登录'
  })
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('online', handleOnline)
  void merchantApi.authMe().then(async me => {
    identity.value = me
    const first = me.memberships[0]
    if (first && me.memberships.length === 1) {
      credentials.merchantId = first.merchantId
      await loadMerchant()
    }
  }).catch(() => {})
})

onBeforeUnmount(() => {
  merchantApi.onSessionExpired(null)
  clearPollTimer()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('online', handleOnline)
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
        <label v-if="identity && identity.memberships.length > 1">
          商家
          <select :value="credentials.merchantId" @change="switchMerchant">
            <option v-for="membership in identity.memberships" :key="membership.merchantId" :value="membership.merchantId">
              {{ membership.merchantName }}
            </option>
          </select>
        </label>
        <button type="button" @click="logout">退出</button>
      </div>
    </header>

    <section v-if="!connected" class="login-panel glass-panel">
      <div>
        <p class="step">阶段 1 / 商家身份</p>
        <h2>{{ identity && identity.memberships.length > 1 ? '选择当前商家' : '进入商品入库工作台' }}</h2>
        <p class="muted">{{ identity && identity.memberships.length > 1 ? '这个账号属于多个商家，请先明确选择要操作的商家。' : isDevelopment ? '本地开发账号已填入，请使用正式会话登录。' : '请输入由平台受控开通的商家账号。' }}</p>
      </div>
      <label v-if="identity && identity.memberships.length > 1">
        当前商家
        <select :value="credentials.merchantId" @change="switchMerchant">
          <option :value="0" disabled>请选择</option>
          <option v-for="membership in identity.memberships" :key="membership.merchantId" :value="membership.merchantId">
            {{ membership.merchantName }} · {{ membership.role }}
          </option>
        </select>
      </label>
      <el-form v-else label-position="top" @submit.prevent="login">
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
        <button
          type="button"
          :class="{ active: activeArea === 'orders' }"
          @click="activeArea = 'orders'; recoverOrderState()"
        >
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
            <input type="file" :disabled="!canEdit" accept="image/jpeg,image/png,image/webp" @change="selectFile" />
            <img v-if="localPreviewUrl" :src="localPreviewUrl" alt="待上传商品预览" />
            <div v-else>
              <strong>选择一张商品图</strong>
              <span>JPEG / PNG / WebP · 最大 10MB</span>
            </div>
          </label>
          <el-button type="primary" class="full-button" :disabled="!canEdit" :loading="busy" @click="upload">生成 AI 草稿</el-button>

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

                <h3>商品资料与尺码依据</h3>
                <p v-if="activeProduct.evidence.dataOrigin === 'LOCAL_TEST'" class="error-banner">本地测试供给，图片为生成素材，属性和尺码均为测试数据。</p>
                <p v-if="!supplyDraft">尚未填写资料，导购不会推断面料、版型或尺码。</p>
                <el-button v-if="!supplyDraft" :disabled="!canEdit || activeProduct.publishStatus === 'PUBLISHED'" @click="addSupplyDetails">添加商品资料</el-button>
                <template v-if="supplyDraft">
                  <el-form-item label="颜色"><el-input v-model="supplyDraft.color" maxlength="80" /></el-form-item>
                  <el-form-item label="面料与成分"><el-input v-model="supplyDraft.fabric" maxlength="200" /></el-form-item>
                  <el-form-item label="版型"><el-input v-model="supplyDraft.fit" maxlength="80" /></el-form-item>
                  <el-form-item label="季节">
                    <el-select v-model="supplyDraft.seasons" multiple class="full-width">
                      <el-option v-for="season in ['春', '夏', '秋', '冬']" :key="season" :label="season" :value="season" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="适用场景（逗号分隔）"><el-input v-model="scenarioText" maxlength="300" /></el-form-item>
                  <el-form-item label="图片来源声明"><el-input v-model="supplyDraft.imageSourceNote" maxlength="300" /></el-form-item>
                  <el-form-item label="测量方法与误差"><el-input v-model="supplyDraft.measurementNote" maxlength="300" /></el-form-item>
                  <div v-for="(row, rowIndex) in supplyDraft.sizeChart" :key="rowIndex" class="form-grid">
                    <el-form-item label="尺码对应 SKU"><el-input v-model="row.skuCode" /></el-form-item>
                    <el-form-item label="尺码名称"><el-input v-model="row.sizeLabel" /></el-form-item>
                    <el-form-item label="成衣胸围 / cm"><el-input-number v-model="row.chestCm" :min="1" :max="400" :precision="1" class="full-width" /></el-form-item>
                    <el-form-item label="衣长 / cm"><el-input-number v-model="row.lengthCm" :min="1" :max="300" :precision="1" class="full-width" /></el-form-item>
                    <el-form-item label="肩宽 / cm"><el-input-number v-model="row.shoulderCm" :min="1" :max="200" :precision="1" class="full-width" /></el-form-item>
                    <el-form-item label="袖长 / cm"><el-input-number v-model="row.sleeveCm" :min="1" :max="200" :precision="1" class="full-width" /></el-form-item>
                  </div>
                </template>

                <div class="action-row">
                  <el-button :disabled="!canEdit" :loading="busy" @click="saveDraft">保存草稿</el-button>
                  <el-button :disabled="!canConfirm" :loading="busy" @click="confirmDraft">确认当前版本</el-button>
                  <el-button type="primary" :disabled="!canPublish" :loading="busy" @click="publishProduct">发布商品</el-button>
                  <el-button type="danger" plain :disabled="!canOffShelf" :loading="busy" @click="offShelfProduct">下架</el-button>
                </div>
                <p v-if="hasUnsavedDraft">有未保存的修改，请先保存草稿。</p>
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
            <el-button :loading="ordersRefreshBusy || busy" @click="manualRefreshOrders">刷新订单</el-button>
          </header>

          <div class="merchant-polling-bar">
            <span v-if="hasPollingOrders && !pollingTimedOut">正在每 2 秒同步退款状态</span>
            <span v-else-if="pollingTimedOut">退款仍在处理，自动刷新已暂停，请手动刷新</span>
            <span v-else>订单状态来自后端事实库</span>
            <small v-if="pollingMessage">{{ pollingMessage }}</small>
          </div>

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
                  <small v-if="order.testOrder">试用子订单 · 下单组 #{{ order.checkoutGroupId }} · 不发货、不结算</small>
                </div>
                <div class="order-statuses">
                  <span :class="`order-status ${order.status.toLowerCase()}`">{{ orderStatusLabel(order.status) }}</span>
                  <span class="payment-status">PAYMENT / {{ paymentStatusLabel(order) }}</span>
                </div>
              </header>

              <div v-if="order.payment?.reconciliationRequired" class="merchant-reconciliation">
                <strong>支付状态需要人工核查</strong>
                <span>{{ order.payment.reconciliationReason || '渠道事件与订单当前终态冲突，系统未自动覆盖业务事实。' }}</span>
              </div>

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
                    <span>AFTER SALE / {{ afterSaleStatusLabel(order) }}</span>
                    <strong>顾客退款申请</strong>
                  </div>
                  <small>{{ formatDate(order.afterSale.requestedAt) }}</small>
                </header>
                <p>{{ order.afterSale.reason }}</p>
                <form v-if="order.afterSale.status === 'REQUESTED' && merchantRole === 'OWNER'" @submit.prevent>
                  <el-input
                    v-model="afterSaleNotes[order.id]"
                    maxlength="500"
                    placeholder="填写审核说明"
                  />
                  <el-button type="primary" :loading="busy" @click="resolveAfterSale(order, 'approve')">批准并 Mock 退款</el-button>
                  <el-button :loading="busy" @click="resolveAfterSale(order, 'reject')">驳回申请</el-button>
                </form>
                <p v-else-if="order.afterSale.status === 'REQUESTED'" class="owner-only-note">
                  只有 OWNER 可以批准或驳回退款申请。
                </p>
                <div v-else class="after-sale-result">
                  <span>处理说明</span>
                  <strong>{{ order.afterSale.merchantNote }}</strong>
                  <template v-if="order.afterSale.refund">
                    <span>退款状态</span>
                    <strong>{{ formatPrice(order.afterSale.refund.amount, order.afterSale.refund.currency) }} · {{ refundStatusLabel(order) }}</strong>
                  </template>
                  <template v-if="order.afterSale.status === 'REFUND_FAILED'">
                    <span>失败摘要</span>
                    <strong class="refund-failure-copy">{{ order.afterSale.refund?.failureMessage || order.afterSale.refund?.failureCode || '退款未完成，需要 OWNER 重试或人工处理。' }}</strong>
                  </template>
                </div>
                <div v-if="order.afterSale.status === 'REFUND_PROCESSING'" class="refund-processing-note">
                  渠道正在处理退款。订单仍保持已支付，库存不会提前回补，发货已锁定。
                </div>
                <div v-else-if="order.afterSale.status === 'REFUND_FAILED'" class="refund-retry-row">
                  <el-input
                    v-if="merchantRole === 'OWNER'"
                    v-model="afterSaleNotes[order.id]"
                    maxlength="500"
                    placeholder="可补充本次重试说明"
                  />
                  <el-button
                    v-if="canRetryRefund(order)"
                    type="primary"
                    :loading="busy"
                    @click="retryRefund(order)"
                  >
                    显式重试退款
                  </el-button>
                  <span v-else>只有 OWNER 可以重试失败退款。</span>
                </div>
              </div>

              <form
                v-if="canShipOrder(order)"
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
