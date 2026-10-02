package com.megagraph.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.catalog.ProductImageRepository;
import com.megagraph.shop.catalog.ProductRepository;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.guide.GuideCatalogSearch;
import com.megagraph.shop.guide.GuideIntent;
import com.megagraph.shop.projection.OutboxEventRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:guide-expanded-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "app.test-supply.asset-dir=../shopper-web/public/demo-products",
        "app.media.upload-dir=${java.io.tmpdir}/megagraph-expanded-supply-tests"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "supply-demo", "supply-expanded", "test"})
class ExpandedSupplyIntegrationTest {
    @Autowired TestSupplyConfig.TestSupplyService supply;
    @Autowired PublicCatalogService catalog;
    @Autowired ProductRepository products;
    @Autowired ProductSkuRepository skus;
    @Autowired ProductImageRepository images;
    @Autowired OutboxEventRepository outbox;
    @Autowired MockMvc mvc;

    @Test
    void expandedFixturesAreConfirmedImageBackedAndIdempotent() throws Exception {
        var visible = catalog.list();
        assertThat(visible).hasSize(65);
        assertThat(visible).allSatisfy(product -> {
            assertThat(product.evidence().dataOrigin()).isEqualTo("LOCAL_TEST");
            assertThat(product.evidence().confirmationStatus()).isEqualTo("TEST_CONFIRMED");
            assertThat(product.evidence().details().imageSourceNote()).contains("测试设定");
        });
        assertThat(visible.stream().map(product -> product.evidence().merchantId()).distinct()).hasSize(2);
        assertThat(visible.stream().map(PublicProductResponse::categoryId).distinct()).hasSize(3);
        for (var product : visible) mvc.perform(get(product.mainImageUrl())).andExpect(status().isOk());
        long events = outbox.count();
        assertThat(events).isEqualTo(65);
        supply.ensureTestSupply();
        assertThat(products.count()).isEqualTo(65);
        assertThat(skus.count()).isEqualTo(65);
        assertThat(images.count()).isEqualTo(65);
        assertThat(outbox.count()).isEqualTo(events);
    }

    @Test
    void olderLowPriceSupplyIsRetrievedFromPersistedCatalogBeyondCandidateLimit() {
        var visible = catalog.list();
        assertThat(visible.subList(0, 50)).allSatisfy(product ->
                assertThat(product.skus().getFirst().code()).startsWith("EXP-"));
        var search = new GuideCatalogSearch(catalog.categoryNames());
        for (var expectation : List.of(
                List.of("衬衫", "浅蓝", "200", "M", "TEST-BLUE-M"),
                List.of("针织", "米白", "180", "M", "TEST-IVORY-M"),
                List.of("文件袋", "绿色", "100", "", "TEST-BAG-A4"),
                List.of("纸质笔记本", "红色", "30", "", "TEST-NOTE-A5"),
                List.of("开衫", "灰色", "230", "M", "TEST-GRAY-M"))) {
            var intent = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, new BigDecimal(expectation.get(2)),
                    "CNY", List.of(), List.of(expectation.get(0)), List.of(expectation.get(1)),
                    List.of(), List.of(), List.of(), expectation.get(3));
            assertThat(search.search(visible, intent, 50).stream().flatMap(product -> product.skus().stream())
                    .map(sku -> sku.code())).containsExactly(expectation.get(4));
        }
    }
}

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:guide-baseline-supply-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "app.test-supply.asset-dir=../shopper-web/public/demo-products",
        "app.media.upload-dir=${java.io.tmpdir}/megagraph-baseline-supply-tests"
})
@ActiveProfiles({"local", "supply-demo", "test"})
class BaselineSupplyIntegrationTest {
    @Autowired PublicCatalogService catalog;

    @Test
    void baselineProfileStillSeedsOnlyTheOriginalFiveProducts() {
        assertThat(catalog.list().stream().flatMap(product -> product.skus().stream()).map(sku -> sku.code()))
                .containsExactlyInAnyOrder("TEST-BLUE-M", "TEST-IVORY-M", "TEST-GRAY-M", "TEST-BAG-A4", "TEST-NOTE-A5");
    }
}
