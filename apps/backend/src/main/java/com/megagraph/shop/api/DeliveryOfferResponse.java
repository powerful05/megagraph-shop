package com.megagraph.shop.api;

import java.math.BigDecimal;

public record DeliveryOfferResponse(
    Long id,
    Long version,
    Long merchantId,
    String merchantName,
    String deliveryType,
    String title,
    String description,
    BigDecimal price,
    String currency,
    String status,
    Long confirmedVersion,
    boolean testOnly,
    Long assetVersion,
    String assetHash,
    String deliveryTerms,
    String refundPolicy,
    int availabilityHours) {}
