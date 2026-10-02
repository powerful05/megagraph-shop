import { computed, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import type { GuideConversationDetail, GuideMessage, GuideRecommendationBasis, GuideReply, GuideTurnRequest, PublicProduct } from '@megagraph/domain-types'
import { shopperApi } from '../api'

export interface AgentMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  advice?: string
  recommendationBasis?: GuideRecommendationBasis[]
  showProducts?: boolean
  demoIds?: string[]
  liveProducts?: PublicProduct[]
  modelReply?: boolean
  error?: boolean
  requestId?: string
}

interface Conversation {
  id: string
  title: string
  messages: AgentMessage[]
  saved: boolean
  loaded: boolean
  pending: boolean
  waiting: boolean
  uncertain?: boolean
}

export function useGuideConversations(apiBase: string, owner: Ref<number | null>,
  useModel: () => boolean, demoAnswer: (query: string) => AgentMessage, onReply: () => void) {
  let sequence = 0
  let epoch = 0
  const newLocal = (): Conversation => ({ id: `local-${++sequence}`, title: '新对话', messages: [], saved: false, loaded: true, pending: false, waiting: false })
  const conversations = ref<Conversation[]>([newLocal()])
  const activeId = ref(conversations.value[0]!.id)
  const loading = ref(false)
  const activeRequests = ref(0)
  const historyError = ref('')
  const controls = new Map<string, AbortController>()
  const timers = new Map<string, ReturnType<typeof setTimeout>>()
  const pollUntil = new Map<string, number>()
  const activeConversation = computed(() => conversations.value.find(c => c.id === activeId.value) ?? conversations.value[0]!)
  const pendingId = computed(() => activeConversation.value.pending ? activeConversation.value.id : null)
  const waiting = computed(() => activeConversation.value.waiting)
  const last = computed(() => activeConversation.value.messages.at(-1))
  const canSend = computed(() => !loading.value && !activeConversation.value.pending && activeRequests.value < 2
    && (!activeConversation.value.saved || activeConversation.value.loaded))
  const canRetry = computed(() => !activeConversation.value.pending && last.value?.error === true && last.value.requestId !== undefined)
  const progress = computed(() => activeConversation.value.waiting ? '正在提交请求并等待核对后的回复…' : '已停止等待；服务端可能仍在处理。')
  const errorText = (error: unknown) => error instanceof Error ? error.message : '请求暂时无法完成'
  const storageKey = () => `megagraph-guide-active-${owner.value}`

  function remember() {
    if (owner.value && activeConversation.value.saved) {
      try { sessionStorage.setItem(storageKey(), activeId.value) } catch { /* Storage may be unavailable. */ }
    }
  }

  function assistant(reply: GuideReply, requestId: string): AgentMessage {
    return { id: `${requestId}-assistant`, requestId, role: 'assistant', text: reply.answer, advice: reply.advice,
      liveProducts: reply.products, recommendationBasis: reply.recommendationBasis, modelReply: true,
      showProducts: reply.demoProductIds.length > 0, demoIds: reply.demoProductIds }
  }

  function applyRequest(conversation: Conversation, request: GuideTurnRequest, updateState = true) {
    if (request.conversationId !== conversation.id) return
    conversation.messages = conversation.messages.filter(m => m.requestId !== request.requestId)
    conversation.messages.push({ id: `${request.requestId}-user`, requestId: request.requestId, role: 'user', text: request.content })
    if (request.result) conversation.messages.push(assistant(request.result, request.requestId))
    else if (request.status === 'FAILED') conversation.messages.push({ id: `${request.requestId}-error`, requestId: request.requestId,
      role: 'assistant', error: true, text: request.error?.message ?? '回复失败，请重试。' })
    if (!updateState) return
    updateRequestState(conversation, request.status === 'PENDING')
  }

  function updateRequestState(conversation: Conversation, pending: boolean) {
    conversation.pending = pending
    conversation.uncertain = false
    if (!conversation.pending) {
      conversation.waiting = false
      pollUntil.delete(conversation.id)
      const timer = timers.get(conversation.id)
      if (timer) clearTimeout(timer)
      timers.delete(conversation.id)
    }
  }

  function applyDetail(conversation: Conversation, detail: GuideConversationDetail) {
    if (detail.id !== conversation.id) return
    conversation.title = detail.title
    conversation.messages = []
    conversation.pending = false
    const wasWaiting = conversation.waiting && controls.get(conversation.id)?.signal.aborted === false
    for (const request of detail.requests) applyRequest(conversation, request, false)
    updateRequestState(conversation, detail.requests.at(-1)?.status === 'PENDING')
    conversation.waiting = conversation.pending && wasWaiting
    conversation.loaded = true
    onReply()
  }

  async function recover(conversation = activeConversation.value) {
    if (!conversation.saved) return
    const version = epoch
    try {
      const detail = await shopperApi.conversation(apiBase, conversation.id)
      if (version !== epoch) return
      applyDetail(conversation, detail)
      historyError.value = ''
      if (conversation.pending) schedulePoll(conversation)
    } catch (error) {
      if (version !== epoch) return
      historyError.value = errorText(error)
      conversation.waiting = false
    }
  }

  function schedulePoll(conversation: Conversation) {
    if (timers.has(conversation.id)) return
    if (!pollUntil.has(conversation.id)) pollUntil.set(conversation.id, Date.now() + 110000)
    if (Date.now() > pollUntil.get(conversation.id)!) {
      historyError.value = '尚未取得请求终态，请刷新请求状态。'
      return
    }
    timers.set(conversation.id, setTimeout(() => {
      timers.delete(conversation.id)
      void recover(conversation)
    }, 2000))
  }

  async function loadHistory() {
    if (!owner.value) return
    const version = epoch
    loading.value = true
    historyError.value = ''
    try {
      const list = await shopperApi.conversations(apiBase)
      if (version !== epoch) return
      conversations.value = list.map(c => ({ id: c.id, title: c.title, messages: [], saved: true, loaded: false, pending: false, waiting: false }))
      if (!conversations.value.length) conversations.value = [newLocal()]
      let stored = ''
      try { stored = sessionStorage.getItem(storageKey()) ?? '' } catch { /* Storage may be unavailable. */ }
      activeId.value = conversations.value.some(c => c.id === stored) ? stored : conversations.value[0]!.id
      await recover()
    } catch (error) {
      if (version === epoch) historyError.value = errorText(error)
    } finally {
      if (version === epoch) loading.value = false
    }
  }

  function reset() {
    epoch++
    controls.forEach(c => c.abort())
    controls.clear()
    activeRequests.value = 0
    timers.forEach(t => clearTimeout(t))
    timers.clear()
    pollUntil.clear()
    conversations.value = [newLocal()]
    activeId.value = conversations.value[0]!.id
    historyError.value = ''
    loading.value = false
  }

  function startConversation() {
    if (!activeConversation.value.messages.length && !activeConversation.value.saved) return
    const conversation = newLocal()
    conversations.value.unshift(conversation)
    activeId.value = conversation.id
  }

  function selectConversation(id: string) {
    activeId.value = id
    remember()
    void recover()
  }

  async function send(query: string, retry = false) {
    const conversation = activeConversation.value
    if (!canSend.value || (!retry && !query.trim())) return
    const version = epoch
    const previous = conversation.messages.at(-1)
    const requestId = retry && previous?.requestId ? previous.requestId : crypto.randomUUID()
    if (retry) {
      query = conversation.messages.find(m => m.role === 'user' && m.requestId === requestId)?.text ?? ''
      if (!query) return
      conversation.messages.pop()
    } else {
      conversation.messages.push({ id: `${requestId}-user`, requestId, role: 'user', text: query })
    }
    if (conversation.title === '新对话') conversation.title = query.slice(0, 60)
    conversation.pending = true
    conversation.waiting = true
    pollUntil.delete(conversation.id)
    const control = new AbortController()
    controls.set(conversation.id, control)
    activeRequests.value = controls.size
    const timeout = setTimeout(() => control.abort('timeout'), 105000)
    let stopped = false
    try {
      if (owner.value && !conversation.saved) {
        const previousId = conversation.id
        const created = await shopperApi.createConversation(apiBase, control.signal)
        if (version !== epoch) return
        controls.delete(conversation.id)
        activeRequests.value = controls.size
        conversation.id = created.id
        controls.set(conversation.id, control)
        activeRequests.value = controls.size
        conversation.saved = true
        if (activeId.value === previousId) activeId.value = conversation.id
        remember()
      }
      if (conversation.saved) {
        const reply = retry && !conversation.uncertain
          ? await shopperApi.retryConversation(apiBase, conversation.id, requestId, control.signal)
          : await shopperApi.sendConversation(apiBase, conversation.id, requestId, query, control.signal)
        if (version !== epoch) return
        applyRequest(conversation, reply)
        if (conversation.pending) schedulePoll(conversation)
      } else if (useModel()) {
        const completed: AgentMessage[] = []
        for (let index = 0; index < conversation.messages.length; index++) {
          const message = conversation.messages[index]!
          if (message.role !== 'user') continue
          const answer = conversation.messages[index + 1]
          if (index === conversation.messages.length - 1) completed.push(message)
          else if (answer?.role === 'assistant' && !answer.error) completed.push(message, answer)
        }
        const history: GuideMessage[] = completed.slice(-19).map(m => ({ role: m.role,
          content: (m.advice ? `${m.text}\n${m.advice}` : m.text).slice(0, 2000),
          displayedProductIds: m.role === 'assistant' ? m.liveProducts?.map(p => p.id) ?? [] : [] }))
        while (history.reduce((sum, m) => sum + m.content.length, 0) > 12000 && history.length > 1) history.splice(0, 2)
        const reply = await shopperApi.guide(apiBase, history, control.signal)
        if (version !== epoch) return
        conversation.messages.push(assistant(reply, requestId))
        conversation.pending = false
      } else {
        conversation.messages.push(demoAnswer(query))
        conversation.pending = false
      }
      onReply()
    } catch (error) {
      if (version !== epoch) return
      stopped = control.signal.aborted
      if (conversation.saved) {
        const detail = await shopperApi.conversation(apiBase, conversation.id).catch(() => null)
        if (version !== epoch) return
        if (detail?.requests.some(r => r.requestId === requestId)) {
          applyDetail(conversation, detail)
          if (conversation.pending) schedulePoll(conversation)
          return
        }
        conversation.uncertain = true
      }
      conversation.pending = false
      conversation.messages.push({ id: `${requestId}-error`, requestId, role: 'assistant', error: true,
        text: stopped ? '已停止等待；不代表上游模型调用已取消。' : errorText(error) })
      onReply()
    } finally {
      clearTimeout(timeout)
      if (version === epoch && controls.get(conversation.id) === control) {
        controls.delete(conversation.id)
        activeRequests.value = controls.size
        conversation.waiting = false
      }
    }
  }

  function stopWaiting() {
    const conversation = activeConversation.value
    controls.get(conversation.id)?.abort('stopped')
    conversation.waiting = false
    if (conversation.saved) schedulePoll(conversation)
  }

  onMounted(() => { if (owner.value) void loadHistory() })
  watch(owner, () => { reset(); if (owner.value) void loadHistory() })
  onBeforeUnmount(reset)
  return { conversations, activeId, activeConversation, pendingId, waiting, progress, loading, historyError, canRetry, canSend,
    startConversation, selectConversation, send, stopWaiting, recover, loadHistory }
}
