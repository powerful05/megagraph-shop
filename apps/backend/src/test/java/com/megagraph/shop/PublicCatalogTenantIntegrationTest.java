package com.megagraph.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.megagraph.shop.catalog.CategoryRepository;
import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductRepository;
import com.megagraph.shop.catalog.ProductSku;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.catalog.ProductImage;
import com.megagraph.shop.catalog.ProductImageRepository;
import com.megagraph.shop.catalog.ImageRole;
import com.megagraph.shop.identity.Merchant;
import com.megagraph.shop.identity.MerchantRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicCatalogTenantIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MerchantRepository merchants;
    @Autowired ProductRepository products;
    @Autowired ProductSkuRepository skus;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductImageRepository images;

    @Test
    void publicCatalogCrossesActiveMerchantsButHidesSuspendedMerchant() throws Exception {
        long categoryId = categories.findAll().getFirst().getId();
        Merchant first = merchants.save(new Merchant("公开商家 A"));
        Merchant second = merchants.save(new Merchant("公开商家 B"));
        Product firstProduct = publish(first.getId(), categoryId, "基础白衬衫", "2e-public-a");
        Product secondProduct = publish(second.getId(), categoryId, "直筒半裙", "2e-public-b");
        Product draft = products.save(new Product(first.getId(), "未公开草稿"));
        ProductImage privateImage = images.save(new ProductImage(first.getId(), draft.getId(),
                "test-only-missing.png", "draft.png", "image/png", 12, ImageRole.MAIN, 0));
        ProductImage mismatchedImage = images.save(new ProductImage(second.getId(), firstProduct.getId(),
                "test-only-missing.png", "mismatched.png", "image/png", 12, ImageRole.MAIN, 0));

        mvc.perform(get("/api/shop/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + firstProduct.getId() + ")]").exists())
                .andExpect(jsonPath("$[?(@.id == " + secondProduct.getId() + ")]").exists());
        mvc.perform(get("/api/shop/v1/products/{id}", draft.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/shop/v1/media/{id}", privateImage.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/shop/v1/media/{id}", mismatchedImage.getId())).andExpect(status().isNotFound());

        jdbc.update("UPDATE merchant SET status = 'SUSPENDED' WHERE id = ?", second.getId());
        mvc.perform(get("/api/shop/v1/products/{id}", secondProduct.getId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/shop/v1/products/{id}", firstProduct.getId()))
                .andExpect(status().isOk());
    }

    private Product publish(Long merchantId, long categoryId, String title, String skuCode) {
        Product product = products.save(new Product(merchantId, title));
        product.revise(title, "测试公开商品", categoryId);
        product.confirm(1L);
        product.publish();
        products.save(product);
        skus.save(new ProductSku(merchantId, product.getId(), skuCode, "标准码", new BigDecimal("99.00"), 3));
        return product;
    }
}
