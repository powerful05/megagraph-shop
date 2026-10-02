package com.megagraph.shop.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.megagraph.shop.shared.ValidationException;
import java.util.List;
import java.util.Map;

// Strict input boundary prevents silently accepting SKU mixtures or client transactional facts.
public record DeliveryOrderRequest(List<Map<String, Object>> items) {
  @JsonAnySetter
  public void reject(String name, Object value) {
    throw new ValidationException(
        "DELIVERY_ORDER_INPUT_INVALID", "数字购买仅接受 items 中的 offerId 和 quantity");
  }
}
