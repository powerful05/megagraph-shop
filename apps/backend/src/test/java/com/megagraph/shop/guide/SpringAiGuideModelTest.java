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
                List.of("均码"));

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
    void chatPreservesMessageRolesAndRejectsDecimalAndStringIds() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {"interpretation":"你更偏好蓝色，可以看这张演示图。","productIds":[-101,1.5,"7"]}
                        """)))));
        List<GuideModel.Turn> turns = List.of(new GuideModel.Turn("user", "想要上班穿的"),
                new GuideModel.Turn("assistant", "偏好什么颜色？"), new GuideModel.Turn("user", "蓝色"));

        GuideModel.Selection result = model.chat(turns, List.of());

        assertThat(result.productIds()).containsExactly(-101L);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        Mockito.verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).hasSize(4);
        assertThat(prompt.getValue().getInstructions().getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getValue().getInstructions().get(1)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getValue().getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(prompt.getValue().getInstructions().get(2).getText()).contains("\"interpretation\"", "偏好什么颜色");
        assertThat(prompt.getValue().getInstructions().getLast().getText()).isEqualTo("蓝色");
    }

    @Test
    void completedPlainTextChatRepliesRemainUsableWithoutProductSelections() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("可以先选择手机摄影的短课，目前没有匹配的在售课程。"),
                        ChatGenerationMetadata.builder().finishReason("STOP").build()))));

        GuideModel.Selection result = model.chat(List.of(new GuideModel.Turn("user", "想学手机摄影")), List.of());

        assertThat(result.interpretation()).contains("手机摄影");
        assertThat(result.productIds()).isEmpty();
    }

    @Test
    void truncatedJsonIsNotShownAsAChatAnswer() {
        when(chatModel.call(Mockito.any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"interpretation\":\"尚未结束"),
                        ChatGenerationMetadata.builder().finishReason("LENGTH").build()))));
        assertThatThrownBy(() -> model.chat(List.of(new GuideModel.Turn("user", "想学摄影")), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
