package com.megagraph.shop.api.strict;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class StrictInteger extends StdDeserializer<Integer> {
  public StrictInteger() {
    super(Integer.class);
  }

  public Integer deserialize(JsonParser p, DeserializationContext c) throws IOException {
    if (p.currentToken() != JsonToken.VALUE_NUMBER_INT)
      return (Integer) c.handleUnexpectedToken(Integer.class, p);
    try {
      return p.getBigIntegerValue().intValueExact();
    } catch (ArithmeticException e) {
      throw JsonMappingException.from(p, "Requires an integer within range");
    }
  }
}
