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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GuideAgent.class);

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
        return new GuideResult("我找到了以下在售商品：" + String.join("、", products.stream().map(PublicProductResponse::title).toList())
                + "。具体属性与交易信息以当前商品资料为准。", List.copyOf(products));
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
            var displayed = messages.get(index).displayedProductIds();
            if (displayed.size() > RESULT_LIMIT || displayed.stream().anyMatch(id -> id <= 0)
                    || new HashSet<>(displayed).size() != displayed.size()
                    || "user".equals(expected) && !displayed.isEmpty()) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "GUIDE_HISTORY_INVALID", "商品展示顺序无效");
            }
        }
        if (!"user".equals(messages.getLast().role())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "GUIDE_HISTORY_INVALID", "请提交新的用户消息");
        }
        GuideModel guideModel = model.orElseThrow(() -> new DomainException(
                HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_UNAVAILABLE", "对话模型暂未启用"));
        GuideIntent extracted = guideModel.interpret(List.copyOf(messages));
        GuideIntent intent = extracted == null ? GuideIntent.empty() : extracted;
        if (intent.requestsMonitoring()) {
            return new GuideChatResult("我不能持续监控库存或在有货时主动通知你，也没有创建后续提醒。需要时请主动发起新的查询。",
                    List.of(), List.of());
        }
        GuideCatalogSearch search = new GuideCatalogSearch(catalog.categoryNames());
        List<PublicProductResponse> publicCatalog = catalog.list();
        var reference = intent.reference();
        PublicProductResponse anchor = null;
        if (reference.mode() != GuideIntent.ReferenceMode.NONE) {
            Long referencedId = referenceId(messages, reference);
            if (referencedId == null) {
                return new GuideChatResult("你指的是哪一款？请补充商品名称，或说明是哪一轮展示的第几件。", List.of(), List.of());
            }
            anchor = publicCatalog.stream().filter(product -> product.id().equals(referencedId)).findFirst().orElse(null);
            if (anchor == null) {
                return new GuideChatResult("你指的商品目前没有可售资料，不能沿用之前的推荐；请重新描述需求。", List.of(), List.of());
            }
            if (isCheaper(reference)) {
                var cheaperBudget = anchor.priceFrom().subtract(new java.math.BigDecimal("0.01"));
                if (intent.maxPrice() != null) cheaperBudget = cheaperBudget.min(intent.maxPrice());
                intent = new GuideIntent(intent.deliveryType(), cheaperBudget.max(java.math.BigDecimal.ZERO), intent.currency(),
                        intent.searchTerms(), intent.categoryKeywords(), intent.requiredColors(), intent.excludedColors(),
                        intent.requiredFabrics(), intent.excludedFabrics(), intent.requiredSize(), intent.needsClarification(), reference);
            }
        }
        List<PublicProductResponse> scopedCatalog = publicCatalog;
        if (anchor != null) {
            PublicProductResponse referenced = anchor;
            scopedCatalog = publicCatalog.stream().filter(product -> isCheaper(reference)
                    ? !product.id().equals(referenced.id()) && java.util.Objects.equals(product.categoryId(), referenced.categoryId())
                            && product.currency().equals(referenced.currency())
                    : product.id().equals(referenced.id())).toList();
        }
        List<PublicProductResponse> available = search.search(scopedCatalog, intent, CANDIDATE_LIMIT);
        LOG.info("Guide qualification requestId={}, referenceMode={}, categoryTerms={}, requiredColors={}, candidates={}",
                org.slf4j.MDC.get("guideRequestId"), reference.mode(), intent.categoryKeywords().size(), intent.requiredColors().size(), available.size());
        if (reference.mode() == GuideIntent.ReferenceMode.EXACT) {
            return referencedProduct(available, intent, search);
        }
        if (isCheaper(reference) && available.isEmpty()) {
            return new GuideChatResult("目前没有比你指定那款更便宜、同时符合本轮条件的同类在售商品。", List.of(), List.of());
        }
        if (reference.mode() == GuideIntent.ReferenceMode.CHEAPER) {
            List<PublicProductResponse> products = new ArrayList<>();
            try {
                PublicProductResponse freshAnchor = catalog.get(anchor.id());
                if (!sameComparisonAnchor(anchor, freshAnchor)) return changedReference();
                for (var original : available.stream().limit(RESULT_LIMIT).toList()) {
                    var fresh = catalog.get(original.id());
                    var matching = search.qualify(fresh, intent);
                    if (!sameEvidenceVersion(available, fresh) || matching.isEmpty()
                            || !java.util.Objects.equals(fresh.categoryId(), freshAnchor.categoryId())
                            || matching.get().priceFrom().compareTo(freshAnchor.priceFrom()) >= 0) return changedReference();
                    products.add(matching.get());
                }
            } catch (NotFoundException ignored) {
                return changedReference();
            }
            String source = products.stream().anyMatch(product -> product.evidence() != null
                    && "LOCAL_TEST".equals(product.evidence().dataOrigin())) ? "其中包含本地测试商品，不是真实商家供给。" : "";
            return new GuideChatResult("以下商品与指定那款属于同一类目，当前起价更低，并符合本轮已提取的条件。"
                    + source + "这只是当前价格和资料的比较，不代表人体合身或未验证性能。具体规格、价格和库存见卡片。",
                    List.copyOf(products), List.of());
        }
        List<GuideModel.Candidate> candidates = new ArrayList<>(available.stream()
                .map(product -> new GuideModel.Candidate(product.id(), product.title(), product.subtitle(),
                        product.priceFrom().toPlainString(), product.currency(),
                        product.skus().stream().map(sku -> sku.name()).toList(), product.evidence(), GuideEvidence.facts(product))).toList());
        boolean useDemo = allowDemoProducts && publicCatalog.isEmpty()
                && reference.mode() == GuideIntent.ReferenceMode.NONE
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
        GuideModel.Selection selection = selectReply(guideModel, List.copyOf(messages), List.copyOf(candidates), intent);
        if (selection == null || selection.productIds() == null || selection.guidance() == null) {
            throw new IllegalStateException("导购模型没有返回有效回复");
        }
        Set<Long> candidateIds = new HashSet<>(available.stream().map(PublicProductResponse::id).toList());
        Set<Long> seen = new HashSet<>();
        List<PublicProductResponse> products = new ArrayList<>();
        List<String> demoIds = new ArrayList<>();
        boolean candidateChanged = false;
        boolean invalidSelection = selection.productIds().stream().anyMatch(id -> id != null
                && !candidateIds.contains(id) && !(useDemo && id >= -103L && id <= -101L));
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
        if (anchor != null) {
            try {
                PublicProductResponse freshAnchor = catalog.get(anchor.id());
                if (!sameComparisonAnchor(anchor, freshAnchor)) candidateChanged = true;
            } catch (NotFoundException ignored) {
                candidateChanged = true;
            }
        }
        if (candidateChanged) {
            return new GuideChatResult("候选商品的资料或可售状态刚刚发生变化，请重新发送需求，我会按当前资料重新核对。",
                    List.of(), List.of());
        }
        if (invalidSelection) {
            return new GuideChatResult("本次回复中的商品选择未通过当前候选校验，请重新描述需求，我会重新核对。", List.of(), List.of());
        }
        if (!validMatchStatus(selection, candidates, intent)) {
            return new GuideChatResult("本次回复的推荐状态与当前筛选结果不一致，请重新发送需求，我会重新核对。", List.of(), List.of());
        }
        List<GuideEvidence.Basis> basis = validatedBasis(products, selection.evidenceReferences());
        if (basis == null) {
            return new GuideChatResult("本次推荐依据未通过当前已确认资料校验，请重新发送需求，我会重新核对。", List.of(), List.of());
        }
        String answer;
        if (intent.needsClarification()) answer = "还需要补充关键条件，暂不推荐具体商品。";
        else if (intent.deliveryType() == GuideIntent.DeliveryType.DIGITAL) answer = "目前没有可核验的虚拟商品供给；课程、软件或下载内容的购买与权益交付尚未开放。";
        else if (intent.deliveryType() == GuideIntent.DeliveryType.SERVICE) answer = "目前没有可核验的服务供给，服务购买与预约尚未开放。";
        else if (!products.isEmpty()) {
            answer = "找到了以下商品，已确认属性与缺失资料分别列在推荐依据中。价格与库存以卡片当前信息为准。";
            if (products.stream().anyMatch(product -> product.evidence() != null && "LOCAL_TEST".equals(product.evidence().dataOrigin()))) {
                answer += "以下包含本地测试商品，不是真实商家在售供给。";
            }
        } else if (!demoIds.isEmpty()) answer = "以下仅为生成的演示图，没有可核验的商品属性或交易信息。";
        else if (selection.matchStatus() == GuideModel.MatchStatus.PURPOSE_MISMATCH) {
            answer = "检索到了候选商品，但本轮没有选出用途合适的推荐。可以补充具体使用场景，帮助继续判断。";
        } else if (selection.matchStatus() == GuideModel.MatchStatus.REQUIRED_EVIDENCE_MISSING) {
            answer = "本轮没有返回具备必要性能依据的推荐；防水、承重等未验证性能不能作为满足条件的依据。";
        } else answer = "当前筛选没有返回符合已提取条件的商品；未验证的属性或性能不能作为满足条件的依据。";
        var selectedCandidates = candidates.stream().filter(candidate -> selection.productIds().contains(candidate.id())).toList();
        var guidance = GuideGuidance.valid(selection.guidance(), intent, selectedCandidates, messages.getLast().content())
                ? selection.guidance() : GuideGuidance.Plan.general();
        return new GuideChatResult(answer, List.copyOf(products), List.copyOf(demoIds),
                GuideGuidance.render(guidance, intent), basis);
    }

    private List<GuideEvidence.Basis> validatedBasis(List<PublicProductResponse> products, List<GuideModel.EvidenceReference> references) {
        if (references == null || references.size() > RESULT_LIMIT) return null;
        Set<Long> referenced = new HashSet<>();
        var basis = new ArrayList<GuideEvidence.Basis>();
        for (var reference : references) {
            if (reference == null || reference.productId() == null || !referenced.add(reference.productId())
                    || reference.keys() == null || reference.keys().isEmpty() || reference.keys().size() > 6
                    || reference.keys().stream().anyMatch(key -> key == null || key.isBlank())
                    || new HashSet<>(reference.keys()).size() != reference.keys().size()) return null;
            var product = products.stream().filter(item -> item.id().equals(reference.productId())).findFirst().orElse(null);
            if (product == null) return null;
            var available = GuideEvidence.facts(product).stream().map(GuideEvidence.Fact::key).toList();
            if (!available.containsAll(reference.keys())) return null;
        }
        for (var product : products) {
            var reference = references.stream().filter(item -> item.productId().equals(product.id())).findFirst();
            if (!GuideEvidence.facts(product).isEmpty() && reference.isEmpty()) return null;
            basis.add(GuideEvidence.basis(product, reference.map(GuideModel.EvidenceReference::keys).orElse(List.of())));
        }
        return List.copyOf(basis);
    }

    private boolean isCheaper(GuideIntent.Reference reference) {
        return reference.mode() == GuideIntent.ReferenceMode.CHEAPER || reference.mode() == GuideIntent.ReferenceMode.CHEAPER_REFINE;
    }

    private GuideModel.Selection selectReply(GuideModel guideModel, List<GuideModel.Turn> messages,
            List<GuideModel.Candidate> candidates, GuideIntent intent) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var selection = guideModel.chat(messages, candidates, intent);
                if (selection != null && selection.guidance() != null && attempt == 0) {
                    LOG.info("Guide plan requestId={}, status={}, selected={}, question={}, checks={}",
                            org.slf4j.MDC.get("guideRequestId"), selection.matchStatus(),
                            selection.productIds() == null ? 0 : selection.productIds().size(),
                            selection.guidance().question(), selection.guidance().checks());
                }
                if (attempt == 1 || validCandidateReferences(selection, candidates)
                        && validMatchStatus(selection, candidates, intent)
                        && GuideGuidance.valid(selection.guidance(), intent, candidates.stream()
                                .filter(candidate -> selection.productIds().contains(candidate.id())).toList(), messages.getLast().content())) return selection;
            } catch (GuideModel.InvalidReplyException exception) {
                if (attempt == 1) throw exception;
            }
            LOG.warn("Guide reply regeneration requestId={}, reason=STRUCTURE_REFERENCE_STATUS_OR_GUIDANCE, retry=1", org.slf4j.MDC.get("guideRequestId"));
        }
        throw new IllegalStateException("导购回复没有完成");
    }

    private boolean validMatchStatus(GuideModel.Selection selection, List<GuideModel.Candidate> candidates, GuideIntent intent) {
        if (selection == null || selection.productIds() == null || selection.matchStatus() == null) return false;
        return switch (selection.matchStatus()) {
            case MATCHED -> !selection.productIds().isEmpty() && !intent.needsClarification();
            case NO_CANDIDATES -> selection.productIds().isEmpty() && candidates.isEmpty() && !intent.needsClarification();
            case NEEDS_CLARIFICATION -> selection.productIds().isEmpty() && intent.needsClarification();
            case PURPOSE_MISMATCH, REQUIRED_EVIDENCE_MISSING -> selection.productIds().isEmpty()
                    && !candidates.isEmpty() && !intent.needsClarification();
        };
    }

    private boolean validCandidateReferences(GuideModel.Selection selection, List<GuideModel.Candidate> candidates) {
        if (selection == null || selection.productIds() == null || selection.evidenceReferences() == null) return false;
        Set<Long> ids = new HashSet<>(candidates.stream().map(GuideModel.Candidate::id).toList());
        if (!ids.containsAll(selection.productIds()) || selection.evidenceReferences().size() > RESULT_LIMIT) return false;
        Set<Long> referenced = new HashSet<>();
        for (var reference : selection.evidenceReferences()) {
            if (reference == null || reference.productId() == null || !selection.productIds().contains(reference.productId())
                    || !referenced.add(reference.productId()) || reference.keys() == null || reference.keys().isEmpty()
                    || reference.keys().size() > 6 || reference.keys().stream().anyMatch(key -> key == null || key.isBlank())
                    || new HashSet<>(reference.keys()).size() != reference.keys().size()) return false;
            var candidate = candidates.stream().filter(item -> item.id().equals(reference.productId())).findFirst().orElse(null);
            if (candidate == null || !candidate.facts().stream().map(GuideEvidence.Fact::key).toList().containsAll(reference.keys())) return false;
        }
        return candidates.stream().filter(item -> selection.productIds().contains(item.id()) && !item.facts().isEmpty())
                .allMatch(item -> referenced.contains(item.id()));
    }

    private boolean sameComparisonAnchor(PublicProductResponse anchor, PublicProductResponse fresh) {
        return sameEvidenceVersion(List.of(anchor), fresh) && fresh.priceFrom().compareTo(anchor.priceFrom()) == 0
                && fresh.currency().equals(anchor.currency()) && java.util.Objects.equals(fresh.categoryId(), anchor.categoryId());
    }

    private GuideChatResult changedReference() {
        return new GuideChatResult("比较商品的资料或可售状态刚刚发生变化，请重新核对。", List.of(), List.of());
    }

    private Long referenceId(List<GuideModel.Turn> messages, GuideIntent.Reference reference) {
        if (reference.mode() == GuideIntent.ReferenceMode.AMBIGUOUS || reference.assistantTurnIndex() == null
                || reference.assistantTurnIndex() >= messages.size() - 1) return null;
        var turn = messages.get(reference.assistantTurnIndex());
        return "assistant".equals(turn.role()) && reference.position() <= turn.displayedProductIds().size()
                ? turn.displayedProductIds().get(reference.position() - 1) : null;
    }

    private GuideChatResult referencedProduct(List<PublicProductResponse> available, GuideIntent intent, GuideCatalogSearch search) {
        if (available.isEmpty()) {
            return new GuideChatResult("你指的那款当前不符合本轮条件，不能换成另一款来冒充；请说明是否调整条件。", List.of(), List.of());
        }
        PublicProductResponse original = available.getFirst();
        try {
            PublicProductResponse fresh = catalog.get(original.id());
            var matching = search.qualify(fresh, intent);
            if (!sameEvidenceVersion(available, fresh) || matching.isEmpty()) {
                return new GuideChatResult("你指的商品资料或可售状态刚刚发生变化，请重新核对。", List.of(), List.of());
            }
            PublicProductResponse product = matching.get();
            var evidence = product.evidence();
            var details = evidence != null && ("MERCHANT_CONFIRMED".equals(evidence.confirmationStatus())
                    || "TEST_CONFIRMED".equals(evidence.confirmationStatus())) ? evidence.details() : null;
            String answer = "你指的是「" + product.title() + "」。";
            if (evidence != null && "LOCAL_TEST".equals(evidence.dataOrigin())) answer += "这是本地测试商品，不是真实商家供给。";
            if (details != null) {
                answer += "已确认资料中的材质为：" + details.fabric() + "。";
                if (!details.measurementNote().isBlank()) answer += details.measurementNote();
                if (details.profile() == com.megagraph.shop.api.SupplyDetails.Profile.APPAREL) answer += "成衣尺寸不等于人体尺寸，不能保证合身。";
            } else answer += "尚无已确认的属性资料。";
            answer += "下方是该款当前可售规格；未列明的属性或性能不能据此确认。";
            return new GuideChatResult(answer, List.of(product), List.of());
        } catch (NotFoundException ignored) {
            return new GuideChatResult("你指的商品目前不可售，不能沿用之前的推荐。", List.of(), List.of());
        }
    }

    private boolean sameEvidenceVersion(List<PublicProductResponse> candidates, PublicProductResponse fresh) {
        return candidates.stream().filter(product -> product.id().equals(fresh.id())).anyMatch(product ->
                product.evidence() == null ? fresh.evidence() == null : fresh.evidence() != null
                        && product.evidence().productVersion().equals(fresh.evidence().productVersion()));
    }
}
