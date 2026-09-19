package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.commerce.OrderService;
import com.megagraph.shop.identity.Merchant;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.identity.MerchantUser;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.projection.OutboxEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommerceFlowIntegrationTest {

    private static final String MERCHANT_USERNAME = "merchant@megagraph.local";
    private static final String MERCHANT_PASSWORD = "merchant123";
    private static final String SHOPPER_USERNAME = "shopper@megagraph.local";
    private static final String SHOPPER_PASSWORD = "shopper123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private MerchantUserRepository memberships;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderService orderService;

    @Test
    void shopperCanReserveStockAndCompleteAnIdempotentMockPayment() throws Exception {
        PublishedSku published = publishProduct("交易测试外套", "TRADE-" + UUID.randomUUID(), 199.00, 5);

        mvc.perform(get("/api/shop/v1/cart"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedTotal").value(398.00))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        String orderKey = "order-" + UUID.randomUUID();
        JsonNode created = createOrder(orderKey);
        long orderId = created.required("id").asLong();
        assertThat(created.required("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(created.at("/payment/status").asText()).isEqualTo("PENDING");
        assertThat(created.at("/reservations/0/status").asText()).isEqualTo("RESERVED");
        assertThat(created.required("totalAmount").decimalValue()).isEqualByComparingTo("398.00");

        JsonNode duplicate = createOrder(orderKey);
        assertThat(duplicate.required("id").asLong()).isEqualTo(orderId);

        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableStock").value(3));

        String paymentKey = "payment-" + UUID.randomUUID();
        JsonNode paid = applyMockPayment(orderId, paymentKey, "SUCCESS");
        assertThat(paid.required("status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/payment/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(paid.at("/reservations/0/status").asText()).isEqualTo("CONFIRMED");

        JsonNode repeated = applyMockPayment(orderId, paymentKey, "SUCCESS");
        assertThat(repeated.required("status").asText()).isEqualTo("PAID");
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableStock").value(3));

        mvc.perform(get("/api/merchant/v1/orders")
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + orderId + ")].status").value("PAID"));

        String outsiderUsername = "outsider-" + UUID.randomUUID() + "@megagraph.local";
        String outsiderPassword = "customer456";
        UserAccount outsider = users.save(new UserAccount(
                outsiderUsername,
                passwordEncoder.encode(outsiderPassword),
                "其他用户"
        ));
        Merchant otherMerchant = merchants.save(new Merchant("其他订单商家"));
        memberships.save(new MerchantUser(otherMerchant.getId(), outsider.getId(), MerchantRole.OWNER));
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId)
                        .with(httpBasic(outsiderUsername, outsiderPassword)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/merchant/v1/orders")
                        .with(httpBasic(outsiderUsername, outsiderPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER", orderId))
                .extracting("eventType")
                .containsExactly("ORDER_CREATED", "ORDER_PAID");
    }

    @Test
    void merchantCanShipPaidOrderAndShopperCanConfirmReceipt() throws Exception {
        PublishedSku published = publishProduct("履约测试外套", "FULFILL-" + UUID.randomUUID(), 299.00, 3);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());

        JsonNode created = createOrder("fulfillment-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();

        mvc.perform(post("/api/merchant/v1/orders/{orderId}/ship", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", "ship-before-payment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF10000001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_READY_TO_SHIP"));

        applyMockPayment(orderId, "fulfillment-payment-" + UUID.randomUUID(), "SUCCESS");
        String shipmentKey = "shipment-" + UUID.randomUUID();
        JsonNode shipped = responseJson(mvc.perform(post("/api/merchant/v1/orders/{orderId}/ship", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", shipmentKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF10000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipment.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipment.carrierName").value("顺丰速运"))
                .andExpect(jsonPath("$.shipment.trackingNo").value("SF10000001"))
                .andReturn());

        JsonNode repeated = responseJson(mvc.perform(post("/api/merchant/v1/orders/{orderId}/ship", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", shipmentKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF10000001\"}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(repeated.at("/shipment/id").asLong()).isEqualTo(shipped.at("/shipment/id").asLong());

        String outsiderUsername = "fulfillment-outsider-" + UUID.randomUUID() + "@megagraph.local";
        String outsiderPassword = "merchant456";
        UserAccount outsider = users.save(new UserAccount(
                outsiderUsername,
                passwordEncoder.encode(outsiderPassword),
                "其他履约商家"
        ));
        Merchant otherMerchant = merchants.save(new Merchant("其他履约店铺"));
        memberships.save(new MerchantUser(otherMerchant.getId(), outsider.getId(), MerchantRole.OWNER));
        mvc.perform(post("/api/merchant/v1/orders/{orderId}/ship", orderId)
                        .with(httpBasic(outsiderUsername, outsiderPassword))
                        .header("Idempotency-Key", "outsider-shipment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"其他承运商\",\"trackingNo\":\"OTHER1000001\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(post("/api/shop/v1/orders/{orderId}/confirm-receipt", orderId)
                        .with(httpBasic(outsiderUsername, outsiderPassword)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        JsonNode completed = responseJson(mvc.perform(post("/api/shop/v1/orders/{orderId}/confirm-receipt", orderId)
                        .with(shopper()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.shipment.status").value("DELIVERED"))
                .andReturn());
        assertThat(completed.at("/shipment/deliveredAt").isTextual()).isTrue();

        mvc.perform(post("/api/shop/v1/orders/{orderId}/confirm-receipt", orderId)
                        .with(shopper()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER", orderId))
                .extracting("eventType")
                .containsExactly("ORDER_CREATED", "ORDER_PAID", "ORDER_SHIPPED", "ORDER_COMPLETED");
    }

    @Test
    void failedPaymentReleasesReservedStockOnlyOnce() throws Exception {
        PublishedSku published = publishProduct("失败释放测试", "RELEASE-" + UUID.randomUUID(), 88.00, 4);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isOk());

        JsonNode order = createOrder("release-order-" + UUID.randomUUID());
        long orderId = order.required("id").asLong();
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(1));

        JsonNode failed = applyMockPayment(orderId, "release-payment-" + UUID.randomUUID(), "FAILURE");
        assertThat(failed.required("status").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(failed.at("/payment/status").asText()).isEqualTo("FAILED");
        assertThat(failed.at("/reservations/0/status").asText()).isEqualTo("RELEASED");

        JsonNode repeated = applyMockPayment(orderId, "different-retry-key-" + UUID.randomUUID(), "FAILURE");
        assertThat(repeated.required("status").asText()).isEqualTo("PAYMENT_FAILED");
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(4));
    }

    @Test
    void cartRejectsMoreThanCurrentStock() throws Exception {
        PublishedSku published = publishProduct("库存边界测试", "STOCK-" + UUID.randomUUID(), 66.00, 1);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SKU_STOCK_INSUFFICIENT"));
    }

    @Test
    void expiredOrderReleasesReservationAndStock() throws Exception {
        PublishedSku published = publishProduct("超时释放测试", "EXPIRE-" + UUID.randomUUID(), 77.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode order = createOrder("expiry-order-" + UUID.randomUUID());
        long orderId = order.required("id").asLong();

        jdbcTemplate.update("UPDATE sales_order SET expires_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE id = ?", orderId);
        orderService.expireOverdueOrders();

        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.payment.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reservations[0].status").value("RELEASED"));
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableStock").value(2));
    }

    private JsonNode createOrder(String idempotencyKey) throws Exception {
        return responseJson(mvc.perform(post("/api/shop/v1/orders")
                        .with(shopper())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "recipientName": "测试顾客",
                                  "recipientPhone": "13800138000",
                                  "shippingAddress": "深圳市盐田区测试路 1 号"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode applyMockPayment(long orderId, String idempotencyKey, String result) throws Exception {
        return responseJson(mvc.perform(post("/api/shop/v1/orders/{orderId}/mock-payment", orderId)
                        .with(shopper())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"" + result + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
    }

    private PublishedSku publishProduct(String title, String skuCode, double price, int stock) throws Exception {
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x00
        };
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "commerce.png",
                MediaType.IMAGE_PNG_VALUE,
                png
        );
        JsonNode ingestion = responseJson(mvc.perform(multipart("/api/merchant/v1/ingestions")
                        .file(image)
                        .header("Idempotency-Key", "commerce-product-" + UUID.randomUUID())
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn());
        long productId = ingestion.required("productId").asLong();
        JsonNode draft = merchantGet("/api/merchant/v1/products/" + productId + "/draft");

        JsonNode categories = merchantGet("/api/merchant/v1/categories");
        long categoryId = 0;
        for (JsonNode category : categories) {
            if (!category.required("parentId").isNull()) {
                categoryId = category.required("id").asLong();
                break;
            }
        }
        JsonNode revised = responseJson(mvc.perform(patch("/api/merchant/v1/products/{productId}/draft", productId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": %d,
                                  "title": "%s",
                                  "categoryId": %d,
                                  "sku": {
                                    "code": "%s",
                                    "name": "标准款",
                                    "price": %.2f,
                                    "stock": %d
                                  }
                                }
                                """.formatted(draft.required("version").asLong(), title, categoryId, skuCode, price, stock)))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode confirmed = responseJson(mvc.perform(post("/api/merchant/v1/products/{productId}/confirm", productId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + revised.required("version").asLong() + "}"))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode published = responseJson(mvc.perform(post("/api/merchant/v1/products/{productId}/publish", productId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + confirmed.required("version").asLong() + "}"))
                .andExpect(status().isOk())
                .andReturn());
        return new PublishedSku(productId, published.at("/skus/0/id").asLong());
    }

    private JsonNode merchantGet(String path) throws Exception {
        return responseJson(mvc.perform(get(path)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private RequestPostProcessor shopper() {
        return httpBasic(SHOPPER_USERNAME, SHOPPER_PASSWORD);
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private record PublishedSku(long productId, long skuId) {
    }
}
