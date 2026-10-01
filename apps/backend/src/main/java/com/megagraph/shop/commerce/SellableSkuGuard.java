package com.megagraph.shop.commerce;

import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductRepository;
import com.megagraph.shop.catalog.ProductSku;
import com.megagraph.shop.catalog.PublishStatus;
import com.megagraph.shop.catalog.SkuStatus;
import com.megagraph.shop.catalog.TestSupplyPolicy;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantStatus;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import org.springframework.stereotype.Component;

@Component
public class SellableSkuGuard {

    private final ProductRepository products;
    private final MerchantRepository merchants;
    private final TestSupplyPolicy testSupply;

    public SellableSkuGuard(ProductRepository products, MerchantRepository merchants, TestSupplyPolicy testSupply) {
        this.products = products;
        this.merchants = merchants;
        this.testSupply = testSupply;
    }

    public Product requireSellable(ProductSku sku, int quantity) {
        if (sku.getDeletedAt() != null || sku.getStatus() != SkuStatus.ENABLED) {
            throw new NotFoundException("SKU_NOT_SELLABLE", "SKU 不存在或当前不可售");
        }
        Product product = products.findById(sku.getProductId())
                .filter(value -> value.getDeletedAt() == null)
                .filter(value -> value.getPublishStatus() == PublishStatus.PUBLISHED)
                .filter(testSupply::permits)
                .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_SELLABLE", "商品不存在或当前不可售"));
        boolean merchantActive = merchants.findById(product.getMerchantId())
                .map(merchant -> merchant.getStatus() == MerchantStatus.ACTIVE)
                .orElse(false);
        if (!merchantActive) {
            throw new NotFoundException("MERCHANT_NOT_SELLABLE", "商家当前不可交易");
        }
        if (sku.getAvailableStock() == null || sku.getAvailableStock() < quantity) {
            throw new ValidationException("SKU_STOCK_INSUFFICIENT", "SKU 可售库存不足");
        }
        if (sku.getPrice() == null || sku.getPrice().signum() < 0) {
            throw new ValidationException("SKU_PRICE_INVALID", "SKU 价格无效");
        }
        return product;
    }
}
