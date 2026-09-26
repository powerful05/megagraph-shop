package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.payment.MockChannelCommandJob;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_COMMERCE_URL", matches = ".+")
class MySqlCommerceSmokeTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("MYSQL_COMMERCE_URL");
        if (url == null || url.isBlank()) return;
        String username = required("MYSQL_COMMERCE_USERNAME");
        String password = required("MYSQL_COMMERCE_PASSWORD");
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
            result.next();
            assertThat(result.getLong(1)).as("Commerce smoke requires a dedicated empty schema").isZero();
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(connection.getMetaData().getDatabaseMinorVersion()).isGreaterThanOrEqualTo(4);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.security.legacy-basic-enabled", () -> "false");
        registry.add("app.security.legacy-merchant-context-enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockChannelCommandJob mockCommands;
    @Autowired JdbcIndexedSessionRepository sessions;

    @Test
    void sessionOrderAndAsyncMockPaymentPersistInMysql() throws Exception {
        Long merchantId = jdbc.queryForObject("SELECT id FROM merchant LIMIT 1", Long.class);
        Long categoryId = jdbc.queryForObject("SELECT id FROM category WHERE parent_id IS NOT NULL LIMIT 1", Long.class);
        Long merchantUserId = jdbc.queryForObject("SELECT id FROM app_user WHERE username_normalized = ?",
                Long.class, "merchant@megagraph.local");
        jdbc.update("INSERT INTO product (merchant_id, category_id, title, draft_status, publish_status, "
                + "confirmed_by, confirmed_at, published_at) VALUES (?, ?, ?, 'CONFIRMED', 'PUBLISHED', "
                + "?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))",
                merchantId, categoryId, "2E MySQL smoke top", merchantUserId);
        Long productId = jdbc.queryForObject("SELECT id FROM product WHERE title = ?", Long.class,
                "2E MySQL smoke top");
        jdbc.update("INSERT INTO product_sku (merchant_id, product_id, sku_code, sku_name, price, currency, "
                + "available_stock, status) VALUES (?, ?, ?, 'M', 129.00, 'CNY', 3, 'ENABLED')",
                merchantId, productId, "2E-MYSQL-SMOKE");
        Long skuId = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code = ?", Long.class,
                "2E-MYSQL-SMOKE");

        Cookie session = mvc.perform(post("/api/auth/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shopper@megagraph.local\",\"password\":\"shopper123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        assertThat(session).isNotNull();
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", skuId).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        var created = mvc.perform(post("/api/shop/v1/orders").cookie(session)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"测试顾客\",\"recipientPhone\":\"13800138000\","
                                + "\"shippingAddress\":\"隔离测试地址\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();
        long orderId = mapper.readTree(created.getResponse().getContentAsString()).required("id").asLong();
        mvc.perform(post("/api/shop/v1/orders/{orderId}/mock-payment", orderId).cookie(session)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("PROCESSING"));
        assertThat(mockCommands.processAvailable(10)).isEqualTo(1);
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservation "
                + "WHERE order_id = ? AND status = 'CONFIRMED'", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_channel_event "
                + "WHERE processing_status = 'APPLIED'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event "
                + "WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = 'ORDER_PAID'",
                Integer.class, orderId)).isEqualTo(1);

        long expiredAccess = Instant.now().minus(Duration.ofHours(9)).toEpochMilli();
        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = ?, EXPIRY_TIME = ? WHERE PRINCIPAL_NAME = ?",
                expiredAccess, expiredAccess, "shopper@megagraph.local");
        mvc.perform(get("/api/shop/v1/cart").cookie(session)).andExpect(status().isUnauthorized());
        String expiredId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO SPRING_SESSION (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, "
                + "MAX_INACTIVE_INTERVAL, EXPIRY_TIME) VALUES (?, ?, ?, ?, 1, ?)",
                expiredId, UUID.randomUUID().toString(), expiredAccess, expiredAccess, expiredAccess);
        jdbc.update("INSERT INTO SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) "
                + "VALUES (?, 'cleanup-test', ?)", expiredId, new byte[] {0});
        sessions.cleanUpExpiredSessions();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRIMARY_ID = ?",
                Integer.class, expiredId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID = ?",
                Integer.class, expiredId)).isZero();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
