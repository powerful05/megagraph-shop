package com.megagraph.shop.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class TrialProfileGuardTest {

    @Test
    void trialCannotBeCombinedWithMockProfiles() {
        for (String mockProfile : new String[] {"local", "test"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("trial", mockProfile);
            assertThatThrownBy(() -> SecurityConfig.rejectMixedTrialProfiles(environment)
                    .postProcessBeanFactory(new DefaultListableBeanFactory()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
