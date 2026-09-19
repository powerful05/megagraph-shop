package com.megagraph.shop.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.CreateOrderRequest;
import com.megagraph.shop.api.OrderResponse;
import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductSku;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.projection.OutboxEvent;
import com.megagraph.shop.projection.OutboxEventRepository;
import com.megagraph.shop.shared.ConflictException;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final ShoppingCartRepository carts;
    private final ShoppingCartItemRepository cartItems;
    private final ProductSkuRepository skus;
    private final SalesOrderRepository orders;
    private final SalesOrderItemRepository orderItems;
    private final StockReservationRepository reservations;
    private final PaymentOrderRepository payments;
    private final PaymentAttemptRepository paymentAttempts;
    private final SellableSkuGuard sellableSkuGuard;
    private final PaymentGateway paymentGateway;
    private final OutboxEventRepository outbox;
    private final OrderMapper mapper;
    private final ObjectMapper objectMapper;
    private final Duration paymentWindow;

    public OrderService(
            ShoppingCartRepository carts,
            ShoppingCartItemRepository cartItems,
            ProductSkuRepository skus,
            SalesOrderRepository orders,
            SalesOrderItemRepository orderItems,
            StockReservationRepository reservations,
            PaymentOrderRepository payments,
            PaymentAttemptRepository paymentAttempts,
            SellableSkuGuard sellableSkuGuard,
            PaymentGateway paymentGateway,
            OutboxEventRepository outbox,
            OrderMapper mapper,
            ObjectMapper objectMapper,
            @Value("${app.orders.payment-window:PT15M}") Duration paymentWindow
    ) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.skus = skus;
        this.orders = orders;
        this.orderItems = orderItems;
        this.reservations = reservations;
        this.payments = payments;
        this.paymentAttempts = paymentAttempts;
        this.sellableSkuGuard = sellableSkuGuard;
        this.paymentGateway = paymentGateway;
        this.outbox = outbox;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.paymentWindow = paymentWindow;
    }

    @Transactional
    public OrderResponse create(Long userId, String idempotencyKey, CreateOrderRequest request) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        return orders.findByUserIdAndIdempotencyKey(userId, normalizedKey)
                .map(mapper::toResponse)
                .orElseGet(() -> createNew(userId, normalizedKey, request));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listForUser(Long userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse getForUser(Long userId, Long orderId) {
        return mapper.toResponse(requireOwnedOrder(userId, orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listForMerchant(Long merchantId) {
        return orders.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    public OrderResponse applyMockPayment(
            Long userId,
            Long orderId,
            String idempotencyKey,
            MockPaymentResult result
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        SalesOrder order = orders.findByIdForUpdate(orderId)
                .filter(value -> value.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
        PaymentOrder payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "支付单不存在"));

        if (paymentAttempts.findByPaymentOrderIdAndIdempotencyKey(payment.getId(), normalizedKey).isPresent()) {
            return mapper.toResponse(order);
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            if (matchesTerminalResult(payment.getStatus(), result)) {
                return mapper.toResponse(order);
            }
            throw new ConflictException("PAYMENT_ALREADY_FINISHED", "支付单已经进入终态");
        }

        paymentAttempts.save(new PaymentAttempt(payment.getId(), normalizedKey, result));
        switch (result) {
            case SUCCESS -> completePayment(order, payment);
            case FAILURE -> failPayment(order, payment);
            case CANCELLED -> cancelPayment(order, payment, "CUSTOMER_CANCELLED_PAYMENT");
        }
        orders.flush();
        payments.flush();
        return mapper.toResponse(order);
    }

    @Transactional
    public OrderResponse cancel(Long userId, Long orderId) {
        SalesOrder order = orders.findByIdForUpdate(orderId)
                .filter(value -> value.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return mapper.toResponse(order);
        }
        PaymentOrder payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "支付单不存在"));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ConflictException("ORDER_CANNOT_CANCEL", "当前订单不能取消");
        }
        cancelPayment(order, payment, "CUSTOMER_CANCELLED_ORDER");
        orders.flush();
        payments.flush();
        return mapper.toResponse(order);
    }

    @Transactional
    public void expireOverdueOrders() {
        List<SalesOrder> overdue = orders.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                OrderStatus.PENDING_PAYMENT,
                Instant.now()
        );
        for (SalesOrder candidate : overdue) {
            SalesOrder order = orders.findByIdForUpdate(candidate.getId()).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                continue;
            }
            PaymentOrder payment = payments.findByOrderIdForUpdate(order.getId()).orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.PENDING) {
                continue;
            }
            payment.cancel();
            order.expire();
            releaseReservations(order.getId());
            orders.flush();
            saveEvent(order, "ORDER_CANCELLED", Map.of("reason", "PAYMENT_TIMEOUT"));
        }
    }

    private OrderResponse createNew(Long userId, String idempotencyKey, CreateOrderRequest request) {
        ShoppingCart cart = carts.findByUserId(userId)
                .orElseThrow(() -> new ValidationException("CART_EMPTY", "购物车为空"));
        List<ShoppingCartItem> requestedItems = cartItems.findByCartIdOrderByIdAsc(cart.getId());
        if (requestedItems.isEmpty()) {
            throw new ValidationException("CART_EMPTY", "购物车为空");
        }

        List<Long> skuIds = requestedItems.stream()
                .map(ShoppingCartItem::getSkuId)
                .distinct()
                .sorted()
                .toList();
        Map<Long, ProductSku> lockedSkus = skus.findAllByIdForUpdate(skuIds).stream()
                .collect(Collectors.toMap(ProductSku::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        if (lockedSkus.size() != skuIds.size()) {
            throw new NotFoundException("CART_SKU_NOT_FOUND", "购物车中的 SKU 不存在");
        }

        Map<Long, Product> productsBySku = new LinkedHashMap<>();
        Long merchantId = null;
        String currency = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ShoppingCartItem item : requestedItems) {
            ProductSku sku = lockedSkus.get(item.getSkuId());
            Product product = sellableSkuGuard.requireSellable(sku, item.getQuantity());
            if (merchantId != null && !merchantId.equals(product.getMerchantId())) {
                throw new ValidationException("ORDER_SINGLE_MERCHANT_REQUIRED", "当前阶段一张订单只能包含同一商家的商品");
            }
            if (currency != null && !currency.equals(sku.getCurrency())) {
                throw new ValidationException("ORDER_SINGLE_CURRENCY_REQUIRED", "一张订单只能使用一种币种");
            }
            merchantId = product.getMerchantId();
            currency = sku.getCurrency();
            productsBySku.put(sku.getId(), product);
            totalAmount = totalAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        Instant expiresAt = Instant.now().plus(paymentWindow);
        SalesOrder order = orders.saveAndFlush(new SalesOrder(
                nextOrderNo(),
                userId,
                merchantId,
                idempotencyKey,
                currency,
                totalAmount,
                request.recipientName().trim(),
                request.recipientPhone().trim(),
                request.shippingAddress().trim(),
                expiresAt
        ));

        for (ShoppingCartItem item : requestedItems) {
            ProductSku sku = lockedSkus.get(item.getSkuId());
            Product product = productsBySku.get(sku.getId());
            sku.reserve(item.getQuantity());
            orderItems.save(new SalesOrderItem(
                    order.getId(),
                    product.getId(),
                    sku.getId(),
                    product.getMerchantId(),
                    product.getTitle(),
                    sku.getSkuCode(),
                    sku.getSkuName(),
                    sku.getPrice(),
                    sku.getCurrency(),
                    item.getQuantity()
            ));
            reservations.save(new StockReservation(order.getId(), sku.getId(), item.getQuantity(), expiresAt));
        }

        PaymentSession session = paymentGateway.createPayment(order.getOrderNo(), totalAmount, currency);
        payments.save(new PaymentOrder(
                order.getId(),
                session.provider(),
                session.providerPaymentId(),
                totalAmount,
                currency
        ));
        cartItems.deleteAll(requestedItems);
        saveEvent(order, "ORDER_CREATED", Map.of("totalAmount", totalAmount, "currency", currency));
        orders.flush();
        payments.flush();
        return mapper.toResponse(order);
    }

    private void completePayment(SalesOrder order, PaymentOrder payment) {
        payment.succeed();
        order.markPaid();
        reservations.findByOrderIdOrderByIdAsc(order.getId()).forEach(StockReservation::confirm);
        orders.flush();
        saveEvent(order, "ORDER_PAID", Map.of("providerPaymentId", payment.getProviderPaymentId()));
    }

    private void failPayment(SalesOrder order, PaymentOrder payment) {
        payment.fail();
        order.markPaymentFailed("MOCK_PAYMENT_FAILED");
        releaseReservations(order.getId());
        orders.flush();
        saveEvent(order, "ORDER_CANCELLED", Map.of("reason", "PAYMENT_FAILED"));
    }

    private void cancelPayment(SalesOrder order, PaymentOrder payment, String reason) {
        payment.cancel();
        order.cancel(reason);
        releaseReservations(order.getId());
        orders.flush();
        saveEvent(order, "ORDER_CANCELLED", Map.of("reason", reason));
    }

    private void releaseReservations(Long orderId) {
        List<StockReservation> reserved = reservations.findByOrderIdOrderByIdAsc(orderId).stream()
                .filter(value -> value.getStatus() == ReservationStatus.RESERVED)
                .toList();
        if (reserved.isEmpty()) {
            return;
        }
        List<Long> skuIds = reserved.stream().map(StockReservation::getSkuId).distinct().sorted().toList();
        Map<Long, ProductSku> lockedSkus = skus.findAllByIdForUpdate(skuIds).stream()
                .collect(Collectors.toMap(ProductSku::getId, Function.identity()));
        for (StockReservation reservation : reserved) {
            if (reservation.release()) {
                ProductSku sku = lockedSkus.get(reservation.getSkuId());
                if (sku == null) {
                    throw new IllegalStateException("Reserved SKU no longer exists");
                }
                sku.release(reservation.getQuantity());
            }
        }
    }

    private SalesOrder requireOwnedOrder(Long userId, Long orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
    }

    private boolean matchesTerminalResult(PaymentStatus status, MockPaymentResult result) {
        return (status == PaymentStatus.SUCCEEDED && result == MockPaymentResult.SUCCESS)
                || (status == PaymentStatus.FAILED && result == MockPaymentResult.FAILURE)
                || (status == PaymentStatus.CANCELLED && result == MockPaymentResult.CANCELLED);
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new ValidationException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 不能为空且不能超过 128 个字符");
        }
        return value.trim();
    }

    private String nextOrderNo() {
        return "MG" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }

    private void saveEvent(SalesOrder order, String eventType, Map<String, ?> details) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", order.getId());
            payload.put("orderNo", order.getOrderNo());
            payload.put("userId", order.getUserId());
            payload.put("merchantId", order.getMerchantId());
            payload.put("status", order.getStatus().name());
            payload.putAll(details);
            outbox.save(new OutboxEvent(
                    "ORDER",
                    order.getId(),
                    order.getVersion(),
                    eventType,
                    objectMapper.writeValueAsString(payload)
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize order outbox payload", exception);
        }
    }
}
