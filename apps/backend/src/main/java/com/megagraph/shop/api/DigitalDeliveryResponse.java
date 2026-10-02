package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;

public record DigitalDeliveryResponse(
    Long offerId,
    Long offerVersion,
    String title,
    String description,
    BigDecimal unitPrice,
    String currency,
    Long assetVersion,
    String assetHash,
    String deliveryTerms,
    String refundPolicy,
    int availabilityHours,
    Entitlement entitlement,
    boolean claimAvailable,
    boolean downloadAvailable) {
  public record Entitlement(
      Long id,
      String status,
      Instant availableAt,
      Instant expiresAt,
      Instant claimedAt,
      Instant revokedAt) {}
}
