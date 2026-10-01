package com.megagraph.shop.guide;

import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.DomainException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

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
        GuideIntent extracted = guideModel.interpret(List.of(new GuideModel.Turn("user", request.strip())));
        GuideIntent intent = extracted == null ? GuideIntent.empty() : extracted;
        GuideCatalogSearch search = new GuideCatalogSearch(catalog.categoryNames());
        List<PublicProductResponse> available = search.search(catalog.list(), intent, CANDIDATE_LIMIT);
        if (available.isEmpty()) {
            return new GuideResult("目前没有可推荐的在售商品。", List.of());
        }

        List<GuideModel.Candidate> candidates = available.stream()
                .map(product -> new GuideModel.Candidate(
                        product.id(), product.title(), product.subtitle(),
                        product.priceFrom().toPlainString(), product.currency(),
                        product.skus().stream().map(sku -> sku.name()).toList(), product.evidence()))
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
                    PublicProductResponse fresh = catalog.get(id);
                    if (sameEvidenceVersion(available, fresh)) search.qualify(fresh, intent).ifPresent(products::add);
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

    public GuideChatResult chat(List<GuideModel.Turn> messages, boolean allowDemoProducts) {
        if (messages == null || messages.isEmpty() || messages.size() > 19
                || messages.stream().mapToInt(turn -> turn.content().length()).sum() > 12000) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "GUIDE_HISTORY_INVALID", "对话过长，请开始新对话");
        }
        for (int index = 0; index < messages.size(); index++) {
            String expected = index % 2 == 0 ? "user" : "assistant";
            if (!expected.equals(messages.get(index).role())) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "GUIDE_HISTORY_INVALID", "对话消息顺序无效");
            }
        }
        if (!"user".equals(messages.getLast().role())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "GUIDE_HISTORY_INVALID", "请提交新的用户消息");
        }
        GuideModel guideModel = model.orElseThrow(() -> new DomainException(
                HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_UNAVAILABLE", "对话模型暂未启用"));
        GuideIntent extracted = guideModel.interpret(List.copyOf(messages));
        GuideIntent intent = extracted == null ? GuideIntent.empty() : extracted;
        GuideCatalogSearch search = new GuideCatalogSearch(catalog.categoryNames());
        List<PublicProductResponse> publicCatalog = catalog.list();
        List<PublicProductResponse> available = search.search(publicCatalog, intent, CANDIDATE_LIMIT);
        List<GuideModel.Candidate> candidates = new ArrayList<>(available.stream()
                .map(product -> new GuideModel.Candidate(product.id(), product.title(), product.subtitle(),
                        product.priceFrom().toPlainString(), product.currency(),
                        product.skus().stream().map(sku -> sku.name()).toList(), product.evidence())).toList());
        boolean useDemo = allowDemoProducts && publicCatalog.isEmpty()
                && !intent.needsClarification()
                && (intent.deliveryType() == GuideIntent.DeliveryType.UNKNOWN || intent.deliveryType() == GuideIntent.DeliveryType.PHYSICAL)
                && intent.maxPrice() == null && intent.requiredSize().isEmpty()
                && intent.requiredColors().isEmpty() && intent.excludedColors().isEmpty()
                && intent.requiredFabrics().isEmpty() && intent.excludedFabrics().isEmpty() && intent.categoryKeywords().isEmpty();
        if (useDemo) {
            candidates.add(new GuideModel.Candidate(-101L, "演示图 A：浅蓝衬衫",
                    "视觉风格清爽利落，其他属性未知；不是在售商品", "", "", List.of(), null));
            candidates.add(new GuideModel.Candidate(-102L, "演示图 B：米白针织上衣",
                    "视觉风格柔和轻松，其他属性未知；不是在售商品", "", "", List.of(), null));
            candidates.add(new GuideModel.Candidate(-103L, "演示图 C：灰色开衫",
                    "视觉上可作叠穿参考，其他属性未知；不是在售商品", "", "", List.of(), null));
        }
        GuideModel.Selection selection = guideModel.chat(List.copyOf(messages), List.copyOf(candidates));
        if (selection == null || selection.interpretation() == null || selection.interpretation().isBlank()
                || selection.productIds() == null) {
            throw new IllegalStateException("导购模型没有返回有效回复");
        }
        Set<Long> candidateIds = new HashSet<>(available.stream().map(PublicProductResponse::id).toList());
        Set<Long> seen = new HashSet<>();
        List<PublicProductResponse> products = new ArrayList<>();
        List<String> demoIds = new ArrayList<>();
        boolean candidateChanged = false;
        for (Long id : selection.productIds()) {
            if (id == null || !seen.add(id)) continue;
            if (candidateIds.contains(id)) {
                try {
                    PublicProductResponse fresh = catalog.get(id);
                    var matching = search.qualify(fresh, intent);
                    if (sameEvidenceVersion(available, fresh) && matching.isPresent()) products.add(matching.get());
                    else candidateChanged = true;
                } catch (NotFoundException ignored) {
                    // Recheck public eligibility after the external model call.
                    candidateChanged = true;
                }
            } else if (useDemo && id >= -103L && id <= -101L) {
                demoIds.add(switch (id.intValue()) {
                    case -101 -> "blue-shirt";
                    case -102 -> "ivory-knit";
                    default -> "gray-cardigan";
                });
            }
            if (products.size() + demoIds.size() == RESULT_LIMIT) break;
        }
        String answer = selection.interpretation().strip();
        if (candidateChanged) {
            return new GuideChatResult("候选商品的资料或可售状态刚刚发生变化，请重新发送需求，我会按当前资料重新核对。",
                    List.of(), List.of());
        }
        if (products.isEmpty() && demoIds.isEmpty() && !selection.productIds().isEmpty()) {
            answer += " 当前没有通过可售校验的匹配商品。";
        }
        return new GuideChatResult(answer, List.copyOf(products), List.copyOf(demoIds));
    }

    private boolean sameEvidenceVersion(List<PublicProductResponse> candidates, PublicProductResponse fresh) {
        return candidates.stream().filter(product -> product.id().equals(fresh.id())).anyMatch(product ->
                product.evidence() == null ? fresh.evidence() == null : fresh.evidence() != null
                        && product.evidence().productVersion().equals(fresh.evidence().productVersion()));
    }
}
