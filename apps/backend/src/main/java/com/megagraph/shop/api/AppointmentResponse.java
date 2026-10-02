package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;

public record AppointmentResponse(
    Long offerId,
    Long offerVersion,
    String title,
    String description,
    BigDecimal unitPrice,
    String currency,
    Long slotId,
    Long slotVersion,
    Instant startAt,
    Instant endAt,
    int capacity,
    String deliveryTerms,
    String refundPolicy,
    boolean testOnly,
    Reservation reservation,
    boolean completeAvailable) {
  public record Reservation(
      Long id,
      Long version,
      String status,
      Instant heldAt,
      Instant expiresAt,
      Instant confirmedAt,
      Instant releasedAt,
      String releaseReason,
      Instant fulfilledAt) {}
}
