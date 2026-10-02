package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiGuideModelTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final SpringAiGuideModel model = new SpringAiGuideModel(chatModel, new ObjectMapper());

    @Test
    void sendsNaturalLanguageAndCandidateFactsAndParsesOnlyNumericIds() {
        when(chatModel.call(Mockito.anyString())).thenReturn("""
                {"interpretation":"想找一件适合通勤的外套","productIds":[7,"8",9]}
                """);
        GuideModel.Candidate candidate = new GuideModel.Candidate(7L, "外套", "简洁剪裁", "199.00", "CNY",
                List.of("均码"), null);

        GuideModel.Selection selection = model.select("想穿去上班", List.of(candidate));

        assertThat(selection.productIds()).containsExactly(7L, 9L);
        assertThat(selection.interpretation()).contains("通勤");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue()).contains("想穿去上班", "简洁剪裁", "199.00");
    }

    @Test
    void rejectsNonJsonResponses() {
        when(chatModel.call(Mockito.anyString())).thenReturn("这件很适合你");
        assertThatThrownBy(() -> model.select("想找外套", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析");
    }

    @Test
    void chatPreservesMessageRolesAndParsesStructuredEvidenceReferences() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {"matchStatus":"MATCHED","productIds":[-101],"evidenceReferences":[],"guidance":{"question":"NONE","checks":["GENERAL_PRIORITY"],"subject":""}}
                        """)))));
        List<GuideModel.Turn> turns = List.of(new GuideModel.Turn("user", "想要上班穿的"),
                new GuideModel.Turn("assistant", "偏好什么颜色？"), new GuideModel.Turn("user", "蓝色"));

        GuideModel.Selection result = model.chat(turns, List.of(), GuideIntent.empty());

        assertThat(result.productIds()).containsExactly(-101L);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        Mockito.verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).hasSize(4);
        assertThat(prompt.getValue().getInstructions().getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getValue().getInstructions().get(1)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getValue().getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(prompt.getValue().getInstructions().get(2).getText()).contains("历史助手内容已省略")
                .doesNotContain("偏好什么颜色", "productIds");
        assertThat(prompt.getValue().getInstructions().getLast().getText()).isEqualTo("蓝色");
    }

    @Test
    void completedPlainTextWithoutEvidenceStructureIsRejected() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("可以先选择手机摄影的短课，目前没有匹配的在售课程。"),
                        ChatGenerationMetadata.builder().finishReason("STOP").build()))));

        assertThatThrownBy(() -> model.chat(List.of(new GuideModel.Turn("user", "想学手机摄影")), List.of(), GuideIntent.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingReferencesMalformedKeysAndNonIntegralChatIds() {
        for (String response : List.of(
                "{\"interpretation\":\"建议\",\"productIds\":[7]}",
                "{\"interpretation\":\"建议\",\"productIds\":[1.5],\"evidenceReferences\":[]}",
                "{\"interpretation\":\"建议\",\"productIds\":[\"7\"],\"evidenceReferences\":[]}",
                "{\"interpretation\":\"建议\",\"productIds\":[7],\"evidenceReferences\":[{\"productId\":7,\"keys\":[1]}]}",
                "{\"interpretation\":\"建议\",\"productIds\":[7],\"evidenceReferences\":[{\"productId\":7,\"keys\":[]}]}",
                "{\"interpretation\":\"建议\",\"productIds\":[7],\"evidenceReferences\":[{\"productId\":-7,\"keys\":[\"material\"]}]}")) {
            String withStatus = response.replace("{\"interpretation\"", "{\"matchStatus\":\"MATCHED\",\"interpretation\"");
            when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(withStatus)))));
            assertThatThrownBy(() -> model.chat(List.of(new GuideModel.Turn("user", "找纸本")), List.of(), GuideIntent.empty()))
                    .isInstanceOf(IllegalStateException.class);
        }
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"matchStatus":"MATCHED","productIds":[7],"evidenceReferences":[{"productId":7,"keys":["material"]}],"guidance":{"question":"NONE","checks":["PAPER_USE"],"subject":""}}
                """)))));
        assertThat(model.chat(List.of(new GuideModel.Turn("user", "找纸本")), List.of(), GuideIntent.empty()).evidenceReferences())
                .containsExactly(new GuideModel.EvidenceReference(7L, List.of("material")));
    }

    @Test
    void replyReceivesCurrentConstraintsAlongsideHistoryAndCandidates() throws Exception {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"matchStatus":"MATCHED","productIds":[7],"evidenceReferences":[],"guidance":{"question":"USE_CASE","checks":[],"subject":""}}
                """)))));
        var intent = new GuideIntent(GuideIntent.DeliveryType.PHYSICAL, new java.math.BigDecimal("180"), "CNY", List.of("通勤"),
                List.of("上衣"), List.of(), List.of(), List.of(), List.of(), "M");
        model.chat(List.of(new GuideModel.Turn("user", "250以内，M码"),
                        new GuideModel.Turn("assistant", "预算250可考虑这些", List.of(7L)), new GuideModel.Turn("user", "改180")),
                List.of(new GuideModel.Candidate(7L, "针织上衣", "通勤", "169", "CNY", List.of("M"), null)), intent);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        Mockito.verify(chatModel).call(prompt.capture());
        String system = prompt.getValue().getInstructions().getFirst().getText();
        int contextStart = system.indexOf('{', system.indexOf("本轮约束与候选商品 JSON："));
        var context = new ObjectMapper().readTree(system.substring(contextStart));
        assertThat(context.path("currentIntent").path("maxPrice").decimalValue()).isEqualByComparingTo("180");
        assertThat(context.path("currentIntent").path("requiredSize").asText()).isEqualTo("M");
        assertThat(context.path("candidates").get(0).path("priceFrom").asText()).isEqualTo("169");
        assertThat(prompt.getValue().getInstructions().get(2).getText()).doesNotContain("预算250", "[7]");
    }

    @Test
    void rejectsUnknownMissingAndContradictoryReplyStatuses() {
        for (String response : List.of(
                "{\"interpretation\":\"建议\",\"productIds\":[],\"evidenceReferences\":[]}",
                "{\"interpretation\":\"建议\",\"matchStatus\":\"NOT_REAL\",\"productIds\":[],\"evidenceReferences\":[]}",
                "{\"interpretation\":\"建议\",\"matchStatus\":\"MATCHED\",\"productIds\":[],\"evidenceReferences\":[]}",
                "{\"interpretation\":\"建议\",\"matchStatus\":\"NO_CANDIDATES\",\"productIds\":[7],\"evidenceReferences\":[]}"
        )) {
            when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
            assertThatThrownBy(() -> model.chat(List.of(new GuideModel.Turn("user", "找上衣")), List.of(), GuideIntent.empty()))
                    .isInstanceOf(GuideModel.InvalidReplyException.class);
        }
    }

    @Test
    void parsesGuidanceCodesAndDoesNotReturnInventedFreeText() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"interpretation":"这件棉粘混纺更柔和、有领、防水。","matchStatus":"MATCHED","productIds":[7],
                "evidenceReferences":[{"productId":7,"keys":["material"]}],
                "guidance":{"question":"PRIORITY","checks":["MATERIAL_FEEL"],"subject":"贴身穿"}}
                """)))));
        var result = model.chat(List.of(new GuideModel.Turn("user", "想贴身穿")), List.of(), GuideIntent.empty());
        assertThat(result.interpretation()).isEmpty();
        assertThat(result.guidance()).isEqualTo(new GuideGuidance.Plan(GuideGuidance.Question.PRIORITY,
                List.of(GuideGuidance.Check.MATERIAL_FEEL), "贴身穿"));
    }

    @Test
    void rejectsMissingUnknownMalformedOrUnboundedGuidancePlans() {
        for (String invalid : List.of("null", "{}", "{\"question\":\"MADE_UP\",\"checks\":[],\"subject\":\"\"}",
                "{\"question\":\"NONE\",\"checks\":[\"SOFT_COTTON\"],\"subject\":\"\"}",
                "{\"question\":\"NONE\",\"checks\":[1],\"subject\":\"\"}",
                "{\"question\":\"NONE\",\"checks\":[\"GENERAL_PRIORITY\",\"GENERAL_PRIORITY\"],\"subject\":\"\"}",
                "{\"question\":\"NONE\",\"checks\":[\"GENERAL_PRIORITY\",\"PAPER_USE\",\"CLOSURE\",\"ITEM_DIMENSIONS\"],\"subject\":\"\"}",
                "{\"question\":\"NONE\",\"checks\":[],\"subject\":\"1234567890123456789012345\"}")) {
            String response = "{\"matchStatus\":\"NO_CANDIDATES\",\"productIds\":[],\"evidenceReferences\":[],\"guidance\":" + invalid + "}";
            when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
            assertThatThrownBy(() -> model.chat(List.of(new GuideModel.Turn("user", "想换个感觉")), List.of(), GuideIntent.empty()))
                    .isInstanceOf(GuideModel.InvalidReplyException.class);
        }
    }

    @Test
    void truncatedJsonIsNotShownAsAChatAnswer() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"interpretation\":\"尚未结束"),
                        ChatGenerationMetadata.builder().finishReason("LENGTH").build()))));
        assertThatThrownBy(() -> model.chat(List.of(new GuideModel.Turn("user", "想学摄影")), List.of(), GuideIntent.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extractsStructuredBudgetAndExclusionsFromMultiTurnInput() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"deliveryType":"PHYSICAL","maxPrice":180.50,"currency":"CNY","searchTerms":["通勤"],
                "categoryKeywords":[],"requiredColors":[],"excludedColors":["蓝"],"requiredFabrics":["100%棉"],
                "excludedFabrics":[],"requiredSize":"","needsClarification":false,"requestsMonitoring":false,
                "reference":{"mode":"NONE","assistantTurnIndex":null,"position":null}}
                """)))));
        var intent = model.interpret(List.of(new GuideModel.Turn("user", "通勤，预算 250"),
                new GuideModel.Turn("assistant", "蓝色可以吗？"), new GuideModel.Turn("user", "不要蓝，改成180.50以内，只要纯棉")));
        assertThat(intent.maxPrice()).isEqualByComparingTo("180.50");
        assertThat(intent.excludedColors()).containsExactly("蓝");
        assertThat(intent.requiredFabrics()).containsExactly("100%棉");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        Mockito.verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().getLast().getText()).contains("改成180.50以内", "不要蓝");
    }

    @Test
    void monitoringFlagIsExplicitAndBooleanInTheIntentContract() throws Exception {
        var mapper = new ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(GuideIntent.empty());
        root.put("requestsMonitoring", true);
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(root.toString())))));
        assertThat(model.interpret(List.of(new GuideModel.Turn("user", "有货后通知我"))).requestsMonitoring()).isTrue();
        root.remove("requestsMonitoring");
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(root.toString())))));
        assertThatThrownBy(() -> model.interpret(List.of(new GuideModel.Turn("user", "有货后通知我"))))
                .isInstanceOf(IllegalStateException.class);
        root.put("requestsMonitoring", "true");
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(root.toString())))));
        assertThatThrownBy(() -> model.interpret(List.of(new GuideModel.Turn("user", "有货后通知我"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedIntentInsteadOfSilentlyLosingHardConstraints() {
        for (String response : List.of("not json", "{\"maxPrice\":\"cheap\"}", "{\"deliveryType\":\"DIGITAL\",\"searchTerms\":[1]}")) {
            when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
            assertThatThrownBy(() -> model.interpret(List.of(new GuideModel.Turn("user", "预算100"))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void parsesReferenceAndSerializesDisplayIdsAsStructuredHistory() throws Exception {
        String base = """
                {"deliveryType":"PHYSICAL","maxPrice":null,"currency":"CNY","searchTerms":[],"categoryKeywords":[],
                "requiredColors":[],"excludedColors":[],"requiredFabrics":[],"excludedFabrics":[],"requiredSize":"",
                "needsClarification":false,"requestsMonitoring":false,"reference":{"mode":"EXACT","assistantTurnIndex":1,"position":2}}
                """;
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(base)))));
        var turns = List.of(new GuideModel.Turn("user", "两款"), new GuideModel.Turn("assistant", "比较", List.of(7L, 9L)),
                new GuideModel.Turn("user", "第二件"));
        assertThat(model.interpret(turns).reference()).isEqualTo(new GuideIntent.Reference(GuideIntent.ReferenceMode.EXACT, 1, 2));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        Mockito.verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().getLast().getText()).contains("\"displayedProductIds\":[7,9]");
        var history = new ObjectMapper().readTree(prompt.getValue().getInstructions().getLast().getText());
        assertThat(history.get(1).path("turnIndex").asInt()).isEqualTo(1);
        assertThat(new GuideIntent.Reference(GuideIntent.ReferenceMode.AMBIGUOUS, 1, null))
                .isEqualTo(new GuideIntent.Reference(GuideIntent.ReferenceMode.AMBIGUOUS, null, null));
        var mapper = new ObjectMapper();
        for (String invalid : List.of("{\"mode\":\"EXACT\",\"assistantTurnIndex\":0,\"position\":2}",
                "{\"mode\":\"EXACT\",\"assistantTurnIndex\":1,\"position\":0}",
                "{\"mode\":\"EXACT\",\"assistantTurnIndex\":1,\"position\":\"2\"}",
                "{\"mode\":\"NONE\",\"assistantTurnIndex\":1,\"position\":1}", "{}")) {
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(base);
            root.set("reference", mapper.readTree(invalid));
            when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(root.toString())))));
            assertThatThrownBy(() -> model.interpret(turns)).isInstanceOf(IllegalStateException.class);
        }
    }
}
