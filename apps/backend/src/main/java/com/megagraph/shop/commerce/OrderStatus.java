package com.megagraph.shop.commerce;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    COMPLETED,
    REFUNDED,
    PAYMENT_FAILED,
    CANCELLED,
    EXPIRED
}
