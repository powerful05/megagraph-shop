package com.megagraph.shop.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipOrderRequest(
        @NotBlank(message = "承运商不能为空")
        @Size(max = 80, message = "承运商不能超过 80 个字符")
        String carrierName,
        @NotBlank(message = "物流单号不能为空")
        @Size(max = 80, message = "物流单号不能超过 80 个字符")
        String trackingNo
) {
}
