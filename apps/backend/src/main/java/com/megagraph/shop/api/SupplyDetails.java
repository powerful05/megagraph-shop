package com.megagraph.shop.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
        @Size(max = 80) String fit,
        @NotNull @Size(max = 4) List<@NotBlank @Size(max = 20) String> seasons,
        @NotNull @Size(min = 1, max = 8) List<@NotBlank @Size(max = 40) String> scenarios,
        @Size(max = 300) String measurementNote,
        @NotBlank @Size(max = 300) String imageSourceNote,
        @NotNull @Size(max = 30) List<@NotNull @Valid SizeRow> sizeChart,
        Profile profile,
        @NotNull @Size(max = 30) List<@NotNull @Valid Specification> specifications
) {
    public enum Profile { APPAREL, GENERAL_PHYSICAL }

    public SupplyDetails {
        profile = profile == null ? Profile.APPAREL : profile;
        fit = fit == null ? "" : fit;
        measurementNote = measurementNote == null ? "" : measurementNote;
        specifications = specifications == null ? List.of() : List.copyOf(specifications);
    }

    public SupplyDetails(String color, String fabric, String fit, List<String> seasons, List<String> scenarios,
            String measurementNote, String imageSourceNote, List<SizeRow> sizeChart) {
        this(color, fabric, fit, seasons, scenarios, measurementNote, imageSourceNote, sizeChart, Profile.APPAREL, List.of());
    }

    @JsonIgnore
    @AssertTrue(message = "服饰须填写版型、季节与成衣尺码；通用实物须填写规格且不能使用成衣尺码")
    public boolean isProfileValid() {
        if (profile == Profile.GENERAL_PHYSICAL) {
            return sizeChart != null && sizeChart.isEmpty() && (fit == null || fit.isBlank()) && !specifications.isEmpty();
        }
        return fit != null && !fit.isBlank() && seasons != null && !seasons.isEmpty()
                && measurementNote != null && !measurementNote.isBlank() && sizeChart != null && !sizeChart.isEmpty()
                && specifications.isEmpty();
    }

    public record Specification(
            @NotBlank @Size(max = 100) String skuCode,
            @NotBlank @Size(max = 40) String name,
            @NotBlank @Size(max = 200) String value
    ) {}

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
