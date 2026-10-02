import type { AuthMe, CheckoutGroup, DeliveryOffer, GuideConversationDetail, GuideConversationSummary, GuideTurnRequest, GuideMessage, GuideReply, MockPaymentResult, PublicProduct, SalesOrder, ShoppingCart } from '@megagraph/domain-types'

export interface ShopperCredentials {
  username: string
  password: string
}

export interface CheckoutDetails {
  recipientName: string
  recipientPhone: string
  shippingAddress: string
}

interface ApiErrorBody {
  code?: string
  message?: string
  fieldErrors?: Record<string, string>
}

let csrfToken = ''
let onSessionExpired: (() => void) | null = null
let shopperRequests = new AbortController()

export function cancelShopperRequests() {
  shopperRequests.abort()
  shopperRequests = new AbortController()
  csrfToken = ''
}

async function ensureCsrf(apiBase: string): Promise<string> {
  if (!csrfToken) {
    const response = await fetch(`${apiBase}/api/auth/v1/csrf`, { credentials: 'same-origin' })
    if (!response.ok) throw new Error('无法获取请求安全令牌')
    const body = await response.json() as { token: string }
    csrfToken = body.token
  }
  return csrfToken
}

async function request<T>(
  apiBase: string,
  path: string,
  _credentials: ShopperCredentials,
  init: RequestInit = {},
): Promise<T> {
  const signal = path.startsWith('/api/shop/v1/') ? (init.signal ? AbortSignal.any([shopperRequests.signal, init.signal]) : shopperRequests.signal) : init.signal
  const headers = new Headers(init.headers)
  if (init.method && !['GET', 'HEAD'].includes(init.method.toUpperCase())) {
    headers.set('X-CSRF-TOKEN', await ensureCsrf(apiBase))
  }
  if (init.body != null) headers.set('Content-Type', 'application/json')
  const response = await fetch(`${apiBase}${path}`, { ...init, headers, signal, credentials: 'same-origin' })
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as ApiErrorBody
    if (body.code === 'CSRF_TOKEN_INVALID') {
      csrfToken = ''
      await ensureCsrf(apiBase).catch(() => {})
    }
    if (response.status === 401 && path !== '/api/auth/v1/login') onSessionExpired?.()
    const fields = body.fieldErrors ? Object.values(body.fieldErrors).join('；') : ''
    throw Object.assign(new Error([body.message ?? `请求失败（${response.status}）`, fields].filter(Boolean).join('：')), { status: response.status, code: body.code })
  }
  const result = response.status === 204 ? undefined : await response.json()
  if (signal?.aborted) throw new DOMException('Shopper session changed', 'AbortError')
  return result as T
}

export const shopperApi = {
  deliveryOffer(apiBase: string, id: number, signal?: AbortSignal) {
    return request<DeliveryOffer>(apiBase, `/api/shop/v1/delivery-offers/${id}`, { username: '', password: '' }, { signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000) })
  },
  createDeliveryOrder(apiBase: string, offerId: number, expectedOfferVersion: number, key: string) {
    return request<SalesOrder>(apiBase, '/api/shop/v1/delivery-orders', { username: '', password: '' }, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ items: [{ offerId, quantity: 1, expectedOfferVersion }] }), signal: AbortSignal.timeout(15000) })
  },
  claimDigital(apiBase: string, orderId: number, key: string) {
    return request<SalesOrder>(apiBase, `/api/shop/v1/orders/${orderId}/digital-claim`, { username: '', password: '' }, { method: 'POST', headers: { 'Idempotency-Key': key }, signal: AbortSignal.timeout(15000) })
  },
  async downloadDigital(apiBase: string, orderId: number) {
    const signal = AbortSignal.any([shopperRequests.signal, AbortSignal.timeout(15000)])
    const response = await fetch(`${apiBase}/api/shop/v1/orders/${orderId}/digital-download`, { credentials: 'same-origin', signal })
    if (!response.ok) {
      const body = await response.json().catch(() => ({})) as ApiErrorBody
      if (response.status === 401) onSessionExpired?.()
      throw new Error(body.message ?? `文件下载失败（${response.status}）`)
    }
    const blob = await response.blob()
    if (signal.aborted) throw new DOMException('Session changed', 'AbortError')
    return blob
  },
  conversations(apiBase: string, signal?: AbortSignal) {
    return request<GuideConversationSummary[]>(apiBase, '/api/shop/v1/guide/conversations', { username: '', password: '' }, { signal })
  },
  createConversation(apiBase: string, signal?: AbortSignal) {
    return request<GuideConversationDetail>(apiBase, '/api/shop/v1/guide/conversations', { username: '', password: '' }, { method: 'POST', signal })
  },
  conversation(apiBase: string, id: string, signal?: AbortSignal) {
    return request<GuideConversationDetail>(apiBase, `/api/shop/v1/guide/conversations/${id}`, { username: '', password: '' }, { signal })
  },
  sendConversation(apiBase: string, id: string, requestId: string, content: string, signal: AbortSignal) {
    return request<GuideTurnRequest>(apiBase, `/api/shop/v1/guide/conversations/${id}/messages`, { username: '', password: '' }, {
      method: 'POST', body: JSON.stringify({ requestId, content }), signal,
    })
  },
  retryConversation(apiBase: string, id: string, requestId: string, signal: AbortSignal) {
    return request<GuideTurnRequest>(apiBase, `/api/shop/v1/guide/conversations/${id}/requests/${requestId}/retry`, { username: '', password: '' }, { method: 'POST', signal })
  },
  product(apiBase: string, id: number, signal?: AbortSignal): Promise<PublicProduct> {
    return request<PublicProduct>(apiBase, `/api/shop/v1/products/${id}`, { username: '', password: '' }, { signal })
  },
  guide(apiBase: string, messages: GuideMessage[], signal: AbortSignal): Promise<GuideReply> {
    return request<GuideReply>(apiBase, '/api/shop/v1/guide/messages', { username: '', password: '' }, {
      method: 'POST', body: JSON.stringify({ messages }), signal,
    })
  },
  onSessionExpired(handler: (() => void) | null) {
    onSessionExpired = handler
  },
  async login(apiBase: string, credentials: ShopperCredentials): Promise<AuthMe> {
    const me = await request<AuthMe>(apiBase, '/api/auth/v1/login', credentials, {
      method: 'POST', body: JSON.stringify(credentials),
    })
    csrfToken = ''
    await ensureCsrf(apiBase)
    return me
  },
  me(apiBase: string): Promise<AuthMe> {
    return request<AuthMe>(apiBase, '/api/auth/v1/me', { username: '', password: '' })
  },
  async register(apiBase: string, input: ShopperCredentials & { inviteCode: string; displayName: string }) {
    return request<{ userId: number; displayName: string }>(apiBase, '/api/auth/v1/register', input, {
      method: 'POST', body: JSON.stringify(input),
    })
  },
  async logout(apiBase: string) {
    await request<void>(apiBase, '/api/auth/v1/logout', { username: '', password: '' }, { method: 'POST' })
    csrfToken = ''
  },
  cart(apiBase: string, credentials: ShopperCredentials) {
    return request<ShoppingCart>(apiBase, '/api/shop/v1/cart', credentials)
  },
  setCartItem(apiBase: string, credentials: ShopperCredentials, skuId: number, quantity: number) {
    return request<ShoppingCart>(apiBase, `/api/shop/v1/cart/items/${skuId}`, credentials, {
      method: 'PUT',
      body: JSON.stringify({ quantity }),
    })
  },
  removeCartItem(apiBase: string, credentials: ShopperCredentials, skuId: number) {
    return request<ShoppingCart>(apiBase, `/api/shop/v1/cart/items/${skuId}`, credentials, {
      method: 'DELETE',
    })
  },
  orders(apiBase: string, credentials: ShopperCredentials) {
    return request<SalesOrder[]>(apiBase, '/api/shop/v1/orders', credentials)
  },
  createOrder(
    apiBase: string,
    credentials: ShopperCredentials,
    idempotencyKey: string,
    details: CheckoutDetails,
  ) {
    return request<SalesOrder>(apiBase, '/api/shop/v1/orders', credentials, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(details),
    })
  },
  createCheckout(apiBase: string, credentials: ShopperCredentials, idempotencyKey: string, details: CheckoutDetails) {
    return request<CheckoutGroup>(apiBase, '/api/shop/v1/checkouts', credentials, {
      method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(details),
    })
  },
  mockPayment(
    apiBase: string,
    credentials: ShopperCredentials,
    orderId: number,
    idempotencyKey: string,
    result: MockPaymentResult,
  ) {
    return request<SalesOrder>(apiBase, `/api/shop/v1/orders/${orderId}/mock-payment`, credentials, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ result }),
    })
  },
  cancelOrder(apiBase: string, credentials: ShopperCredentials, orderId: number) {
    return request<SalesOrder>(apiBase, `/api/shop/v1/orders/${orderId}/cancel`, credentials, {
      method: 'POST',
    })
  },
  confirmReceipt(apiBase: string, credentials: ShopperCredentials, orderId: number) {
    return request<SalesOrder>(apiBase, `/api/shop/v1/orders/${orderId}/confirm-receipt`, credentials, {
      method: 'POST',
    })
  },
  requestRefund(
    apiBase: string,
    credentials: ShopperCredentials,
    orderId: number,
    idempotencyKey: string,
    reason: string,
  ) {
    return request<SalesOrder>(apiBase, `/api/shop/v1/orders/${orderId}/after-sales`, credentials, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ reason }),
    })
  },
}
