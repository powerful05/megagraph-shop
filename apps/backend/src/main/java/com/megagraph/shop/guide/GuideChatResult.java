package com.megagraph.shop.guide;

import com.megagraph.shop.api.PublicProductResponse;
import java.util.List;

public record GuideChatResult(String answer, List<PublicProductResponse> products,
                              List<String> demoProductIds) {
}
