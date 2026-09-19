import type { Category, IngestionJob, MerchantProduct } from '@megagraph/domain-types'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export interface Credentials {
  username: string
  password: string
}

interface ApiErrorBody {
  code?: string
  message?: string
  fieldErrors?: Record<string, string>
}

function authHeader(credentials: Credentials): string {
  return `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`
}

async function request<T>(
  path: string,
  credentials: Credentials,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Authorization', authHeader(credentials))
  if (!(init.body instanceof FormData) && init.body != null) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as ApiErrorBody
    const fields = body.fieldErrors ? Object.values(body.fieldErrors).join('；') : ''
    throw new Error([body.message ?? `请求失败（${response.status}）`, fields].filter(Boolean).join('：'))
  }
  return response.json() as Promise<T>
}

export const merchantApi = {
  me(credentials: Credentials) {
    return request<{ userId: number; merchantId: number; role: string }>('/api/merchant/v1/me', credentials)
  },
  categories(credentials: Credentials) {
    return request<Category[]>('/api/merchant/v1/categories', credentials)
  },
  products(credentials: Credentials) {
    return request<MerchantProduct[]>('/api/merchant/v1/products', credentials)
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
  ) {
    return request<MerchantProduct>(`/api/merchant/v1/products/${product.id}/draft`, credentials, {
      method: 'PATCH',
      body: JSON.stringify({
        version: product.version,
        title: form.title,
        subtitle: form.subtitle,
        categoryId: form.categoryId,
        sku: { code: form.code, name: form.name, price: form.price, stock: form.stock },
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
      headers: { Authorization: authHeader(credentials) },
    })
    if (!response.ok) throw new Error('商品图片加载失败')
    return URL.createObjectURL(await response.blob())
  },
}

