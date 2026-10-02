package com.megagraph.shop.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.megagraph.shop.shared.ValidationException;
import java.util.List;
import java.util.Map;

@com.fasterxml.jackson.databind.annotation.JsonDeserialize(
    using = com.megagraph.shop.api.strict.StrictAppointmentOrder.class)
public record AppointmentOrderRequest(List<Map<String, Object>> items) {
  @JsonAnySetter
  public void reject(String name, Object value) {
    throw new ValidationException("APPOINTMENT_INPUT_INVALID", "预约购买仅接受items及规定字段");
  }
}
