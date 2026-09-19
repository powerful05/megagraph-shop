<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { Category, MerchantProduct } from '@megagraph/domain-types'
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
    const [categoryList, productList] = await Promise.all([
      merchantApi.categories(credentials),
      merchantApi.products(credentials),
    ])
    categories.value = categoryList
    products.value = productList
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
      <section class="workspace-grid">
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
    </template>

    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  </main>
</template>
