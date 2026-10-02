package com.megagraph.shop.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.megagraph.shop.api.strict.StrictLong;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record AppointmentOfferRequest(
    @JsonDeserialize(using = StrictLong.class) Long version,
    String deliveryType,
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 2000) String description,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
        BigDecimal price,
    @NotBlank String currency) {
  public DeliveryOfferRequest internal() {
    return new DeliveryOfferRequest(version, deliveryType, title, description, price, currency);
  }
}
