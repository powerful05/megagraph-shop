package com.megagraph.shop.api;

import java.time.Instant;

public record ProductEvidence(
        Long productVersion,
        Long merchantId,
        String merchantName,
        String deliveryType,
        String dataOrigin,
        String confirmationStatus,
        Instant confirmedAt,
        SupplyDetails details
) {
}
