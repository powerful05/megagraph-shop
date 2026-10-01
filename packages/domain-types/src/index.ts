export interface MerchantMembership {
  merchantId: number
  merchantName: string
  role: 'OWNER' | 'EDITOR' | 'PUBLISHER' | 'VIEWER'
}

export interface AuthMe {
  userId: number
  displayName: string
  memberships: MerchantMembership[]
}

export interface ShopCapabilities {
  mockPaymentAvailable: boolean
  guideAvailable?: boolean
  demoCatalogAvailable?: boolean
  guideModelName?: string
}

export interface GuideMessage {
  role: 'user' | 'assistant'
  content: string
}

export type GuideDemoProductId = 'blue-shirt' | 'ivory-knit' | 'gray-cardigan'

export interface GuideReply {
  answer: string
  products: PublicProduct[]
  demoProductIds: GuideDemoProductId[]
}

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

export interface SupplySizeRow {
  skuCode: string
  sizeLabel: string
  chestCm: number
  lengthCm: number
  shoulderCm: number | null
  sleeveCm: number | null
}

export interface SupplyDetails {
  color: string
  fabric: string
  fit: string
  seasons: string[]
  scenarios: string[]
  measurementNote: string
  imageSourceNote: string
  sizeChart: SupplySizeRow[]
}

export interface ProductEvidence {
  productVersion: number
  merchantId: number
  merchantName: string
  deliveryType: 'PHYSICAL'
  dataOrigin: 'MERCHANT' | 'LOCAL_TEST'
  confirmationStatus: 'MISSING' | 'UNCONFIRMED' | 'MERCHANT_CONFIRMED' | 'TEST_CONFIRMED'
  confirmedAt: string | null
  details: SupplyDetails | null
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
  evidence: ProductEvidence
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
  evidence: ProductEvidence
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
  | 'REFUNDED'
  | 'PAYMENT_FAILED'
  | 'CANCELLED'
  | 'EXPIRED'
export type ReservationStatus = 'RESERVED' | 'CONFIRMED' | 'RESTOCKED' | 'RELEASED'
export type ShipmentStatus = 'SHIPPED' | 'DELIVERED'
export type PaymentStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'REFUNDED'
  | 'FAILED'
  | 'CANCELLED'
export type RefundStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
export type AfterSaleStatus =
  | 'REQUESTED'
  | 'REFUND_PROCESSING'
  | 'REFUND_FAILED'
  | 'REFUNDED'
  | 'REJECTED'
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
  reconciliationRequired?: boolean
  reconciliationReason?: string | null
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

export interface RefundSummary {
  id: number
  provider: string
  providerRefundId: string | null
  amount: number
  currency: string
  status: RefundStatus
  refundedAt: string | null
  failureCode?: string | null
  failureMessage?: string | null
}

export interface AfterSaleSummary {
  id: number
  status: AfterSaleStatus
  reason: string
  merchantNote: string | null
  requestedAt: string
  resolvedAt: string | null
  refund: RefundSummary | null
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
  afterSale: AfterSaleSummary | null
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

export function isPaymentInProgress(status: PaymentStatus | null | undefined): boolean {
  return status === 'PENDING' || status === 'PROCESSING'
}

export function isRefundInProgress(status: RefundStatus | null | undefined): boolean {
  return status === 'PENDING' || status === 'PROCESSING'
}

export function isAfterSaleBlockingShipment(status: AfterSaleStatus | null | undefined): boolean {
  return status != null && status !== 'REJECTED'
}

export function needsShopperOrderPolling(
  order: Pick<SalesOrder, 'payment' | 'afterSale'>,
): boolean {
  return isPaymentInProgress(order.payment?.status)
    || order.afterSale?.status === 'REFUND_PROCESSING'
    || isRefundInProgress(order.afterSale?.refund?.status)
}

export function needsMerchantOrderPolling(
  order: Pick<SalesOrder, 'afterSale'>,
): boolean {
  return order.afterSale?.status === 'REFUND_PROCESSING'
    || isRefundInProgress(order.afterSale?.refund?.status)
}

export const ORDER_POLL_INTERVAL_MS = 2_000
export const ORDER_POLL_TIMEOUT_MS = 30_000

export function nextOrderPollingDelay(startedAt: number, now: number): number | null {
  const elapsed = Math.max(0, now - startedAt)
  const remaining = ORDER_POLL_TIMEOUT_MS - elapsed
  if (remaining <= 0) return null
  return Math.min(ORDER_POLL_INTERVAL_MS, remaining)
}
