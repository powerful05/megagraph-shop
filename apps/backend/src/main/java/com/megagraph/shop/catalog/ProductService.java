package com.megagraph.shop.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.ProductDraftUpdateRequest;
import com.megagraph.shop.api.ProductResponse;
import com.megagraph.shop.api.SkuDraftRequest;
import com.megagraph.shop.identity.Merchant;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantStatus;
import com.megagraph.shop.projection.OutboxEvent;
import com.megagraph.shop.projection.OutboxEventRepository;
import com.megagraph.shop.security.MerchantActor;
import com.megagraph.shop.shared.ConflictException;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final ProductSkuRepository skus;
    private final ProductImageRepository images;
    private final CategoryRepository categories;
    private final MerchantRepository merchants;
    private final OutboxEventRepository outbox;
    private final ProductMapper mapper;
    private final ObjectMapper objectMapper;

    public ProductService(
            ProductRepository products,
            ProductSkuRepository skus,
            ProductImageRepository images,
            CategoryRepository categories,
            MerchantRepository merchants,
            OutboxEventRepository outbox,
            ProductMapper mapper,
            ObjectMapper objectMapper
    ) {
        this.products = products;
        this.skus = skus;
        this.images = images;
        this.categories = categories;
        this.merchants = merchants;
        this.outbox = outbox;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list(MerchantActor actor) {
        return products.findByMerchantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(actor.merchantId()).stream()
                .map(mapper::toMerchantResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(MerchantActor actor, Long productId) {
        return mapper.toMerchantResponse(requireOwnedProduct(actor, productId));
    }

    @Transactional
    public ProductResponse updateDraft(
            MerchantActor actor,
            Long productId,
            ProductDraftUpdateRequest request
    ) {
        Product product = requireOwnedProduct(actor, productId);
        requireVersion(product, request.version());
        if (product.getPublishStatus() == PublishStatus.PUBLISHED) {
            throw new ConflictException("PUBLISHED_PRODUCT_IMMUTABLE", "请先下架商品，再修改草稿");
        }
        Category category = categories.findById(request.categoryId())
                .filter(value -> value.isVisible()
                        && !categories.existsByParentIdAndVisibleTrue(value.getId()))
                .orElseThrow(() -> new ValidationException("CATEGORY_INVALID", "请选择有效类目"));

        product.revise(request.title().trim(), trimToNull(request.subtitle()), category.getId());
        upsertSku(actor, product, request.sku());
        Product saved = products.saveAndFlush(product);
        return mapper.toMerchantResponse(saved);
    }

    @Transactional
    public ProductResponse confirm(MerchantActor actor, Long productId, Long version) {
        Product product = requireOwnedProduct(actor, productId);
        requireVersion(product, version);
        validatePublishable(product);
        product.confirm(actor.userId());
        return mapper.toMerchantResponse(products.saveAndFlush(product));
    }

    @Transactional
    public ProductResponse publish(MerchantActor actor, Long productId, Long version) {
        Product product = requireOwnedProduct(actor, productId);
        requireVersion(product, version);
        if (product.getDraftStatus() != DraftStatus.CONFIRMED) {
            throw new ValidationException("PRODUCT_NOT_CONFIRMED", "请先确认当前商品草稿");
        }
        validatePublishable(product);
        Merchant merchant = merchants.findById(actor.merchantId())
                .filter(value -> value.getStatus() == MerchantStatus.ACTIVE)
                .orElseThrow(() -> new ValidationException("MERCHANT_NOT_ACTIVE", "商家当前不可发布商品"));

        product.publish();
        Product saved = products.saveAndFlush(product);
        outbox.save(new OutboxEvent(
                "PRODUCT",
                saved.getId(),
                saved.getVersion(),
                "PRODUCT_PUBLISHED",
                eventPayload(saved)
        ));
        return mapper.toMerchantResponse(saved);
    }

    @Transactional
    public ProductResponse offShelf(MerchantActor actor, Long productId, Long version) {
        Product product = requireOwnedProduct(actor, productId);
        requireVersion(product, version);
        if (product.getPublishStatus() != PublishStatus.PUBLISHED) {
            throw new ValidationException("PRODUCT_NOT_PUBLISHED", "只有已发布商品可以下架");
        }
        product.offShelf();
        Product saved = products.saveAndFlush(product);
        outbox.save(new OutboxEvent(
                "PRODUCT",
                saved.getId(),
                saved.getVersion(),
                "PRODUCT_OFF_SHELF",
                eventPayload(saved)
        ));
        return mapper.toMerchantResponse(saved);
    }

    private void upsertSku(MerchantActor actor, Product product, SkuDraftRequest request) {
        ProductSku sku = skus.findFirstByProductIdAndDeletedAtIsNullOrderByIdAsc(product.getId())
                .orElseGet(() -> new ProductSku(
                        actor.merchantId(),
                        product.getId(),
                        request.code().trim(),
                        request.name().trim(),
                        request.price(),
                        request.stock()
                ));
        sku.revise(
                request.code().trim(),
                request.name().trim(),
                request.price(),
                request.stock()
        );
        skus.save(sku);
    }

    private void validatePublishable(Product product) {
        if (product.getTitle() == null || product.getTitle().isBlank()) {
            throw new ValidationException("PRODUCT_TITLE_REQUIRED", "商品标题不能为空");
        }
        if (product.getCategoryId() == null
                || categories.findById(product.getCategoryId())
                .filter(value -> value.isVisible()
                        && !categories.existsByParentIdAndVisibleTrue(value.getId()))
                .isEmpty()) {
            throw new ValidationException("PRODUCT_CATEGORY_REQUIRED", "请选择有效类目");
        }
        if (images.findFirstByProductIdOrderBySortOrderAsc(product.getId()).isEmpty()) {
            throw new ValidationException("PRODUCT_MAIN_IMAGE_REQUIRED", "商品必须有主图");
        }
        List<ProductSku> productSkus = skus.findByProductIdAndDeletedAtIsNullOrderByIdAsc(product.getId());
        if (productSkus.isEmpty()) {
            throw new ValidationException("PRODUCT_SKU_REQUIRED", "商品必须至少有一个 SKU");
        }
        boolean hasSellableSku = productSkus.stream().anyMatch(sku ->
                sku.getStatus() == SkuStatus.ENABLED
                        && sku.getPrice() != null
                        && sku.getPrice().signum() >= 0
                        && sku.getAvailableStock() != null
                        && sku.getAvailableStock() > 0
        );
        if (!hasSellableSku) {
            throw new ValidationException("PRODUCT_SELLABLE_SKU_REQUIRED", "至少一个启用 SKU 必须有合法价格和可售库存");
        }
    }

    private Product requireOwnedProduct(MerchantActor actor, Long productId) {
        return products.findByIdAndMerchantIdAndDeletedAtIsNull(productId, actor.merchantId())
                .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "商品不存在"));
    }

    private void requireVersion(Product product, Long requestedVersion) {
        if (!Objects.equals(product.getVersion(), requestedVersion)) {
            throw new ConflictException("VERSION_CONFLICT", "商品已被更新，请刷新后重试");
        }
    }

    private String eventPayload(Product product) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "productId", product.getId(),
                    "merchantId", product.getMerchantId(),
                    "sourceVersion", product.getVersion(),
                    "publishStatus", product.getPublishStatus().name()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize outbox payload", exception);
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
