package com.megagraph.shop.guide;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

public record GuideIntent(DeliveryType deliveryType, BigDecimal maxPrice, String currency,
        List<String> searchTerms, List<String> categoryKeywords, List<String> requiredColors,
        List<String> excludedColors, List<String> requiredFabrics, List<String> excludedFabrics, String requiredSize,
        boolean needsClarification) {
    public enum DeliveryType { UNKNOWN, PHYSICAL, DIGITAL, SERVICE }

    public GuideIntent {
        deliveryType = deliveryType == null ? DeliveryType.UNKNOWN : deliveryType;
        currency = currency == null || currency.isBlank() ? "CNY" : currency.strip().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}") || maxPrice != null && (maxPrice.signum() < 0
                || maxPrice.compareTo(new BigDecimal("1000000")) > 0 || maxPrice.scale() > 2)) {
            throw new IllegalArgumentException("Invalid guide budget");
        }
        searchTerms = terms(searchTerms); categoryKeywords = terms(categoryKeywords);
        requiredColors = terms(requiredColors); excludedColors = terms(excludedColors);
        requiredFabrics = terms(requiredFabrics); excludedFabrics = terms(excludedFabrics);
        requiredSize = requiredSize == null ? "" : requiredSize.strip();
        if (requiredSize.length() > 16) throw new IllegalArgumentException("Invalid guide size");
    }

    public GuideIntent(DeliveryType deliveryType, BigDecimal maxPrice, String currency, List<String> searchTerms,
            List<String> categoryKeywords, List<String> requiredColors, List<String> excludedColors,
            List<String> requiredFabrics, List<String> excludedFabrics, String requiredSize) {
        this(deliveryType, maxPrice, currency, searchTerms, categoryKeywords, requiredColors, excludedColors,
                requiredFabrics, excludedFabrics, requiredSize, false);
    }

    public static GuideIntent empty() {
        return new GuideIntent(DeliveryType.UNKNOWN, null, "CNY", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "");
    }

    private static List<String> terms(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 10 || values.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 40)) {
            throw new IllegalArgumentException("Invalid guide terms");
        }
        return values.stream().map(String::strip).distinct().toList();
    }
}
