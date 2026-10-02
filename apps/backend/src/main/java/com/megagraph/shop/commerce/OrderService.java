package com.megagraph.shop.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.CreateOrderRequest;
import com.megagraph.shop.api.OrderResponse;
import com.megagraph.shop.api.ShipOrderRequest;
import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductSku;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.payment.ChannelEventType;
import com.megagraph.shop.payment.ChannelResourceType;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.payment.PaymentChannelCommand;
import com.megagraph.shop.payment.PaymentChannelCommandRepository;
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
  private final PaymentChannelCommandRepository channelCommands;
  private final ShipmentRepository shipments;
  private final AfterSaleRequestRepository afterSales;
  private final SellableSkuGuard sellableSkuGuard;
  private final PaymentGateway paymentGateway;
  private final OutboxEventRepository outbox;
  private final OrderMapper mapper;
  private final ObjectMapper objectMapper;
  private final CommerceOutboxWriter commerceOutbox;
  private final Duration paymentWindow;
  private final com.megagraph.shop.delivery.AppointmentStore appointments;
  private final org.springframework.transaction.support.TransactionTemplate expiryTransactions;

  public OrderService(
      ShoppingCartRepository carts,
      ShoppingCartItemRepository cartItems,
      ProductSkuRepository skus,
      SalesOrderRepository orders,
      SalesOrderItemRepository orderItems,
      StockReservationRepository reservations,
      PaymentOrderRepository payments,
      PaymentAttemptRepository paymentAttempts,
      PaymentChannelCommandRepository channelCommands,
      ShipmentRepository shipments,
      AfterSaleRequestRepository afterSales,
      SellableSkuGuard sellableSkuGuard,
      PaymentGateway paymentGateway,
      OutboxEventRepository outbox,
      OrderMapper mapper,
      ObjectMapper objectMapper,
      CommerceOutboxWriter commerceOutbox,
      @Value("${app.orders.payment-window:PT15M}") Duration paymentWindow,
      com.megagraph.shop.delivery.AppointmentStore appointments,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.carts = carts;
    this.cartItems = cartItems;
    this.skus = skus;
    this.orders = orders;
    this.orderItems = orderItems;
    this.reservations = reservations;
    this.payments = payments;
    this.paymentAttempts = paymentAttempts;
    this.channelCommands = channelCommands;
    this.shipments = shipments;
    this.afterSales = afterSales;
    this.sellableSkuGuard = sellableSkuGuard;
    this.paymentGateway = paymentGateway;
    this.outbox = outbox;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.commerceOutbox = commerceOutbox;
    this.paymentWindow = paymentWindow;
    this.appointments = appointments;
    this.expiryTransactions =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    this.expiryTransactions.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
  public OrderResponse create(Long userId, String idempotencyKey, CreateOrderRequest request) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    return orders
        .findByUserIdAndIdempotencyKey(userId, normalizedKey)
        .map(
            order -> {
              if (!"PHYSICAL".equals(order.getDeliveryType()))
                throw new ConflictException("ORDER_IDEMPOTENCY_CONFLICT", "该键已用于其他交付类型的订单");
              return mapper.toResponse(order);
            })
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
  public OrderResponse ship(
      Long merchantId, Long orderId, String idempotencyKey, ShipOrderRequest request) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    SalesOrder order =
        orders
            .findByIdForUpdate(orderId)
            .filter(value -> value.getMerchantId().equals(merchantId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
    if (!"PHYSICAL".equals(order.getDeliveryType()))
      throw new ConflictException("DELIVERY_NOT_PHYSICAL", "该订单不是实物发货订单");
    if (order.isTestOrder()) {
      throw new ValidationException("TEST_ORDER_FULFILLMENT_BLOCKED", "试用订单不允许创建真实物流或发货记录");
    }

    Shipment existing = shipments.findByOrderId(orderId).orElse(null);
    if (existing != null) {
      if (existing.getIdempotencyKey().equals(normalizedKey)) {
        return mapper.toResponse(order);
      }
      throw new ConflictException("ORDER_ALREADY_SHIPPED", "订单已经创建物流单");
    }
    shipments
        .findByMerchantIdAndIdempotencyKey(merchantId, normalizedKey)
        .ifPresent(
            value -> {
              throw new ConflictException("SHIPMENT_IDEMPOTENCY_CONFLICT", "该幂等键已用于其他订单");
            });
    if (afterSales.existsByOrderIdAndStatusIn(
        orderId,
        List.of(
            AfterSaleStatus.REQUESTED,
            AfterSaleStatus.REFUND_PROCESSING,
            AfterSaleStatus.REFUND_FAILED))) {
      throw new ConflictException("ORDER_HAS_PENDING_AFTER_SALE", "订单存在待处理退款申请，不能发货");
    }

    order.markShipped();
    Shipment shipment =
        shipments.save(
            new Shipment(
                orderId,
                merchantId,
                normalizedKey,
                request.carrierName().trim(),
                request.trackingNo().trim()));
    orders.flush();
    shipments.flush();
    saveEvent(
        order,
        "ORDER_SHIPPED",
        Map.of(
            "shipmentId", shipment.getId(),
            "carrierName", shipment.getCarrierName(),
            "trackingNo", shipment.getTrackingNo()));
    return mapper.toResponse(order);
  }

  @Transactional
  public OrderResponse confirmReceipt(Long userId, Long orderId) {
    SalesOrder order =
        orders
            .findByIdForUpdate(orderId)
            .filter(value -> value.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
    if (!"PHYSICAL".equals(order.getDeliveryType()))
      throw new ConflictException("DELIVERY_NOT_PHYSICAL", "该订单不是实物收货订单");
    if (order.getStatus() == OrderStatus.COMPLETED) {
      return mapper.toResponse(order);
    }
    Shipment shipment =
        shipments
            .findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new NotFoundException("SHIPMENT_NOT_FOUND", "物流单不存在"));

    order.markCompleted();
    shipment.markDelivered();
    orders.flush();
    shipments.flush();
    saveEvent(order, "ORDER_COMPLETED", Map.of("shipmentId", shipment.getId()));
    return mapper.toResponse(order);
  }

  @Transactional
  public OrderResponse applyMockPayment(
      Long userId, Long orderId, String idempotencyKey, MockPaymentResult result) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    SalesOrder order =
        orders
            .findByIdForUpdate(orderId)
            .filter(value -> value.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
    PaymentOrder payment =
        payments
            .findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "支付单不存在"));

    if (paymentAttempts
        .findByPaymentOrderIdAndIdempotencyKey(payment.getId(), normalizedKey)
        .isPresent()) {
      return mapper.toResponse(order);
    }
    if (payment.getStatus() != PaymentStatus.PENDING) {
      if (matchesTerminalResult(payment.getStatus(), result)) {
        return mapper.toResponse(order);
      }
      throw new ConflictException("PAYMENT_ALREADY_FINISHED", "支付单已经进入终态");
    }

    PaymentStatus oldStatus = payment.getStatus();
    paymentAttempts.save(new PaymentAttempt(payment.getId(), normalizedKey, result));
    payment.markProcessing(null);
    payments.flush();
    commerceOutbox.save(
        "PAYMENT",
        payment.getId(),
        payment.getVersion(),
        "PAYMENT_PROCESSING",
        Map.of(
            "paymentOrderId", payment.getId(),
            "orderId", order.getId(),
            "oldStatus", oldStatus.name(),
            "newStatus", payment.getStatus().name(),
            "provider", payment.getProvider()));
    String providerEventId = "mock_evt_" + UUID.randomUUID().toString().replace("-", "");
    PaymentChannelCommand command =
        channelCommands.saveAndFlush(
            new PaymentChannelCommand(
                payment.getProvider(),
                providerEventId,
                paymentEventType(result),
                ChannelResourceType.PAYMENT,
                payment.getProviderPaymentId(),
                payment.getId(),
                null,
                null,
                payment.getAmount(),
                payment.getCurrency()));
    commerceOutbox.save(
        "CHANNEL_COMMAND",
        command.getId(),
        0L,
        "MOCK_PAYMENT_CALLBACK_REQUESTED",
        Map.of(
            "paymentOrderId", payment.getId(),
            "orderId", order.getId(),
            "provider", payment.getProvider(),
            "providerEventId", providerEventId,
            "oldStatus", oldStatus.name(),
            "newStatus", payment.getStatus().name()));
    return mapper.toResponse(order);
  }

  @Transactional
  public OrderResponse cancel(Long userId, Long orderId) {
    SalesOrder order =
        orders
            .findByIdForUpdate(orderId)
            .filter(value -> value.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
    PaymentOrder payment =
        payments
            .findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "支付单不存在"));
    if (order.getStatus() == OrderStatus.CANCELLED) {
      return mapper.toResponse(order);
    }
    if (payment.getStatus() != PaymentStatus.PENDING) {
      throw new ConflictException("ORDER_CANNOT_CANCEL", "当前订单不能取消");
    }
    cancelPayment(order, payment, "CUSTOMER_CANCELLED_ORDER");
    orders.flush();
    payments.flush();
    return mapper.toResponse(order);
  }

  public void expireOverdueOrders() {
    for (Long id : orders.findOverdueIds(OrderStatus.PENDING_PAYMENT, Instant.now())) {
      expiryTransactions.executeWithoutResult(tx -> expireOne(id));
    }
  }

  private void expireOne(Long id) {
    SalesOrder order = orders.findByIdForUpdate(id).orElse(null);
    if (order == null
        || order.getStatus() != OrderStatus.PENDING_PAYMENT
        || Instant.now().isBefore(order.getExpiresAt())) return;
    PaymentOrder payment = payments.findByOrderIdForUpdate(id).orElse(null);
    if (payment == null
        || payment.getStatus() != PaymentStatus.PENDING
            && payment.getStatus() != PaymentStatus.PROCESSING) return;
    PaymentStatus old = payment.getStatus();
    payment.cancel();
    order.expire();
    releaseReservations(id);
    appointments.release(order, "EXPIRED");
    orders.flush();
    payments.flush();
    commerceOutbox.save(
        "PAYMENT",
        payment.getId(),
        payment.getVersion(),
        "PAYMENT_CANCELLED",
        Map.of(
            "paymentOrderId",
            payment.getId(),
            "orderId",
            id,
            "provider",
            payment.getProvider(),
            "oldStatus",
            old.name(),
            "newStatus",
            payment.getStatus().name(),
            "reason",
            "PAYMENT_TIMEOUT"));
    saveEvent(order, "ORDER_CANCELLED", Map.of("reason", "PAYMENT_TIMEOUT"));
  }

  private OrderResponse createNew(Long userId, String idempotencyKey, CreateOrderRequest request) {
    ShoppingCart cart =
        carts
            .findByUserIdForUpdate(userId)
            .orElseThrow(() -> new ValidationException("CART_EMPTY", "购物车为空"));
    List<ShoppingCartItem> requestedItems = cartItems.findByCartIdOrderByIdAsc(cart.getId());
    if (requestedItems.isEmpty()) {
      throw new ValidationException("CART_EMPTY", "购物车为空");
    }
    return createForItems(userId, idempotencyKey, request, requestedItems, null, false);
  }

  @Transactional
  public OrderResponse createForItems(
      Long userId,
      String idempotencyKey,
      CreateOrderRequest request,
      List<ShoppingCartItem> requestedItems,
      Long checkoutGroupId,
      boolean testOrder) {

    List<Long> skuIds =
        requestedItems.stream().map(ShoppingCartItem::getSkuId).distinct().sorted().toList();
    Map<Long, ProductSku> lockedSkus =
        skus.findAllByIdForUpdate(skuIds).stream()
            .collect(
                Collectors.toMap(
                    ProductSku::getId,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
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
      totalAmount =
          totalAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
    }

    Instant expiresAt = Instant.now().plus(paymentWindow);
    SalesOrder order =
        orders.saveAndFlush(
            new SalesOrder(
                nextOrderNo(),
                userId,
                merchantId,
                idempotencyKey,
                currency,
                totalAmount,
                request.recipientName().trim(),
                request.recipientPhone().trim(),
                request.shippingAddress().trim(),
                expiresAt));
    order.assignCheckout(
        checkoutGroupId,
        testOrder || productsBySku.values().stream().anyMatch(Product::isTestSupply));

    for (ShoppingCartItem item : requestedItems) {
      ProductSku sku = lockedSkus.get(item.getSkuId());
      Product product = productsBySku.get(sku.getId());
      sku.reserve(item.getQuantity());
      SalesOrderItem snapshot =
          new SalesOrderItem(
              order.getId(),
              product.getId(),
              sku.getId(),
              product.getMerchantId(),
              product.getTitle(),
              sku.getSkuCode(),
              sku.getSkuName(),
              sku.getPrice(),
              sku.getCurrency(),
              item.getQuantity());
      snapshot.captureSupply(product);
      orderItems.save(snapshot);
      reservations.save(
          new StockReservation(order.getId(), sku.getId(), item.getQuantity(), expiresAt));
    }

    PaymentSession session =
        paymentGateway.createPayment(order.getOrderNo(), totalAmount, currency);
    payments.save(
        new PaymentOrder(
            order.getId(), session.provider(), session.providerPaymentId(), totalAmount, currency));
    cartItems.deleteAll(requestedItems);
    saveEvent(order, "ORDER_CREATED", Map.of("totalAmount", totalAmount, "currency", currency));
    orders.flush();
    payments.flush();
    return mapper.toResponse(order);
  }

  private void cancelPayment(SalesOrder order, PaymentOrder payment, String reason) {
    PaymentStatus oldPaymentStatus = payment.getStatus();
    payment.cancel();
    order.cancel(reason);
    releaseReservations(order.getId());
    appointments.release(order, "CANCELLED");
    orders.flush();
    payments.flush();
    commerceOutbox.save(
        "PAYMENT",
        payment.getId(),
        payment.getVersion(),
        "PAYMENT_CANCELLED",
        Map.of(
            "paymentOrderId", payment.getId(),
            "orderId", order.getId(),
            "provider", payment.getProvider(),
            "oldStatus", oldPaymentStatus.name(),
            "newStatus", payment.getStatus().name(),
            "reason", reason));
    saveEvent(order, "ORDER_CANCELLED", Map.of("reason", reason));
  }

  private void releaseReservations(Long orderId) {
    List<StockReservation> reserved =
        reservations.findByOrderIdOrderByIdAsc(orderId).stream()
            .filter(value -> value.getStatus() == ReservationStatus.RESERVED)
            .toList();
    if (reserved.isEmpty()) {
      return;
    }
    List<Long> skuIds =
        reserved.stream().map(StockReservation::getSkuId).distinct().sorted().toList();
    Map<Long, ProductSku> lockedSkus =
        skus.findAllByIdForUpdate(skuIds).stream()
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
    return orders
        .findByIdAndUserId(orderId, userId)
        .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "订单不存在"));
  }

  private boolean matchesTerminalResult(PaymentStatus status, MockPaymentResult result) {
    return (status == PaymentStatus.SUCCEEDED && result == MockPaymentResult.SUCCESS)
        || (status == PaymentStatus.FAILED && result == MockPaymentResult.FAILURE)
        || (status == PaymentStatus.CANCELLED && result == MockPaymentResult.CANCELLED);
  }

  private ChannelEventType paymentEventType(MockPaymentResult result) {
    return switch (result) {
      case SUCCESS -> ChannelEventType.PAYMENT_SUCCEEDED;
      case FAILURE -> ChannelEventType.PAYMENT_FAILED;
      case CANCELLED -> ChannelEventType.PAYMENT_CANCELLED;
    };
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
      outbox.save(
          new OutboxEvent(
              "ORDER",
              order.getId(),
              order.getVersion(),
              eventType,
              objectMapper.writeValueAsString(payload)));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize order outbox payload", exception);
    }
  }
}
