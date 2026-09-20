package com.megagraph.shop.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveAfterSaleRequest(
        @NotBlank(message = "处理说明不能为空")
        @Size(max = 500, message = "处理说明不能超过 500 个字符")
        String note
) {
}
