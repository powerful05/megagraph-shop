package com.megagraph.shop.guide;

import com.megagraph.shop.api.PublicProductResponse;
import java.util.List;

public record GuideResult(String answer, List<PublicProductResponse> products) {
}
