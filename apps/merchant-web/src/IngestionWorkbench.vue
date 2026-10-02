<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { IngestionArtifact, IngestionJob, IngestionStatus } from '@megagraph/domain-types'
import { canRetryIngestion, needsIngestionPolling, nextIngestionPollingDelay } from '@megagraph/domain-types'
import { merchantApi, type Credentials } from './api'

const props = defineProps<{ credentials: Credentials; canEdit: boolean; userId: number }>()
const emit = defineEmits<{ openDraft: [productId: number]; productsChanged: [] }>()
const jobs = ref<IngestionJob[]>([])
const activeJob = ref<IngestionJob | null>(null)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
const historyArtifacts = ref<IngestionArtifact[]>([])
let historyController: AbortController | null = null
let historyGeneration = 0
let historyLoaded = false
let historyJobId: number | null = null
const selectedFile = ref<File | null>(null)
const previewUrl = ref('')
const taskPreviewUrl = ref('')
let previewJobId: number | null = null
const fileInput = ref<HTMLInputElement | null>(null)
const busy = ref(false)
const refreshing = ref(false)
const message = ref('')
const error = ref('')
const timedOut = ref(false)
const controller = new AbortController()
let disposed = false
let timer: ReturnType<typeof setTimeout> | null = null
let pollingStartedAt = Date.now()
let refreshRequest: Promise<void> | null = null
let mutationVersion = 0
let uploadKey = ''
let selectedJobId: number | null = null
const storageKey = `megagraph.ingestion.${props.userId}.${props.credentials.merchantId}`
const pending = computed(() => jobs.value.some(needsIngestionPolling))
const statusLabels: Record<IngestionStatus, string> = {
  QUEUED: '等待领取', RUNNING: '生成中', RETRY_WAIT: '等待自动重试', SUCCEEDED: '草稿已生成',
  FAILED: '生成失败', DEAD_LETTER: '需要人工处理', CANCELLED: '已取消',
}
function label(status: IngestionStatus) { return statusLabels[status] ?? status }
function json(value: unknown): string { return value == null ? '未提供' : typeof value === 'string' ? value : JSON.stringify(value, null, 2) }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }
function clearTimer() { if (timer) clearTimeout(timer); timer = null }
function invalidateHistory(clear = false) {
  historyController?.abort()
  historyController = null
  historyGeneration += 1
  historyLoading.value = false
  historyLoaded = false
  historyError.value = ''
  if (clear) historyArtifacts.value = []
}
async function loadHistory() {
  const job = activeJob.value
  if (disposed || !job || !historyOpen.value) return
  invalidateHistory()
  const generation = historyGeneration
  const version = job.version
  const taskController = new AbortController()
  historyController = taskController
  historyLoading.value = true
  try {
    const artifacts = await merchantApi.ingestionArtifacts({ ...props.credentials }, job.id,
      AbortSignal.any([controller.signal, taskController.signal, AbortSignal.timeout(15_000)]))
    if (disposed || generation !== historyGeneration || selectedJobId !== job.id || activeJob.value?.version !== version) return
    historyArtifacts.value = artifacts
    historyLoaded = true
  } catch (reason) {
    if (!disposed && generation === historyGeneration && !(reason instanceof DOMException && reason.name === 'AbortError')) {
      historyError.value = reason instanceof Error ? reason.message : '尝试历史读取失败'
    }
  } finally {
    if (generation === historyGeneration) { historyLoading.value = false; historyController = null }
  }
}
function toggleHistory() {
  historyOpen.value = !historyOpen.value
  if (!historyOpen.value) invalidateHistory()
  else if (!historyLoaded) void loadHistory()
}
watch(() => activeJob.value ? `${activeJob.value.id}:${activeJob.value.version}` : '', () => {
  const id = activeJob.value?.id ?? null
  const switched = historyJobId !== id
  historyJobId = id
  invalidateHistory(switched)
  if (switched) historyOpen.value = false
  else if (historyOpen.value) void loadHistory()
})
function rememberSelection(id: number) {
  selectedJobId = id
  try { sessionStorage.setItem(storageKey, String(id)) } catch { /* Storage may be disabled. */ }
}
async function loadTaskPreview(id: number) {
  if (previewJobId === id) return
  previewJobId = id
  if (taskPreviewUrl.value) URL.revokeObjectURL(taskPreviewUrl.value)
  taskPreviewUrl.value = ''
  try {
    const url = await merchantApi.imageUrl({ ...props.credentials }, `/api/merchant/v1/ingestions/${id}/media`, AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]))
    if (!disposed && previewJobId === id) taskPreviewUrl.value = url
    else URL.revokeObjectURL(url)
  } catch { /* Task status remains usable if private media preview fails. */ }
}
function applyJob(job: IngestionJob) {
  jobs.value = [job, ...jobs.value.filter(item => item.id !== job.id)]
    .sort((a, b) => b.id - a.id)
  if (selectedJobId === job.id) activeJob.value = job
}
async function refresh() {
  if (disposed) return
  if (refreshRequest) return refreshRequest
  const version = mutationVersion
  refreshing.value = true
  refreshRequest = (async () => {
    const next = await merchantApi.ingestions({ ...props.credentials }, AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]))
    const wanted = selectedJobId != null && next.some(job => job.id === selectedJobId)
      ? selectedJobId : next[0]?.id ?? null
    const detail = wanted != null ? await merchantApi.ingestion({ ...props.credentials }, wanted, AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)])) : null
    if (disposed || version !== mutationVersion) return
    const succeeded = next.some(job => job.status === 'SUCCEEDED' && jobs.value.find(old => old.id === job.id)?.status !== 'SUCCEEDED')
    jobs.value = next.map(job => job.id === detail?.id ? detail : job)
    if (wanted === selectedJobId || selectedJobId == null || !next.some(job => job.id === selectedJobId)) {
      activeJob.value = detail
      if (wanted != null) { rememberSelection(wanted); void loadTaskPreview(wanted) }
    }
    if (succeeded) emit('productsChanged')
    error.value = ''
  })().finally(() => { refreshRequest = null; if (!disposed) refreshing.value = false })
  return refreshRequest
}
function schedule(reset = false) {
  clearTimer()
  if (disposed || !pending.value || document.visibilityState !== 'visible' || !navigator.onLine) return
  if (reset) { pollingStartedAt = Date.now(); timedOut.value = false }
  const delay = nextIngestionPollingDelay(pollingStartedAt, Date.now())
  if (delay == null) { timedOut.value = true; return }
  timer = setTimeout(async () => {
    try { await refresh() } catch (reason) { report(reason) }
    schedule()
  }, delay)
}
function report(reason: unknown) {
  if (!disposed && !(reason instanceof DOMException && reason.name === 'AbortError')) {
    error.value = reason instanceof Error ? reason.message : '任务请求失败'
  }
}
async function recover() {
  if (disposed) return
  try { await refresh() } catch (reason) { report(reason) }
  schedule(true)
}
async function selectJob(job: IngestionJob) {
  rememberSelection(job.id)
  activeJob.value = job
  void loadTaskPreview(job.id)
  const version = mutationVersion
  try {
    const detail = await merchantApi.ingestion({ ...props.credentials }, job.id, AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]))
    if (!disposed && version === mutationVersion && selectedJobId === job.id) activeJob.value = detail
  } catch (reason) { report(reason) }
}
function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  error.value = ''
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  selectedFile.value = null
  previewUrl.value = ''
  uploadKey = ''
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024 || file.size === 0) {
    error.value = '请选择不超过 10MB 的 JPEG、PNG 或 WebP 图片。服务器还会校验图片实际内容。'
    input.value = ''
    return
  }
  selectedFile.value = file
  uploadKey = crypto.randomUUID()
  previewUrl.value = URL.createObjectURL(file)
}
async function upload() {
  if (!props.canEdit || !selectedFile.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const job = await merchantApi.upload({ ...props.credentials }, selectedFile.value, uploadKey, AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]))
    if (disposed) return
    mutationVersion += 1
    rememberSelection(job.id)
    applyJob(job)
    void loadTaskPreview(job.id)
    message.value = `任务 #${job.id} 已持久化，刷新页面后仍可查看。`
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    uploadKey = ''
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
    if (job.status === 'SUCCEEDED') emit('productsChanged')
    schedule(true)
  } catch (reason) { report(reason) } finally { if (!disposed) busy.value = false }
}
async function mutate(action: 'retry' | 'cancel') {
  const job = activeJob.value
  if (!props.canEdit || !job || busy.value) return
  busy.value = true
  error.value = ''
  clearTimer()
  mutationVersion += 1
  invalidateHistory()
  try {
    const updated = await merchantApi.mutateIngestion({ ...props.credentials }, job, action, AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]))
    if (disposed) return
    applyJob(updated)
    message.value = action === 'cancel' ? '任务已取消，迟到结果不会写入草稿。' : '人工重试已提交，历史尝试记录保留。'
  } catch (reason) {
    report(reason)
    // A version conflict requires current server state before the next action.
    if (refreshRequest) await refreshRequest.catch(() => {})
    try { await refresh() } catch { /* Keep the action failure visible. */ }
    report(reason)
  } finally { if (!disposed) { busy.value = false; schedule(true) } }
}
function visibility() { if (document.visibilityState === 'visible') void recover(); else clearTimer() }
function offline() { clearTimer() }
onMounted(() => {
  try { const saved = Number(sessionStorage.getItem(storageKey)); if (Number.isSafeInteger(saved) && saved > 0) selectedJobId = saved } catch { /* Optional UI selection only. */ }
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('online', recover)
  window.addEventListener('offline', offline)
  void recover()
})
onBeforeUnmount(() => {
  disposed = true
  clearTimer()
  controller.abort()
  invalidateHistory(true)
  document.removeEventListener('visibilitychange', visibility)
  window.removeEventListener('online', recover)
  window.removeEventListener('offline', offline)
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  if (taskPreviewUrl.value) URL.revokeObjectURL(taskPreviewUrl.value)
})
</script>

<template>
  <section class="glass-panel ingestion-workbench" aria-label="异步商品生产任务">
    <header class="section-heading">
      <div><p class="step">01 / 异步生产</p><h2>上传与生成任务</h2></div>
      <el-button :loading="refreshing" :disabled="busy" @click="recover">刷新任务</el-button>
    </header>
    <p class="test-supply-note">当前商品生产使用 Mock 测试生成器，未验证看图或 OCR 能力。材质、规格、性能均需商家核实；价格与库存由商家填写，任务不会自动确认或发布。</p>
    <div class="ingestion-layout">
      <div>
        <label class="dropzone">
          <input ref="fileInput" type="file" :disabled="!canEdit || busy" aria-label="选择待生成草稿的商品图片" accept="image/jpeg,image/png,image/webp" @change="selectFile" />
          <img v-if="previewUrl" :src="previewUrl" alt="待上传商品预览" />
          <div v-else><strong>选择一张商品图</strong><span>JPEG / PNG / WebP · 最大 10MB</span></div>
        </label>
        <el-button type="primary" class="full-button" :disabled="!canEdit || !selectedFile" :loading="busy" @click="upload">提交生成任务</el-button>
        <p v-if="!canEdit" class="muted">只有 OWNER / EDITOR 可以上传、重试与取消。</p>
        <div class="product-list" aria-label="持久任务列表">
          <button v-for="job in jobs" :key="job.id" type="button" class="product-list-item" :class="{ active: activeJob?.id === job.id }" :aria-pressed="activeJob?.id === job.id" @click="selectJob(job)">
            <span>#{{ job.id }}</span><strong>{{ job.originalFilename }}</strong><small>{{ label(job.status) }} · 第 {{ job.attempt }} 次尝试</small>
          </button>
          <p v-if="!jobs.length && !refreshing" class="muted">暂无任务。已提交的任务会保留在服务器。</p>
        </div>
      </div>
      <article v-if="activeJob" class="ingestion-detail">
        <div class="section-heading"><h3>任务 #{{ activeJob.id }}</h3><el-tag>{{ label(activeJob.status) }}</el-tag></div>
        <img v-if="taskPreviewUrl" class="ingestion-input-preview" :src="taskPreviewUrl" :alt="`任务 #${activeJob.id} 的输入图片`" />
        <dl class="ingestion-facts">
          <div><dt>文件</dt><dd>{{ activeJob.originalFilename }}</dd></div>
          <div><dt>提交时间</dt><dd>{{ date(activeJob.createdAt) }}</dd></div>
          <div><dt>任务版本 / 尝试</dt><dd>{{ activeJob.version }} / {{ activeJob.attempt }}</dd></div>
          <div v-if="activeJob.nextAttemptAt"><dt>下次重试</dt><dd>{{ date(activeJob.nextAttemptAt) }}</dd></div>
          <div v-if="activeJob.leaseUntil"><dt>当前租约</dt><dd>{{ date(activeJob.leaseUntil) }}</dd></div>
          <div v-if="activeJob.finishedAt"><dt>结束时间</dt><dd>{{ date(activeJob.finishedAt) }}</dd></div>
        </dl>
        <p v-if="activeJob.errorCode || activeJob.errorSummary" class="ingestion-error" role="status">{{ activeJob.errorCode }} · {{ activeJob.errorSummary || '生成未完成，请查看当前任务状态。' }}</p>
        <ol class="ingestion-steps">
          <li v-for="(step, index) in activeJob.steps" :key="`${step.type}-${index}`"><strong>{{ step.type }}</strong><span>{{ step.status }} · {{ step.attempts }} 次</span><small v-if="step.errorCode || step.errorSummary">{{ step.errorCode }} {{ step.errorSummary }}</small></li>
        </ol>
        <div class="action-row">
          <el-button v-if="canRetryIngestion(activeJob)" :disabled="!canEdit" :loading="busy" @click="mutate('retry')">人工重试</el-button>
          <el-button v-if="needsIngestionPolling(activeJob)" :disabled="!canEdit" :loading="busy" @click="mutate('cancel')">取消任务</el-button>
          <el-button v-if="activeJob.status === 'SUCCEEDED' && activeJob.productId != null" type="primary" @click="emit('openDraft', activeJob.productId)">打开草稿 #{{ activeJob.productId }}</el-button>
        </div>
        <p v-if="needsIngestionPolling(activeJob)" class="muted">取消会阻止结果落库，不能保证上游调用已停止。成功草稿通过下方编辑、确认和发布。</p>
        <div v-if="activeJob.artifact" class="ingestion-artifact">
          <h3>生成产物与依据</h3>
          <p v-if="activeJob.artifact.errorCode" class="ingestion-error">产物错误：{{ activeJob.artifact.errorCode }}</p>
          <dl class="ingestion-facts">
            <div><dt>Provider / Model</dt><dd>{{ activeJob.artifact.provider }} / {{ activeJob.artifact.model }}</dd></div>
            <div><dt>模板 / 协议</dt><dd>{{ activeJob.artifact.templateVersion }} / {{ activeJob.artifact.protocolVersion }}</dd></div>
            <div><dt>输入 SHA256</dt><dd>{{ activeJob.artifact.inputHash }}</dd></div>
            <div><dt>尝试 / 耗时</dt><dd>{{ activeJob.artifact.attempt }} / {{ activeJob.artifact.elapsedMs == null ? '未提供' : `${activeJob.artifact.elapsedMs} ms` }}</dd></div>
          </dl>
          <details open><summary>事实依据</summary><pre>{{ json(activeJob.artifact.evidence) }}</pre></details>
          <details open><summary>未知与待确认项</summary><ul v-if="activeJob.artifact.unknowns.length"><li v-for="(item, index) in activeJob.artifact.unknowns" :key="index">{{ item }}</li></ul><p v-else class="muted">未提供未知项；材质、规格与性能仍须商家核实。</p></details>
          <details><summary>结构化输出</summary><pre>{{ json(activeJob.artifact.parsedOutput) }}</pre></details>
          <details><summary>原始输出</summary><pre>{{ json(activeJob.artifact.rawOutput) }}</pre></details>
          <details><summary>调用用量（未知费用 / 置信度留空）</summary><pre>{{ json(activeJob.artifact.usage) }}</pre></details>
        </div>
        <section class="ingestion-artifact ingestion-history" aria-label="尝试历史">
          <div class="action-row">
            <el-button :aria-expanded="historyOpen" @click="toggleHistory">{{ historyOpen ? '收起尝试历史' : '查看尝试历史' }}</el-button>
            <el-button v-if="historyOpen" :loading="historyLoading" :disabled="busy" @click="loadHistory">刷新历史</el-button>
          </div>
          <template v-if="historyOpen">
            <p class="muted">每次尝试分别保存，按尝试编号从早到晚展示。人工重试保留既有记录。</p>
            <p v-if="historyLoading" class="muted" role="status">正在读取尝试历史…</p>
            <p v-if="historyError" class="ingestion-error" role="alert">{{ historyError }}</p>
            <p v-else-if="!historyLoading && !historyArtifacts.length" class="muted">尚未保存尝试产物。等待领取且未开始的任务没有产物记录。</p>
            <details v-for="artifact in historyArtifacts" :key="artifact.attempt">
              <summary>尝试 {{ artifact.attempt }} · {{ artifact.errorCode || '产物已保存' }} · {{ artifact.elapsedMs == null ? '耗时未提供' : `${artifact.elapsedMs} ms` }}</summary>
              <dl class="ingestion-facts">
                <div><dt>Provider / Model</dt><dd>{{ artifact.provider }} / {{ artifact.model }}</dd></div>
                <div><dt>安全错误</dt><dd>{{ artifact.errorCode || '无错误记录' }}</dd></div>
                <div><dt>模板 / 协议</dt><dd>{{ artifact.templateVersion }} / {{ artifact.protocolVersion }}</dd></div>
                <div><dt>输入 SHA256</dt><dd>{{ artifact.inputHash }}</dd></div>
              </dl>
              <details><summary>事实依据</summary><pre>{{ json(artifact.evidence) }}</pre></details>
              <details><summary>未知与待确认项</summary><pre>{{ json(artifact.unknowns) }}</pre></details>
              <details><summary>结构化输出</summary><pre>{{ json(artifact.parsedOutput) }}</pre></details>
              <details><summary>原始输出</summary><pre>{{ json(artifact.rawOutput) }}</pre></details>
              <details><summary>调用用量</summary><pre>{{ json(artifact.usage) }}</pre></details>
            </details>
          </template>
        </section>
      </article>
      <div v-else class="ingestion-empty"><h3>提交后查看生成进度</h3><p>任务与步骤以服务器状态为准；页面刷新后会重新读取。生成完成后可打开草稿。</p></div>
    </div>
    <p class="ingestion-polling" role="status">{{ pending ? (timedOut ? '任务仍在运行；本次自动刷新已满 60 秒，请点击刷新任务恢复。' : '每 2 秒同步任务，最多 60 秒；离开页面或隐藏窗口会暂停。') : '当前任务状态已同步。' }}</p>
    <p v-if="message" class="muted" role="status">{{ message }}</p>
    <p v-if="error" class="ingestion-error" role="alert">{{ error }}</p>
  </section>
</template>
