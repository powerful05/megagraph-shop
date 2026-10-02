package com.megagraph.shop.guide;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
@Profile("guide")
public class GuideHttpConfiguration {
    @Bean
    RestClientCustomizer guideHttpTimeouts() {
        return builder -> {
            var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build());
            factory.setReadTimeout(Duration.ofSeconds(25));
            builder.requestFactory(factory);
        };
    }
}
