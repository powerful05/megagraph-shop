package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles({"test", "guide"})
@TestPropertySource(properties = {
        "GUIDE_MODEL_API_KEY=test-only-not-a-real-key",
        "GUIDE_MODEL_NAME=test-only-model"
})
class GuideProfileIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void guideProfileWiresTheSpringAiModelAdapter() {
        assertThat(context.getBean(GuideModel.class)).isInstanceOf(SpringAiGuideModel.class);
    }
}
