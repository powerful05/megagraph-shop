package com.megagraph.shop.api;

public record PaymentResponse(
        String provider,
        String providerPaymentId,
        String status
) {
}
