package com.megagraph.shop.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record DeliveryOfferRequest(
    Long version,
    String deliveryType,
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 2000) String description,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
        BigDecimal price,
    @NotBlank String currency) {}
