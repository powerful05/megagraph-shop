import type { AuthMe, Category, IngestionJob, MerchantProduct, SalesOrder, SupplyDetails } from '@megagraph/domain-types'

const API_BASE = ''

export interface Credentials {
  username: string
  password: string
  merchantId: number
}

export interface ShipmentDetails {
  carrierName: string
  trackingNo: string
}

interface ApiErrorBody {
  code?: string
  message?: string
  fieldErrors?: Record<string, string>
}

let csrfToken = ''
let onSessionExpired: (() => void) | null = null
let merchantRequests = new AbortController()

export function cancelMerchantRequests() {
  merchantRequests.abort()
  merchantRequests = new AbortController()
  csrfToken = ''
}

async function ensureCsrf(): Promise<string> {
  if (!csrfToken) {
    const response = await fetch('/api/auth/v1/csrf', { credentials: 'same-origin' })
    if (!response.ok) throw new Error('无法获取请求安全令牌')
    csrfToken = (await response.json() as { token: string }).token
  }
  return csrfToken
}

async function request<T>(
  path: string,
  credentials: Credentials,
  init: RequestInit = {},
): Promise<T> {
  const signal = path.startsWith('/api/merchant/v1/') ? merchantRequests.signal : init.signal
  const headers = new Headers(init.headers)
  if (path.startsWith('/api/merchant/v1/')) {
    headers.set('X-Merchant-Id', String(credentials.merchantId))
  }
  if (init.method && !['GET', 'HEAD'].includes(init.method.toUpperCase())) {
    headers.set('X-CSRF-TOKEN', await ensureCsrf())
  }
  if (!(init.body instanceof FormData) && init.body != null) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...init, headers, credentials: 'same-origin',
    signal,
  })
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as ApiErrorBody
    if (body.code === 'CSRF_TOKEN_INVALID') {
      csrfToken = ''
      await ensureCsrf().catch(() => {})
    }
    if (response.status === 401 && path !== '/api/auth/v1/login') onSessionExpired?.()
    const fields = body.fieldErrors ? Object.values(body.fieldErrors).join('；') : ''
    throw new Error([body.message ?? `请求失败（${response.status}）`, fields].filter(Boolean).join('：'))
  }
  const result = response.status === 204 ? undefined : await response.json()
  if (signal?.aborted) throw new DOMException('Merchant selection changed', 'AbortError')
  return result as T
}

export const merchantApi = {
  onSessionExpired(handler: (() => void) | null) {
    onSessionExpired = handler
  },
  async login(credentials: Credentials): Promise<AuthMe> {
    const me = await request<AuthMe>('/api/auth/v1/login', credentials, {
      method: 'POST', body: JSON.stringify({ username: credentials.username, password: credentials.password }),
    })
    csrfToken = ''
    await ensureCsrf()
    return me
  },
  authMe(): Promise<AuthMe> {
    return request<AuthMe>('/api/auth/v1/me', { username: '', password: '', merchantId: 0 })
  },
  async logout(credentials: Credentials): Promise<void> {
    await request<void>('/api/auth/v1/logout', credentials, { method: 'POST' })
    csrfToken = ''
  },
  me(credentials: Credentials) {
    return request<{ userId: number; merchantId: number; role: string }>('/api/merchant/v1/me', credentials)
  },
  categories(credentials: Credentials) {
    return request<Category[]>('/api/merchant/v1/categories', credentials)
  },
  products(credentials: Credentials) {
    return request<MerchantProduct[]>('/api/merchant/v1/products', credentials)
  },
  orders(credentials: Credentials) {
    return request<SalesOrder[]>('/api/merchant/v1/orders', credentials)
  },
  shipOrder(
    credentials: Credentials,
    orderId: number,
    idempotencyKey: string,
    details: ShipmentDetails,
  ) {
    return request<SalesOrder>(`/api/merchant/v1/orders/${orderId}/ship`, credentials, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(details),
    })
  },
  resolveAfterSale(
    credentials: Credentials,
    orderId: number,
    action: 'approve' | 'reject',
    idempotencyKey: string,
    note: string,
  ) {
    return request<SalesOrder>(`/api/merchant/v1/orders/${orderId}/after-sales/${action}`, credentials, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ note }),
    })
  },
  retryRefund(
    credentials: Credentials,
    orderId: number,
    idempotencyKey: string,
    note: string,
  ) {
    return request<SalesOrder>(`/api/merchant/v1/orders/${orderId}/after-sales/refund-retry`, credentials, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ note }),
    })
  },
  upload(credentials: Credentials, image: File) {
    const data = new FormData()
    data.append('image', image)
    return request<IngestionJob>('/api/merchant/v1/ingestions', credentials, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: data,
    })
  },
  product(credentials: Credentials, id: number) {
    return request<MerchantProduct>(`/api/merchant/v1/products/${id}/draft`, credentials)
  },
  updateProduct(
    credentials: Credentials,
    product: MerchantProduct,
    form: { title: string; subtitle: string; categoryId: number; code: string; name: string; price: number; stock: number },
    supplyDetails: SupplyDetails | null,
  ) {
    return request<MerchantProduct>(`/api/merchant/v1/products/${product.id}/draft`, credentials, {
      method: 'PATCH',
      body: JSON.stringify({
        version: product.version,
        title: form.title,
        subtitle: form.subtitle,
        categoryId: form.categoryId,
        sku: { code: form.code, name: form.name, price: form.price, stock: form.stock },
        supplyDetails,
      }),
    })
  },
  confirm(credentials: Credentials, product: MerchantProduct) {
    return request<MerchantProduct>(`/api/merchant/v1/products/${product.id}/confirm`, credentials, {
      method: 'POST',
      body: JSON.stringify({ version: product.version }),
    })
  },
  publish(credentials: Credentials, product: MerchantProduct) {
    return request<MerchantProduct>(`/api/merchant/v1/products/${product.id}/publish`, credentials, {
      method: 'POST',
      body: JSON.stringify({ version: product.version }),
    })
  },
  offShelf(credentials: Credentials, product: MerchantProduct) {
    return request<MerchantProduct>(`/api/merchant/v1/products/${product.id}/off-shelf`, credentials, {
      method: 'POST',
      body: JSON.stringify({ version: product.version }),
    })
  },
  async imageUrl(credentials: Credentials, path: string): Promise<string> {
    const response = await fetch(`${API_BASE}${path}`, {
      headers: { 'X-Merchant-Id': String(credentials.merchantId) },
      credentials: 'same-origin',
      signal: merchantRequests.signal,
    })
    if (!response.ok) throw new Error('商品图片加载失败')
    return URL.createObjectURL(await response.blob())
  },
}
