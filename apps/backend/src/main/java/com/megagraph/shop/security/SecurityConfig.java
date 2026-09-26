package com.megagraph.shop.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.shared.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
public class SecurityConfig {

    @Bean
    @Profile("trial")
    static BeanFactoryPostProcessor rejectMixedTrialProfiles(Environment environment) {
        return factory -> {
            if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException("trial must not run with local or test profiles");
            }
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contextRepository,
            ObjectMapper mapper,
            @Value("${app.security.legacy-basic-enabled:false}") boolean legacyBasic,
            @Value("${app.security.csrf-enabled:true}") boolean csrfEnabled
    ) throws Exception {
        http.securityContext(context -> context.securityContextRepository(contextRepository))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/auth/v1/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/v1/login", "/api/auth/v1/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/shop/v1/products", "/api/shop/v1/products/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/shop/v1/capabilities").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/shop/v1/media/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payment-callbacks/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                mapper, response, 401, "AUTHENTICATION_REQUIRED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                mapper, response, 403,
                                exception instanceof CsrfException ? "CSRF_TOKEN_INVALID" : "ACCESS_DENIED",
                                exception instanceof CsrfException ? "请求安全令牌无效" : "无权访问")));

        if (csrfEnabled) {
            http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/payment-callbacks/**"));
        } else {
            http.csrf(csrf -> csrf.disable());
        }
        if (legacyBasic) {
            http.httpBasic(basic -> {});
        }
        return http.build();
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse response, int status,
            String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), ApiError.of(code, message));
    }
}
