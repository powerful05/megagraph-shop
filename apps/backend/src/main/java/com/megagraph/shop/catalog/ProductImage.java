package com.megagraph.shop.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "product_image")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long merchantId;
    private Long productId;
    private String storageKey;
    private String originalFilename;
    private String contentType;
    private Long byteSize;

    @Enumerated(EnumType.STRING)
    private ImageRole imageRole;

    private Integer sortOrder;
    private Instant createdAt;

    protected ProductImage() {
    }

    public ProductImage(
            Long merchantId,
            Long productId,
            String storageKey,
            String originalFilename,
            String contentType,
            long byteSize,
            ImageRole imageRole,
            int sortOrder
    ) {
        this.merchantId = merchantId;
        this.productId = productId;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.imageRole = imageRole;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
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

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public ImageRole getImageRole() {
        return imageRole;
    }
}

