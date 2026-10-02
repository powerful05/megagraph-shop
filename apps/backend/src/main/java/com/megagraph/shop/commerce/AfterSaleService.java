package com.megagraph.shop.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.CreateAfterSaleRequest;
import com.megagraph.shop.api.OrderResponse;
import com.megagraph.shop.api.ResolveAfterSaleRequest;
import com.megagraph.shop.projection.OutboxEvent;
import com.megagraph.shop.projection.OutboxEventRepository;
import com.megagraph.shop.payment.ChannelEventType;
import com.megagraph.shop.payment.ChannelResourceType;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.payment.PaymentChannelCommand;
import com.megagraph.shop.payment.PaymentChannelCommandRepository;
import com.megagraph.shop.payment.RefundAttempt;
import com.megagraph.shop.payment.RefundAttemptRepository;
import com.megagraph.shop.shared.ConflictException;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSaleService {

    private final SalesOrderRepository orders;
    private final PaymentOrderRepository payments;
    private final AfterSaleRequestRepository afterSales;
    private final RefundOrderRepository refunds;
    private final RefundAttemptRepository refundAttempts;
    private final PaymentChannelCommandRepository channelCommands;
    private final CommerceOutboxWriter commerceOutbox;
    private final OutboxEventRepository outbox;
    private final OrderMapper mapper;
    private final ObjectMapper objectMapper;
    private final com.megagraph.shop.delivery.DigitalDeliveryStore digital;
    private final com.megagraph.shop.delivery.AppointmentStore appointments;

    public AfterSaleService(
            SalesOrderRepository orders,
            PaymentOrderRepository payments,
            AfterSaleRequestRepository afterSales,
            RefundOrderRepository refunds,
            RefundAttemptRepository refundAttempts,
            PaymentChannelCommandRepository channelCommands,
            CommerceOutboxWriter commerceOutbox,
            OutboxEventRepository outbox,
            OrderMapper mapper,
            ObjectMapper objectMapper,
            com.megagraph.shop.delivery.DigitalDeliveryStore digital,
            com.megagraph.shop.delivery.AppointmentStore appointments
    ) {
        this.orders = orders;
        this.payments = payments;
        this.afterSales = afterSales;
        this.refunds = refunds;
        this.refundAttempts = refundAttempts;
        this.channelCommands = channelCommands;
        this.commerceOutbox = commerceOutbox;
        this.outbox = outbox;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.digital = digital;
        this.appointments = appointments;
    }

    @Transactional
    public OrderResponse request(
            Long userId,
            Long orderId,
            String idempotencyKey,
            CreateAfterSaleRequest request
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        AfterSaleRequest duplicate = afterSales
                .findByUserIdAndRequestIdempotencyKey(userId, normalizedKey)
                .orElse(null);
        if (duplicate != null) {
            if (!duplicate.getOrderId().equals(orderId)) {
                throw new ConflictException("AFTER_SALE_IDEMPOTENCY_CONFLICT", "该幂等键已用于其他订单");
            }
            return mapper.toResponse(requireOwnedOrder(userId, orderId));
        }

        SalesOrder order = orders.findByIdForUpdate(orderId)
                .filter(value -> value.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
        AfterSaleRequest existing = afterSales.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            throw new ConflictException("AFTER_SALE_ALREADY_EXISTS", "该订单已经提交过退款申请");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ConflictException("ORDER_NOT_REFUNDABLE", "只有已支付且未发货的订单可以申请退款");
        }

        digital.requireRefundable(order);
        appointments.requireRefundable(order);
        AfterSaleRequest afterSale = afterSales.saveAndFlush(new AfterSaleRequest(
                orderId,
                userId,
                order.getMerchantId(),
                normalizedKey,
                request.reason().trim()
        ));
        saveAfterSaleEvent(afterSale, "AFTER_SALE_REQUESTED", Map.of("reason", afterSale.getReason()));
        return mapper.toResponse(order);
    }

    @Transactional
    public OrderResponse approve(
            Long merchantId,
            Long orderId,
            String idempotencyKey,
            ResolveAfterSaleRequest request
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        SalesOrder order = requireMerchantOrderForUpdate(merchantId, orderId);
        PaymentOrder payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "支付单不存在"));
        AfterSaleRequest afterSale = requireAfterSaleForUpdate(merchantId, orderId);
        if (afterSale.getStatus() != AfterSaleStatus.REQUESTED) {
            if ((afterSale.getStatus() == AfterSaleStatus.REFUND_PROCESSING
                    || afterSale.getStatus() == AfterSaleStatus.REFUNDED)
                    && normalizedKey.equals(afterSale.getResolutionIdempotencyKey())) {
                return mapper.toResponse(order);
            }
            throw new ConflictException("AFTER_SALE_ALREADY_RESOLVED", "退款申请已经处理");
        }
        ensureResolutionKeyAvailable(merchantId, afterSale.getId(), normalizedKey);

        if (order.getStatus() != OrderStatus.PAID || payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("ORDER_NOT_REFUNDABLE", "订单当前不能退款");
        }

        digital.requireRefundable(order);
        appointments.requireRefundable(order);
        afterSale.startRefund(normalizedKey, request.note().trim());
        RefundOrder refund = refunds.saveAndFlush(new RefundOrder(
                afterSale.getId(),
                orderId,
                payment.getId(),
                payment.getProvider(),
                payment.getAmount(),
                payment.getCurrency()
        ));
        RefundAttempt attempt = refundAttempts.saveAndFlush(new RefundAttempt(
                refund.getId(),
                normalizedKey,
                1
        ));
        afterSales.flush();
        String providerEventId = nextMockEventId();
        PaymentChannelCommand command = channelCommands.saveAndFlush(new PaymentChannelCommand(
                payment.getProvider(),
                providerEventId,
                ChannelEventType.REFUND_SUCCEEDED,
                ChannelResourceType.REFUND,
                null,
                null,
                refund.getId(),
                attempt.getId(),
                refund.getAmount(),
                refund.getCurrency()
        ));
        commerceOutbox.save("REFUND", refund.getId(), refund.getVersion(), "REFUND_REQUESTED", Map.of(
                "refundId", refund.getId(),
                "afterSaleId", afterSale.getId(),
                "orderId", orderId,
                "provider", payment.getProvider(),
                "providerEventId", providerEventId,
                "oldStatus", "NONE",
                "newStatus", refund.getStatus().name()
        ));
        commerceOutbox.save("CHANNEL_COMMAND", command.getId(), 0L, "MOCK_REFUND_CALLBACK_REQUESTED", Map.of(
                "refundId", refund.getId(),
                "afterSaleId", afterSale.getId(),
                "orderId", orderId,
                "provider", payment.getProvider(),
                "providerEventId", providerEventId,
                "oldStatus", "NONE",
                "newStatus", "PENDING"
        ));
        return mapper.toResponse(order);
    }

    @Transactional
    public OrderResponse retry(
            Long merchantId,
            Long orderId,
            String idempotencyKey,
            ResolveAfterSaleRequest request
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        SalesOrder order = requireMerchantOrderForUpdate(merchantId, orderId);
        PaymentOrder payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "支付单不存在"));
        AfterSaleRequest afterSale = requireAfterSaleForUpdate(merchantId, orderId);
        RefundOrder refund = refunds.findByAfterSaleId(afterSale.getId())
                .flatMap(value -> refunds.findByIdForUpdate(value.getId()))
                .orElseThrow(() -> new NotFoundException("REFUND_NOT_FOUND", "退款单不存在"));
        RefundAttempt duplicate = refundAttempts
                .findByRefundOrderIdAndIdempotencyKey(refund.getId(), normalizedKey)
                .orElse(null);
        if (duplicate != null) {
            return mapper.toResponse(order);
        }
        if (afterSale.getStatus() != AfterSaleStatus.REFUND_FAILED
                || refund.getStatus() != RefundStatus.FAILED
                || order.getStatus() != OrderStatus.PAID
                || payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("REFUND_NOT_RETRYABLE", "当前退款不能重试");
        }
        ensureResolutionKeyAvailable(merchantId, afterSale.getId(), normalizedKey);
        afterSale.retryRefund(normalizedKey, request.note().trim());
        refund.retry();
        int attemptNo = Math.toIntExact(refundAttempts.countByRefundOrderId(refund.getId()) + 1);
        RefundAttempt attempt = refundAttempts.saveAndFlush(new RefundAttempt(
                refund.getId(), normalizedKey, attemptNo
        ));
        refunds.flush();
        afterSales.flush();
        String providerEventId = nextMockEventId();
        PaymentChannelCommand command = channelCommands.saveAndFlush(new PaymentChannelCommand(
                refund.getProvider(),
                providerEventId,
                ChannelEventType.REFUND_SUCCEEDED,
                ChannelResourceType.REFUND,
                refund.getProviderRefundId(),
                null,
                refund.getId(),
                attempt.getId(),
                refund.getAmount(),
                refund.getCurrency()
        ));
        commerceOutbox.save("REFUND", refund.getId(), refund.getVersion(), "REFUND_REQUESTED", Map.of(
                "refundId", refund.getId(),
                "afterSaleId", afterSale.getId(),
                "orderId", orderId,
                "provider", refund.getProvider(),
                "providerEventId", providerEventId,
                "oldStatus", RefundStatus.FAILED.name(),
                "newStatus", refund.getStatus().name()
        ));
        commerceOutbox.save("CHANNEL_COMMAND", command.getId(), 0L, "MOCK_REFUND_CALLBACK_REQUESTED", Map.of(
                "refundId", refund.getId(),
                "afterSaleId", afterSale.getId(),
                "orderId", orderId,
                "provider", refund.getProvider(),
                "providerEventId", providerEventId,
                "oldStatus", "FAILED",
                "newStatus", "PENDING"
        ));
        return mapper.toResponse(order);
    }

    @Transactional
    public OrderResponse reject(
            Long merchantId,
            Long orderId,
            String idempotencyKey,
            ResolveAfterSaleRequest request
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        SalesOrder order = requireMerchantOrderForUpdate(merchantId, orderId);
        AfterSaleRequest afterSale = requireAfterSaleForUpdate(merchantId, orderId);
        if (afterSale.getStatus() != AfterSaleStatus.REQUESTED) {
            if (afterSale.getStatus() == AfterSaleStatus.REJECTED
                    && normalizedKey.equals(afterSale.getResolutionIdempotencyKey())) {
                return mapper.toResponse(order);
            }
            throw new ConflictException("AFTER_SALE_ALREADY_RESOLVED", "退款申请已经处理");
        }
        ensureResolutionKeyAvailable(merchantId, afterSale.getId(), normalizedKey);

        afterSale.reject(normalizedKey, request.note().trim());
        afterSales.flush();
        saveAfterSaleEvent(afterSale, "AFTER_SALE_REJECTED", Map.of("note", afterSale.getMerchantNote()));
        return mapper.toResponse(order);
    }

    private SalesOrder requireOwnedOrder(Long userId, Long orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
    }

    private SalesOrder requireMerchantOrderForUpdate(Long merchantId, Long orderId) {
        return orders.findByIdForUpdate(orderId)
                .filter(value -> value.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
    }

    private AfterSaleRequest requireAfterSaleForUpdate(Long merchantId, Long orderId) {
        return afterSales.findByOrderIdForUpdate(orderId)
                .filter(value -> value.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new NotFoundException("AFTER_SALE_NOT_FOUND", "退款申请不存在"));
    }

    private void ensureResolutionKeyAvailable(Long merchantId, Long afterSaleId, String key) {
        afterSales.findByMerchantIdAndResolutionIdempotencyKey(merchantId, key)
                .filter(value -> !value.getId().equals(afterSaleId))
                .ifPresent(value -> {
                    throw new ConflictException("AFTER_SALE_IDEMPOTENCY_CONFLICT", "该幂等键已用于其他退款申请");
                });
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new ValidationException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 不能为空且不能超过 128 个字符");
        }
        return value.trim();
    }

    private String nextMockEventId() {
        return "mock_evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void saveOrderEvent(SalesOrder order, String eventType, Map<String, ?> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId());
        payload.put("orderNo", order.getOrderNo());
        payload.put("userId", order.getUserId());
        payload.put("merchantId", order.getMerchantId());
        payload.put("status", order.getStatus().name());
        payload.putAll(details);
        saveEvent("ORDER", order.getId(), order.getVersion(), eventType, payload);
    }

    private void saveAfterSaleEvent(AfterSaleRequest afterSale, String eventType, Map<String, ?> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("afterSaleId", afterSale.getId());
        payload.put("orderId", afterSale.getOrderId());
        payload.put("userId", afterSale.getUserId());
        payload.put("merchantId", afterSale.getMerchantId());
        payload.put("status", afterSale.getStatus().name());
        payload.putAll(details);
        saveEvent("AFTER_SALE", afterSale.getId(), afterSale.getVersion(), eventType, payload);
    }

    private void saveEvent(
            String aggregateType,
            Long aggregateId,
            Long aggregateVersion,
            String eventType,
            Map<String, ?> payload
    ) {
        try {
            outbox.save(new OutboxEvent(
                    aggregateType,
                    aggregateId,
                    aggregateVersion,
                    eventType,
                    objectMapper.writeValueAsString(payload)
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize after-sale outbox payload", exception);
        }
    }
}
