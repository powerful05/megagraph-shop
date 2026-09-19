package com.megagraph.shop.commerce;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "shopping_cart_item")
public class ShoppingCartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cartId;
    private Long skuId;
    private Integer quantity;
    private Instant createdAt;
    private Instant updatedAt;

    protected ShoppingCartItem() {
    }

    public ShoppingCartItem(Long cartId, Long skuId, int quantity) {
        this.cartId = cartId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCartId() {
        return cartId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public Integer getQuantity() {
        return quantity;
    }
}
