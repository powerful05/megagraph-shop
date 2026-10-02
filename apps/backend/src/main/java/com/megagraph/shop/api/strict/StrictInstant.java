package com.megagraph.shop.api.strict;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.*;

public class StrictInstant extends StdDeserializer<Instant> {
  public StrictInstant() {
    super(Instant.class);
  }

  public Instant deserialize(JsonParser p, DeserializationContext c) throws IOException {
    if (p.currentToken() != JsonToken.VALUE_STRING)
      return (Instant) c.handleUnexpectedToken(Instant.class, p);
    try {
      return OffsetDateTime.parse(p.getText()).toInstant();
    } catch (Exception e) {
      throw JsonMappingException.from(p, "Requires ISO time with explicit UTC offset");
    }
  }
}
