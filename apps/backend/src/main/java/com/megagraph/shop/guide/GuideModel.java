package com.megagraph.shop.guide;

import java.util.List;
import com.megagraph.shop.api.ProductEvidence;

public interface GuideModel {

    Selection select(String request, List<Candidate> candidates);

    Selection chat(List<Turn> messages, List<Candidate> candidates);

    default GuideIntent interpret(List<Turn> messages) { return GuideIntent.empty(); }

    record Turn(String role, String content) {
    }

    record Candidate(Long id, String title, String subtitle, String priceFrom, String currency,
                     List<String> skuNames, ProductEvidence evidence) {
    }

    record Selection(String interpretation, List<Long> productIds) {
    }
}
