package com.megagraph.shop.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record MockCommandSnapshot(
        Long id,
        String provider,
        String providerEventId,
        ChannelEventType eventType,
        ChannelResourceType resourceType,
        String providerResourceId,
        Long refundOrderId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
}
