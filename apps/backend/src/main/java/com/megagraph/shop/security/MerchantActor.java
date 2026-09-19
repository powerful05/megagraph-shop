package com.megagraph.shop.security;

import com.megagraph.shop.identity.MerchantRole;

public record MerchantActor(Long userId, Long merchantId, MerchantRole role) {
}

