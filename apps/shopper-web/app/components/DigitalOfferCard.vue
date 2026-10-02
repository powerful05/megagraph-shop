<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import type { DeliveryOffer } from '@megagraph/domain-types'
import { shopperApi } from '../api'
const props = defineProps<{ offer: DeliveryOffer; busy: boolean; accountUserId: number | null }>()
const emit = defineEmits<{ purchase: [offer: DeliveryOffer] }>()
const apiBase = useRuntimeConfig().public.apiBase
const current = ref<DeliveryOffer | null>(null)
const open = ref(false)
const loading = ref(false)
const error = ref('')
const pendingPurchase = ref(false)
let epoch = 0
let control: AbortController | null = null
function reset() { epoch++; control?.abort(); current.value = null; open.value = false; loading.value = false; error.value = ''; pendingPurchase.value = false }
watch(() => props.accountUserId, reset)
watch(() => props.offer.id, reset)
async function view() {
  const version = ++epoch; control?.abort(); control = new AbortController()
  loading.value = true; open.value = true; error.value = ''; current.value = null
  try { const result = await shopperApi.deliveryOffer(apiBase, props.offer.id, control.signal); if (version === epoch) { current.value = result; try { pendingPurchase.value = !!sessionStorage.getItem(`megagraph-digital-buy-${props.accountUserId}-${result.id}`) } catch { /* Optional. */ } } }
  catch (reason) { if (version === epoch && !(reason instanceof DOMException && reason.name === 'AbortError')) error.value = reason instanceof Error ? reason.message : '当前数字供给不可访问' }
  finally { if (version === epoch) loading.value = false }
}
function money(offer: DeliveryOffer) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: offer.currency }).format(offer.price) }
onBeforeUnmount(reset)
</script>
<template>
  <article class="digital-offer-card">
    <small>{{ offer.merchantName }} · 自建测试文件</small><h3>{{ offer.title }}</h3><p>{{ offer.description }}</p><strong>{{ money(offer) }}</strong><p>数字文件 · 支付成功后 {{ offer.availabilityHours }} 小时内领取及下载</p>
    <button type="button" :disabled="busy || loading" :aria-expanded="open" @click="view">{{ loading ? '核对当前供给…' : '查看数字文件详情' }}</button>
    <section v-if="open" class="digital-current" aria-label="当前数字文件详情">
      <p v-if="error" role="alert">{{ error }}</p><template v-if="current"><h4>{{ current.title }}</h4><p>{{ current.description }}</p><p>{{ money(current) }} · 供给 v{{ current.version }} · 文件 v{{ current.assetVersion }}</p><p>{{ current.deliveryTerms }}</p><p>{{ current.refundPolicy }}</p><p>仅用于受控 Demo，不提供真实收费。一次单独购买一个文件。</p><button type="button" :disabled="busy || loading" @click="emit('purchase', current)">{{ accountUserId ? pendingPurchase ? '核对上次数字购买请求' : '单独购买数字测试文件' : '登录后购买数字测试文件' }}</button></template>
      <button type="button" @click="reset">收起详情</button>
    </section>
  </article>
</template>
<style scoped>
.digital-offer-card { margin: 12px 0; padding: 16px; border: 1px solid #dce6df; border-radius: 6px; background: #fbfdfb; overflow-wrap: anywhere; }.digital-offer-card small { color: #52705c; }.digital-offer-card h3, .digital-offer-card h4 { margin: 10px 0; font-size: 16px; }.digital-offer-card p { font-size: 13px; line-height: 1.65; }.digital-offer-card button { min-height: 40px; padding: 9px 12px; margin: 5px 8px 0 0; border: 1px solid #9db9a7; border-radius: 4px; color: #246342; background: #fff; cursor: pointer; }.digital-offer-card button:disabled { opacity: .5; cursor: default; }.digital-current { margin-top: 16px; padding-top: 12px; border-top: 1px solid #dce6df; }.digital-offer-card :focus-visible { outline: 2px solid #267b53; outline-offset: 3px; }
</style>
