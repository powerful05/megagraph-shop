package com.megagraph.shop.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.megagraph.shop.api.strict.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record AppointmentSlotRequest(
    @NotNull @JsonDeserialize(using = StrictLong.class) Long version,
    @NotNull @JsonDeserialize(using = StrictInstant.class) Instant startAt,
    @NotNull @JsonDeserialize(using = StrictInstant.class) Instant endAt,
    @NotNull @Min(1) @Max(50) @JsonDeserialize(using = StrictInteger.class) Integer capacity) {
  public AppointmentSlotRequest {
    startAt = startAt == null ? null : startAt.truncatedTo(ChronoUnit.MILLIS);
    endAt = endAt == null ? null : endAt.truncatedTo(ChronoUnit.MILLIS);
  }
}
