package com.megagraph.shop.api;

import com.megagraph.shop.commerce.OrderService;
import com.megagraph.shop.security.CurrentMerchant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/v1/orders")
public class MerchantOrderController {

    private final CurrentMerchant currentMerchant;
    private final OrderService orders;

    public MerchantOrderController(CurrentMerchant currentMerchant, OrderService orders) {
        this.currentMerchant = currentMerchant;
        this.orders = orders;
    }

    @GetMapping
    List<OrderResponse> orders(Authentication authentication) {
        return orders.listForMerchant(currentMerchant.requireActor(authentication).merchantId());
    }
}
