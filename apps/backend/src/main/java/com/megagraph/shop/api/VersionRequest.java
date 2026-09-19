package com.megagraph.shop.api;

import jakarta.validation.constraints.NotNull;

public record VersionRequest(@NotNull Long version) {
}

