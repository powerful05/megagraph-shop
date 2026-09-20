# 阶段 2C：未发货退款纵切片

## 目标

在不接入真实支付渠道的前提下，完成一条可验证的逆向交易链路：

```text
已支付且未发货 → 顾客申请退款 → 暂停发货
→ 商家批准 → Mock 退款 → 库存回补 → 订单已退款
或商家驳回 → 订单保持已支付 → 可继续发货
```

## 默认决策

- 本阶段只支持 `PAID` 且未发货订单的整单退款。
- 退款申请、退款单、支付单、订单和库存预占保持独立状态。
- 同一订单本阶段只允许一次退款申请。
- 顾客创建申请和商家处理都必须提供 `Idempotency-Key`。
- 存在 `REQUESTED` 退款申请时，订单不能发货。
- 批准退款时的金额、币种、支付单和 SKU 数量全部由服务端事实重算，不接受客户端金额。
- 本地 `MockRefundGateway` 放在 `RefundGateway` 接口后，可在后续替换为真实渠道。
- 退款成功后，支付单为 `REFUNDED`、订单为 `REFUNDED`、库存预占为 `RESTOCKED`。
- 退款、库存回补与 Outbox 事件在同一事务内提交。

## API

- `POST /api/shop/v1/orders/{orderId}/after-sales`：顾客提交退款原因，需要 `Idempotency-Key`。
- `POST /api/merchant/v1/orders/{orderId}/after-sales/approve`：商家批准并执行 Mock 退款，需要 `Idempotency-Key`。
- `POST /api/merchant/v1/orders/{orderId}/after-sales/reject`：商家驳回申请，需要 `Idempotency-Key`。
- 原有顾客和商家订单响应增加 `afterSale` 和 `refund` 快照。

## 验收场景

1. 未支付、已发货、已完成、已取消或已退款订单不能再申请本阶段退款。
2. 顾客只能给自己的订单提交申请。
3. 商家只能处理自己订单下的申请，且只有 `OWNER` 可批准资金退回。
4. 重复请求相同幂等键不会创建第二张退款申请或退款单。
5. 待审核退款申请会阻止商家发货。
6. 批准后只回补一次库存，重试不能重复增加 SKU 库存。
7. 驳回后订单、支付和库存状态不变，商家可继续发货。
8. `AFTER_SALE_REQUESTED`、`AFTER_SALE_REFUNDED`、`AFTER_SALE_REJECTED` 和 `ORDER_REFUNDED` 事件可追踪。

## 暂不包含

- 已发货退货、退货物流和商家收货质检。
- 部分商品、部分数量或部分金额退款。
- 多次售后工单、换货、客服介入和仲裁。
- 真实支付渠道的退款异步回调。
- 商家结算、通道手续费和账务对账。
