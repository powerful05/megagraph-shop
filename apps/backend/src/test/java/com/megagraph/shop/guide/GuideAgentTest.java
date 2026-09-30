package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.api.PublicSkuResponse;
import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.shared.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GuideAgentTest {

    private final PublicCatalogService catalog = mock(PublicCatalogService.class);
    private final GuideModel model = mock(GuideModel.class);
    private final GuideAgent agent = new GuideAgent(catalog, Optional.of(model));

    @Test
    void onlyReturnsFreshEligibleProductsFromTheCandidateSet() {
        PublicProductResponse first = product(1L, "通勤外套", "旧图片");
        PublicProductResponse second = product(2L, "休闲外套", "即将下架");
        when(catalog.list()).thenReturn(List.of(first, second));
        when(model.select(org.mockito.ArgumentMatchers.eq("想找一件通勤外套"),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GuideModel.Selection("你希望找通勤外套。", List.of(999L, 2L, 1L, 1L)));
        when(catalog.get(2L)).thenThrow(new NotFoundException("PUBLIC_PRODUCT_NOT_FOUND", "商品已下架"));
        when(catalog.get(1L)).thenReturn(product(1L, "通勤外套", "最新图片"));

        GuideResult result = agent.recommend("  想找一件通勤外套  ");

        assertThat(result.products()).extracting(PublicProductResponse::id).containsExactly(1L);
        assertThat(result.products().getFirst().mainImageUrl()).isEqualTo("最新图片");
        assertThat(result.answer()).contains("通勤外套");
        verify(catalog).get(1L);
    }

    @Test
    void returnsNoInventedProductsWhenModelChoosesOnlyUnknownIds() {
        when(catalog.list()).thenReturn(List.of(product(1L, "通勤外套", "图片")));
        when(model.select(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GuideModel.Selection("", List.of(999L)));

        GuideResult result = agent.recommend("适合上班穿的外套");

        assertThat(result.products()).isEmpty();
        assertThat(result.answer()).contains("没有找到");
    }

    @Test
    void rejectsBlankRequestsAndDoesNotCallTheModelWithoutProducts() {
        assertThatThrownBy(() -> agent.recommend("  ")).isInstanceOf(IllegalArgumentException.class);
        when(catalog.list()).thenReturn(List.of());
        assertThat(agent.recommend("我想找一件特别的礼物").products()).isEmpty();
    }

    private PublicProductResponse product(Long id, String title, String image) {
        return new PublicProductResponse(id, title, "适合日常穿着", 1L, new BigDecimal("199.00"),
                "CNY", 2, image, Instant.parse("2026-09-30T00:00:00Z"),
                List.of(new PublicSkuResponse(id, "sku-" + id, "均码", new BigDecimal("199.00"), "CNY", 2)));
    }
}
