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
            Category daily = category(null, "日用与文具", 1);
            createGeneral(first, userId, category(daily.getId(), "文件袋", 2).getId(), "测试 · 绿色帆布文件袋",
                    "TEST-BAG-A4", "绿色 / A4", "绿色", "100% 棉帆布", "79.00", List.of("通勤", "文件收纳"),
                    "测试设定：袋身平铺外尺寸约36 × 32 × 8 cm，误差±1 cm；未验证防水或承重。",
                    List.of(new SupplyDetails.Specification("TEST-BAG-A4", "适用纸张", "A4文件"),
                            new SupplyDetails.Specification("TEST-BAG-A4", "袋身外尺寸", "约36 × 32 × 8 cm"),
                            new SupplyDetails.Specification("TEST-BAG-A4", "封口", "开放式袋口，无拉链")),
                    "merchant-a-green-document-bag.png");
            createGeneral(second, userId, category(daily.getId(), "纸质笔记本", 2).getId(), "测试 · 红色横线笔记本",
                    "TEST-NOTE-A5", "红色 / A5", "红色", "纸质内页，硬质纸板封面", "29.00", List.of("会议记录", "学习", "手写"),
                    "规格均为测试设定；未验证钢笔防洇墨或数码功能。",
                    List.of(new SupplyDetails.Specification("TEST-NOTE-A5", "规格", "A5，148 × 210 mm"),
                            new SupplyDetails.Specification("TEST-NOTE-A5", "内页", "横线，80张 / 160页"),
                            new SupplyDetails.Specification("TEST-NOTE-A5", "装订", "精装硬壳")),
                    "merchant-b-red-notebook.png");
        }

        private Category category(Long parentId, String name, int level) {
            return categories.findAll().stream().filter(value -> value.getName().equals(name)
                    && java.util.Objects.equals(parentId, value.getParentId())).findFirst()
                    .orElseGet(() -> categories.save(new Category(parentId, name, level)));
        }

        private void createGeneral(Merchant merchant, Long userId, Long categoryId, String title, String code,
                String skuName, String color, String material, String price, List<String> scenarios,
                String measurementNote, List<SupplyDetails.Specification> specifications, String filename) throws IOException {
            var details = new SupplyDetails(color, material, "", List.of(), scenarios, measurementNote,
                    "AI 生成的本地演示图，非真实商家拍摄；规格均为测试设定。", List.of(),
                    SupplyDetails.Profile.GENERAL_PHYSICAL, specifications);
            createProduct(merchant, userId, categoryId, title, code, skuName, price, filename, details);
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
            var details = new SupplyDetails(color, fabric, fit, List.of("春", "秋"), List.of("通勤", "日常"),
                    "测试设定：平铺测量，胸围为成衣一周长度，误差 ±2 cm；不代表图片实物尺寸。",
                    "AI 生成的本地演示图，非真实商家拍摄；属性和尺寸均为测试设定。",
                    List.of(new SupplyDetails.SizeRow(code, "M", new BigDecimal(chest), new BigDecimal(length),
                            new BigDecimal(shoulder), new BigDecimal(sleeve))));
            createProduct(merchant, userId, categoryId, title, code, color + " / M", price, filename, details);
        }

        private void createProduct(Merchant merchant, Long userId, Long categoryId, String title, String code,
                String skuName, String price, String filename, SupplyDetails details) throws IOException {
            if (products.findByMerchantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(merchant.getId()).stream()
                    .anyMatch(product -> product.isTestSupply() && product.getTitle().equals(title))) return;
            Product product = new Product(merchant.getId(), title);
            product.markTestSupply();
            product = products.saveAndFlush(product);
            String contentType = filename.endsWith(".png") ? "image/png" : "image/jpeg";
            try (var input = Files.newInputStream(assets.resolve(filename))) {
                var stored = media.store(merchant.getId(), filename, contentType, input);
                images.save(new ProductImage(merchant.getId(), product.getId(), stored.storageKey(), filename,
                        contentType, stored.byteSize(), ImageRole.MAIN, 0));
            }
            var actor = new MerchantActor(userId, merchant.getId(), MerchantRole.OWNER);
            var revised = productService.updateDraft(actor, product.getId(), new ProductDraftUpdateRequest(
                    product.getVersion(), title, "仅用于本地流程验证，不是真实商家供给", categoryId,
                    new SkuDraftRequest(code, skuName, new BigDecimal(price), 20), details));
            var confirmed = productService.confirm(actor, product.getId(), revised.version());
            productService.publish(actor, product.getId(), confirmed.version());
        }
    }
}
