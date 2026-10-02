package com.megagraph.shop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.*;
import com.megagraph.shop.commerce.*;
import com.megagraph.shop.delivery.*;
import com.megagraph.shop.guide.GuideChatResult;
import com.megagraph.shop.payment.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DigitalDeliveryIntegrationTest.Faults.class)
class DigitalDeliveryIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired DigitalDeliveryService digital;
  @Autowired OrderService orders;
  @Autowired AfterSaleService afterSales;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  SalesOrderRepository orderRepo;

  @Autowired MockChannelCommandJob commands;
  @Autowired PlatformTransactionManager tm;
  @Autowired AtomicReference<String> fault;
  static final String M = "merchant@megagraph.local", S = "shopper@megagraph.local";

  @TestConfiguration
  static class Faults {
    @Bean
    AtomicReference<String> fault() {
      return new AtomicReference<>();
    }

    @Bean
    OutboxFaultInjector injector(AtomicReference<String> f) {
      return e -> {
        if (e.equals(f.get())) throw new IllegalStateException("digital outbox injected");
      };
    }
  }

  MockHttpServletRequestBuilder merchant(MockHttpServletRequestBuilder r) {
    return r.with(httpBasic(M, "merchant123"));
  }

  MockHttpServletRequestBuilder shopper(MockHttpServletRequestBuilder r) {
    return r.with(httpBasic(S, "shopper123"));
  }

  JsonNode result(MockHttpServletRequestBuilder r) throws Exception {
    return json.readTree(
        mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  JsonNode create() throws Exception {
    return result(
        merchant(post("/api/merchant/v1/delivery-offers"))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"deliveryType\":\"DIGITAL_FILE\",\"title\":\"自建测试文件\",\"description\":\"自建测试资料\",\"price\":9.90,\"currency\":\"CNY\"}"));
  }

  JsonNode action(JsonNode o, String a) throws Exception {
    return result(
        merchant(post("/api/merchant/v1/delivery-offers/" + o.get("id").asLong() + "/" + a))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + o.get("version") + "}"));
  }

  JsonNode published() throws Exception {
    return action(action(create(), "confirm"), "publish");
  }

  DeliveryOrderRequest request(JsonNode o) {
    return new DeliveryOrderRequest(
        List.of(
            Map.of(
                "offerId",
                o.get("id").asLong(),
                "quantity",
                1,
                "expectedOfferVersion",
                o.get("version").asLong())));
  }

  JsonNode buy(JsonNode o, String k) throws Exception {
    return result(
        shopper(post("/api/shop/v1/delivery-orders"))
            .header("Idempotency-Key", k)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request(o))));
  }

  JsonNode paid() throws Exception {
    JsonNode o = buy(published(), key());
    long id = o.get("id").asLong();
    result(
        shopper(post("/api/shop/v1/orders/" + id + "/mock-payment"))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"result\":\"SUCCESS\"}"));
    commands.processAvailable(100);
    return result(shopper(get("/api/shop/v1/orders/" + id)));
  }

  JsonNode claim(long id, String k) throws Exception {
    return result(
        shopper(post("/api/shop/v1/orders/" + id + "/digital-claim")).header("Idempotency-Key", k));
  }

  Long user() {
    return db.queryForObject("select id from app_user where username=?", Long.class, S);
  }

  Long merchantId() {
    return db.queryForObject(
        "select id from merchant_user where user_id=(select id from app_user where username=?)",
        Long.class,
        M);
  }

  @Test
  void publishedOnlyConfirmationAndVersion() throws Exception {
    var o = create();
    mvc.perform(get("/api/shop/v1/delivery-offers/" + o.get("id")))
        .andExpect(status().isNotFound());
    mvc.perform(
            merchant(post("/api/merchant/v1/delivery-offers/" + o.get("id") + "/publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isConflict());
    var p = action(action(o, "confirm"), "publish");
    assertThat(result(get("/api/shop/v1/delivery-offers/" + p.get("id"))).get("assetHash").asText())
        .hasSize(64);
    action(p, "off-shelf");
    mvc.perform(get("/api/shop/v1/delivery-offers/" + p.get("id")))
        .andExpect(status().isNotFound());
  }

  @Test
  void priceAssetSnapshotAndReplayPrecedesVersionValidation() throws Exception {
    var offer = published();
    String k = key();
    var order = buy(offer, k);
    var edit =
        merchant(patch("/api/merchant/v1/delivery-offers/" + offer.get("id")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"version\":"
                    + offer.get("version")
                    + ",\"title\":\"更新测试文件\",\"description\":\"新的自建测试文件\",\"price\":29.90,\"currency\":\"CNY\"}");
    var updated = action(action(result(edit), "confirm"), "publish");
    assertThat(buy(offer, k).get("id")).isEqualTo(order.get("id"));
    mvc.perform(
            shopper(post("/api/shop/v1/delivery-orders"))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(offer))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OFFER_CHANGED"));
    assertThat(order.at("/delivery/assetHash")).isNotEqualTo(updated.get("assetHash"));
    assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo("9.90");
    mvc.perform(
            shopper(post("/api/shop/v1/delivery-orders"))
                .header("Idempotency-Key", k)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(updated))))
        .andExpect(status().isConflict());
  }

  @Test
  void strictSingleQuantityAndNoClientFacts() throws Exception {
    var o = published();
    for (String body :
        List.of(
            "{\"items\":[]}",
            "{\"items\":[{\"offerId\":"
                + o.get("id")
                + ",\"quantity\":2,\"expectedOfferVersion\":2}]}",
            "{\"items\":[{\"offerId\":" + o.get("id") + ",\"quantity\":1}]}",
            "{\"items\":[{\"skuId\":1,\"quantity\":1,\"expectedOfferVersion\":2}]}",
            "{\"items\":[{\"offerId\":"
                + o.get("id")
                + ",\"quantity\":1,\"expectedOfferVersion\":2,\"price\":0}]}")) {
      mvc.perform(
              shopper(post("/api/shop/v1/delivery-orders"))
                  .header("Idempotency-Key", key())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void paymentClaimDownloadImmutableAndNoStock() throws Exception {
    var o = paid();
    long id = o.get("id").asLong();
    assertThat(o.get("deliveryType").asText()).isEqualTo("DIGITAL_FILE");
    assertThat(o.get("items").size()).isZero();
    assertThat(o.get("reservations").size()).isZero();
    assertThat(o.at("/delivery/entitlement/status").asText()).isEqualTo("AVAILABLE");
    mvc.perform(shopper(get("/api/shop/v1/orders/" + id + "/digital-download")))
        .andExpect(status().isConflict());
    var first = claim(id, key());
    var second = claim(id, key());
    assertThat(second.at("/delivery/entitlement/expiresAt"))
        .isEqualTo(first.at("/delivery/entitlement/expiresAt"));
    var response =
        mvc.perform(shopper(get("/api/shop/v1/orders/" + id + "/digital-download")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        "attachment; filename=megagraph-test-order-" + id + ".txt"))
            .andReturn()
            .getResponse();
    String hash =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(response.getContentAsByteArray()));
    assertThat(hash).isEqualTo(o.at("/delivery/assetHash").asText());
    assertThat(
            db.queryForObject(
                "select count(*) from digital_entitlement where order_id=?", Long.class, id))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from stock_reservation where order_id=?", Long.class, id))
        .isZero();
    mvc.perform(get("/api/shop/v1/orders/" + id + "/digital-download"))
        .andExpect(status().isUnauthorized());
    mvc.perform(merchant(get("/api/shop/v1/orders/" + id + "/digital-download")))
        .andExpect(status().isNotFound());
  }

  @Test
  void unclaimedRefundBlocksThenRevokesAndClaimedRejects() throws Exception {
    var o = paid();
    long id = o.get("id").asLong();
    result(
        shopper(post("/api/shop/v1/orders/" + id + "/after-sales"))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"测试未领取退款\"}"));
    mvc.perform(
            shopper(post("/api/shop/v1/orders/" + id + "/digital-claim"))
                .header("Idempotency-Key", key()))
        .andExpect(status().isConflict());
    result(
        merchant(post("/api/merchant/v1/orders/" + id + "/after-sales/approve"))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"测试批准\"}"));
    commands.processAvailable(100);
    var refunded = result(shopper(get("/api/shop/v1/orders/" + id)));
    assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");
    assertThat(refunded.at("/delivery/entitlement/status").asText()).isEqualTo("REVOKED");
    mvc.perform(shopper(get("/api/shop/v1/orders/" + id + "/digital-download")))
        .andExpect(status().isConflict());
    var claimed = paid();
    claim(claimed.get("id").asLong(), key());
    mvc.perform(
            shopper(post("/api/shop/v1/orders/" + claimed.get("id") + "/after-sales"))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"测试退款\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectedRefundRestoresClaimAndExpiredCannotDownload() throws Exception {
    var o = paid();
    long id = o.get("id").asLong();
    result(
        shopper(post("/api/shop/v1/orders/" + id + "/after-sales"))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"测试申请\"}"));
    result(
        merchant(post("/api/merchant/v1/orders/" + id + "/after-sales/reject"))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"测试拒绝\"}"));
    claim(id, key());
    db.update(
        "update digital_entitlement set expires_at=? where order_id=?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
        id);
    mvc.perform(shopper(get("/api/shop/v1/orders/" + id + "/digital-download")))
        .andExpect(status().isConflict());
    mvc.perform(
            shopper(post("/api/shop/v1/orders/" + id + "/digital-claim"))
                .header("Idempotency-Key", key()))
        .andExpect(status().isConflict());
  }

  @Test
  void concurrentPurchaseIdempotencyAndClaimRefundSerialize() throws Exception {
    var o = published();
    String k = key();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> digital.purchase(user(), k, request(o)));
      var b = pool.submit(() -> digital.purchase(user(), k, request(o)));
      assertThat(a.get(10, TimeUnit.SECONDS).id()).isEqualTo(b.get(10, TimeUnit.SECONDS).id());
    }
    var paid = paid();
    long id = paid.get("id").asLong();
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  digital.claim(user(), id, key());
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException e) {
                  return false;
                }
              });
      var b =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  afterSales.request(user(), id, key(), new CreateAfterSaleRequest("测试并发退款"));
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException e) {
                  return false;
                }
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS) ^ b.get(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void claimOutboxFailureRollsBackJpaJdbcAndIdempotencyKey() throws Exception {
    var o = paid();
    long id = o.get("id").asLong();
    String k = key();
    fault.set("DIGITAL_ENTITLEMENT_CLAIMED");
    try {
      assertThatThrownBy(() -> digital.claim(user(), id, k))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(
            db.queryForObject(
                "select status from digital_entitlement where order_id=?", String.class, id))
        .isEqualTo("AVAILABLE");
    assertThat(
            db.queryForObject(
                "select count(*) from digital_claim_key where user_id=? and idempotency_key=?",
                Long.class,
                user(),
                k))
        .isZero();
    claim(id, k);
  }

  @Test
  void purchaseOutboxFailureRollsBackAllAndSupplyHashCanonical() throws Exception {
    var o = published();
    String k = key();
    fault.set("ORDER_CREATED");
    try {
      assertThatThrownBy(() -> digital.purchase(user(), k, request(o)))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(orderRepo.findByUserIdAndIdempotencyKey(user(), k)).isEmpty();
    digital.purchase(user(), k, request(o));
    String idem = key();
    digital.create(
        merchantId(),
        idem,
        new DeliveryOfferRequest(null, "DIGITAL_FILE", "测试\nx", "测试 y", BigDecimal.ONE, "CNY"));
    assertThatThrownBy(
            () ->
                digital.create(
                    merchantId(),
                    idem,
                    new DeliveryOfferRequest(
                        null, "DIGITAL_FILE", "测试", "x\n测试 y", BigDecimal.ONE, "CNY")))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
  }

  @Test
  void defaultNoModelUnifiedConversationAndHistoricalJson() throws Exception {
    var offer = published();
    var c = result(shopper(post("/api/shop/v1/guide/conversations")));
    var response =
        result(
            shopper(post("/api/shop/v1/guide/conversations/" + c.get("id").asText() + "/messages"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + key() + "\",\"content\":\"我要数字测试文件\"}"));
    assertThat(response.toString()).contains("deliveryOffers", "DIGITAL_FILE");
    var restored = result(shopper(get("/api/shop/v1/guide/conversations/" + c.get("id").asText())));
    assertThat(restored.toString()).contains("deliveryOffers");
    var old =
        json.readValue(
            "{\"answer\":\"旧历史\",\"products\":[],\"demoProductIds\":[],\"advice\":\"\",\"recommendationBasis\":[]}",
            GuideChatResult.class);
    assertThat(old.deliveryOffers()).isEmpty();
    mvc.perform(get("/api/shop/v1/capabilities"))
        .andExpect(jsonPath("$.guideAvailable").value(false))
        .andExpect(jsonPath("$.digitalDeliveryAvailable").value(true));
  }

  @Autowired PaymentCallbackIngress ingress;
  @Autowired MockPaymentCallbackAdapter adapter;

  CallbackIngressResult event(
      String eventId, String resource, String type, String resourceType, BigDecimal amount)
      throws Exception {
    byte[] raw =
        json.writeValueAsBytes(
            Map.of(
                "providerEventId",
                eventId,
                "eventType",
                type,
                "resourceType",
                resourceType,
                "providerResourceId",
                resource,
                "occurredAt",
                Instant.now().toString(),
                "amount",
                amount,
                "currency",
                "CNY"));
    var headers = new org.springframework.http.HttpHeaders();
    headers.set(MockPaymentCallbackAdapter.SIGNATURE_HEADER, adapter.sign(raw));
    return ingress.receive("MOCK", headers, raw);
  }

  @Test
  void paymentAndRevocationOutboxFailuresRollbackEntireCallerTransaction() throws Exception {
    var order = buy(published(), key());
    long id = order.get("id").asLong();
    String payment = order.at("/payment/providerPaymentId").asText();
    fault.set("DIGITAL_ENTITLEMENT_AVAILABLE");
    try {
      assertThatThrownBy(
              () -> event(key(), payment, "PAYMENT_SUCCEEDED", "PAYMENT", new BigDecimal("9.90")))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(db.queryForObject("select status from sales_order where id=?", String.class, id))
        .isEqualTo("PENDING_PAYMENT");
    assertThat(
            db.queryForObject(
                "select status from payment_order where order_id=?", String.class, id))
        .isEqualTo("PENDING");
    assertThat(
            db.queryForObject(
                "select count(*) from digital_entitlement where order_id=?", Long.class, id))
        .isZero();
    event(key(), payment, "PAYMENT_SUCCEEDED", "PAYMENT", new BigDecimal("9.90"));
    afterSales.request(user(), id, key(), new CreateAfterSaleRequest("测试退款回滚"));
    afterSales.approve(merchantId(), id, key(), new ResolveAfterSaleRequest("测试批准"));
    Long refund = db.queryForObject("select id from refund_order where order_id=?", Long.class, id);
    db.update(
        "update refund_order set provider_refund_id=? where id=?", "d1-test-refund-" + id, refund);
    fault.set("DIGITAL_ENTITLEMENT_REVOKED");
    try {
      assertThatThrownBy(
              () ->
                  event(
                      key(),
                      "d1-test-refund-" + id,
                      "REFUND_SUCCEEDED",
                      "REFUND",
                      new BigDecimal("9.90")))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(db.queryForObject("select status from sales_order where id=?", String.class, id))
        .isEqualTo("PAID");
    assertThat(
            db.queryForObject("select status from refund_order where id=?", String.class, refund))
        .isEqualTo("PENDING");
    assertThat(
            db.queryForObject(
                "select status from digital_entitlement where order_id=?", String.class, id))
        .isEqualTo("AVAILABLE");
    event(key(), "d1-test-refund-" + id, "REFUND_SUCCEEDED", "REFUND", new BigDecimal("9.90"));
    assertThat(
            db.queryForObject(
                "select status from digital_entitlement where order_id=?", String.class, id))
        .isEqualTo("REVOKED");
  }

  @Test
  void differentPaymentEventsConcurrentAreBothAppliedWithOneEntitlement() throws Exception {
    var order = buy(published(), key());
    long id = order.get("id").asLong();
    String payment = order.at("/payment/providerPaymentId").asText();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> event(key(), payment, "PAYMENT_SUCCEEDED", "PAYMENT", new BigDecimal("9.90")));
      var b =
          pool.submit(
              () -> event(key(), payment, "PAYMENT_SUCCEEDED", "PAYMENT", new BigDecimal("9.90")));
      assertThat(a.get(10, TimeUnit.SECONDS).result().status())
          .isEqualTo(ChannelEventProcessingStatus.APPLIED);
      assertThat(b.get(10, TimeUnit.SECONDS).result().status())
          .isEqualTo(ChannelEventProcessingStatus.APPLIED);
    }
    assertThat(
            db.queryForObject(
                "select count(*) from digital_entitlement where order_id=?", Long.class, id))
        .isEqualTo(1);
  }

  @Test
  void snapshotEstablishedBeforeAssetEditStillUsesCurrentAssetFacts() throws Exception {
    var old = published();
    CountDownLatch ready = new CountDownLatch(1), go = new CountDownLatch(1);
    AtomicReference<JsonNode> updated = new AtomicReference<>();
    try (var pool = Executors.newSingleThreadExecutor()) {
      var work =
          pool.submit(
              () ->
                  new TransactionTemplate(tm)
                      .execute(
                          tx -> {
                            db.queryForObject("select count(*) from sales_order", Long.class);
                            ready.countDown();
                            try {
                              if (!go.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("latch timeout");
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                            return digital.purchase(user(), key(), request(updated.get()));
                          }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      var edited =
          digital.edit(
              merchantId(),
              old.get("id").asLong(),
              new DeliveryOfferRequest(
                  old.get("version").asLong(),
                  null,
                  "改价自建测试文件",
                  "另一个自建测试文件",
                  new BigDecimal("19.90"),
                  "CNY"));
      var confirmed = digital.transition(merchantId(), edited.id(), edited.version(), "confirm");
      var current =
          digital.transition(merchantId(), confirmed.id(), confirmed.version(), "publish");
      updated.set(json.valueToTree(current));
      go.countDown();
      var order = work.get(10, TimeUnit.SECONDS);
      assertThat(order.totalAmount()).isEqualByComparingTo("19.90");
      assertThat(order.delivery().assetHash()).isEqualTo(current.assetHash());
      String storedHash =
          db.queryForObject(
              "select a.content_hash from delivery_purchase p join digital_asset a on"
                  + " a.id=p.asset_id where p.order_id=?",
              String.class,
              order.id());
      assertThat(storedHash).isEqualTo(current.assetHash());
    }
  }

  @Test
  void staleSnapshotBeforeClaimCannotAdmitRefundAndBeforeRefundCannotAdmitClaim() throws Exception {
    for (boolean claimWins : List.of(true, false)) {
      var o = paid();
      long id = o.get("id").asLong();
      CountDownLatch ready = new CountDownLatch(1), go = new CountDownLatch(1);
      try (var pool = Executors.newSingleThreadExecutor()) {
        var loser =
            pool.submit(
                () ->
                    new TransactionTemplate(tm)
                        .execute(
                            tx -> {
                              db.queryForObject(
                                  "select count(*) from after_sale_request", Long.class);
                              db.queryForObject(
                                  "select count(*) from digital_entitlement", Long.class);
                              ready.countDown();
                              try {
                                if (!go.await(5, TimeUnit.SECONDS))
                                  throw new IllegalStateException("latch timeout");
                              } catch (InterruptedException e) {
                                throw new IllegalStateException(e);
                              }
                              if (claimWins)
                                return afterSales.request(
                                    user(), id, key(), new CreateAfterSaleRequest("测试过期读"));
                              return digital.claim(user(), id, key());
                            }));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        if (claimWins) digital.claim(user(), id, key());
        else afterSales.request(user(), id, key(), new CreateAfterSaleRequest("测试先申请"));
        go.countDown();
        assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(com.megagraph.shop.shared.ConflictException.class);
      }
    }
  }

  @Test
  void oversizedVersionAndFractionRejectedAndCrossTypeIdempotencyConflicts() throws Exception {
    var o = published();
    for (String version : List.of("18446744073709551618", "2.5"))
      mvc.perform(
              shopper(post("/api/shop/v1/delivery-orders"))
                  .header("Idempotency-Key", key())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"items\":[{\"offerId\":"
                          + o.get("id")
                          + ",\"quantity\":1,\"expectedOfferVersion\":"
                          + version
                          + "}]}"))
          .andExpect(status().isBadRequest());
    String k = key();
    buy(o, k);
    assertThatThrownBy(
            () -> orders.create(user(), k, new CreateOrderRequest("测试", "00000000000", "测试")))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
  }

  @Autowired com.megagraph.shop.identity.UserAccountRepository users;
  @Autowired com.megagraph.shop.identity.MerchantRepository merchants;
  @Autowired com.megagraph.shop.identity.MerchantUserRepository memberships;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

  String roleAccount(com.megagraph.shop.identity.MerchantRole role, Long merchant) {
    String name = "digital-role-" + key() + "@test.local";
    var account =
        users.saveAndFlush(
            new com.megagraph.shop.identity.UserAccount(
                name, encoder.encode("digital-test-only"), "数字角色测试"));
    memberships.saveAndFlush(
        new com.megagraph.shop.identity.MerchantUser(merchant, account.getId(), role));
    return name;
  }

  @Test
  void rolesAndMerchantIsolation() throws Exception {
    Long merchant = merchantId();
    String editor = roleAccount(com.megagraph.shop.identity.MerchantRole.EDITOR, merchant),
        publisher = roleAccount(com.megagraph.shop.identity.MerchantRole.PUBLISHER, merchant);
    var offer =
        result(
            post("/api/merchant/v1/delivery-offers")
                .with(httpBasic(editor, "digital-test-only"))
                .header("X-Merchant-Id", merchant)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"deliveryType\":\"DIGITAL_FILE\",\"title\":\"角色测试文件\",\"description\":\"自建测试资料\",\"price\":9.90,\"currency\":\"CNY\"}"));
    long id = offer.get("id").asLong();
    var confirm =
        result(
            post("/api/merchant/v1/delivery-offers/" + id + "/confirm")
                .with(httpBasic(editor, "digital-test-only"))
                .header("X-Merchant-Id", merchant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"));
    mvc.perform(
            post("/api/merchant/v1/delivery-offers/" + id + "/publish")
                .with(httpBasic(editor, "digital-test-only"))
                .header("X-Merchant-Id", merchant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/merchant/v1/delivery-offers/" + id)
                .with(httpBasic(publisher, "digital-test-only"))
                .header("X-Merchant-Id", merchant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":1,\"title\":\"测试\",\"description\":\"测试\",\"price\":1,\"currency\":\"CNY\"}"))
        .andExpect(status().isForbidden());
    result(
        post("/api/merchant/v1/delivery-offers/" + id + "/publish")
            .with(httpBasic(publisher, "digital-test-only"))
            .header("X-Merchant-Id", merchant)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":1}"));
    Long other = merchants.saveAndFlush(new com.megagraph.shop.identity.Merchant("另一测试商家")).getId();
    String owner = roleAccount(com.megagraph.shop.identity.MerchantRole.OWNER, other);
    mvc.perform(
            patch("/api/merchant/v1/delivery-offers/" + id)
                .with(httpBasic(owner, "digital-test-only"))
                .header("X-Merchant-Id", other)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":2,\"title\":\"测试\",\"description\":\"测试\",\"price\":1,\"currency\":\"CNY\"}"))
        .andExpect(status().isNotFound());
    assertThat(
            result(
                get("/api/merchant/v1/delivery-offers")
                    .with(httpBasic(owner, "digital-test-only"))
                    .header("X-Merchant-Id", other)))
        .isEmpty();
  }

  @Test
  void failedCancelledExpiredNeverIssueEntitlement() throws Exception {
    for (String state : List.of("FAILURE", "CANCELLED", "EXPIRED")) {
      var order = buy(published(), key());
      long id = order.get("id").asLong();
      if ("EXPIRED".equals(state)) {
        db.update(
            "update sales_order set expires_at=? where id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
            id);
        orders.expireOverdueOrders();
      } else {
        orders.applyMockPayment(user(), id, key(), MockPaymentResult.valueOf(state));
        commands.processAvailable(100);
      }
      assertThat(
              db.queryForObject(
                  "select count(*) from digital_entitlement where order_id=?", Long.class, id))
          .isZero();
      mvc.perform(
              shopper(post("/api/shop/v1/orders/" + id + "/digital-claim"))
                  .header("Idempotency-Key", key()))
          .andExpect(status().isConflict());
      mvc.perform(shopper(get("/api/shop/v1/orders/" + id + "/digital-download")))
          .andExpect(status().isConflict());
    }
    var pending = buy(published(), key());
    orders.cancel(user(), pending.get("id").asLong());
    assertThat(
            db.queryForObject(
                "select count(*) from digital_entitlement where order_id=?",
                Long.class,
                pending.get("id").asLong()))
        .isZero();
  }

  @Test
  void physicalIdempotencyCannotReplayAsDigital() throws Exception {
    String k = key();
    var physical =
        orderRepo.saveAndFlush(
            new SalesOrder(
                "D1-PHYSICAL-" + key().substring(0, 20),
                user(),
                merchantId(),
                k,
                "CNY",
                BigDecimal.ONE,
                "测试",
                "00000000000",
                "测试",
                Instant.now().plusSeconds(300)));
    var offer = published();
    assertThatThrownBy(() -> digital.purchase(user(), k, request(offer)))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    assertThat(
            db.queryForObject(
                "select delivery_type from sales_order where id=?", String.class, physical.getId()))
        .isEqualTo("PHYSICAL");
  }

  @Test
  void failedRefundRemainsBlockedAndNoModelSourceAndValidation() throws Exception {
    var o = paid();
    long id = o.get("id").asLong();
    afterSales.request(user(), id, key(), new CreateAfterSaleRequest("测试失败退款"));
    afterSales.approve(merchantId(), id, key(), new ResolveAfterSaleRequest("测试批准"));
    db.update(
        "update refund_order set provider_refund_id=? where order_id=?",
        "d1-failed-refund-" + id,
        id);
    event(key(), "d1-failed-refund-" + id, "REFUND_FAILED", "REFUND", new BigDecimal("9.90"));
    var blocked = result(shopper(get("/api/shop/v1/orders/" + id)));
    assertThat(blocked.at("/afterSale/status").asText()).isEqualTo("REFUND_FAILED");
    assertThat(blocked.at("/delivery/claimAvailable").asBoolean()).isFalse();
    mvc.perform(
            shopper(post("/api/shop/v1/orders/" + id + "/digital-claim"))
                .header("Idempotency-Key", key()))
        .andExpect(status().isConflict());
    var answer =
        result(
            shopper(post("/api/shop/v1/guide/messages"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"我想买衣服\"}]}"));
    assertThat(answer.get("responseSource").asText()).isEqualTo("NO_MODEL");
    assertThat(answer.get("products")).isEmpty();
    mvc.perform(
            shopper(post("/api/shop/v1/guide/messages"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"assistant\",\"content\":\"数字文件\"}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void offerCreationOutboxFailureLeavesNoAssetOrOffer() {
    Long m = merchantId();
    long
        before =
            db.queryForObject(
                "select count(*) from digital_asset where merchant_id=?", Long.class, m),
        offersBefore =
            db.queryForObject(
                "select count(*) from delivery_offer where merchant_id=?", Long.class, m);
    fault.set("DELIVERY_OFFER_CREATED");
    try {
      assertThatThrownBy(
              () ->
                  digital.create(
                      m,
                      key(),
                      new DeliveryOfferRequest(
                          null, "DIGITAL_FILE", "测试回滚", "自建测试文件", BigDecimal.ONE, "CNY")))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(
            db.queryForObject(
                "select count(*) from digital_asset where merchant_id=?", Long.class, m))
        .isEqualTo(before);
    assertThat(
            db.queryForObject(
                "select count(*) from delivery_offer where merchant_id=?", Long.class, m))
        .isEqualTo(offersBefore);
  }

  @Test
  void refundHoldingOrderCanInsertUserForeignKeyWhileClaimWaitsForOrder() throws Exception {
    var o = paid();
    long id = o.get("id").asLong();
    CountDownLatch claimAtOrder = new CountDownLatch(1);
    var repositoryDelegate =
        org.mockito.Mockito.mockingDetails(orderRepo).getMockCreationSettings().getDefaultAnswer();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (Thread.currentThread().getName().equals("digital-claim-fk-worker"))
                claimAtOrder.countDown();
              return repositoryDelegate.answer(invocation);
            })
        .when(orderRepo)
        .findByIdForUpdate(id);
    try (var pool =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "digital-claim-fk-worker"))) {
      AtomicReference<Future<Boolean>> claimant = new AtomicReference<>();
      var refund =
          new TransactionTemplate(tm)
              .execute(
                  tx -> {
                    orderRepo.findByIdForUpdate(id).orElseThrow();
                    claimant.set(
                        pool.submit(
                            () -> {
                              try {
                                digital.claim(user(), id, key());
                                return true;
                              } catch (com.megagraph.shop.shared.ConflictException expected) {
                                return false;
                              }
                            }));
                    try {
                      assertThat(claimAtOrder.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                      throw new IllegalStateException(e);
                    }
                    // The claim has entered its order-lock query. With the previous account-first
                    // implementation it already held app_user X here and the insert's FK needed S.
                    return afterSales.request(
                        user(), id, key(), new CreateAfterSaleRequest("测试FK竞争退款"));
                  });
      assertThat(refund.afterSale().status()).isEqualTo("REQUESTED");
      assertThat(claimant.get().get(10, TimeUnit.SECONDS)).isFalse();
      assertThat(
              db.queryForObject(
                  "select count(*) from digital_claim_key where order_id=?", Long.class, id))
          .isZero();
      Long entitlementId =
          db.queryForObject("select id from digital_entitlement where order_id=?", Long.class, id);
      assertThat(
              db.queryForObject(
                  "select count(*) from outbox_event where aggregate_type='DIGITAL_ENTITLEMENT' and"
                      + " aggregate_id=? and event_type='DIGITAL_ENTITLEMENT_CLAIMED'",
                  Long.class,
                  entitlementId))
          .isZero();
      assertThat(
              db.queryForObject(
                  "select status from digital_entitlement where order_id=?", String.class, id))
          .isEqualTo("AVAILABLE");
    }
  }

  @Test
  void sameClaimKeyAcrossDistinctOrdersRemainsAtomic() throws Exception {
    long a = paid().get("id").asLong(), b = paid().get("id").asLong();
    String k = key();
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  digital.claim(user(), a, k);
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException expected) {
                  return false;
                }
              });
      var second =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  digital.claim(user(), b, k);
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException expected) {
                  return false;
                }
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            db.queryForObject(
                "select count(*) from digital_claim_key where user_id=? and idempotency_key=?",
                Long.class,
                user(),
                k))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from digital_entitlement where order_id in (?,?) and"
                    + " status='CLAIMED'",
                Long.class,
                a,
                b))
        .isEqualTo(1);
  }
}
