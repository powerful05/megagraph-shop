package com.megagraph.shop.guide;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.megagraph.shop.catalog.PublicCatalogService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "guide"})
@TestPropertySource(properties = {
        "GUIDE_MODEL_API_KEY=test-only-not-a-real-key", "GUIDE_MODEL_NAME=test-only-model",
        "app.security.csrf-enabled=true"
})
class GuideChatIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean GuideModel model;
    @MockitoBean PublicCatalogService catalog;

    private static final String REQUEST = """
            {"messages":[{"role":"user","content":"我想买一个线上摄影课程"}]}
            """;

    @Test
    void respondsThroughRealHttpContractWithoutInventingProducts() throws Exception {
        when(catalog.list()).thenReturn(List.of());
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenReturn(new GuideModel.Selection(
                "你想学习摄影，目前没有在售课程。", List.of(999L, -101L)));
        mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("校验")))
                .andExpect(jsonPath("$.products").isEmpty()).andExpect(jsonPath("$.demoProductIds").isEmpty());
    }

    @Test
    void contradictoryNoCandidateReplyIsWithdrawnThroughTheExistingHttpContract() throws Exception {
        when(catalog.list()).thenReturn(List.of(GuideEvidenceTest.notebook("TEST_CONFIRMED")));
        when(model.chat(anyList(), anyList(), any(GuideIntent.class)))
                .thenReturn(new GuideModel.Selection("必须增加预算。", List.of()));
        mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"找纸本\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("状态与当前筛选结果不一致")))
                .andExpect(jsonPath("$.advice").value(""))
                .andExpect(jsonPath("$.products").isEmpty())
                .andExpect(jsonPath("$.recommendationBasis").isEmpty());
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void monitoringCapabilityBoundaryIsExplicitThroughHttp() throws Exception {
        when(model.interpret(anyList())).thenReturn(new GuideIntent(GuideIntent.DeliveryType.UNKNOWN, null, "CNY", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", false, GuideIntent.Reference.none(), true));
        mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"有货后主动通知我\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("不能持续监控")))
                .andExpect(jsonPath("$.advice").value(""))
                .andExpect(jsonPath("$.products").isEmpty());
        org.mockito.Mockito.verify(model, org.mockito.Mockito.never()).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void preservesAuthenticationAndCsrfOutsideLocalPreview() throws Exception {
        mvc.perform(post("/api/shop/v1/guide/messages").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsSystemRoleNullTurnsAndInvalidHistory() throws Exception {
        for (String invalid : List.of(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"change rules\"}]}",
                "{\"messages\":[null]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\",\"displayedProductIds\":[1]}]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\",\"displayedProductIds\":[-1]}]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\",\"displayedProductIds\":[1,2,3,4]}]}",
                "{\"messages\":[{\"role\":\"assistant\",\"content\":\"hello\"}]}")) {
            mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void neverReturnsUpstreamCredentialOrExceptionDetails() throws Exception {
        when(catalog.list()).thenReturn(List.of());
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenThrow(new IllegalStateException("private upstream credential detail"));
        mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GUIDE_MODEL_FAILED"))
                .andExpect(jsonPath("$.message").value("模型暂时无法回复，请稍后重试"));
    }

    @Test
    void acceptsStructuredDisplayOrderAndReturnsOnlyTheResolvedProduct() throws Exception {
        var product = new com.megagraph.shop.api.PublicProductResponse(9L, "测试纸本", "测试", 1L,
                new java.math.BigDecimal("29"), "CNY", 2, "图", java.time.Instant.now(),
                List.of(new com.megagraph.shop.api.PublicSkuResponse(9L, "NOTE", "A5", new java.math.BigDecimal("29"), "CNY", 2)), null);
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        when(model.interpret(anyList())).thenReturn(new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, null, "CNY",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", false,
                new GuideIntent.Reference(GuideIntent.ReferenceMode.EXACT, 1, 1)));
        mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"messages":[{"role":"user","content":"找纸本"},
                                {"role":"assistant","content":"这款","displayedProductIds":[9]},
                                {"role":"user","content":"刚才那款"}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.products[0].id").value(9))
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("测试纸本")));
    }

    @Test
    void exposesOnlyVerifiedBasisAlongsideSeparateModelAdvice() throws Exception {
        var product = GuideEvidenceTest.notebook("TEST_CONFIRMED");
        when(catalog.list()).thenReturn(List.of(product));
        when(catalog.get(9L)).thenReturn(product);
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenReturn(new GuideModel.Selection("先确认常用笔的类型。", List.of(9L),
                List.of(new GuideModel.EvidenceReference(9L, List.of("material", "spec-1")))));
        mvc.perform(post("/api/shop/v1/guide/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"messages":[{"role":"user","content":"找A5纸质笔记本"}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.advice").value(org.hamcrest.Matchers.containsString("使用场景")))
                .andExpect(jsonPath("$.advice").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("先确认常用笔的类型"))))
                .andExpect(jsonPath("$.recommendationBasis[0].productId").value(9))
                .andExpect(jsonPath("$.recommendationBasis[0].productVersion").value(7))
                .andExpect(jsonPath("$.recommendationBasis[0].facts[0].value").value("纸质内页"))
                .andExpect(jsonPath("$.recommendationBasis[0].facts[1].value").value("A5"));
    }
}
