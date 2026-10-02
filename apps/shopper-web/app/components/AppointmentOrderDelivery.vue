<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { SalesOrder } from '@megagraph/domain-types'
import { formatBeijingTime } from '@megagraph/domain-types'
const props = defineProps<{ order: SalesOrder }>()
const emit = defineEmits<{ holdExpired: [] }>()
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | null = null
onMounted(() => { clock = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => { if (clock) clearInterval(clock) })
const reservation = computed(() => props.order.appointment?.reservation)
const holdExpired = computed(() => reservation.value?.status === 'HELD' && (Date.parse(reservation.value.expiresAt) <= now.value || Date.parse(props.order.appointment!.startAt) <= now.value))
watch(holdExpired, value => { if (value) emit('holdExpired') }, { immediate: true })
function label(status: string) { const labels: Record<string, string> = { HELD: '预占名额', CONFIRMED: '预约已确认', RELEASED: '名额已释放', FULFILLED: '已模拟测试完成' }; return labels[status] ?? status }
</script>
<template>
  <section v-if="order.appointment" class="appointment-order-delivery" aria-label="预约交付事实">
    <strong>{{ order.appointment.title }} · 测试预约</strong><p>{{ formatBeijingTime(order.appointment.startAt) }} 至 {{ formatBeijingTime(order.appointment.endAt) }}（北京时间）</p><p>资料快照 v{{ order.appointment.offerVersion }} · 固定时段 #{{ order.appointment.slotId }} · 容量 {{ order.appointment.capacity }}</p><p>{{ order.appointment.deliveryTerms }}</p><p>{{ order.appointment.refundPolicy }}</p>
    <template v-if="reservation"><p>预约 / {{ label(reservation.status) }}</p><p v-if="reservation.status === 'HELD'">预占截止 {{ formatBeijingTime(reservation.expiresAt) }}（北京时间）<span v-if="holdExpired"> · 已到截止，请刷新订单核对释放状态，不能再确认预约。</span></p><p v-if="reservation.confirmedAt">确认时间 {{ formatBeijingTime(reservation.confirmedAt) }}（北京时间）</p><p v-if="reservation.status === 'CONFIRMED'">支付成功已确认预约，尚未测试完成；不会自动履约，也不提供真实服务。</p><p v-if="reservation.fulfilledAt">模拟测试完成时间 {{ formatBeijingTime(reservation.fulfilledAt) }}（北京时间），非真实服务履约，按本 Demo 规则不再接受退款。</p><p v-if="reservation.status === 'RELEASED'">名额已释放{{ order.afterSale?.status === 'REFUNDED' ? '，退款已完成。' : '，请以当前订单与支付状态为准。' }}</p><p v-else-if="order.afterSale && order.afterSale.status !== 'REJECTED'">退款流程尚未完成，模拟测试完成已暂停。</p><p v-else-if="order.afterSale?.status === 'REJECTED'">退款申请已驳回，当前预约仍按原约定处理。</p><p v-if="reservation.releaseReason">释放原因 / {{ reservation.releaseReason }}</p></template>
    <p>受控 Demo · Mock 支付 · 不实际预约或线下履约</p>
  </section>
</template>
<style scoped>
.appointment-order-delivery { padding: 16px; margin: 12px 0; border: 1px solid #dce6df; border-radius: 6px; background: #f8fbf8; overflow-wrap: anywhere; }.appointment-order-delivery p { font-size: 12px; line-height: 1.65; }.appointment-order-delivery strong { font-size: 14px; }
</style>
