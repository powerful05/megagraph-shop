package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.megagraph.shop.api.ProductEvidence;
import com.megagraph.shop.api.PublicProductResponse;
import com.megagraph.shop.api.PublicSkuResponse;
import com.megagraph.shop.api.SupplyDetails;
import com.megagraph.shop.catalog.PublicCatalogService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GuideEvidenceTest {
    private final PublicCatalogService catalog = mock(PublicCatalogService.class);
    private final GuideModel model = mock(GuideModel.class);
    private final GuideAgent agent = new GuideAgent(catalog, Optional.of(model));

    static PublicProductResponse notebook(String status) {
        var details = new SupplyDetails("红色", "纸质内页", "", List.of(), List.of("会议记录"),
                "未验证钢笔防洇墨。", "AI生成的测试图片。", List.of(), SupplyDetails.Profile.GENERAL_PHYSICAL,
                List.of(new SupplyDetails.Specification("OTHER", "尺寸", "A4"),
                        new SupplyDetails.Specification("NOTE-A5", "尺寸", "A5"),
                        new SupplyDetails.Specification("NOTE-A5", "装订", "硬壳")));
        return new PublicProductResponse(9L, "标题声称防水的纸本", "未经确认的真丝介绍", 2L,
                new BigDecimal("29"), "CNY", 3, "图", Instant.now(),
                List.of(new PublicSkuResponse(9L, "NOTE-A5", "A5", new BigDecimal("29"), "CNY", 3)),
                new ProductEvidence(7L, 2L, "测试商家", "PHYSICAL", "LOCAL_TEST", status, Instant.now(), details));
    }

    @Test
    void confirmedTouchRemainsAFactWithoutDerivingItFromMaterial() {
        var details = new SupplyDetails("", "棉", "", List.of(), List.of(), "", "测试资料", List.of(),
                SupplyDetails.Profile.GENERAL_PHYSICAL,
                List.of(new SupplyDetails.Specification("NOTE-A5", "触感", "表面有颗粒感")));
        var product = new PublicProductResponse(9L, "测试纸本", "测试", 2L, new BigDecimal("29"), "CNY", 3,
                "图", Instant.now(), notebook("TEST_CONFIRMED").skus(),
                new ProductEvidence(7L, 2L, "测试商家", "PHYSICAL", "LOCAL_TEST", "TEST_CONFIRMED", Instant.now(), details));
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenReturn(new GuideModel.Selection(
                "棉所以很柔软", List.of(9L), List.of(new GuideModel.EvidenceReference(9L, List.of("spec-0")))));
        var reply = agent.chat(List.of(new GuideModel.Turn("user", "找纸本")), false);
        assertThat(reply.recommendationBasis().getFirst().facts()).extracting(GuideEvidence.Fact::value)
                .containsExactly("表面有颗粒感");
        assertThat(reply.advice()).isNotBlank().doesNotContain("很柔软", "棉所以");
    }

    @Test
    void onlyConfirmedAttributesAndQualifiedSkuSpecificationsBecomeFacts() {
        var facts = GuideEvidence.facts(notebook("TEST_CONFIRMED"));
        assertThat(facts).extracting(GuideEvidence.Fact::key).containsExactly("color", "material", "scenarios", "spec-1", "spec-2");
        assertThat(facts).extracting(GuideEvidence.Fact::value).contains("纸质内页", "A5", "硬壳").doesNotContain("A4", "真丝", "防水");
        assertThat(facts).extracting(GuideEvidence.Fact::label).doesNotContain("胸围", "成衣测量");
        assertThat(GuideEvidence.facts(notebook("UNCONFIRMED"))).isEmpty();
        assertThat(GuideEvidence.facts(notebook("MISSING"))).isEmpty();
    }

    @Test
    void sourceVersionAndLimitationsComeFromTheCurrentConfirmedProduct() {
        var basis = GuideEvidence.basis(notebook("TEST_CONFIRMED"), List.of("material", "spec-1"));
        assertThat(basis.productVersion()).isEqualTo(7L);
        assertThat(basis.dataOrigin()).isEqualTo("LOCAL_TEST");
        assertThat(basis.facts()).extracting(GuideEvidence.Fact::value).containsExactly("纸质内页", "A5");
        assertThat(basis.limitations()).contains("未验证钢笔防洇墨。", "AI生成的测试图片。");
        assertThat(GuideEvidence.basis(notebook("UNCONFIRMED"), List.of()).limitations()).anyMatch(text -> text.contains("尚无已确认"));
    }

    @Test
    void modelChoosesFactKeysButCannotSupplyFactValuesOrOverwriteTheServerAnswer() {
        var product = notebook("TEST_CONFIRMED");
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenReturn(new GuideModel.Selection("选购时先确认常用笔的类型。", List.of(9L),
                List.of(new GuideModel.EvidenceReference(9L, List.of("material", "spec-1")))));
        var reply = agent.chat(List.of(new GuideModel.Turn("user", "找纸本")), false);
        assertThat(reply.products()).hasSize(1);
        assertThat(reply.recommendationBasis().getFirst().facts()).extracting(GuideEvidence.Fact::value).containsExactly("纸质内页", "A5");
        assertThat(reply.answer()).contains("测试商品").doesNotContain("先确认常用笔");
        assertThat(reply.advice()).contains("使用场景").doesNotContain("先确认常用笔", "防水", "真丝");
    }

    @Test
    void inventedWrongProductUnqualifiedSkuAndMissingReferencesWithdrawAllRecommendationContent() {
        var product = notebook("TEST_CONFIRMED");
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        for (var references : List.of(List.<GuideModel.EvidenceReference>of(),
                List.of(new GuideModel.EvidenceReference(9L, List.of("waterproof"))),
                List.of(new GuideModel.EvidenceReference(9L, List.of("spec-0"))),
                List.of(new GuideModel.EvidenceReference(99L, List.of("material"))),
                List.of(new GuideModel.EvidenceReference(9L, List.of("material", "material"))))) {
            when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenReturn(new GuideModel.Selection("不应保留的建议", List.of(9L), references));
            var reply = agent.chat(List.of(new GuideModel.Turn("user", "找纸本")), false);
            assertThat(reply.answer()).contains("依据未通过").doesNotContain("不应保留");
            assertThat(reply.products()).isEmpty();
            assertThat(reply.recommendationBasis()).isEmpty();
            assertThat(reply.advice()).isEmpty();
        }
    }

    @Test
    void regeneratesOnceForMalformedStructureAndRevalidatesTheNewReferences() {
        var product = notebook("TEST_CONFIRMED");
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenThrow(new GuideModel.InvalidReplyException("invalid JSON", null))
                .thenReturn(new GuideModel.Selection("先确认常用笔。", List.of(9L),
                        List.of(new GuideModel.EvidenceReference(9L, List.of("material")))));
        assertThat(agent.chat(List.of(new GuideModel.Turn("user", "找纸本")), false).recommendationBasis())
                .hasSize(1);
        verify(model, times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void regeneratesOnceForUnknownProductSelectionWithoutReplacingItWithAGuessedProduct() {
        var product = notebook("TEST_CONFIRMED");
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenReturn(new GuideModel.Selection("错误选择", List.of(999L)))
                .thenReturn(new GuideModel.Selection("先确认用途。", List.of(9L),
                        List.of(new GuideModel.EvidenceReference(9L, List.of("material")))));
        var reply = agent.chat(List.of(new GuideModel.Turn("user", "找纸本")), false);
        assertThat(reply.products()).extracting(PublicProductResponse::id).containsExactly(9L);
        assertThat(reply.advice()).doesNotContain("错误选择");
        verify(model, times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void passesTheCurrentBudgetAndRegeneratesAContradictoryEmptySelection() {
        var product = notebook("TEST_CONFIRMED");
        var intent = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, new BigDecimal("30"), "CNY", List.of(),
                List.of("纸本"), List.of(), List.of(), List.of(), List.of(), "");
        var turns = List.of(new GuideModel.Turn("user", "找纸本，50以内"),
                new GuideModel.Turn("assistant", "先看看这些", List.of(9L)), new GuideModel.Turn("user", "预算改30，其他不变"));
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        when(model.interpret(turns)).thenReturn(intent);
        when(model.chat(anyList(), anyList(), any(GuideIntent.class)))
                .thenReturn(new GuideModel.Selection("没有预算内商品，请放宽预算。", List.of()))
                .thenReturn(new GuideModel.Selection("先考虑书写场景。", List.of(9L),
                        List.of(new GuideModel.EvidenceReference(9L, List.of("material")))));
        var reply = agent.chat(turns, false);
        assertThat(reply.products()).extracting(PublicProductResponse::id).containsExactly(9L);
        assertThat(reply.advice()).contains("使用场景").doesNotContain("没有预算内", "放宽预算");
        var capture = org.mockito.ArgumentCaptor.forClass(GuideIntent.class);
        verify(model, times(2)).chat(org.mockito.ArgumentMatchers.eq(turns), anyList(), capture.capture());
        assertThat(capture.getAllValues()).containsExactly(intent, intent);
    }

    @Test
    void repeatedEmptyStatusContradictionWithdrawsAdviceAndDoesNotGuessAProduct() {
        when(catalog.list()).thenReturn(List.of(notebook("TEST_CONFIRMED")));
        when(model.chat(anyList(), anyList(), any(GuideIntent.class)))
                .thenReturn(new GuideModel.Selection("都超过预算，必须加钱。", List.of()));
        var reply = agent.chat(List.of(new GuideModel.Turn("user", "找纸本")), false);
        assertThat(reply.answer()).contains("状态与当前筛选结果不一致").doesNotContain("超过预算");
        assertThat(reply.advice()).isEmpty();
        assertThat(reply.products()).isEmpty();
        assertThat(reply.recommendationBasis()).isEmpty();
        verify(model, times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void purposeAndPerformanceRejectionsKeepEmptyResultsWithoutRepeatingUnverifiedModelClaims() {
        when(catalog.list()).thenReturn(List.of(notebook("TEST_CONFIRMED")));
        for (var status : List.of(GuideModel.MatchStatus.PURPOSE_MISMATCH, GuideModel.MatchStatus.REQUIRED_EVIDENCE_MISSING)) {
            when(model.chat(anyList(), anyList(), any(GuideIntent.class)))
                    .thenReturn(new GuideModel.Selection("没有30元以内商品，需要加钱。", List.of(), List.of(), status));
            var reply = agent.chat(List.of(new GuideModel.Turn("user", "找纸本")), false);
            assertThat(reply.products()).isEmpty();
            assertThat(reply.advice()).contains("使用场景").doesNotContain("30元", "加钱");
            assertThat(reply.answer()).doesNotContain("30元", "加钱", "目录没有");
        }
        verify(model, times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void clarificationStatusMustAgreeWithTheCurrentIntent() {
        when(catalog.list()).thenReturn(List.of());
        when(model.interpret(anyList())).thenReturn(new GuideIntent(GuideIntent.DeliveryType.UNKNOWN, null, "CNY", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", true));
        when(model.chat(anyList(), anyList(), any(GuideIntent.class)))
                .thenReturn(new GuideModel.Selection("没有商品。", List.of()))
                .thenReturn(new GuideModel.Selection("想改善哪个场景？", List.of(), List.of(), GuideModel.MatchStatus.NEEDS_CLARIFICATION));
        var reply = agent.chat(List.of(new GuideModel.Turn("user", "想换点感觉")), false);
        assertThat(reply.products()).isEmpty();
        assertThat(reply.advice()).contains("场景").doesNotContain("没有商品");
        verify(model, times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void monitoringRequestGetsAnExplicitCapabilityReplyWithoutSelectingProducts() {
        when(model.interpret(anyList())).thenReturn(new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, null, "CNY", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", false, GuideIntent.Reference.none(), true));
        var reply = agent.chat(List.of(new GuideModel.Turn("user", "有货后主动通知我")), false);
        assertThat(reply.answer()).contains("不能持续监控", "主动通知", "没有创建");
        assertThat(reply.products()).isEmpty();
        assertThat(reply.advice()).isEmpty();
        verify(model, org.mockito.Mockito.never()).chat(anyList(), anyList(), any(GuideIntent.class));
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void doesNotRetryUpstreamFailureAndStopsAfterTwoMalformedReplies() {
        when(catalog.list()).thenReturn(List.of());
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenThrow(new IllegalStateException("upstream failure"));
        assertThatThrownBy(() -> agent.chat(List.of(new GuideModel.Turn("user", "找商品")), false)).isInstanceOf(IllegalStateException.class);
        verify(model, times(1)).chat(anyList(), anyList(), any(GuideIntent.class));
        org.mockito.Mockito.clearInvocations(model);
        org.mockito.Mockito.doThrow(new GuideModel.InvalidReplyException("invalid JSON", null)).when(model).chat(anyList(), anyList(), any(GuideIntent.class));
        assertThatThrownBy(() -> agent.chat(List.of(new GuideModel.Turn("user", "找商品")), false)).isInstanceOf(GuideModel.InvalidReplyException.class);
        verify(model, times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }
}
