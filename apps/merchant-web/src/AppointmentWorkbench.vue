<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type { AppointmentOffer, AppointmentSlot } from '@megagraph/domain-types'
import { beijingLocalToOffsetIso, formatBeijingTime } from '@megagraph/domain-types'
import { merchantApi, type Credentials } from './api'
const props = defineProps<{ credentials: Credentials; userId: number; role: string }>()
const offers = ref<AppointmentOffer[]>([])
const selected = ref<AppointmentOffer | null>(null)
const form = reactive({ title: '测试预约服务', description: '仅用于受控 Demo，不实际预约或线下履约。', price: 29.9 })
const slotForm = reactive({ start: '', end: '', capacity: 1 })
const error = ref('')
const notice = ref('')
const busy = ref(false)
let epoch = 0
const control = new AbortController()
const storage = `megagraph-appointment-merchant-${props.userId}-${props.credentials.merchantId}`
type OfferInput = { title: string; description: string; price: number }
type SlotInput = { version: number; startAt: string; endAt: string; capacity: number }
const pendingCreate = ref<{ key: string; payload: OfferInput } | null>(null)
const pendingSlot = ref<{ key: string; offerId: number; payload: SlotInput } | null>(null)
const canEdit = computed(() => ['OWNER', 'EDITOR'].includes(props.role))
const canPublish = computed(() => ['OWNER', 'PUBLISHER'].includes(props.role))
const dirty = computed(() => selected.value !== null && (form.title !== selected.value.title || form.description !== selected.value.description || form.price !== selected.value.price))
const hasFutureSlot = computed(() => selected.value?.slots.some(slot => slot.status === 'OPEN' && Date.parse(slot.startAt) > Date.now()) ?? false)
function readSaved<T>(key: string): T | null { try { return JSON.parse(sessionStorage.getItem(key) ?? 'null') as T | null } catch { return null } }
function saveMemory(key: string, value: unknown) { try { if (value === null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, JSON.stringify(value)) } catch { /* Optional browser storage. */ } }
function choose(offer: AppointmentOffer | null) {
  selected.value = offer
  Object.assign(form, offer ? { title: offer.title, description: offer.description, price: offer.price } : pendingCreate.value?.payload ?? { title: '测试预约服务', description: '仅用于受控 Demo，不实际预约或线下履约。', price: 29.9 })
  saveMemory(`${storage}-selected`, offer?.id ?? null)
  pendingSlot.value = offer ? (pendingSlot.value?.offerId === offer.id ? pendingSlot.value : readSaved(`${storage}-slot-${offer.id}`)) : null
  if (pendingSlot.value?.offerId !== offer?.id) pendingSlot.value = null
  error.value = ''; notice.value = ''
}
function apply(offer: AppointmentOffer, message: string) {
  offers.value = [offer, ...offers.value.filter(item => item.id !== offer.id)]
  const draftSlot = { ...slotForm }; choose(offer); Object.assign(slotForm, draftSlot)
  notice.value = message
}
async function refresh() {
  if (busy.value) return
  const version = ++epoch; busy.value = true; error.value = ''
  try {
    const next = await merchantApi.appointmentOffers({ ...props.credentials }, control.signal)
    if (version !== epoch || control.signal.aborted) return
    offers.value = next
    const remembered = selected.value?.id ?? readSaved<number>(`${storage}-selected`)
    const current = next.find(item => item.id === remembered)
    if (current && !dirty.value) choose(current)
    else if (selected.value && !current) { selected.value = null; error.value = '当前预约供给已不可访问，请重新选择。' }
  } catch (reason) { showError(reason, version) }
  finally { if (version === epoch) busy.value = false }
}
function showError(reason: unknown, version: number) { if (version === epoch && !control.signal.aborted && !(reason instanceof DOMException && reason.name === 'AbortError')) error.value = reason instanceof Error ? reason.message : '预约操作失败' }
function definiteFailure(reason: unknown) { const status = reason instanceof Error && 'status' in reason ? Number(reason.status) : 0; return status >= 400 && status < 500 }
async function mutate(task: () => Promise<AppointmentOffer>, message: string, onFailure?: (reason: unknown) => void) {
  const version = ++epoch; busy.value = true; error.value = ''; notice.value = ''
  try { const offer = await task(); if (version === epoch && !control.signal.aborted) apply(offer, message) }
  catch (reason) { if (version === epoch && !control.signal.aborted) onFailure?.(reason); showError(reason, version) }
  finally { if (version === epoch) busy.value = false }
}
async function save() {
  if (busy.value || !canEdit.value) return
  if (!form.title.includes('测试') || !form.description.trim() || !Number.isFinite(form.price) || form.price <= 0) { error.value = '标题须含“测试”，填写非真实履约说明和正数价格。'; return }
  const offer = selected.value
  const payload = { title: form.title.trim(), description: form.description.trim(), price: form.price }
  if (!offer) { pendingCreate.value ??= { key: crypto.randomUUID(), payload }; saveMemory(`${storage}-create`, pendingCreate.value) }
  await mutate(async () => {
    const result = offer ? await merchantApi.updateAppointmentOffer({ ...props.credentials }, offer, payload, control.signal) : await merchantApi.createAppointmentOffer({ ...props.credentials }, pendingCreate.value!.payload, pendingCreate.value!.key, control.signal)
    if (!offer) { pendingCreate.value = null; saveMemory(`${storage}-create`, null) }
    return result
  }, offer ? '资料已保存，需要重新确认并发布。' : '测试预约草稿已创建，请添加固定时段。', reason => { if (!offer && definiteFailure(reason)) { pendingCreate.value = null; saveMemory(`${storage}-create`, null) } })
}
async function action(kind: 'confirm' | 'publish' | 'off-shelf') {
  const offer = selected.value
  if (!offer || dirty.value || busy.value) return
  await mutate(() => merchantApi.mutateAppointmentOffer({ ...props.credentials }, offer, kind, control.signal), { confirm: '预约资料与固定时段已确认。', publish: '测试预约已发布，实际履约仍由商家显式模拟完成。', 'off-shelf': '已下架，已有预约继续保留原约定。' }[kind])
}
async function addSlot() {
  const offer = selected.value
  if (!offer || dirty.value || busy.value || !canEdit.value) return
  try {
    if (!pendingSlot.value) {
      const startAt = beijingLocalToOffsetIso(slotForm.start), endAt = beijingLocalToOffsetIso(slotForm.end)
      const start = Date.parse(startAt), end = Date.parse(endAt), now = Date.now()
      if (start <= now || start > now + 30 * 86400000 || end <= start || end - start > 4 * 3600000 || !Number.isInteger(slotForm.capacity) || slotForm.capacity < 1 || slotForm.capacity > 50) throw new Error('请选择未来30天内、最长4小时的北京时间时段；容量为1–50的整数。')
      pendingSlot.value = { key: crypto.randomUUID(), offerId: offer.id, payload: { version: offer.version, startAt, endAt, capacity: slotForm.capacity } }
      saveMemory(`${storage}-slot-${offer.id}`, pendingSlot.value)
    }
    await mutate(async () => {
      const pending = pendingSlot.value!
      const result = await merchantApi.createAppointmentSlot({ ...props.credentials }, pending.offerId, pending.payload, pending.key, control.signal)
      pendingSlot.value = null; saveMemory(`${storage}-slot-${offer.id}`, null); Object.assign(slotForm, { start: '', end: '', capacity: 1 })
      return result
    }, '固定时段已创建，资料需要重新确认并发布。', reason => { if (definiteFailure(reason)) { pendingSlot.value = null; saveMemory(`${storage}-slot-${offer.id}`, null) } })
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '时段输入无效' }
}
async function closeSlot(slot: AppointmentSlot) {
  if (!selected.value || busy.value || dirty.value || !canEdit.value || slot.status !== 'OPEN') return
  const offer = selected.value
  await mutate(() => merchantApi.closeAppointmentSlot({ ...props.credentials }, offer.id, slot.id, slot.version, control.signal), '时段已关闭，阻止新购买；已有预占及确认预约继续按原约定处理。')
}
onMounted(() => { pendingCreate.value = readSaved(`${storage}-create`); if (pendingCreate.value) Object.assign(form, pendingCreate.value.payload); void refresh() })
onBeforeUnmount(() => { epoch++; control.abort() })
</script>
<template>
  <section class="appointment-workspace glass-panel" aria-labelledby="appointment-workspace-title">
    <header><div><p class="step">APPOINTMENT / 受控 Demo</p><h2 id="appointment-workspace-title">测试预约</h2><p>固定时段均为北京时间；容量独立于实物库存。不实际预约或线下履约，支付成功后仍需商家显式模拟测试完成。</p></div><button type="button" :disabled="busy" @click="refresh">刷新预约供给</button></header>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div class="appointment-columns"><nav aria-label="测试预约供给"><button type="button" :disabled="busy || !canEdit" @click="choose(null)">新建测试预约</button><button v-for="offer in offers" :key="offer.id" type="button" :disabled="busy" :aria-pressed="selected?.id === offer.id" @click="choose(offer)"><strong>#{{ offer.id }} {{ offer.title }}</strong><span>{{ { DRAFT: '草稿', PUBLISHED: '已发布', OFF_SHELF: '已下架' }[offer.status] }} · ¥{{ offer.price.toFixed(2) }} · v{{ offer.version }}</span></button></nav>
      <div class="appointment-editor"><form @submit.prevent="save"><label>测试预约标题<input v-model="form.title" required maxlength="200" :disabled="busy || !canEdit || (!selected && pendingCreate !== null)" /></label><label>测试预约说明<textarea v-model="form.description" required maxlength="2000" rows="3" :disabled="busy || !canEdit || (!selected && pendingCreate !== null)" /></label><label>预约价格（CNY）<input v-model.number="form.price" type="number" min="0.01" step="0.01" required :disabled="busy || !canEdit || (!selected && pendingCreate !== null)" /></label><p v-if="!selected && pendingCreate">上次创建结果待核实，再次提交将核对原请求。</p>
        <template v-if="selected"><p>资料 v{{ selected.version }} · {{ selected.confirmedVersion === selected.version ? '当前版本已确认' : '当前版本待确认' }}<span v-if="dirty"> · 有未保存修改</span></p><p>{{ selected.deliveryTerms }}</p><p>{{ selected.refundPolicy }}</p></template>
        <div class="appointment-actions"><button type="submit" :disabled="busy || !canEdit || (selected !== null && !dirty)">{{ busy ? '处理中…' : selected ? '保存预约草稿' : pendingCreate ? '核对上次预约创建' : '创建测试预约' }}</button><button v-if="selected" type="button" :disabled="busy || !canEdit || dirty || !hasFutureSlot || selected.confirmedVersion === selected.version" @click="action('confirm')">确认预约当前版本</button><button v-if="selected" type="button" :disabled="busy || !canPublish || dirty || selected.confirmedVersion !== selected.version || selected.status === 'PUBLISHED'" @click="action('publish')">发布测试预约</button><button v-if="selected?.status === 'PUBLISHED'" type="button" :disabled="busy || !canPublish || dirty" @click="action('off-shelf')">下架测试预约</button></div>
      </form>
      <section v-if="selected" aria-label="固定预约时段"><h3>固定时段 · 北京时间</h3><p>时段创建后不能改日期或容量，可关闭以停止新购。新增时段需要重新确认及发布预约资料。</p><article v-for="slot in selected.slots" :key="slot.id" class="appointment-slot"><strong>{{ formatBeijingTime(slot.startAt) }} 至 {{ formatBeijingTime(slot.endAt) }}</strong><p>{{ slot.status === 'OPEN' ? '开放' : '已关闭' }} · 容量 {{ slot.capacity }} · 当前剩余 {{ slot.remaining }} · 时段 v{{ slot.version }}</p><button v-if="slot.status === 'OPEN' && canEdit" type="button" :disabled="busy || dirty" @click="closeSlot(slot)">关闭预约时段 #{{ slot.id }}</button></article><p v-if="!selected.slots.length">尚无固定时段，请先添加未来时段。</p>
        <form v-if="canEdit" @submit.prevent="addSlot"><label>开始时间（北京时间）<input v-model="slotForm.start" type="datetime-local" required :disabled="busy || pendingSlot !== null" /></label><label>结束时间（北京时间）<input v-model="slotForm.end" type="datetime-local" required :disabled="busy || pendingSlot !== null" /></label><label>时段容量（1–50）<input v-model.number="slotForm.capacity" type="number" min="1" max="50" step="1" required :disabled="busy || pendingSlot !== null" /></label><p v-if="pendingSlot">上次时段创建结果待核实，再次提交将核对原时段，不重复增加容量。</p><button type="submit" :disabled="busy || dirty">{{ pendingSlot ? '核对上次时段创建' : '添加固定测试时段' }}</button></form>
      </section></div></div>
  </section>
</template>
<style scoped>
.appointment-workspace { padding: 24px; margin-top: 20px; color: #e8eeea; }.appointment-workspace header { display: flex; justify-content: space-between; gap: 20px; }.appointment-workspace p { font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }.appointment-columns { display: grid; grid-template-columns: minmax(180px, .6fr) minmax(0, 1.4fr); gap: 24px; margin-top: 20px; }.appointment-columns nav, .appointment-columns form, .appointment-editor { display: grid; align-content: start; gap: 12px; }.appointment-columns label { display: grid; gap: 6px; font-size: 13px; }.appointment-workspace input, .appointment-workspace textarea { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid #879f90; border-radius: 5px; background: #171f1a; color: #e8eeea; font: inherit; color-scheme: dark; }.appointment-workspace button { padding: 10px 14px; border: 1px solid #879f90; border-radius: 5px; background: #222c26; color: inherit; cursor: pointer; min-height: 40px; }.appointment-workspace button:disabled { opacity: .5; cursor: default; }.appointment-columns nav button { text-align: left; }.appointment-columns nav strong, .appointment-columns nav span { display: block; overflow-wrap: anywhere; }.appointment-columns nav span { font-size: 12px; margin-top: 6px; }.appointment-workspace [aria-pressed=true] { border-color: #58bd7d; background: #243a2c; }.appointment-actions { display: flex; flex-wrap: wrap; gap: 8px; }.appointment-slot { padding: 12px; margin: 12px 0; border: 1px solid #506a59; border-radius: 5px; }.appointment-editor section { margin-top: 16px; padding-top: 12px; border-top: 1px solid #506a59; }.appointment-workspace :focus-visible { outline: 2px solid #58bd7d; outline-offset: 3px; }@media(max-width: 700px) { .appointment-columns { grid-template-columns: minmax(0, 1fr); }.appointment-workspace { padding: 16px; }.appointment-workspace header { flex-wrap: wrap; } }
</style>
