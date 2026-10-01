package com.megagraph.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TestSupplyPolicyTest {
    @Test
    void requiresBothExplicitDemoAndLocalProfiles() {
        Product product = new Product(1L, "测试商品");
        product.markTestSupply();
        var environment = new MockEnvironment();
        var policy = new TestSupplyPolicy(environment);
        assertThat(policy.permits(product)).isFalse();
        environment.setActiveProfiles("supply-demo");
        assertThat(policy.permits(product)).isFalse();
        environment.setActiveProfiles("local");
        assertThat(policy.permits(product)).isFalse();
        environment.setActiveProfiles("local", "supply-demo");
        assertThat(policy.permits(product)).isTrue();
        environment.setActiveProfiles("trial");
        assertThat(policy.permits(new Product(1L, "普通商品"))).isTrue();
    }
}
