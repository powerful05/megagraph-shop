package com.megagraph.shop.api;

public record PaymentCallbackResponse(
        String provider,
        String eventId,
        String processingStatus,
        boolean duplicate
) {
}
