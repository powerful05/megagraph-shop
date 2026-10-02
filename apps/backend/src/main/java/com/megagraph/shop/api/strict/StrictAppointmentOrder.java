package com.megagraph.shop.api.strict;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.megagraph.shop.api.AppointmentOrderRequest;
import java.io.IOException;
import java.util.*;

public class StrictAppointmentOrder extends StdDeserializer<AppointmentOrderRequest> {
  public StrictAppointmentOrder() {
    super(AppointmentOrderRequest.class);
  }

  public AppointmentOrderRequest deserialize(JsonParser p, DeserializationContext c)
      throws IOException {
    if (p.currentToken() != JsonToken.START_OBJECT)
      throw JsonMappingException.from(p, "Requires an object");
    List<Map<String, Object>> items = null;
    while (p.nextToken() != JsonToken.END_OBJECT) {
      String name = p.currentName();
      p.nextToken();
      if (!"items".equals(name) || items != null || p.currentToken() != JsonToken.START_ARRAY)
        throw JsonMappingException.from(p, "Only one items array is accepted");
      items = new ArrayList<>();
      while (p.nextToken() != JsonToken.END_ARRAY) {
        if (p.currentToken() != JsonToken.START_OBJECT)
          throw JsonMappingException.from(p, "Requires item object");
        Map<String, Object> item = new LinkedHashMap<>();
        while (p.nextToken() != JsonToken.END_OBJECT) {
          String f = p.currentName();
          p.nextToken();
          if (item.containsKey(f)
              || !Set.of(
                      "offerId",
                      "slotId",
                      "quantity",
                      "expectedOfferVersion",
                      "expectedSlotVersion")
                  .contains(f)
              || p.currentToken() != JsonToken.VALUE_NUMBER_INT)
            throw JsonMappingException.from(p, "Only specified integer fields accepted");
          item.put(f, p.getBigIntegerValue());
        }
        items.add(item);
      }
    }
    return new AppointmentOrderRequest(items);
  }
}
