package com.megagraph.shop.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "product_sku")
public class ProductSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long merchantId;
    private Long productId;
    private String skuCode;
    private String skuName;
    private BigDecimal price;
    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;
    private Integer availableStock;

    @Enumerated(EnumType.STRING)
    private SkuStatus status;

    private Instant deletedAt;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected ProductSku() {
    }

    public ProductSku(
            Long merchantId,
            Long productId,
            String skuCode,
            String skuName,
            BigDecimal price,
            int availableStock
    ) {
        this.merchantId = merchantId;
        this.productId = productId;
        revise(skuCode, skuName, price, availableStock);
        this.currency = "CNY";
        this.status = SkuStatus.ENABLED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void revise(String skuCode, String skuName, BigDecimal price, int availableStock) {
        this.skuCode = skuCode;
        this.skuName = skuName;
        this.price = price;
        this.availableStock = availableStock;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public String getSkuName() {
        return skuName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getAvailableStock() {
        return availableStock;
    }

    public SkuStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
