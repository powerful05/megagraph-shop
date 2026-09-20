package com.megagraph.shop.api;

import com.megagraph.shop.commerce.AfterSaleService;
import com.megagraph.shop.commerce.CartService;
import com.megagraph.shop.commerce.OrderService;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shop/v1")
public class CommerceController {

    private final CurrentUser currentUser;
    private final CartService carts;
    private final OrderService orders;
    private final AfterSaleService afterSales;

    public CommerceController(
            CurrentUser currentUser,
            CartService carts,
            OrderService orders,
            AfterSaleService afterSales
    ) {
        this.currentUser = currentUser;
        this.carts = carts;
        this.orders = orders;
        this.afterSales = afterSales;
    }

    @GetMapping("/cart")
    CartResponse cart(Authentication authentication) {
        return carts.get(account(authentication).getId());
    }

    @PutMapping("/cart/items/{skuId}")
    CartResponse setCartItem(
            Authentication authentication,
            @PathVariable Long skuId,
            @Valid @RequestBody CartItemQuantityRequest request
    ) {
        return carts.setItem(account(authentication).getId(), skuId, request.quantity());
    }

    @DeleteMapping("/cart/items/{skuId}")
    CartResponse removeCartItem(Authentication authentication, @PathVariable Long skuId) {
        return carts.removeItem(account(authentication).getId(), skuId);
    }

    @PostMapping("/orders")
    OrderResponse createOrder(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        return orders.create(account(authentication).getId(), idempotencyKey, request);
    }

    @GetMapping("/orders")
    List<OrderResponse> orders(Authentication authentication) {
        return orders.listForUser(account(authentication).getId());
    }

    @GetMapping("/orders/{orderId}")
    OrderResponse order(Authentication authentication, @PathVariable Long orderId) {
        return orders.getForUser(account(authentication).getId(), orderId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    OrderResponse cancelOrder(Authentication authentication, @PathVariable Long orderId) {
        return orders.cancel(account(authentication).getId(), orderId);
    }

    @PostMapping("/orders/{orderId}/confirm-receipt")
    OrderResponse confirmReceipt(Authentication authentication, @PathVariable Long orderId) {
        return orders.confirmReceipt(account(authentication).getId(), orderId);
    }

    @PostMapping("/orders/{orderId}/after-sales")
    OrderResponse requestAfterSale(
            Authentication authentication,
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateAfterSaleRequest request
    ) {
        return afterSales.request(account(authentication).getId(), orderId, idempotencyKey, request);
    }

    private UserAccount account(Authentication authentication) {
        return currentUser.requireAccount(authentication);
    }
}
