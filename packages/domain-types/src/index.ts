export interface Category {
  id: number
  parentId: number | null
  name: string
  level: number
}

export interface ProductImage {
  id: number
  url: string
  role: 'MAIN' | 'DETAIL'
  originalFilename: string
}

export interface ProductSku {
  id: number
  code: string
  name: string
  price: number
  currency: string
  stock: number
  status: 'ENABLED' | 'DISABLED'
}

export interface MerchantProduct {
  id: number
  version: number
  categoryId: number | null
  title: string
  subtitle: string | null
  draftStatus: 'EDITING' | 'READY_FOR_CONFIRMATION' | 'CONFIRMED'
  publishStatus: 'UNPUBLISHED' | 'PUBLISHED' | 'OFF_SHELF' | 'ARCHIVED'
  publishedAt: string | null
  images: ProductImage[]
  skus: ProductSku[]
}

export interface PublicProduct {
  id: number
  title: string
  subtitle: string | null
  categoryId: number
  priceFrom: number
  currency: string
  availableStock: number
  mainImageUrl: string | null
  publishedAt: string
  skus: PublicSku[]
}

export interface PublicSku {
  id: number
  code: string
  name: string
  price: number
  currency: string
  availableStock: number
}

export interface CartItem {
  skuId: number
  productId: number
  merchantId: number
  productTitle: string
  skuCode: string
  skuName: string
  unitPrice: number
  currency: string
  quantity: number
  lineAmount: number
  availableStock: number
}

export interface ShoppingCart {
  cartId: number
  currency: string | null
  estimatedTotal: number
  items: CartItem[]
}

export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'PAYMENT_FAILED'
  | 'CANCELLED'
  | 'EXPIRED'
export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type ReservationStatus = 'RESERVED' | 'CONFIRMED' | 'RELEASED'
export type ShipmentStatus = 'SHIPPED' | 'DELIVERED'
export type MockPaymentResult = 'SUCCESS' | 'FAILURE' | 'CANCELLED'

export interface OrderItem {
  productId: number
  skuId: number
  productTitle: string
  skuCode: string
  skuName: string
  unitPrice: number
  currency: string
  quantity: number
  lineAmount: number
}

export interface PaymentSummary {
  provider: string
  providerPaymentId: string
  status: PaymentStatus
}

export interface StockReservation {
  skuId: number
  quantity: number
  status: ReservationStatus
}

export interface ShipmentSummary {
  id: number
  carrierName: string
  trackingNo: string
  status: ShipmentStatus
  shippedAt: string
  deliveredAt: string | null
}

export interface SalesOrder {
  id: number
  orderNo: string
  userId: number
  merchantId: number
  status: OrderStatus
  totalAmount: number
  currency: string
  recipientName: string
  recipientPhone: string
  shippingAddress: string
  expiresAt: string
  createdAt: string
  payment: PaymentSummary | null
  shipment: ShipmentSummary | null
  items: OrderItem[]
  reservations: StockReservation[]
}

export interface IngestionJob {
  id: number
  productId: number
  status: string
  originalFilename: string
  createdAt: string
  finishedAt: string | null
  steps: Array<{ type: string; status: string; attempts: number }>
}
