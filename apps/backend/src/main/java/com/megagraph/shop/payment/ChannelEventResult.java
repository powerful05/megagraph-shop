package com.megagraph.shop.payment;

public record ChannelEventResult(
        String provider,
        String providerEventId,
        ChannelEventProcessingStatus status,
        String failureCode
) {
}
