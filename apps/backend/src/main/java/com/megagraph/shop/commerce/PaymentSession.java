package com.megagraph.shop.commerce;

public record PaymentSession(
        String provider,
        String providerPaymentId
) {
}
