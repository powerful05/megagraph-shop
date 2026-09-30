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
}
