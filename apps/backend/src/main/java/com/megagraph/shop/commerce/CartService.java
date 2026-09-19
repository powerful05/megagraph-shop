package com.megagraph.shop.commerce;

import com.megagraph.shop.api.CartItemResponse;
import com.megagraph.shop.api.CartResponse;
import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductSku;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

    private final ShoppingCartRepository carts;
    private final ShoppingCartItemRepository items;
    private final ProductSkuRepository skus;
    private final SellableSkuGuard sellableSkuGuard;

    public CartService(
            ShoppingCartRepository carts,
            ShoppingCartItemRepository items,
            ProductSkuRepository skus,
            SellableSkuGuard sellableSkuGuard
    ) {
        this.carts = carts;
        this.items = items;
        this.skus = skus;
        this.sellableSkuGuard = sellableSkuGuard;
    }

    @Transactional
    public CartResponse get(Long userId) {
        return toResponse(requireCart(userId));
    }

    @Transactional
    public CartResponse setItem(Long userId, Long skuId, int quantity) {
        ShoppingCart cart = requireCart(userId);
        ProductSku sku = skus.findById(skuId)
                .orElseThrow(() -> new NotFoundException("SKU_NOT_FOUND", "SKU 不存在"));
        Product product = sellableSkuGuard.requireSellable(sku, quantity);
        requireSingleMerchant(cart, product.getMerchantId());

        ShoppingCartItem item = items.findByCartIdAndSkuId(cart.getId(), skuId)
                .orElseGet(() -> new ShoppingCartItem(cart.getId(), skuId, quantity));
        item.changeQuantity(quantity);
        items.save(item);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse removeItem(Long userId, Long skuId) {
        ShoppingCart cart = requireCart(userId);
        items.deleteByCartIdAndSkuId(cart.getId(), skuId);
        return toResponse(cart);
    }

    private ShoppingCart requireCart(Long userId) {
        return carts.findByUserId(userId).orElseGet(() -> carts.save(new ShoppingCart(userId)));
    }

    private void requireSingleMerchant(ShoppingCart cart, Long merchantId) {
        for (ShoppingCartItem item : items.findByCartIdOrderByIdAsc(cart.getId())) {
            ProductSku existingSku = skus.findById(item.getSkuId())
                    .orElseThrow(() -> new NotFoundException("CART_SKU_NOT_FOUND", "购物车中的 SKU 不存在"));
            if (!existingSku.getMerchantId().equals(merchantId)) {
                throw new ValidationException("CART_SINGLE_MERCHANT_REQUIRED", "当前阶段购物车只能包含同一商家的商品");
            }
        }
    }

    private CartResponse toResponse(ShoppingCart cart) {
        List<CartItemResponse> responses = new ArrayList<>();
        String currency = null;
        BigDecimal total = BigDecimal.ZERO;
        for (ShoppingCartItem item : items.findByCartIdOrderByIdAsc(cart.getId())) {
            ProductSku sku = skus.findById(item.getSkuId())
                    .orElseThrow(() -> new NotFoundException("CART_SKU_NOT_FOUND", "购物车中的 SKU 不存在"));
            Product product = sellableSkuGuard.requireSellable(sku, 1);
            BigDecimal lineAmount = sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            responses.add(new CartItemResponse(
                    sku.getId(),
                    product.getId(),
                    product.getMerchantId(),
                    product.getTitle(),
                    sku.getSkuCode(),
                    sku.getSkuName(),
                    sku.getPrice(),
                    sku.getCurrency(),
                    item.getQuantity(),
                    lineAmount,
                    sku.getAvailableStock()
            ));
            currency = sku.getCurrency();
            total = total.add(lineAmount);
        }
        return new CartResponse(cart.getId(), currency, total, List.copyOf(responses));
    }
}
