package com.megagraph.shop.config;

import com.megagraph.shop.api.ProductDraftUpdateRequest;
import com.megagraph.shop.api.SkuDraftRequest;
import com.megagraph.shop.api.SupplyDetails;
import com.megagraph.shop.catalog.*;
import com.megagraph.shop.identity.*;
import com.megagraph.shop.media.MediaStorage;
import com.megagraph.shop.security.MerchantActor;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@Profile("local & supply-demo")
public class TestSupplyConfig {
    @Bean
    ApplicationRunner testSupplyInitializer(TestSupplyService service) {
        return args -> service.ensureTestSupply();
    }

    @Service
    @Profile("local & supply-demo")
    public static class TestSupplyService {
        private final DevSeedConfig.DevSeedService devSeed;
        private final UserAccountRepository users;
        private final MerchantRepository merchants;
        private final MerchantUserRepository memberships;
        private final CategoryRepository categories;
        private final ProductRepository products;
        private final ProductImageRepository images;
        private final ProductService productService;
        private final MediaStorage media;
        private final Path assets;

        public TestSupplyService(DevSeedConfig.DevSeedService devSeed, UserAccountRepository users,
                MerchantRepository merchants, MerchantUserRepository memberships, CategoryRepository categories,
                ProductRepository products, ProductImageRepository images, ProductService productService,
                MediaStorage media, @Value("${app.test-supply.asset-dir}") String assetDir) {
            this.devSeed = devSeed;
            this.users = users;
            this.merchants = merchants;
            this.memberships = memberships;
            this.categories = categories;
            this.products = products;
            this.images = images;
            this.productService = productService;
            this.media = media;
            this.assets = Path.of(assetDir);
        }

        @Transactional(rollbackFor = IOException.class)
        public void ensureTestSupply() throws IOException {
            devSeed.ensureSeedData();
            Long userId = users.findByUsernameIgnoreCase("merchant@megagraph.local").orElseThrow().getId();
            Merchant first = testMerchant("测试商家 A · 轻织", userId);
            Merchant second = testMerchant("测试商家 B · 日常", userId);
            Long categoryId = categories.findAll().stream().filter(category -> category.getName().equals("上衣"))
                    .findFirst().orElseThrow().getId();
            create(first, userId, categoryId, "测试 · 浅蓝衬衫", "TEST-BLUE-M", "浅蓝", "100% 棉", "直筒",
                    "199.00", "104", "66", "44", "58", "merchant-a-blue-shirt.jpg");
            create(second, userId, categoryId, "测试 · 米白针织上衣", "TEST-IVORY-M", "米白", "60% 棉 / 40% 粘纤", "常规",
                    "169.00", "96", "58", "38", "56", "merchant-b-ivory-knit.jpg");
            create(first, userId, categoryId, "测试 · 灰色开衫", "TEST-GRAY-M", "灰色", "100% 棉", "宽松",
                    "229.00", "110", "62", "46", "57", "merchant-c-gray-cardigan.jpg");
        }

        private Merchant testMerchant(String name, Long userId) {
            Merchant merchant = merchants.findAll().stream().filter(value -> value.getName().equals(name)).findFirst()
                    .orElseGet(() -> {
                        Merchant created = merchants.save(new Merchant(name));
                        memberships.save(new MerchantUser(created.getId(), userId, MerchantRole.OWNER));
                        return created;
                    });
            return merchant;
        }

        private void create(Merchant merchant, Long userId, Long categoryId, String title, String code,
                String color, String fabric, String fit, String price, String chest, String length,
                String shoulder, String sleeve, String filename) throws IOException {
            if (products.findByMerchantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(merchant.getId()).stream()
                    .anyMatch(product -> product.isTestSupply() && product.getTitle().equals(title))) return;
            Product product = new Product(merchant.getId(), title);
            product.markTestSupply();
            product = products.saveAndFlush(product);
            try (var input = Files.newInputStream(assets.resolve(filename))) {
                var stored = media.store(merchant.getId(), filename, "image/jpeg", input);
                images.save(new ProductImage(merchant.getId(), product.getId(), stored.storageKey(), filename,
                        "image/jpeg", stored.byteSize(), ImageRole.MAIN, 0));
            }
            var details = new SupplyDetails(color, fabric, fit, List.of("春", "秋"), List.of("通勤", "日常"),
                    "测试设定：平铺测量，胸围为成衣一周长度，误差 ±2 cm；不代表图片实物尺寸。",
                    "AI 生成的本地演示图，非真实商家拍摄；属性和尺寸均为测试设定。",
                    List.of(new SupplyDetails.SizeRow(code, "M", new BigDecimal(chest), new BigDecimal(length),
                            new BigDecimal(shoulder), new BigDecimal(sleeve))));
            var actor = new MerchantActor(userId, merchant.getId(), MerchantRole.OWNER);
            var revised = productService.updateDraft(actor, product.getId(), new ProductDraftUpdateRequest(
                    product.getVersion(), title, "仅用于本地流程验证，不是真实商家供给", categoryId,
                    new SkuDraftRequest(code, color + " / M", new BigDecimal(price), 20), details));
            var confirmed = productService.confirm(actor, product.getId(), revised.version());
            productService.publish(actor, product.getId(), confirmed.version());
        }
    }
}
