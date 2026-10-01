package com.megagraph.shop.guide;

import java.util.List;

public interface GuideModel {

    Selection select(String request, List<Candidate> candidates);

    Selection chat(List<Turn> messages, List<Candidate> candidates);

    record Turn(String role, String content) {
    }

    record Candidate(Long id, String title, String subtitle, String priceFrom, String currency,
                     List<String> skuNames) {
    }

    record Selection(String interpretation, List<Long> productIds) {
    }
}
