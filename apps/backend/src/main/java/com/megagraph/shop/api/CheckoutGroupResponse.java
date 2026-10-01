package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CheckoutGroupResponse(Long id, String groupNo, String currency, BigDecimal totalAmount,
                                    Instant createdAt, List<OrderResponse> orders) {}
