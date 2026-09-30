package com.megagraph.shop.guide;

import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.shared.NotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class GuideAgent {

    private static final int CANDIDATE_LIMIT = 50;
    private static final int RESULT_LIMIT = 3;

    private final PublicCatalogService catalog;
    private final Optional<GuideModel> model;

    public GuideAgent(PublicCatalogService catalog, Optional<GuideModel> model) {
        this.catalog = catalog;
        this.model = model;
    }

    public GuideResult recommend(String request) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("商品需求不能为空");
        }
        GuideModel guideModel = model.orElseThrow(() -> new IllegalStateException("导购模型未启用"));
        List<PublicProductResponse> available = catalog.list().stream().limit(CANDIDATE_LIMIT).toList();
        if (available.isEmpty()) {
            return new GuideResult("目前没有可推荐的在售商品。", List.of());
        }

        List<GuideModel.Candidate> candidates = available.stream()
                .map(product -> new GuideModel.Candidate(
                        product.id(), product.title(), product.subtitle(),
                        product.priceFrom().toPlainString(), product.currency(),
                        product.skus().stream().map(sku -> sku.name()).toList()))
                .toList();
        GuideModel.Selection selection = guideModel.select(request.strip(), candidates);
        Set<Long> candidateIds = new HashSet<>(available.stream().map(PublicProductResponse::id).toList());
        List<PublicProductResponse> products = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (selection != null && selection.productIds() != null) {
            for (Long id : selection.productIds()) {
                if (id == null || !candidateIds.contains(id) || !seen.add(id)) {
                    continue;
                }
                try {
                    products.add(catalog.get(id));
                } catch (NotFoundException ignored) {
                    // Availability can change between candidate retrieval and the final response.
                }
                if (products.size() == RESULT_LIMIT) {
                    break;
                }
            }
        }
        if (products.isEmpty()) {
            return new GuideResult("我理解了你的需求，但目前没有找到合适的在售商品。", List.of());
        }
        String interpretation = selection.interpretation() == null ? "" : selection.interpretation().strip();
        String answer = interpretation.isEmpty()
                ? "我找到了与你描述相关的在售商品。"
                : interpretation + " 我找到了以下在售商品。";
        return new GuideResult(answer, List.copyOf(products));
    }
}
