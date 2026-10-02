package com.megagraph.shop.guide;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

public record GuideIntent(DeliveryType deliveryType, BigDecimal maxPrice, String currency,
        List<String> searchTerms, List<String> categoryKeywords, List<String> requiredColors,
        List<String> excludedColors, List<String> requiredFabrics, List<String> excludedFabrics, String requiredSize,
        boolean needsClarification, Reference reference, boolean requestsMonitoring) {
    public enum DeliveryType { UNKNOWN, PHYSICAL, DIGITAL, SERVICE }
    public enum ReferenceMode { NONE, EXACT, REFINE, CHEAPER, CHEAPER_REFINE, AMBIGUOUS }

    public record Reference(ReferenceMode mode, Integer assistantTurnIndex, Integer position) {
        public Reference {
            if (mode == null) throw new IllegalArgumentException("Invalid guide reference mode");
            if (mode == ReferenceMode.AMBIGUOUS) {
                assistantTurnIndex = null;
                position = null;
            }
            boolean targeted = mode == ReferenceMode.EXACT || mode == ReferenceMode.REFINE
                    || mode == ReferenceMode.CHEAPER || mode == ReferenceMode.CHEAPER_REFINE;
            if (targeted ? assistantTurnIndex == null || assistantTurnIndex < 1 || assistantTurnIndex > 17
                    || assistantTurnIndex % 2 == 0 || position == null || position < 1 || position > 3
                    : assistantTurnIndex != null || position != null) {
                throw new IllegalArgumentException("Invalid guide reference position");
            }
        }
        public static Reference none() { return new Reference(ReferenceMode.NONE, null, null); }
    }

    public GuideIntent {
        deliveryType = deliveryType == null ? DeliveryType.UNKNOWN : deliveryType;
        reference = reference == null ? Reference.none() : reference;
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
            List<String> requiredFabrics, List<String> excludedFabrics, String requiredSize, boolean needsClarification,
            Reference reference) {
        this(deliveryType, maxPrice, currency, searchTerms, categoryKeywords, requiredColors, excludedColors,
                requiredFabrics, excludedFabrics, requiredSize, needsClarification, reference, false);
    }

    public GuideIntent(DeliveryType deliveryType, BigDecimal maxPrice, String currency, List<String> searchTerms,
            List<String> categoryKeywords, List<String> requiredColors, List<String> excludedColors,
            List<String> requiredFabrics, List<String> excludedFabrics, String requiredSize, boolean needsClarification) {
        this(deliveryType, maxPrice, currency, searchTerms, categoryKeywords, requiredColors, excludedColors,
                requiredFabrics, excludedFabrics, requiredSize, needsClarification, Reference.none());
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
