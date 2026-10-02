package com.megagraph.shop.guide;

import java.util.List;
import com.megagraph.shop.api.ProductEvidence;

public interface GuideModel {

    class InvalidReplyException extends IllegalStateException {
        public InvalidReplyException(String message, Throwable cause) { super(message, cause); }
    }

    Selection select(String request, List<Candidate> candidates);

    Selection chat(List<Turn> messages, List<Candidate> candidates, GuideIntent intent);

    default GuideIntent interpret(List<Turn> messages) { return GuideIntent.empty(); }

    record Turn(String role, String content, List<Long> displayedProductIds) {
        public Turn { displayedProductIds = displayedProductIds == null ? List.of() : List.copyOf(displayedProductIds); }
        public Turn(String role, String content) { this(role, content, List.of()); }
    }

    record Candidate(Long id, String title, String subtitle, String priceFrom, String currency,
                     List<String> skuNames, ProductEvidence evidence, List<GuideEvidence.Fact> facts) {
        public Candidate(Long id, String title, String subtitle, String priceFrom, String currency,
                         List<String> skuNames, ProductEvidence evidence) {
            this(id, title, subtitle, priceFrom, currency, skuNames, evidence, List.of());
        }
    }

    record EvidenceReference(Long productId, List<String> keys) {}

    enum MatchStatus { MATCHED, NO_CANDIDATES, NEEDS_CLARIFICATION, PURPOSE_MISMATCH, REQUIRED_EVIDENCE_MISSING }

    record Selection(String interpretation, List<Long> productIds, List<EvidenceReference> evidenceReferences,
                     MatchStatus matchStatus, GuideGuidance.Plan guidance) {
        public Selection(String interpretation, List<Long> productIds, List<EvidenceReference> evidenceReferences, MatchStatus matchStatus) {
            this(interpretation, productIds, evidenceReferences, matchStatus, GuideGuidance.Plan.general());
        }
        public Selection(String interpretation, List<Long> productIds, List<EvidenceReference> evidenceReferences) {
            this(interpretation, productIds, evidenceReferences,
                    productIds != null && !productIds.isEmpty() ? MatchStatus.MATCHED : MatchStatus.NO_CANDIDATES);
        }
        public Selection(String interpretation, List<Long> productIds) { this(interpretation, productIds, List.of()); }
    }
}
