package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AppointmentOfferResponse(
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
    String deliveryTerms,
    String refundPolicy,
    List<Slot> slots) {
  public record Slot(
      Long id,
      Long offerId,
      Long version,
      Instant startAt,
      Instant endAt,
      int capacity,
      int remaining,
      String status) {}
}
