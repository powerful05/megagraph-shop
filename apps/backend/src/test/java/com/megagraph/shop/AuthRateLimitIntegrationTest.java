package com.megagraph.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.login-rate-limit-enabled=true")
class AuthRateLimitIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void loginAttemptsAreBoundedAndReturnRetryAfter() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(post("/api/auth/v1/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"missing@example.test\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mvc.perform(post("/api/auth/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing@example.test\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
    }
}
