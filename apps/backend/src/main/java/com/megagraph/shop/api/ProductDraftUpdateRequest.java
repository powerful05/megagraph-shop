package com.megagraph.shop.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductDraftUpdateRequest(
        @NotNull Long version,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 300) String subtitle,
        @NotNull Long categoryId,
        @NotNull @Valid SkuDraftRequest sku,
        @Valid SupplyDetails supplyDetails
) {
}
