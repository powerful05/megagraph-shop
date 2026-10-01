package com.megagraph.shop.catalog;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class TestSupplyPolicy {
    private final Environment environment;

    public TestSupplyPolicy(Environment environment) {
        this.environment = environment;
    }

    public boolean permits(Product product) {
        return !product.isTestSupply() || environment.acceptsProfiles(Profiles.of("local & supply-demo"));
    }
}
