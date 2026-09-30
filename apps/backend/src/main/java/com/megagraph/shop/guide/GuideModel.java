package com.megagraph.shop.guide;

import java.util.List;

public interface GuideModel {

    Selection select(String request, List<Candidate> candidates);

    record Candidate(Long id, String title, String subtitle, String priceFrom, String currency,
                     List<String> skuNames) {
    }

    record Selection(String interpretation, List<Long> productIds) {
    }
}
