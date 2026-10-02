package com.megagraph.shop.api.strict;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class StrictLong extends StdDeserializer<Long> {
  public StrictLong() {
    super(Long.class);
  }

  public Long deserialize(JsonParser p, DeserializationContext c) throws IOException {
    if (p.currentToken() != JsonToken.VALUE_NUMBER_INT)
      return (Long) c.handleUnexpectedToken(Long.class, p);
    try {
      long v = p.getBigIntegerValue().longValueExact();
      if (v < 0) throw new ArithmeticException();
      return v;
    } catch (ArithmeticException e) {
      throw JsonMappingException.from(p, "Requires a nonnegative long integer");
    }
  }
}
