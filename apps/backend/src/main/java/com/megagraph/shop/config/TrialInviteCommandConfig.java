package com.megagraph.shop.config;

import com.megagraph.shop.identity.TrialInviteService;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TrialInviteCommandConfig {

    @Bean
    @ConditionalOnProperty(name = "app.invites.issue", havingValue = "true")
    ApplicationRunner issueTrialInvite(TrialInviteService invites, ConfigurableApplicationContext context) {
        return arguments -> {
            if (!arguments.containsOption("server.port")
                    || !arguments.getOptionValues("server.port").contains("0")) {
                throw new IllegalStateException("Invite issuance requires --server.port=0");
            }
            String code = invites.issue(Duration.ofDays(7));
            System.out.println("TRIAL_INVITE_CODE=" + code);
            context.close();
        };
    }
}
