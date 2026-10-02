package com.megagraph.shop.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.megagraph.shop.api.strict.StrictLong;
import jakarta.validation.constraints.NotNull;

public record AppointmentVersionRequest(
    @NotNull @JsonDeserialize(using = StrictLong.class) Long version) {}
