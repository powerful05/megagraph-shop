import type { MockPaymentResult, SalesOrder, ShoppingCart } from '@megagraph/domain-types'

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
  message?: string
  fieldErrors?: Record<string, string>
}

function authHeader(credentials: ShopperCredentials): string {
  return `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`
}

async function request<T>(
  apiBase: string,
  path: string,
  credentials: ShopperCredentials,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Authorization', authHeader(credentials))
  if (init.body != null) headers.set('Content-Type', 'application/json')
  const response = await fetch(`${apiBase}${path}`, { ...init, headers })
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as ApiErrorBody
    const fields = body.fieldErrors ? Object.values(body.fieldErrors).join('；') : ''
    throw new Error([body.message ?? `请求失败（${response.status}）`, fields].filter(Boolean).join('：'))
  }
  return response.json() as Promise<T>
}

export const shopperApi = {
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
