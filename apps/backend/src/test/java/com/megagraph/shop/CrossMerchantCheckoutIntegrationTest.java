package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.SupplyDetails;
import com.megagraph.shop.catalog.*;
import com.megagraph.shop.commerce.*;
import com.megagraph.shop.identity.*;
import com.megagraph.shop.payment.MockChannelCommandJob;
import com.megagraph.shop.projection.OutboxEventRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossMerchantCheckoutIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired MerchantRepository merchants;
    @Autowired MerchantUserRepository memberships;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired ProductSkuRepository skus;
    @Autowired ProductImageRepository images;
    @Autowired PasswordEncoder passwords;
    @Autowired SalesOrderRepository orders;
    @Autowired CheckoutGroupRepository groups;
    @Autowired OutboxEventRepository events;
    @Autowired MockChannelCommandJob commands;

    @Test
    void mixedCartCreatesIdempotentIndependentTestOrdersWithSupplySnapshots() throws Exception {
        Fixture f = fixture();
        add(f, f.firstSku(), 1); add(f, f.secondSku(), 1);
        String key = UUID.randomUUID().toString();
        JsonNode result = checkout(f, key);
        assertThat(result.path("orders")).hasSize(2);
        assertThat(result.path("totalAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(result.path("orders").get(0).path("merchantId").asLong()).isNotEqualTo(result.path("orders").get(1).path("merchantId").asLong());
        assertThat(result.path("orders").get(0).path("testOrder").asBoolean()).isTrue();
        assertThat(result.path("orders").get(0).path("items").get(0).path("supplyDetails").path("fabric").asText()).isEqualTo("测试棉面料");
        assertThat(result.path("orders").get(0).path("items").get(0).path("productVersion").isNumber()).isTrue();
        JsonNode repeated = checkout(f, key);
        assertThat(repeated.path("id")).isEqualTo(result.path("id"));
        assertThat(repeated.path("orders").get(0).path("id")).isEqualTo(result.path("orders").get(0).path("id"));
        assertThat(orders.findByUserIdOrderByCreatedAtDesc(f.buyerId())).hasSize(2);
        assertThat(skus.findById(f.firstSku()).orElseThrow().getAvailableStock()).isEqualTo(4);
        assertThat(events.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("CHECKOUT", result.path("id").asLong())).hasSize(1);
        mvc.perform(get("/api/shop/v1/cart").with(httpBasic(f.buyer(), "test123"))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post("/api/shop/v1/checkouts").with(httpBasic(f.buyer(), "test123")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(address("不同地址")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKOUT_IDEMPOTENCY_CONFLICT"));
        mvc.perform(get("/api/shop/v1/checkouts/{id}", result.path("id").asLong())
                .with(httpBasic("shopper@megagraph.local", "shopper123"))).andExpect(status().isNotFound());
    }

    @Test
    void oneMerchantPaymentFailureCannotUndoTheOtherMerchantSuccessOrAllowShipping() throws Exception {
        Fixture f = fixture();
        add(f, f.firstSku(), 1); add(f, f.secondSku(), 1);
        JsonNode result = checkout(f, UUID.randomUUID().toString());
        long a = result.path("orders").get(0).path("id").asLong();
        long b = result.path("orders").get(1).path("id").asLong();
        pay(f, a, "SUCCESS"); commands.processAvailable(100);
        pay(f, b, "FAILURE"); commands.processAvailable(100);
        mvc.perform(get("/api/shop/v1/orders/{id}", a).with(httpBasic(f.buyer(), "test123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.reservations[0].status").value("CONFIRMED"));
        mvc.perform(get("/api/shop/v1/orders/{id}", b).with(httpBasic(f.buyer(), "test123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.reservations[0].status").value("RELEASED"));
        assertThat(skus.findById(f.firstSku()).orElseThrow().getAvailableStock()).isEqualTo(4);
        assertThat(skus.findById(f.secondSku()).orElseThrow().getAvailableStock()).isEqualTo(5);
        mvc.perform(post("/api/merchant/v1/orders/{id}/ship", a).with(httpBasic(f.owner(), "test123"))
                .header("X-Merchant-Id", result.path("orders").get(0).path("merchantId").asLong())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrierName\":\"测试物流\",\"trackingNo\":\"TEST-ONLY\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TEST_ORDER_FULFILLMENT_BLOCKED"));
    }

    @Test
    void failedSecondMerchantCreationRollsBackFirstChildStockGroupAndEvents() throws Exception {
        Fixture f = fixture();
        add(f, f.firstSku(), 1); add(f, f.secondSku(), 2);
        ProductSku second = skus.findById(f.secondSku()).orElseThrow();
        second.revise(second.getSkuCode(), second.getSkuName(), second.getPrice(), 1); skus.saveAndFlush(second);
        long before = groups.count();
        mvc.perform(post("/api/shop/v1/checkouts").with(httpBasic(f.buyer(), "test123"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(address("测试地址")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SKU_STOCK_INSUFFICIENT"));
        assertThat(groups.count()).isEqualTo(before);
        assertThat(orders.findByUserIdOrderByCreatedAtDesc(f.buyerId())).isEmpty();
        assertThat(skus.findById(f.firstSku()).orElseThrow().getAvailableStock()).isEqualTo(5);
        mvc.perform(get("/api/shop/v1/cart").with(httpBasic(f.buyer(), "test123"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void simultaneousSameKeyRequestsCreateOnlyOneGroupAndReserveStockOnce() throws Exception {
        Fixture f = fixture();
        add(f, f.firstSku(), 1); add(f, f.secondSku(), 1);
        String key = UUID.randomUUID().toString();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> checkout(f, key));
            var second = executor.submit(() -> checkout(f, key));
            JsonNode a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            JsonNode b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.path("id")).isEqualTo(b.path("id"));
        }
        assertThat(orders.findByUserIdOrderByCreatedAtDesc(f.buyerId())).hasSize(2);
        assertThat(skus.findById(f.firstSku()).orElseThrow().getAvailableStock()).isEqualTo(4);
        assertThat(skus.findById(f.secondSku()).orElseThrow().getAvailableStock()).isEqualTo(4);
    }

    private Fixture fixture() throws Exception {
        String token = UUID.randomUUID().toString();
        var buyer = users.save(new UserAccount("buyer-" + token, passwords.encode("test123"), "测试顾客"));
        var owner = users.save(new UserAccount("owner-" + token, passwords.encode("test123"), "测试商家账号"));
        Merchant a = merchants.save(new Merchant("测试 A " + token));
        Merchant b = merchants.save(new Merchant("测试 B " + token));
        memberships.save(new MerchantUser(a.getId(), owner.getId(), MerchantRole.OWNER));
        memberships.save(new MerchantUser(b.getId(), owner.getId(), MerchantRole.OWNER));
        Long category = categories.findAll().stream().filter(value -> value.getName().equals("上衣")).findFirst().orElseThrow().getId();
        return new Fixture(buyer.getId(), buyer.getUsername(), owner.getUsername(), product(a, category, "10.00", token + "-A", owner.getId()),
                product(b, category, "20.00", token + "-B", owner.getId()));
    }

    private Long product(Merchant merchant, Long category, String price, String code, Long owner) throws Exception {
        Product product = new Product(merchant.getId(), "集成测试商品");
        product.revise("集成测试商品", null, category);
        product.reviseSupplyDetails(json.writeValueAsString(new SupplyDetails("灰", "测试棉面料", "常规", List.of("春"),
                List.of("通勤"), "测试尺寸", "测试图片", List.of(new SupplyDetails.SizeRow(code, "M", new BigDecimal("104"), new BigDecimal("66"), null, null)))));
        product.confirm(owner); product.publish(); product = products.saveAndFlush(product);
        images.save(new ProductImage(merchant.getId(), product.getId(), "test-only.png", "test-only.png", "image/png", 12, ImageRole.MAIN, 0));
        return skus.saveAndFlush(new ProductSku(merchant.getId(), product.getId(), code, "灰 / M", new BigDecimal(price), 5)).getId();
    }

    private void add(Fixture f, Long sku, int quantity) throws Exception {
        mvc.perform(put("/api/shop/v1/cart/items/{id}", sku).with(httpBasic(f.buyer(), "test123"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("quantity", quantity))))
                .andExpect(status().isOk());
    }
    private String address(String address) throws Exception { return json.writeValueAsString(Map.of("recipientName", "测试顾客", "recipientPhone", "13800138000", "shippingAddress", address)); }
    private JsonNode checkout(Fixture f, String key) throws Exception {
        return json.readTree(mvc.perform(post("/api/shop/v1/checkouts").with(httpBasic(f.buyer(), "test123"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(address("测试地址")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private void pay(Fixture f, long order, String result) throws Exception {
        mvc.perform(post("/api/shop/v1/orders/{id}/mock-payment", order).with(httpBasic(f.buyer(), "test123"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("result", result)))).andExpect(status().isOk());
    }
    private record Fixture(Long buyerId, String buyer, String owner, Long firstSku, Long secondSku) {}
}
