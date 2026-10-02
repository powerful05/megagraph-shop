package com.megagraph.shop.catalog;

import com.megagraph.shop.api.ProductImageResponse;
import com.megagraph.shop.api.ProductResponse;
import com.megagraph.shop.api.SkuResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    private final ProductImageRepository images;
    private final ProductSkuRepository skus;
    private final ProductEvidenceMapper evidence;

    public ProductMapper(ProductImageRepository images, ProductSkuRepository skus, ProductEvidenceMapper evidence) {
        this.images = images;
        this.skus = skus;
        this.evidence = evidence;
    }

    public ProductResponse toMerchantResponse(Product product) {
        List<ProductImageResponse> imageResponses = images.findByProductIdOrderBySortOrderAsc(product.getId()).stream()
                .map(image -> new ProductImageResponse(
                        image.getId(),
                        "/api/merchant/v1/media/" + image.getId(),
                        image.getImageRole().name(),
                        image.getOriginalFilename()
                ))
                .toList();
        List<SkuResponse> skuResponses = skus.findByProductIdAndDeletedAtIsNullOrderByIdAsc(product.getId()).stream()
                .map(sku -> new SkuResponse(
                        sku.getId(),
                        sku.getSkuCode(),
                        sku.getSkuName(),
                        sku.getPrice(),
                        sku.getCurrency(),
                        sku.getAvailableStock(),
                        sku.getStatus().name()
                ))
                .toList();

        return new ProductResponse(
                product.getId(),
                product.getVersion(),
                product.getCategoryId(),
                product.getTitle(),
                product.getSubtitle(),
                product.getDraftStatus().name(),
                product.getPublishStatus().name(),
                product.getPublishedAt(),
                imageResponses,
                skuResponses,
                evidence.map(product)
        );
    }
}
