package com.megagraph.shop.api;

import com.megagraph.shop.commerce.MockPaymentResult;
import jakarta.validation.constraints.NotNull;

public record MockPaymentResultRequest(
        @NotNull MockPaymentResult result
) {
}
