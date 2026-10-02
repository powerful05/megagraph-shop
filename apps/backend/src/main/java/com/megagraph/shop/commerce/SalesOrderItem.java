package com.megagraph.shop.commerce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sales_order_item")
public class SalesOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;
    private Long productId;
    private Long skuId;
    private Long merchantId;
    private String productTitle;
    private String skuCode;
    private String skuName;
    private Long productVersion;
    @Column(columnDefinition = "TEXT")
    private String supplyDetails;
    private BigDecimal unitPrice;

    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;

    private Integer quantity;
    private BigDecimal lineAmount;
    private Instant createdAt;

    protected SalesOrderItem() {
    }

    public SalesOrderItem(
            Long orderId,
            Long productId,
            Long skuId,
            Long merchantId,
            String productTitle,
            String skuCode,
            String skuName,
            BigDecimal unitPrice,
            String currency,
            int quantity
    ) {
        this.orderId = orderId;
        this.productId = productId;
        this.skuId = skuId;
        this.merchantId = merchantId;
        this.productTitle = productTitle;
        this.skuCode = skuCode;
        this.skuName = skuName;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.quantity = quantity;
        this.lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void captureSupply(com.megagraph.shop.catalog.Product product) {
        this.productVersion = product.getVersion();
        this.supplyDetails = product.getSupplyDetails();
    }
    public Long getProductVersion() { return productVersion; }
    public String getSupplyDetails() { return supplyDetails; }

    public Long getProductId() {
        return productId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getProductTitle() {
        return productTitle;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public String getSkuName() {
        return skuName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getLineAmount() {
        return lineAmount;
    }
}
