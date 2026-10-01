package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.megagraph.shop.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuideCatalogSearchTest {
    private final GuideCatalogSearch search = new GuideCatalogSearch();

    @Test
    void filtersBeforeCandidateLimitAndNeverLosesOlderMatchingSupply() {
        List<PublicProductResponse> catalog = new ArrayList<>();
        for (long id = 1; id <= 60; id++) catalog.add(product(id, "衬衫", "蓝", "100% 棉", "CONFIRMED", "CNY", "M", "199"));
        catalog.add(product(61L, "米白针织", "米白", "含棉混纺", "CONFIRMED", "CNY", "M", "169"));
        GuideIntent intent = intent(null, List.of("白"), List.of(), List.of(), "");
        assertThat(search.search(catalog, intent, 50)).extracting(PublicProductResponse::id).containsExactly(61L);
    }

    @Test
    void budgetAndSizeMustBeSatisfiedByTheSameSku() {
        PublicProductResponse base = product(1L, "开衫", "灰", "100% 棉", "CONFIRMED", "CNY", "M", "229");
        PublicProductResponse multi = new PublicProductResponse(base.id(), base.title(), base.subtitle(), base.categoryId(),
                new BigDecimal("99"), "CNY", 4, null, base.publishedAt(),
                List.of(sku(1L, "S", "99", "CNY"), sku(2L, "M", "229", "CNY")), base.evidence());
        assertThat(search.qualify(multi, intent("150", List.of(), List.of(), List.of(), "M"))).isEmpty();
        var result = search.qualify(multi, intent("150", List.of(), List.of(), List.of(), "S")).orElseThrow();
        assertThat(result.skus()).extracting(PublicSkuResponse::id).containsExactly(1L);
        assertThat(result.priceFrom()).isEqualByComparingTo("99");
        assertThat(result.availableStock()).isEqualTo(2);
    }

    @Test
    void hardAttributesRequireConfirmedEvidenceAndExcludedColorsAreRemoved() {
        var blue = product(1L, "蓝衬衫", "浅蓝", "100% 棉", "CONFIRMED", "CNY", "M", "100");
        var unconfirmed = product(2L, "白衬衫", "白", "100% 棉", "UNCONFIRMED", "CNY", "M", "100");
        var white = product(3L, "白衬衫", "米白", "100% 棉", "CONFIRMED", "CNY", "M", "100");
        assertThat(search.search(List.of(blue, unconfirmed, white), intent(null, List.of(), List.of("蓝"), List.of(), ""), 50))
                .extracting(PublicProductResponse::id).containsExactly(3L);
    }

    @Test
    void pureCottonDoesNotMatchACottonBlend() {
        var pure = product(1L, "上衣", "白", "100% 棉", "CONFIRMED", "CNY", "M", "100");
        var blend = product(2L, "上衣", "白", "60% 棉 / 40% 粘纤", "CONFIRMED", "CNY", "M", "100");
        assertThat(search.search(List.of(pure, blend), intent(null, List.of(), List.of(), List.of("纯棉"), ""), 50))
                .extracting(PublicProductResponse::id).containsExactly(1L);
    }

    @Test
    void broadCategoryMatchesTaxonomyEvenWhenTitleDoesNotRepeatItsName() {
        var shirt = product(1L, "浅蓝衬衫", "蓝", "100% 棉", "CONFIRMED", "CNY", "M", "199");
        var taxonomy = new GuideCatalogSearch(java.util.Map.of(1L, "上衣 女装"));
        var intent = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, null, "CNY", List.of(), List.of("上衣"),
                List.of(), List.of(), List.of("100%棉"), List.of(), "");
        assertThat(taxonomy.search(List.of(shirt), intent, 50)).extracting(PublicProductResponse::id).containsExactly(1L);
    }

    @Test
    void digitalAndServiceRequestsNeverReturnPhysicalSupply() {
        var physical = product(1L, "上衣", "灰", "棉", "CONFIRMED", "CNY", "M", "100");
        for (var type : List.of(GuideIntent.DeliveryType.DIGITAL, GuideIntent.DeliveryType.SERVICE)) {
            var intent = new GuideIntent(type, null, "CNY", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "");
            assertThat(search.search(List.of(physical), intent, 50)).isEmpty();
        }
    }

    @Test
    void unclearPurposeDoesNotBecomeApparelJustBecauseApparelIsAvailable() {
        var product = product(1L, "上衣", "灰", "棉", "CONFIRMED", "CNY", "M", "100");
        var intent = new GuideIntent(GuideIntent.DeliveryType.UNKNOWN, null, "CNY", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "", true);
        assertThat(search.search(List.of(product), intent, 50)).isEmpty();
    }

    @Test
    void explicitCategoryAndCurrencyAreNotSilentlyReplaced() {
        var physical = product(1L, "上衣", "灰", "棉", "CONFIRMED", "CNY", "M", "100");
        var phone = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, null, "CNY", List.of(), List.of("手机"), List.of(), List.of(), List.of(), List.of(), "");
        assertThat(search.search(List.of(physical), phone, 50)).isEmpty();
        var usd = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, new BigDecimal("200"), "USD", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "");
        assertThat(search.search(List.of(physical), usd, 50)).isEmpty();
    }

    @Test
    void sizeLabelsAreExactAndDoNotTreatXlAsL() {
        var xl = product(1L, "上衣", "灰", "棉", "CONFIRMED", "CNY", "XL", "100");
        assertThat(search.qualify(xl, intent(null, List.of(), List.of(), List.of(), "L"))).isEmpty();
    }

    @Test
    void scenarioTermsRankSupplyWithoutInventingHardRequirements() {
        var first = product(1L, "休闲上衣", "灰", "棉", "CONFIRMED", "CNY", "M", "100");
        var second = product(2L, "通勤衬衫", "白", "棉", "CONFIRMED", "CNY", "M", "100");
        var intent = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, null, "CNY", List.of("通勤"), List.of(), List.of(), List.of(), List.of(), List.of(), "");
        assertThat(search.search(List.of(first, second), intent, 50)).extracting(PublicProductResponse::id).containsExactly(2L, 1L);
    }

    private GuideIntent intent(String budget, List<String> colors, List<String> excluded, List<String> fabrics, String size) {
        return new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, budget == null ? null : new BigDecimal(budget), "CNY", List.of(), List.of(), colors, excluded, fabrics, List.of(), size);
    }

    private PublicProductResponse product(Long id, String title, String color, String fabric, String confirmation, String currency, String size, String price) {
        var details = new SupplyDetails(color, fabric, "常规", List.of("春"), List.of("日常"), "测试成衣尺寸", "测试图", List.of());
        var evidence = new ProductEvidence(1L, id, "测试商家", "PHYSICAL", "LOCAL_TEST", "CONFIRMED".equals(confirmation) ? "TEST_CONFIRMED" : confirmation, Instant.now(), details);
        return new PublicProductResponse(id, title, "测试资料", 1L, new BigDecimal(price), currency, 2, null, Instant.now(), List.of(sku(id, size, price, currency)), evidence);
    }
    private PublicSkuResponse sku(Long id, String size, String price, String currency) {
        return new PublicSkuResponse(id, "TEST-" + id, "灰 / " + size, new BigDecimal(price), currency, 2);
    }
}
