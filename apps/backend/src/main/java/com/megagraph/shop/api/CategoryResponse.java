package com.megagraph.shop.api;

public record CategoryResponse(Long id, Long parentId, String name, Integer level) {
}

