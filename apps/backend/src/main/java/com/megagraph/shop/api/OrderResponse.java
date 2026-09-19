package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String orderNo,
        Long userId,
        Long merchantId,
        String status,
        BigDecimal totalAmount,
        String currency,
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        Instant expiresAt,
        Instant createdAt,
        PaymentResponse payment,
        ShipmentResponse shipment,
        List<OrderItemResponse> items,
        List<ReservationResponse> reservations
) {
}
