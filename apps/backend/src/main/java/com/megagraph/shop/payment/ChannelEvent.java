package com.megagraph.shop.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record ChannelEvent(
        String provider,
        String providerEventId,
        ChannelEventType eventType,
        ChannelResourceType resourceType,
        String providerResourceId,
        Instant occurredAt,
        BigDecimal amount,
        String currency,
        String payloadDigest
) {
}
