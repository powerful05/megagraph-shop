<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AppointmentOffer, AppointmentSlot } from '@megagraph/domain-types'
import { formatBeijingTime } from '@megagraph/domain-types'
import { readPendingAppointmentPurchase, shopperApi } from '../api'
const props = defineProps<{ offer: AppointmentOffer; busy: boolean; accountUserId: number | null }>()
const emit = defineEmits<{ purchase: [offer: AppointmentOffer, slot: AppointmentSlot]; recover: [offerId: number] }>()
const apiBase = useRuntimeConfig().public.apiBase
const current = ref<AppointmentOffer | null>(null)
const slotId = ref<number | null>(null)
const open = ref(false)
const loading = ref(false)
const error = ref('')
const pendingPurchase = ref(false)
const now = ref(Date.now())
let epoch = 0
let control: AbortController | null = null
let clock: ReturnType<typeof setInterval> | null = null
const slots = computed(() => current.value?.slots.filter(slot => slot.status === 'OPEN' && slot.remaining > 0 && Date.parse(slot.startAt) > now.value) ?? [])
const chosen = computed(() => slots.value.find(slot => slot.id === slotId.value))
function reset() { epoch++; control?.abort(); current.value = null; slotId.value = null; open.value = false; loading.value = false; error.value = ''; pendingPurchase.value = false }
watch(() => props.accountUserId, reset)
watch(() => props.offer.id, reset)
async function view() {
  const version = ++epoch; control?.abort(); control = new AbortController(); loading.value = true; open.value = true; error.value = ''; current.value = null; slotId.value = null
  pendingPurchase.value = props.accountUserId !== null && readPendingAppointmentPurchase(props.accountUserId, props.offer.id) !== null
  try {
    const result = await shopperApi.appointmentOffer(apiBase, props.offer.id, control.signal)
    if (version !== epoch) return
    current.value = result

  } catch (reason) { if (version === epoch && !(reason instanceof DOMException && reason.name === 'AbortError')) error.value = reason instanceof Error ? reason.message : '当前测试预约不可购，请重新查询' }
  finally { if (version === epoch) loading.value = false }
}
function purchase() { if (current.value && chosen.value) emit('purchase', current.value, chosen.value) }
function money(offer: AppointmentOffer) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: offer.currency }).format(offer.price) }
onMounted(() => { clock = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => { reset(); if (clock) clearInterval(clock) })
</script>
<template>
  <article class="appointment-offer-card">
    <small>{{ offer.merchantName }} · 测试预约</small><h3>{{ offer.title }}</h3><p>{{ offer.description }}</p><strong>{{ money(offer) }}</strong><p>固定时段 · 北京时间 · 仅 Demo，不实际预约或线下履约</p>
    <button type="button" :disabled="busy || loading" :aria-expanded="open" @click="view">{{ loading ? '核对当前预约…' : '查看当前预约时段' }}</button>
    <section v-if="open" class="appointment-current" aria-label="当前测试预约详情">
      <p v-if="error" role="alert">{{ error }}</p><template v-if="current"><h4>{{ current.title }}</h4><p>{{ current.description }}</p><p>{{ money(current) }} · 资料 v{{ current.version }}</p><p>{{ current.deliveryTerms }}</p><p>{{ current.refundPolicy }}</p><p>支付成功确认预约，仍需商家显式模拟测试完成；一次单独试购一个时段。</p>
        <label :for="`appointment-slot-${offer.id}`">选择预约时段（北京时间）</label><select :id="`appointment-slot-${offer.id}`" v-model.number="slotId" :disabled="busy || loading || !slots.length"><option :value="null" disabled>请明确选择固定时段</option><option v-for="slot in slots" :key="slot.id" :value="slot.id">{{ formatBeijingTime(slot.startAt) }} 至 {{ formatBeijingTime(slot.endAt) }} · 剩余 {{ slot.remaining }} / {{ slot.capacity }}</option></select><p v-if="!slots.length">当前没有未来可购名额，请刷新时段或重新查询。</p><p v-else-if="slotId !== null && !chosen">所选时段已开始或失效，请刷新时段后重新选择。</p>
        <button v-if="!pendingPurchase" type="button" :disabled="busy || loading || !chosen" @click="purchase">{{ accountUserId ? '单独试购选定预约时段' : '登录后试购预约时段' }}</button></template>
      <button v-if="pendingPurchase" type="button" :disabled="busy || loading" @click="emit('recover', offer.id)">核对上次预约试购请求</button>
      <button type="button" @click="reset">收起预约详情</button>
    </section>
  </article>
</template>
<style scoped>
.appointment-offer-card { margin: 12px 0; padding: 16px; border: 1px solid #dce6df; border-radius: 6px; background: #fbfdfb; overflow-wrap: anywhere; }.appointment-offer-card small { color: #52705c; }.appointment-offer-card h3, .appointment-offer-card h4 { margin: 10px 0; font-size: 16px; }.appointment-offer-card p { font-size: 13px; line-height: 1.65; }.appointment-offer-card button { min-height: 40px; padding: 9px 12px; margin: 5px 8px 0 0; border: 1px solid #9db9a7; border-radius: 4px; color: #246342; background: #fff; cursor: pointer; }.appointment-offer-card button:disabled { opacity: .5; cursor: default; }.appointment-current { margin-top: 16px; padding-top: 12px; border-top: 1px solid #dce6df; }.appointment-current label { display: block; font-size: 13px; margin: 12px 0 6px; }.appointment-current select { display: block; width: 100%; box-sizing: border-box; min-height: 40px; padding: 8px; font: inherit; border: 1px solid #9db9a7; border-radius: 4px; background: #fff; }.appointment-offer-card :focus-visible { outline: 2px solid #267b53; outline-offset: 3px; }
</style>
