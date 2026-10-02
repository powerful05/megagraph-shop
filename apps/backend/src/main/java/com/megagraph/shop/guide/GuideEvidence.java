package com.megagraph.shop.guide;

import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.api.SupplyDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class GuideEvidence {
    public record Fact(String key, String label, String value) {}
    public record Basis(Long productId, Long productVersion, String dataOrigin, List<Fact> facts, List<String> limitations) {}

    private GuideEvidence() {}

    static List<Fact> facts(PublicProductResponse product) {
        var evidence = product.evidence();
        if (evidence == null || evidence.details() == null || !("TEST_CONFIRMED".equals(evidence.confirmationStatus())
                || "MERCHANT_CONFIRMED".equals(evidence.confirmationStatus()))) return List.of();
        var details = evidence.details();
        var facts = new ArrayList<Fact>();
        add(facts, "color", "颜色", details.color());
        add(facts, "material", "材质", details.fabric());
        add(facts, "scenarios", "标注用途", String.join("、", details.scenarios()));
        Set<String> codes = product.skus().stream().map(sku -> sku.code()).collect(Collectors.toSet());
        if (details.profile() == SupplyDetails.Profile.APPAREL) {
            add(facts, "fit", "版型", details.fit());
            add(facts, "seasons", "季节", String.join("、", details.seasons()));
            for (int index = 0; index < details.sizeChart().size(); index++) {
                var row = details.sizeChart().get(index);
                if (!codes.contains(row.skuCode())) continue;
                String value = row.sizeLabel() + "：胸围 " + row.chestCm().stripTrailingZeros().toPlainString() + " cm，衣长 " + row.lengthCm().stripTrailingZeros().toPlainString() + " cm";
                if (row.shoulderCm() != null) value += "，肩宽 " + row.shoulderCm().stripTrailingZeros().toPlainString() + " cm";
                if (row.sleeveCm() != null) value += "，袖长 " + row.sleeveCm().stripTrailingZeros().toPlainString() + " cm";
                add(facts, "size-" + index, "成衣测量", value);
            }
        } else {
            for (int index = 0; index < details.specifications().size(); index++) {
                var row = details.specifications().get(index);
                if (codes.contains(row.skuCode())) add(facts, "spec-" + index, row.name(), row.value());
            }
        }
        return List.copyOf(facts);
    }

    static Basis basis(PublicProductResponse product, List<String> keys) {
        var evidence = product.evidence();
        var available = facts(product);
        List<Fact> chosen = available.stream().filter(fact -> keys.contains(fact.key())).toList();
        var limitations = new ArrayList<String>();
        if (available.isEmpty()) limitations.add("尚无已确认的属性资料；标题与图片不能代替属性依据。");
        else {
            var details = evidence.details();
            if (!details.measurementNote().isBlank()) limitations.add(details.measurementNote());
            if (!details.imageSourceNote().isBlank()) limitations.add(details.imageSourceNote());
            if (details.profile() == SupplyDetails.Profile.APPAREL) limitations.add("成衣测量不等于人体尺寸，不能保证合身。");
            limitations.add("资料未列明的性能不能确认；图片外观不作为性能依据。");
        }
        return new Basis(product.id(), evidence == null ? null : evidence.productVersion(),
                evidence == null ? "UNKNOWN" : evidence.dataOrigin(), chosen, List.copyOf(limitations));
    }

    static List<Basis> defaults(List<PublicProductResponse> products) {
        return products.stream().map(product -> basis(product,
                facts(product).stream().limit(6).map(Fact::key).toList())).toList();
    }

    private static void add(List<Fact> facts, String key, String label, String value) {
        if (value != null && !value.isBlank()) facts.add(new Fact(key, label, value));
    }
}
