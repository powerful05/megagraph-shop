package com.megagraph.shop.catalog;

import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.api.PublicSkuResponse;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantStatus;
import com.megagraph.shop.shared.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicCatalogService {

    private final ProductRepository products;
    private final ProductSkuRepository skus;
    private final ProductImageRepository images;
    private final MerchantRepository merchants;

    public PublicCatalogService(
            ProductRepository products,
            ProductSkuRepository skus,
            ProductImageRepository images,
            MerchantRepository merchants
    ) {
        this.products = products;
        this.skus = skus;
        this.images = images;
        this.merchants = merchants;
    }

    @Transactional(readOnly = true)
    public List<PublicProductResponse> list() {
        return products.findByPublishStatusAndDeletedAtIsNullOrderByPublishedAtDesc(PublishStatus.PUBLISHED).stream()
                .filter(this::isEligible)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PublicProductResponse get(Long productId) {
        Product product = products.findById(productId)
                .filter(value -> value.getDeletedAt() == null)
                .filter(value -> value.getPublishStatus() == PublishStatus.PUBLISHED)
                .filter(this::isEligible)
                .orElseThrow(() -> new NotFoundException("PUBLIC_PRODUCT_NOT_FOUND", "商品不存在或当前不可售"));
        return toResponse(product);
    }

    public boolean isEligible(Product product) {
        if (product.getDeletedAt() != null || product.getPublishStatus() != PublishStatus.PUBLISHED) {
            return false;
        }
        boolean activeMerchant = merchants.findById(product.getMerchantId())
                .map(merchant -> merchant.getStatus() == MerchantStatus.ACTIVE)
                .orElse(false);
        return activeMerchant && skus.existsByProductIdAndStatusAndDeletedAtIsNullAndAvailableStockGreaterThan(
                product.getId(),
                SkuStatus.ENABLED,
                0
        );
    }

    private PublicProductResponse toResponse(Product product) {
        List<ProductSku> sellableSkus = skus.findByProductIdAndDeletedAtIsNullOrderByIdAsc(product.getId()).stream()
                .filter(sku -> sku.getStatus() == SkuStatus.ENABLED && sku.getAvailableStock() > 0)
                .toList();
        BigDecimal priceFrom = sellableSkus.stream()
                .map(ProductSku::getPrice)
                .min(BigDecimal::compareTo)
                .orElseThrow();
        int availableStock = sellableSkus.stream().mapToInt(ProductSku::getAvailableStock).sum();
        String imageUrl = images.findFirstByProductIdOrderBySortOrderAsc(product.getId())
                .map(image -> "/api/shop/v1/media/" + image.getId())
                .orElse(null);
        String currency = sellableSkus.getFirst().getCurrency();

        return new PublicProductResponse(
                product.getId(),
                product.getTitle(),
                product.getSubtitle(),
                product.getCategoryId(),
                priceFrom,
                currency,
                availableStock,
                imageUrl,
                product.getPublishedAt(),
                sellableSkus.stream()
                        .map(sku -> new PublicSkuResponse(
                                sku.getId(),
                                sku.getSkuCode(),
                                sku.getSkuName(),
                                sku.getPrice(),
                                sku.getCurrency(),
                                sku.getAvailableStock()
                        ))
                        .toList()
        );
    }
}
