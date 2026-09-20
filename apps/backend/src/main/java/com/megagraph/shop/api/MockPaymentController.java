package com.megagraph.shop.api;

import com.megagraph.shop.commerce.OrderService;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/shop/v1/orders")
public class MockPaymentController {

    private final CurrentUser currentUser;
    private final OrderService orders;

    public MockPaymentController(CurrentUser currentUser, OrderService orders) {
        this.currentUser = currentUser;
        this.orders = orders;
    }

    @PostMapping("/{orderId}/mock-payment")
    OrderResponse mockPayment(
            Authentication authentication,
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MockPaymentResultRequest request
    ) {
        UserAccount account = currentUser.requireAccount(authentication);
        return orders.applyMockPayment(account.getId(), orderId, idempotencyKey, request.result());
    }
}
