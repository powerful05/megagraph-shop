package com.megagraph.shop.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record SupplyDetails(
        @NotBlank @Size(max = 80) String color,
        @NotBlank @Size(max = 200) String fabric,
        @NotBlank @Size(max = 80) String fit,
        @NotNull @Size(min = 1, max = 4) List<@NotBlank @Size(max = 20) String> seasons,
        @NotNull @Size(min = 1, max = 8) List<@NotBlank @Size(max = 40) String> scenarios,
        @NotBlank @Size(max = 300) String measurementNote,
        @NotBlank @Size(max = 300) String imageSourceNote,
        @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid SizeRow> sizeChart
) {
    public record SizeRow(
            @NotBlank @Size(max = 100) String skuCode,
            @NotBlank @Size(max = 40) String sizeLabel,
            @NotNull @DecimalMin("1") @DecimalMax("400") BigDecimal chestCm,
            @NotNull @DecimalMin("1") @DecimalMax("300") BigDecimal lengthCm,
            @DecimalMin("1") @DecimalMax("200") BigDecimal shoulderCm,
            @DecimalMin("1") @DecimalMax("200") BigDecimal sleeveCm
    ) {
    }
}
