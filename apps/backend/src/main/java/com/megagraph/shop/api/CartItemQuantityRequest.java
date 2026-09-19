package com.megagraph.shop.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemQuantityRequest(
        @NotNull @Min(1) @Max(999) Integer quantity
) {
}
