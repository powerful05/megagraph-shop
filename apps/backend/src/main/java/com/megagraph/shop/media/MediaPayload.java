package com.megagraph.shop.media;

import org.springframework.core.io.Resource;

public record MediaPayload(Resource resource, String contentType) {
}

