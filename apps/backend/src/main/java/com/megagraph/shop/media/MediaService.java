package com.megagraph.shop.media;

import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductImage;
import com.megagraph.shop.catalog.ProductImageRepository;
import com.megagraph.shop.catalog.ProductRepository;
import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.security.MerchantActor;
import com.megagraph.shop.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaService {

    private final ProductImageRepository images;
    private final ProductRepository products;
    private final PublicCatalogService publicCatalog;
    private final MediaStorage storage;

    public MediaService(
            ProductImageRepository images,
            ProductRepository products,
            PublicCatalogService publicCatalog,
            MediaStorage storage
    ) {
        this.images = images;
        this.products = products;
        this.publicCatalog = publicCatalog;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public MediaPayload loadMerchantMedia(MerchantActor actor, Long imageId) {
        ProductImage image = images.findById(imageId)
                .filter(value -> value.getMerchantId().equals(actor.merchantId()))
                .orElseThrow(() -> new NotFoundException("MEDIA_NOT_FOUND", "图片不存在"));
        return payload(image);
    }

    @Transactional(readOnly = true)
    public MediaPayload loadPublicMedia(Long imageId) {
        return loadPublicMedia(imageId, null);
    }

    @Transactional(readOnly = true)
    public MediaPayload loadPublicMedia(Long imageId, Integer width) {
        if (width != null && width != 320 && width != 640 && width != 960) {
            throw new com.megagraph.shop.shared.ValidationException("MEDIA_WIDTH_INVALID", "图片宽度须为320、640或960");
        }
        ProductImage image = images.findById(imageId)
                .orElseThrow(() -> new NotFoundException("MEDIA_NOT_FOUND", "图片不存在"));
        Product product = products.findById(image.getProductId())
                .filter(value -> value.getMerchantId().equals(image.getMerchantId()))
                .filter(publicCatalog::isEligible)
                .orElseThrow(() -> new NotFoundException("MEDIA_NOT_FOUND", "图片不存在"));
        if (product.getPublishStatus() != com.megagraph.shop.catalog.PublishStatus.PUBLISHED) {
            throw new NotFoundException("MEDIA_NOT_FOUND", "图片不存在");
        }
        MediaPayload original = payload(image);
        return width == null ? original : MediaPreview.resize(original, width);
    }

    private MediaPayload payload(ProductImage image) {
        return new MediaPayload(storage.load(image.getStorageKey()), image.getContentType());
    }
}
