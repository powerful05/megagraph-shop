package com.megagraph.shop.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank @Size(max = 80) String recipientName,
        @NotBlank @Size(max = 32) String recipientPhone,
        @NotBlank @Size(max = 500) String shippingAddress
) {
}
