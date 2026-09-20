package com.megagraph.shop.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.CreateAfterSaleRequest;
import com.megagraph.shop.api.OrderResponse;
import com.megagraph.shop.api.ResolveAfterSaleRequest;
import com.megagraph.shop.catalog.ProductSku;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.projection.OutboxEvent;
import com.megagraph.shop.projection.OutboxEventRepository;
import com.megagraph.shop.shared.ConflictException;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSaleService {

    private final SalesOrderRepository orders;
    private final PaymentOrderRepository payments;
    private final StockReservationRepository reservations;
    private final ProductSkuRepository skus;
    private final AfterSaleRequestRepository afterSales;
    private final RefundOrderRepository refunds;
    private final RefundGateway refundGateway;
    private final OutboxEventRepository outbox;
    private final OrderMapper mapper;
    private final ObjectMapper objectMapper;

    public AfterSaleService(
            SalesOrderRepository orders,
            PaymentOrderRepository payments,
            StockReservationRepository reservations,
            ProductSkuRepository skus,
            AfterSaleRequestRepository afterSales,
            RefundOrderRepository refunds,
            RefundGateway refundGateway,
            OutboxEventRepository outbox,
            OrderMapper mapper,
            ObjectMapper objectMapper
    ) {
        this.orders = orders;
        this.payments = payments;
        this.reservations = reservations;
        this.skus = skus;
        this.afterSales = afterSales;
        this.refunds = refunds;
        this.refundGateway = refundGateway;
        this.outbox = outbox;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
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
        AfterSaleRequest afterSale = requireAfterSaleForUpdate(merchantId, orderId);
        if (afterSale.getStatus() != AfterSaleStatus.REQUESTED) {
            if (afterSale.getStatus() == AfterSaleStatus.REFUNDED
                    && normalizedKey.equals(afterSale.getResolutionIdempotencyKey())) {
                return mapper.toResponse(order);
            }
            throw new ConflictException("AFTER_SALE_ALREADY_RESOLVED", "退款申请已经处理");
        }
        ensureResolutionKeyAvailable(merchantId, afterSale.getId(), normalizedKey);

        PaymentOrder payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "支付单不存在"));
        if (order.getStatus() != OrderStatus.PAID || payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("ORDER_NOT_REFUNDABLE", "订单当前不能退款");
        }

        RefundResult result = refundGateway.refund(
                payment.getProviderPaymentId(),
                payment.getAmount(),
                payment.getCurrency(),
                normalizedKey
        );
        afterSale.markRefunded(normalizedKey, request.note().trim());
        payment.refund();
        order.markRefunded();
        restockConfirmedReservations(orderId);
        RefundOrder refund = refunds.save(new RefundOrder(
                afterSale.getId(),
                orderId,
                payment.getId(),
                result.provider(),
                result.providerRefundId(),
                payment.getAmount(),
                payment.getCurrency()
        ));

        orders.flush();
        payments.flush();
        afterSales.flush();
        refunds.flush();
        saveOrderEvent(order, "ORDER_REFUNDED", Map.of(
                "afterSaleId", afterSale.getId(),
                "refundId", refund.getId(),
                "amount", refund.getAmount(),
                "currency", refund.getCurrency()
        ));
        saveAfterSaleEvent(afterSale, "AFTER_SALE_REFUNDED", Map.of(
                "refundId", refund.getId(),
                "providerRefundId", refund.getProviderRefundId()
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

    private void restockConfirmedReservations(Long orderId) {
        List<StockReservation> confirmed = reservations.findByOrderIdOrderByIdAsc(orderId).stream()
                .filter(value -> value.getStatus() == ReservationStatus.CONFIRMED)
                .toList();
        List<Long> skuIds = confirmed.stream().map(StockReservation::getSkuId).distinct().sorted().toList();
        Map<Long, ProductSku> lockedSkus = skus.findAllByIdForUpdate(skuIds).stream()
                .collect(Collectors.toMap(ProductSku::getId, Function.identity()));
        for (StockReservation reservation : confirmed) {
            if (reservation.restock()) {
                ProductSku sku = lockedSkus.get(reservation.getSkuId());
                if (sku == null) {
                    throw new IllegalStateException("Refunded SKU no longer exists");
                }
                sku.release(reservation.getQuantity());
            }
        }
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
