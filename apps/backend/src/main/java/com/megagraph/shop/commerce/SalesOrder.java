package com.megagraph.shop.commerce;

import com.megagraph.shop.shared.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sales_order")
public class SalesOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNo;
    private Long userId;
    private Long merchantId;
    private String idempotencyKey;
    private Long checkoutGroupId;
    private boolean testOrder;
    private String deliveryType = "PHYSICAL";

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;

    private BigDecimal totalAmount;
    private String recipientName;
    private String recipientPhone;
    private String shippingAddress;
    private Instant expiresAt;
    private Instant paidAt;
    private Instant cancelledAt;
    private String cancelReason;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected SalesOrder() {
    }

    public SalesOrder(
            String orderNo,
            Long userId,
            Long merchantId,
            String idempotencyKey,
            String currency,
            BigDecimal totalAmount,
            String recipientName,
            String recipientPhone,
            String shippingAddress,
            Instant expiresAt
    ) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.shippingAddress = shippingAddress;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markDigital() { this.deliveryType = "DIGITAL_FILE"; this.testOrder = true; }
    public void markAppointment() { this.deliveryType = "APPOINTMENT"; this.testOrder = true; }
    public String getDeliveryType() { return deliveryType; }
    public Instant getPaidAt() { return paidAt; }

    public void markPaid() {
        requirePending();
        this.status = OrderStatus.PAID;
        this.paidAt = Instant.now();
    }

    public void assignCheckout(Long groupId, boolean testOrder) {
        this.checkoutGroupId = groupId;
        this.testOrder = testOrder;
    }
    public Long getCheckoutGroupId() { return checkoutGroupId; }
    public boolean isTestOrder() { return testOrder; }

    public void markShipped() {
        if (status != OrderStatus.PAID) {
            throw new ConflictException("ORDER_NOT_READY_TO_SHIP", "只有已支付订单可以发货");
        }
        this.status = OrderStatus.SHIPPED;
    }

    public void markCompleted() {
        if (status != OrderStatus.SHIPPED) {
            throw new ConflictException("ORDER_NOT_IN_TRANSIT", "只有运输中的订单可以确认收货");
        }
        this.status = OrderStatus.COMPLETED;
    }

    public void markRefunded() {
        if (status != OrderStatus.PAID) {
            throw new ConflictException("ORDER_NOT_REFUNDABLE", "只有已支付且未发货的订单可以退款");
        }
        this.status = OrderStatus.REFUNDED;
    }

    public void markPaymentFailed(String reason) {
        requirePending();
        this.status = OrderStatus.PAYMENT_FAILED;
        this.cancelledAt = Instant.now();
        this.cancelReason = reason;
    }

    public void cancel(String reason) {
        requirePending();
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.cancelReason = reason;
    }

    public void expire() {
        requirePending();
        this.status = OrderStatus.EXPIRED;
        this.cancelledAt = Instant.now();
        this.cancelReason = "PAYMENT_TIMEOUT";
    }

    private void requirePending() {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new ConflictException("ORDER_NOT_PENDING_PAYMENT", "订单已不处于待支付状态");
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getRecipientPhone() {
        return recipientPhone;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
