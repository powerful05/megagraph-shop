<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { SalesOrder } from '@megagraph/domain-types'
const props = defineProps<{ order: SalesOrder; busy: boolean }>()
const emit = defineEmits<{ claim: []; download: [] }>()
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
const entitlement = computed(() => props.order.delivery?.entitlement)
const expired = computed(() => entitlement.value ? Date.parse(entitlement.value.expiresAt) <= now.value : false)
const blocked = computed(() => !!props.order.afterSale && props.order.afterSale.status !== 'REJECTED')
const eligible = computed(() => props.order.status === 'PAID' && props.order.payment?.status === 'SUCCEEDED' && !props.order.payment.reconciliationRequired && !blocked.value && !!entitlement.value && !expired.value && entitlement.value.status !== 'REVOKED')
const claimEligible = computed(() => eligible.value && props.order.delivery?.claimAvailable !== false)
const downloadEligible = computed(() => eligible.value && props.order.delivery?.downloadAvailable !== false)
const serverUnavailable = computed(() => eligible.value && (entitlement.value?.status === 'AVAILABLE' && props.order.delivery?.claimAvailable === false || entitlement.value?.status === 'CLAIMED' && props.order.delivery?.downloadAvailable === false))
function statusLabel(value: string) { return value === 'AVAILABLE' ? '待领取' : value === 'CLAIMED' ? '已领取' : '已撤销' }
function date(value: string) { return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
</script>
<template>
  <section v-if="order.delivery" class="digital-order-delivery" aria-label="数字文件交付">
    <strong>{{ order.delivery.title }} · 数字测试文件</strong><p>供给快照 v{{ order.delivery.offerVersion }} · 文件 v{{ order.delivery.assetVersion }}</p><p>{{ order.delivery.deliveryTerms }}</p><p>{{ order.delivery.refundPolicy }}</p>
    <template v-if="entitlement"><p>权益 / {{ statusLabel(entitlement.status) }}<span v-if="expired"> · 已到期</span></p><p>领取及下载截止 {{ date(entitlement.expiresAt) }}（北京时间），重复领取不会延长。</p><p v-if="order.status === 'REFUNDED' || order.afterSale?.status === 'REFUNDED' || entitlement.status === 'REVOKED'">退款已完成，文件权益已撤销。</p><p v-else-if="blocked">当前售后处理中，领取和下载已暂停。</p><p v-else-if="order.payment?.reconciliationRequired">支付状态需要核查，领取和下载暂不可用。</p><p v-else-if="serverUnavailable">当前权益暂不可{{ entitlement.status === 'AVAILABLE' ? '领取' : '下载' }}，请刷新订单核对。</p><button v-if="entitlement.status === 'AVAILABLE'" type="button" :disabled="busy || !claimEligible" @click="emit('claim')">领取自建测试文件</button><button v-if="entitlement.status === 'CLAIMED'" type="button" :disabled="busy || !downloadEligible" @click="emit('download')">下载已领取测试文件</button></template>
    <p v-else>支付成功并由后端确认后开放领取。</p><details><summary>下单文件校验值</summary><p>SHA-256 {{ order.delivery.assetHash }}</p></details>
  </section>
</template>
<style scoped>
.digital-order-delivery { padding: 16px; margin: 12px 0; border: 1px solid #dce6df; border-radius: 6px; background: #f8fbf8; overflow-wrap: anywhere; }.digital-order-delivery p { font-size: 12px; line-height: 1.65; }.digital-order-delivery strong { font-size: 14px; }.digital-order-delivery button { min-height: 40px; padding: 10px 14px; border: 1px solid #a4bdaa; border-radius: 4px; background: #fff; color: #246342; cursor: pointer; }.digital-order-delivery button:disabled { opacity: .5; cursor: default; }.digital-order-delivery :focus-visible { outline: 2px solid #267b53; outline-offset: 3px; }
</style>
