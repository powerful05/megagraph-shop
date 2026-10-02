<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowUpRight, RefreshCw, X } from 'lucide-vue-next'
import type { PublicProduct } from '@megagraph/domain-types'
import { shopperApi } from '../api'
import ProductImage from './ProductImage.vue'

const props = defineProps<{ products: PublicProduct[] }>()
const emit = defineEmits<{ close: []; details: [product: PublicProduct] }>()
const apiBase = useRuntimeConfig().public.apiBase
const dialog = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const loading = ref(false)
const entries = ref(props.products.slice(0, 3).map(product => ({ id: product.id, title: product.title,
  beforeVersion: product.evidence.productVersion, beforePrice: product.priceFrom,
  product: null as PublicProduct | null, error: '' })))
let request: AbortController | null = null

async function refresh() {
  request?.abort()
  const current = new AbortController()
  request = current
  loading.value = true
  for (const entry of entries.value) { entry.product = null; entry.error = '' }
  await Promise.all(entries.value.map(async entry => {
    try {
      const product = await shopperApi.product(apiBase, entry.id, current.signal)
      if (!current.signal.aborted) entry.product = product
    } catch (error) {
      if (!current.signal.aborted) entry.error = error instanceof Error ? error.message : '商品当前不可用'
    }
  }))
  if (!current.signal.aborted) loading.value = false
}

function handleKey(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); emit('close') }
  if (event.key !== 'Tab') return
  const controls = Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), summary, [tabindex="0"]') ?? [])
    .filter(control => control.getClientRects().length > 0)
  const first = controls[0]
  const last = controls.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}

onMounted(() => { closeButton.value?.focus(); void refresh() })
onBeforeUnmount(() => request?.abort())
</script>

<template>
  <div class="comparison-layer" @click.self="emit('close')">
    <section ref="dialog" class="comparison-dialog" role="dialog" aria-modal="true" aria-label="商品比较" @keydown="handleKey">
      <header class="comparison-header">
        <div><h2>商品比较</h2><p>当前资料 · 最多三件</p></div>
        <div class="comparison-tools">
          <button type="button" aria-label="重新核对比较商品" title="重新核对比较商品" :disabled="loading" @click="refresh"><RefreshCw :size="18" /></button>
          <button ref="closeButton" type="button" aria-label="关闭商品比较" title="关闭商品比较" @click="emit('close')"><X :size="20" /></button>
        </div>
      </header>
      <p v-if="loading" class="comparison-status" role="status">正在读取当前资料、价格和可售规格…</p>
      <div class="comparison-scroll" tabindex="0" aria-label="并列商品资料">
        <div class="comparison-columns" :style="{ '--columns': entries.length }">
          <section v-for="entry in entries" :key="entry.id" class="comparison-product" :aria-label="entry.title">
            <h3>{{ entry.product?.title ?? entry.title }}</h3>
            <p v-if="entry.error" class="comparison-error" role="alert">{{ entry.error }}。不能沿用历史价格或库存进行交易。</p>
            <template v-if="entry.product">
              <ProductImage class="comparison-image" :src="entry.product.mainImageUrl ? `${apiBase}${entry.product.mainImageUrl}` : null" :alt="entry.product.title" contain />
              <p class="comparison-price">{{ new Intl.NumberFormat('zh-CN', { style: 'currency', currency: entry.product.currency }).format(entry.product.priceFrom) }} 起</p>
              <p v-if="entry.product.evidence.productVersion !== entry.beforeVersion || entry.product.priceFrom !== entry.beforePrice" class="comparison-changed">与历史推荐相比，资料或起价已更新。</p>
              <ProductSupplyEvidence :product="entry.product" />
              <button class="comparison-details" type="button" @click="emit('details', entry.product)">查看当前详情 <ArrowUpRight :size="16" /></button>
            </template>
          </section>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.comparison-layer { position: fixed; z-index: 45; inset: 0; display: grid; place-items: center; padding: 24px; background: #16231b72; }
.comparison-dialog { width: min(1100px, 100%); max-height: calc(100svh - 48px); min-width: 0; display: flex; flex-direction: column; background: #fff; border-radius: 8px; overflow: hidden; }
.comparison-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 18px 22px; border-bottom: 1px solid #dce4df; }
.comparison-header h2 { margin: 0; font-size: 19px; }
.comparison-header p { margin: 5px 0 0; color: #69756e; font-size: 12px; }
.comparison-tools { display: flex; gap: 4px; flex-shrink: 0; }
.comparison-tools button { display: grid; place-items: center; width: 36px; height: 36px; border: 0; background: #f3f6f4; border-radius: 4px; cursor: pointer; }
.comparison-status { margin: 0; padding: 12px 22px; color: #69756e; font-size: 13px; }
.comparison-scroll { overflow: auto; min-height: 0; overscroll-behavior: contain; }
.comparison-columns { display: grid; grid-template-columns: repeat(var(--columns), minmax(240px, 1fr)); }
.comparison-product { min-width: 0; padding: 18px 22px 24px; border-right: 1px solid #dce4df; overflow-wrap: anywhere; }
.comparison-product:last-child { border-right: 0; }
.comparison-product h3 { min-height: 40px; margin: 0 0 12px; font-size: 15px; line-height: 1.4; }
.comparison-image { height: 190px; aspect-ratio: auto; }
.comparison-price { margin: 12px 0; font-size: 18px; font-weight: 650; }
.comparison-error { color: #a83c3c; font-size: 13px; line-height: 1.7; }
.comparison-changed { color: #916222; font-size: 12px; }
.comparison-details { display: flex; justify-content: center; align-items: center; gap: 6px; width: 100%; min-height: 40px; margin-top: 18px; border: 1px solid #cdd8d0; border-radius: 4px; color: #267b53; background: #fff; cursor: pointer; }
@media (max-width: 760px) { .comparison-layer { padding: 12px; } .comparison-dialog { max-height: calc(100svh - 24px); } .comparison-columns { grid-template-columns: repeat(var(--columns), minmax(240px, 1fr)); } }
</style>
