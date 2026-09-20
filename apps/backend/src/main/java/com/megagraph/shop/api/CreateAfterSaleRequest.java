package com.megagraph.shop.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAfterSaleRequest(
        @NotBlank(message = "退款原因不能为空")
        @Size(max = 500, message = "退款原因不能超过 500 个字符")
        String reason
) {
}
