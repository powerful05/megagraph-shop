<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { DeliveryOffer } from '@megagraph/domain-types'
import { merchantApi, type Credentials } from './api'
const props = defineProps<{ credentials: Credentials; userId: number; role: string }>()
const offers = ref<DeliveryOffer[]>([])
const selected = ref<DeliveryOffer | null>(null)
const form = reactive({ title: '测试数字文件', description: '商家自建测试文件，仅用于受控 Demo。', price: 9.9 })
const error = ref('')
const notice = ref('')
const busy = ref(false)
let epoch = 0
let request: AbortController | null = null
let createKey: string | null = null
const storageKey = `megagraph-digital-offer-${props.userId}-${props.credentials.merchantId}`
const canEdit = computed(() => ['OWNER', 'EDITOR'].includes(props.role))
const canPublish = computed(() => ['OWNER', 'PUBLISHER'].includes(props.role))
const dirty = computed(() => selected.value !== null && (form.title !== selected.value.title || form.description !== selected.value.description || form.price !== selected.value.price))
watch(() => JSON.stringify(form), () => { createKey = null })
function choose(offer: DeliveryOffer | null) {
  selected.value = offer
  Object.assign(form, offer ? { title: offer.title, description: offer.description, price: offer.price } : { title: '测试数字文件', description: '商家自建测试文件，仅用于受控 Demo。', price: 9.9 })
  try { if (offer) sessionStorage.setItem(storageKey, String(offer.id)); else sessionStorage.removeItem(storageKey) } catch { /* Optional UI memory. */ }
  error.value = ''; notice.value = ''
}
async function refresh() {
  if (busy.value) return
  const version = ++epoch
  request?.abort(); request = new AbortController()
  busy.value = true; error.value = ''
  try {
    const next = await merchantApi.deliveryOffers(props.credentials, request.signal)
    if (version !== epoch) return
    offers.value = next
    let remembered = selected.value?.id
    if (!remembered) { try { remembered = Number(sessionStorage.getItem(storageKey)) || undefined } catch { /* Optional. */ } }
    const current = next.find(item => item.id === remembered)
    // A refresh never overwrites unsaved merchant input.
    if (current && !dirty.value) choose(current)
    else if (selected.value && !current) { selected.value = null; error.value = '当前供给已不可访问，请重新选择。' }
  } catch (reason) { if (version === epoch && !(reason instanceof DOMException && reason.name === 'AbortError')) error.value = reason instanceof Error ? reason.message : '供给列表加载失败' }
  finally { if (version === epoch) busy.value = false }
}
async function save() {
  if (busy.value || !canEdit.value) return
  if (!form.title.includes('测试') || !form.description.trim() || !Number.isFinite(form.price) || form.price <= 0) { error.value = '标题须含“测试”，请填写自建文件说明和正数价格。'; return }
  await mutate(async () => {
    const payload = { title: form.title.trim(), description: form.description.trim(), price: form.price }
    createKey ??= crypto.randomUUID()
    return selected.value ? merchantApi.updateDeliveryOffer(props.credentials, selected.value, payload) : merchantApi.createDeliveryOffer(props.credentials, payload, createKey)
  }, selected.value ? '已保存；新文件版本需要重新确认。' : '已创建商家专属测试文件草稿。')
}
async function action(kind: 'confirm' | 'publish' | 'off-shelf') {
  const offer = selected.value
  if (!offer || dirty.value || busy.value) return
  await mutate(() => merchantApi.mutateDeliveryOffer(props.credentials, offer, kind), { confirm: '当前版本已确认，可显式发布。', publish: '测试数字文件已发布。', 'off-shelf': '已下架；历史订单保留原文件快照。' }[kind])
}
async function mutate(task: () => Promise<DeliveryOffer>, message: string) {
  const version = ++epoch; busy.value = true; error.value = ''; notice.value = ''
  try {
    const result = await task()
    if (version !== epoch) return
    offers.value = [result, ...offers.value.filter(item => item.id !== result.id)]
    choose(result); createKey = null; notice.value = message
  } catch (reason) { if (version === epoch && !(reason instanceof DOMException && reason.name === 'AbortError')) error.value = reason instanceof Error ? reason.message : '供给操作失败；可保留同一创建请求重试。' }
  finally { if (version === epoch) busy.value = false }
}
onMounted(() => { void refresh() })
onBeforeUnmount(() => { epoch++; request?.abort() })
</script>
<template>
  <section class="digital-workspace glass-panel" aria-labelledby="digital-workspace-title">
    <header><div><p class="step">DIGITAL FILE / 受控 Demo</p><h2 id="digital-workspace-title">数字测试文件</h2><p>自建文本文件 · 支付成功后 24 小时内领取和下载 · 仅未领取可申请退款，需商家审批。</p></div><button type="button" :disabled="busy" @click="refresh">刷新供给</button></header>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div class="digital-columns"><nav aria-label="数字文件供给"><button type="button" :disabled="busy || !canEdit" @click="choose(null)">新建测试文件</button><button v-for="offer in offers" :key="offer.id" type="button" :disabled="busy" :aria-pressed="selected?.id === offer.id" @click="choose(offer)"><strong>#{{ offer.id }} {{ offer.title }}</strong><span>{{ { DRAFT: '草稿', PUBLISHED: '已发布', OFF_SHELF: '已下架' }[offer.status] }} · ¥{{ offer.price.toFixed(2) }} · v{{ offer.version }}</span></button></nav>
      <form @submit.prevent="save"><label>测试文件标题<input v-model="form.title" required maxlength="200" :disabled="busy || !canEdit" /></label><label>自建文件说明<textarea v-model="form.description" required maxlength="2000" rows="3" :disabled="busy || !canEdit" /></label><label>数字文件价格（CNY）<input v-model.number="form.price" type="number" min="0.01" step="0.01" required :disabled="busy || !canEdit" /></label>
        <template v-if="selected"><p>供给 v{{ selected.version }} · 文件 v{{ selected.assetVersion }} · {{ selected.confirmedVersion === selected.version ? '当前版本已确认' : '当前版本待确认' }}<span v-if="dirty"> · 有未保存修改</span></p><p>{{ selected.deliveryTerms }}</p><p>{{ selected.refundPolicy }}</p><details><summary>测试文件版本与校验值</summary><p>SHA-256 {{ selected.assetHash }}</p></details></template>
        <div class="digital-actions"><button type="submit" :disabled="busy || !canEdit || (selected !== null && !dirty)">{{ busy ? '处理中…' : selected ? '保存数字草稿' : '创建自建测试文件' }}</button><button v-if="selected" type="button" :disabled="busy || !canEdit || dirty || selected.confirmedVersion === selected.version" @click="action('confirm')">确认数字当前版本</button><button v-if="selected" type="button" :disabled="busy || !canPublish || dirty || selected.confirmedVersion !== selected.version || selected.status === 'PUBLISHED'" @click="action('publish')">发布数字文件</button><button v-if="selected?.status === 'PUBLISHED'" type="button" :disabled="busy || !canPublish || dirty" @click="action('off-shelf')">下架数字文件</button></div>
      </form></div>
  </section>
</template>
<style scoped>
.digital-workspace { padding: 24px; margin-top: 20px; color: #e8eeea; }.digital-workspace header { display: flex; justify-content: space-between; gap: 20px; }.digital-workspace p { font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }.digital-columns { display: grid; grid-template-columns: minmax(180px, .6fr) minmax(0, 1.4fr); gap: 24px; margin-top: 20px; }.digital-columns nav, .digital-columns form { display: grid; align-content: start; gap: 12px; }.digital-columns label { display: grid; gap: 6px; font-size: 13px; }.digital-workspace input, .digital-workspace textarea { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid #cad8cf; border-radius: 5px; background: #171f1a; color: #e8eeea; font: inherit; }.digital-workspace button { padding: 10px 14px; border: 1px solid #cad8cf; border-radius: 5px; background: #222c26; color: inherit; cursor: pointer; min-height: 40px; }.digital-workspace button:disabled { opacity: .5; cursor: default; }.digital-columns nav button { text-align: left; }.digital-columns nav strong, .digital-columns nav span { display: block; overflow-wrap: anywhere; }.digital-columns nav span { font-size: 12px; margin-top: 6px; }.digital-workspace [aria-pressed=true] { border-color: #267b53; background: #243a2c; }.digital-actions { display: flex; flex-wrap: wrap; gap: 8px; }.digital-workspace :focus-visible { outline: 2px solid #267b53; outline-offset: 3px; }@media(max-width: 700px) { .digital-columns { grid-template-columns: minmax(0, 1fr); }.digital-workspace { padding: 16px; }.digital-workspace header { flex-wrap: wrap; } }
</style>
