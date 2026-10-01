package com.megagraph.shop.guide;

import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.api.PublicSkuResponse;
import com.megagraph.shop.api.SupplyDetails;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;

public class GuideCatalogSearch {
    private final Map<Long, String> categoryNames;

    public GuideCatalogSearch() { this(Map.of()); }
    public GuideCatalogSearch(Map<Long, String> categoryNames) { this.categoryNames = categoryNames == null ? Map.of() : Map.copyOf(categoryNames); }
    public List<PublicProductResponse> search(List<PublicProductResponse> catalog, GuideIntent intent, int limit) {
        return catalog.stream().map(product -> qualify(product, intent)).flatMap(Optional::stream)
                .sorted(Comparator.comparingInt((PublicProductResponse product) -> score(product, intent)).reversed())
                .limit(limit).toList();
    }

    public Optional<PublicProductResponse> qualify(PublicProductResponse product, GuideIntent intent) {
        if (intent.needsClarification()) return Optional.empty();
        if (intent.deliveryType() == GuideIntent.DeliveryType.DIGITAL || intent.deliveryType() == GuideIntent.DeliveryType.SERVICE) {
            return Optional.empty();
        }
        SupplyDetails details = confirmedDetails(product);
        String categoryText = normalize(product.title() + " " + product.subtitle() + " " + categoryNames.getOrDefault(product.categoryId(), ""));
        if (!intent.categoryKeywords().isEmpty() && intent.categoryKeywords().stream().noneMatch(term -> categoryText.contains(normalize(term)))) {
            return Optional.empty();
        }
        if (!attributesMatch(details == null ? null : details.color(), intent.requiredColors(), intent.excludedColors(), true)
                || !attributesMatch(details == null ? null : details.fabric(), intent.requiredFabrics(), intent.excludedFabrics(), false)) {
            return Optional.empty();
        }
        List<PublicSkuResponse> matching = product.skus().stream()
                .filter(sku -> sku.availableStock() > 0 && sku.price().signum() > 0)
                .filter(sku -> sku.currency().equals(intent.currency()))
                .filter(sku -> intent.maxPrice() == null || sku.price().compareTo(intent.maxPrice()) <= 0)
                .filter(sku -> sizeMatches(sku, details, intent.requiredSize())).toList();
        if (matching.isEmpty()) return Optional.empty();
        return Optional.of(new PublicProductResponse(product.id(), product.title(), product.subtitle(), product.categoryId(),
                matching.stream().map(PublicSkuResponse::price).min(java.math.BigDecimal::compareTo).orElseThrow(),
                intent.currency(), matching.stream().mapToInt(PublicSkuResponse::availableStock).sum(), product.mainImageUrl(),
                product.publishedAt(), matching, product.evidence()));
    }

    private boolean sizeMatches(PublicSkuResponse sku, SupplyDetails details, String size) {
        if (size.isEmpty()) return true;
        if (details != null && details.sizeChart().stream().anyMatch(row -> row.skuCode().equals(sku.code())
                && row.sizeLabel().equalsIgnoreCase(size))) return true;
        return Arrays.stream(sku.name().split("[\\s/·,]+" )).anyMatch(token -> token.equalsIgnoreCase(size));
    }

    private boolean attributesMatch(String value, List<String> required, List<String> excluded, boolean color) {
        if (required.isEmpty() && excluded.isEmpty()) return true;
        if (value == null || value.isBlank()) return false;
        String actual = attribute(value, color);
        return required.stream().allMatch(term -> actual.contains(attribute(term, color)))
                && excluded.stream().noneMatch(term -> actual.contains(attribute(term, color)));
    }

    private String attribute(String text, boolean color) {
        String normalized = normalize(text);
        return color ? normalized.replace("色", "") : normalized.replace("纯棉", "100%棉");
    }

    private int score(PublicProductResponse product, GuideIntent intent) {
        SupplyDetails details = confirmedDetails(product);
        String text = normalize(product.title() + " " + product.subtitle() + " " + (details == null ? "" :
                details.color() + " " + details.fabric() + " " + details.fit() + " " + String.join(" ", details.scenarios())
                        + " " + String.join(" ", details.seasons())));
        return (int) intent.searchTerms().stream().filter(term -> text.contains(normalize(term))).count();
    }

    private SupplyDetails confirmedDetails(PublicProductResponse product) {
        var evidence = product.evidence();
        return evidence != null && ("MERCHANT_CONFIRMED".equals(evidence.confirmationStatus())
                || "TEST_CONFIRMED".equals(evidence.confirmationStatus())) ? evidence.details() : null;
    }

    private String normalize(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); }
}
