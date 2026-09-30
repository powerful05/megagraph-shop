package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GuideConfigurationIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired GuideAgent agent;

    @Test
    void defaultProfileStartsWithoutExternalModelCredentials() {
        assertThat(context.getBeansOfType(GuideModel.class)).isEmpty();
        assertThatThrownBy(() -> agent.recommend("帮我找一件外套"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未启用");
    }
}
