import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isAfterSaleBlockingShipment,
  isPaymentInProgress,
  isRefundInProgress,
  needsMerchantOrderPolling,
  needsShopperOrderPolling,
  nextOrderPollingDelay,
  type SalesOrder,
} from './index.ts'

function order(overrides: Partial<SalesOrder>): SalesOrder {
  return {
    id: 1,
    orderNo: 'ORDER-1',
    userId: 1,
    merchantId: 1,
    status: 'PENDING_PAYMENT',
    totalAmount: 100,
    currency: 'CNY',
    recipientName: '顾客',
    recipientPhone: '13800138000',
    shippingAddress: '深圳',
    expiresAt: '2026-09-20T00:00:00Z',
    createdAt: '2026-09-20T00:00:00Z',
    payment: null,
    shipment: null,
    afterSale: null,
    items: [],
    reservations: [],
    ...overrides,
  }
}

test('payment and refund processing states are non-terminal', () => {
  assert.equal(isPaymentInProgress('PENDING'), true)
  assert.equal(isPaymentInProgress('PROCESSING'), true)
  assert.equal(isPaymentInProgress('SUCCEEDED'), false)
  assert.equal(isRefundInProgress('PENDING'), true)
  assert.equal(isRefundInProgress('PROCESSING'), true)
  assert.equal(isRefundInProgress('FAILED'), false)
})

test('shopper polling follows payment and refund transitions', () => {
  assert.equal(needsShopperOrderPolling(order({
    payment: { provider: 'mock', providerPaymentId: 'pay-1', status: 'PROCESSING' },
  })), true)
  assert.equal(needsShopperOrderPolling(order({
    status: 'PAID',
    afterSale: {
      id: 2,
      status: 'REFUND_PROCESSING',
      reason: '不需要了',
      merchantNote: '同意退款',
      requestedAt: '2026-09-20T00:00:00Z',
      resolvedAt: null,
      refund: null,
    },
  })), true)
  assert.equal(needsShopperOrderPolling(order({
    payment: { provider: 'mock', providerPaymentId: 'pay-1', status: 'FAILED' },
  })), false)
})

test('merchant polling stops on refund failure and shipping remains blocked', () => {
  const failed = order({
    status: 'PAID',
    afterSale: {
      id: 2,
      status: 'REFUND_FAILED',
      reason: '不需要了',
      merchantNote: '同意退款',
      requestedAt: '2026-09-20T00:00:00Z',
      resolvedAt: null,
      refund: {
        id: 3,
        provider: 'mock',
        providerRefundId: null,
        amount: 100,
        currency: 'CNY',
        status: 'FAILED',
        refundedAt: null,
        failureCode: 'MOCK_REJECTED',
      },
    },
  })
  assert.equal(needsMerchantOrderPolling(failed), false)
  assert.equal(isAfterSaleBlockingShipment(failed.afterSale?.status), true)
  assert.equal(isAfterSaleBlockingShipment('REQUESTED'), true)
  assert.equal(isAfterSaleBlockingShipment('REFUND_PROCESSING'), true)
  assert.equal(isAfterSaleBlockingShipment('REFUND_FAILED'), true)
  assert.equal(isAfterSaleBlockingShipment('REFUNDED'), true)
  assert.equal(isAfterSaleBlockingShipment('REJECTED'), false)
})

test('merchant polling follows pending and processing refund channel states', () => {
  for (const status of ['PENDING', 'PROCESSING'] as const) {
    assert.equal(needsMerchantOrderPolling(order({
      status: 'PAID',
      afterSale: {
        id: 2,
        status: 'REFUND_PROCESSING',
        reason: '不需要了',
        merchantNote: '同意退款',
        requestedAt: '2026-09-20T00:00:00Z',
        resolvedAt: null,
        refund: {
          id: 3,
          provider: 'mock',
          providerRefundId: null,
          amount: 100,
          currency: 'CNY',
          status,
          refundedAt: null,
        },
      },
    })), true)
  }
})

test('bounded polling uses two-second intervals and stops after thirty seconds', () => {
  assert.equal(nextOrderPollingDelay(1_000, 1_000), 2_000)
  assert.equal(nextOrderPollingDelay(1_000, 29_500), 1_500)
  assert.equal(nextOrderPollingDelay(1_000, 31_000), null)
  assert.equal(nextOrderPollingDelay(1_000, 35_000), null)
})
