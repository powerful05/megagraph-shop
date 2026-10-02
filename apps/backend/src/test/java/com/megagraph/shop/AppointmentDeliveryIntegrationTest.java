package com.megagraph.shop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.megagraph.shop.api.*;
import com.megagraph.shop.commerce.*;
import com.megagraph.shop.delivery.*;
import com.megagraph.shop.payment.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AppointmentDeliveryIntegrationTest.Faults.class)
class AppointmentDeliveryIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired AppointmentService service;
  @MockitoSpyBean AppointmentStore store;
  @Autowired OrderService orders;
  @Autowired AfterSaleService afterSales;
  @MockitoSpyBean SalesOrderRepository orderRepo;
  @Autowired MockChannelCommandJob commands;
  @Autowired PlatformTransactionManager tm;
  @Autowired AtomicReference<String> fault;
  @Autowired AtomicReference<Runnable> releaseHook;
  @Autowired PaymentCallbackIngress ingress;
  @Autowired MockPaymentCallbackAdapter adapter;
  @Autowired com.megagraph.shop.identity.UserAccountRepository users;
  @Autowired com.megagraph.shop.identity.MerchantRepository merchants;
  @Autowired com.megagraph.shop.identity.MerchantUserRepository memberships;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

  @TestConfiguration
  static class Faults {
    @Bean
    AtomicReference<String> fault() {
      return new AtomicReference<>();
    }

    @Bean
    AtomicReference<Runnable> releaseHook() {
      return new AtomicReference<>();
    }

    @Bean
    OutboxFaultInjector injector(AtomicReference<String> f, AtomicReference<Runnable> h) {
      return type -> {
        if (type.equals(f.get())) throw new IllegalStateException("appointment outbox injected");
        if (type.equals("APPOINTMENT_RELEASED")) {
          Runnable r = h.getAndSet(null);
          if (r != null) r.run();
        }
      };
    }
  }

  static final String M = "merchant@megagraph.local", S = "shopper@megagraph.local";

  MockHttpServletRequestBuilder m(MockHttpServletRequestBuilder r) {
    return r.with(httpBasic(M, "merchant123"));
  }

  MockHttpServletRequestBuilder s(MockHttpServletRequestBuilder r) {
    return r.with(httpBasic(S, "shopper123"));
  }

  JsonNode result(MockHttpServletRequestBuilder r) throws Exception {
    return json.readTree(
        mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  Instant future() {
    return Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MILLIS);
  }

  Long user() {
    return db.queryForObject("select id from app_user where username=?", Long.class, S);
  }

  Long merchant() {
    return db.queryForObject(
        "select merchant_id from merchant_user where user_id=(select id from app_user where"
            + " username=?)",
        Long.class,
        M);
  }

  Long anotherUser() {
    return users
        .saveAndFlush(
            new com.megagraph.shop.identity.UserAccount(
                "appointment-" + key() + "@test.local",
                encoder.encode("test-only-appointment"),
                "预约测试账号"))
        .getId();
  }

  DeliveryOfferRequest details(Long v) {
    return new DeliveryOfferRequest(
        v, "APPOINTMENT", "固定时段测试服务", "仅测试，不实际预约或线下履约", new BigDecimal("19.90"), "CNY");
  }

  AppointmentOfferResponse offer(int capacity) {
    var o = service.create(merchant(), key(), details(null));
    Instant start = future();
    o =
        service.addSlot(
            merchant(),
            o.id(),
            key(),
            new AppointmentSlotRequest(o.version(), start, start.plusSeconds(3600), capacity));
    o = service.transition(merchant(), o.id(), o.version(), "confirm");
    return service.transition(merchant(), o.id(), o.version(), "publish");
  }

  AppointmentOrderRequest request(AppointmentOfferResponse o) {
    var slot = o.slots().getFirst();
    return new AppointmentOrderRequest(
        List.of(
            Map.of(
                "offerId",
                o.id(),
                "slotId",
                slot.id(),
                "quantity",
                1,
                "expectedOfferVersion",
                o.version(),
                "expectedSlotVersion",
                slot.version())));
  }

  OrderResponse buy(AppointmentOfferResponse o) {
    return service.purchase(user(), key(), request(o));
  }

  CallbackIngressResult event(
      String eventId, String resource, String type, String resourceType, BigDecimal amount)
      throws Exception {
    byte[] body =
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
    headers.set(MockPaymentCallbackAdapter.SIGNATURE_HEADER, adapter.sign(body));
    return ingress.receive("MOCK", headers, body);
  }

  OrderResponse pay(OrderResponse o) throws Exception {
    event(key(), o.payment().providerPaymentId(), "PAYMENT_SUCCEEDED", "PAYMENT", o.totalAmount());
    return orders.getForUser(o.userId(), o.id());
  }

  OrderResponse paid(int cap) throws Exception {
    return pay(buy(offer(cap)));
  }

  void expired(Long id) {
    var past = java.sql.Timestamp.from(Instant.now().minusSeconds(1));
    db.update("update sales_order set expires_at=? where id=?", past, id);
    db.update("update appointment_reservation set expires_at=? where order_id=?", past, id);
  }

  @Test
  void draftConfirmPublishSlotsAndVersionGuards() throws Exception {
    var o = service.create(merchant(), key(), details(null));
    mvc.perform(get("/api/shop/v1/appointment-offers/" + o.id())).andExpect(status().isNotFound());
    assertThatThrownBy(() -> service.transition(merchant(), o.id(), o.version(), "publish"))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    var p = offer(1);
    assertThat(result(get("/api/shop/v1/appointment-offers/" + p.id())).get("slots").size())
        .isEqualTo(1);
    var closed = service.closeSlot(merchant(), p.id(), p.slots().getFirst().id(), 0L);
    service.closeSlot(merchant(), p.id(), p.slots().getFirst().id(), 1L);
    assertThatThrownBy(() -> service.closeSlot(merchant(), p.id(), p.slots().getFirst().id(), 0L))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    mvc.perform(get("/api/shop/v1/appointment-offers/" + p.id())).andExpect(status().isNotFound());
  }

  @Test
  void twoAccountsLastCapacityAndSameKeyConcurrent() throws Exception {
    var o = offer(1);
    Long other = anotherUser();
    CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  service.purchase(user(), key(), request(o));
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
                  service.purchase(other, key(), request(o));
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException e) {
                  return false;
                }
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS) ^ b.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            db.queryForObject(
                "select count(*) from appointment_reservation where slot_id=? and status='HELD'",
                Long.class,
                o.slots().getFirst().id()))
        .isEqualTo(1);
    var many = offer(2);
    String k = key();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> service.purchase(user(), k, request(many)));
      var b = pool.submit(() -> service.purchase(user(), k, request(many)));
      assertThat(a.get(10, TimeUnit.SECONDS).id()).isEqualTo(b.get(10, TimeUnit.SECONDS).id());
    }
  }

  @Test
  void purchaseReplaySnapshotsAndChangedVersions() throws Exception {
    var o = offer(2);
    String k = key();
    var order = service.purchase(user(), k, request(o));
    var edit =
        service.edit(
            merchant(),
            o.id(),
            new DeliveryOfferRequest(
                o.version(), null, "改价测试服务", "不实际预约，测试资料", new BigDecimal("39.90"), "CNY"));
    var p = service.transition(merchant(), edit.id(), edit.version(), "confirm");
    p = service.transition(merchant(), p.id(), p.version(), "publish");
    assertThat(service.purchase(user(), k, request(o)).id()).isEqualTo(order.id());
    assertThat(order.totalAmount()).isEqualByComparingTo("19.90");
    assertThatThrownBy(() -> service.purchase(user(), key(), request(o)))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    var updated = p;
    assertThatThrownBy(() -> service.purchase(user(), k, request(updated)))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
  }

  @Test
  void strictInputTokensDatesAndCapacity() throws Exception {
    var o = offer(2);
    var slot = o.slots().getFirst();
    String valid = json.writeValueAsString(request(o));
    for (String body :
        List.of(
            valid.replace("\"quantity\":1", "\"quantity\":1.00000000000000001"),
            valid.replace("\"quantity\":1", "\"quantity\":2"),
            valid.replace(
                "\"expectedSlotVersion\":0", "\"expectedSlotVersion\":18446744073709551616"),
            valid.replace("\"slotId\":", "\"skuId\":"),
            "{\"items\":[],\"price\":1}")) {
      mvc.perform(
              s(post("/api/shop/v1/appointment-orders"))
                  .header("Idempotency-Key", key())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    for (String date : List.of("1730000000", "\"1730000000\"", "\"2026-10-03T12:30:00\"")) {
      mvc.perform(
              m(post("/api/merchant/v1/appointment-offers/" + o.id() + "/slots"))
                  .header("Idempotency-Key", key())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"version\":"
                          + o.version()
                          + ",\"startAt\":"
                          + date
                          + ",\"endAt\":\""
                          + future()
                          + "\",\"capacity\":1}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            m(post("/api/merchant/v1/appointment-offers/" + o.id() + "/slots"))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":"
                        + o.version()
                        + ",\"startAt\":\""
                        + future()
                        + "\",\"endAt\":\""
                        + future().plusSeconds(3600)
                        + "\",\"capacity\":1.5}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void utcOffsetIdempotencyAndMillisecondBoundaries() throws Exception {
    var o = service.create(merchant(), key(), details(null));
    Instant start = future();
    String k = key();
    String base = "{\"version\":0,\"startAt\":\"%s\",\"endAt\":\"%s\",\"capacity\":1}";
    var a =
        result(
            m(post("/api/merchant/v1/appointment-offers/" + o.id() + "/slots"))
                .header("Idempotency-Key", k)
                .contentType(MediaType.APPLICATION_JSON)
                .content(base.formatted(start, start.plusSeconds(3600))));
    var b =
        result(
            m(post("/api/merchant/v1/appointment-offers/" + o.id() + "/slots"))
                .header("Idempotency-Key", k)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    base.formatted(
                        start.atOffset(ZoneOffset.ofHours(8)),
                        start.plusSeconds(3600).atOffset(ZoneOffset.ofHours(8)))));
    assertThat(a.get("slots")).isEqualTo(b.get("slots"));
    assertThat(a.at("/slots/0/startAt").asText()).isEqualTo(start.toString());
    assertThatThrownBy(
            () ->
                service.addSlot(
                    merchant(),
                    o.id(),
                    key(),
                    new AppointmentSlotRequest(1L, start, start.plusNanos(100), 1)))
        .isInstanceOf(com.megagraph.shop.shared.DomainException.class);
    for (Instant invalid :
        List.of(Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(31))))
      assertThatThrownBy(
              () ->
                  service.addSlot(
                      merchant(),
                      o.id(),
                      key(),
                      new AppointmentSlotRequest(1L, invalid, invalid.plusSeconds(100), 1)))
          .isInstanceOf(com.megagraph.shop.shared.DomainException.class);
  }

  @Test
  void paymentIsConfirmationNotFulfillmentAndClosedHeldCanConfirm() throws Exception {
    var o = offer(1);
    var order = buy(o);
    assertThat(order.appointment().reservation().status()).isEqualTo("HELD");
    service.closeSlot(merchant(), o.id(), o.slots().getFirst().id(), 0L);
    var paid = pay(order);
    assertThat(paid.appointment().reservation().status()).isEqualTo("CONFIRMED");
    assertThat(paid.appointment().reservation().fulfilledAt()).isNull();
    assertThat(paid.items()).isEmpty();
    assertThat(paid.reservations()).isEmpty();
    assertThat(paid.delivery()).isNull();
    assertThat(paid.appointment().completeAvailable()).isTrue();
    String k = key();
    var completed =
        service.complete(merchant(), paid.id(), k, paid.appointment().reservation().version());
    assertThat(completed.appointment().reservation().status()).isEqualTo("FULFILLED");
    assertThat(
            service
                .complete(merchant(), paid.id(), k, paid.appointment().reservation().version())
                .appointment())
        .isEqualTo(completed.appointment());
    assertThatThrownBy(
            () -> afterSales.request(user(), paid.id(), key(), new CreateAfterSaleRequest("测试退款")))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    mvc.perform(
            s(post("/api/shop/v1/orders/" + paid.id() + "/digital-claim"))
                .header("Idempotency-Key", key()))
        .andExpect(status().isNotFound());
    mvc.perform(s(post("/api/shop/v1/orders/" + paid.id() + "/confirm-receipt")))
        .andExpect(status().isConflict());
  }

  @Test
  void duplicatePaymentAndCancellationFailureExpiryRelease() throws Exception {
    var p = paid(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () ->
                  event(
                      key(),
                      p.payment().providerPaymentId(),
                      "PAYMENT_SUCCEEDED",
                      "PAYMENT",
                      p.totalAmount()));
      var b =
          pool.submit(
              () ->
                  event(
                      key(),
                      p.payment().providerPaymentId(),
                      "PAYMENT_SUCCEEDED",
                      "PAYMENT",
                      p.totalAmount()));
      assertThat(a.get(10, TimeUnit.SECONDS).result().status())
          .isEqualTo(ChannelEventProcessingStatus.APPLIED);
      assertThat(b.get(10, TimeUnit.SECONDS).result().status())
          .isEqualTo(ChannelEventProcessingStatus.APPLIED);
    }
    assertThat(
            db.queryForObject(
                "select count(*) from outbox_event where aggregate_type='APPOINTMENT_RESERVATION'"
                    + " and aggregate_id=? and event_type='APPOINTMENT_CONFIRMED'",
                Long.class,
                p.appointment().reservation().id()))
        .isEqualTo(1);
    for (String mode : List.of("FAILURE", "CANCELLED", "EXPIRED", "DIRECT_CANCEL")) {
      var o = offer(1);
      var order = buy(o);
      if (mode.equals("EXPIRED")) {
        expired(order.id());
        orders.expireOverdueOrders();
      } else if (mode.equals("DIRECT_CANCEL")) orders.cancel(user(), order.id());
      else {
        orders.applyMockPayment(user(), order.id(), key(), MockPaymentResult.valueOf(mode));
        commands.processAvailable(100);
      }
      var latest = orders.getForUser(user(), order.id());
      assertThat(latest.appointment().reservation().status()).isEqualTo("RELEASED");
      assertThat(store.publicOffer(o.id()).slots().getFirst().remaining()).isEqualTo(1);
    }
  }

  @Test
  void expiredHoldNewAccountCanBuyAndLateSuccessReleasesAtomically() throws Exception {
    var o = offer(1);
    var old = buy(o);
    expired(old.id());
    var newer = service.purchase(anotherUser(), key(), request(o));
    var result =
        event(
            key(),
            old.payment().providerPaymentId(),
            "PAYMENT_SUCCEEDED",
            "PAYMENT",
            old.totalAmount());
    assertThat(result.result().status()).isEqualTo(ChannelEventProcessingStatus.REVIEW_REQUIRED);
    var oldNow = orders.getForUser(user(), old.id());
    assertThat(oldNow.status()).isEqualTo("EXPIRED");
    assertThat(oldNow.appointment().reservation().status()).isEqualTo("RELEASED");
    assertThat(oldNow.appointment().reservation().releaseReason()).isEqualTo("EXPIRED");
    assertThat(orders.getForUser(newer.userId(), newer.id()).appointment().reservation().status())
        .isEqualTo("HELD");
    assertThat(
            db.queryForObject(
                "select count(*) from appointment_reservation where slot_id=? and status in"
                    + " ('HELD','CONFIRMED','FULFILLED')",
                Long.class,
                o.slots().getFirst().id()))
        .isEqualTo(1);
  }

  @Test
  void refundableUntilExplicitCompleteAndPendingRejectFailureRules() throws Exception {
    var p = paid(1);
    afterSales.request(user(), p.id(), key(), new CreateAfterSaleRequest("测试未完成退款"));
    assertThatThrownBy(
            () ->
                service.complete(
                    merchant(), p.id(), key(), p.appointment().reservation().version()))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    afterSales.reject(merchant(), p.id(), key(), new ResolveAfterSaleRequest("测试拒绝"));
    service.complete(merchant(), p.id(), key(), p.appointment().reservation().version());
    var r = paid(1);
    afterSales.request(user(), r.id(), key(), new CreateAfterSaleRequest("测试退款"));
    afterSales.approve(merchant(), r.id(), key(), new ResolveAfterSaleRequest("测试批准"));
    commands.processAvailable(100);
    var refunded = orders.getForUser(user(), r.id());
    assertThat(refunded.status()).isEqualTo("REFUNDED");
    assertThat(refunded.appointment().reservation().status()).isEqualTo("RELEASED");
    assertThat(refunded.appointment().reservation().releaseReason()).isEqualTo("REFUNDED");
  }

  @Test
  void completionRefundAndCrossOrderCompletionKeysSerialize() throws Exception {
    var p = paid(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                try {
                  service.complete(
                      merchant(), p.id(), key(), p.appointment().reservation().version());
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException e) {
                  return false;
                }
              });
      var b =
          pool.submit(
              () -> {
                try {
                  afterSales.request(user(), p.id(), key(), new CreateAfterSaleRequest("并发测试退款"));
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException e) {
                  return false;
                }
              });
      assertThat(a.get(10, TimeUnit.SECONDS) ^ b.get(10, TimeUnit.SECONDS)).isTrue();
    }
    var a = paid(1);
    var b = paid(1);
    String k = key();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var x =
          pool.submit(
              () -> {
                try {
                  service.complete(merchant(), a.id(), k, a.appointment().reservation().version());
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException e) {
                  return false;
                }
              });
      var y =
          pool.submit(
              () -> {
                try {
                  service.complete(merchant(), b.id(), k, b.appointment().reservation().version());
                  return true;
                } catch (com.megagraph.shop.shared.ConflictException e) {
                  return false;
                }
              });
      assertThat(x.get(10, TimeUnit.SECONDS) ^ y.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            db.queryForObject(
                "select count(*) from appointment_complete_key where merchant_id=? and"
                    + " idempotency_key=?",
                Long.class,
                merchant(),
                k))
        .isEqualTo(1);
  }

  @Test
  void noModelUnifiedConversationHistoricalCompatibility() throws Exception {
    offer(1);
    var c = result(s(post("/api/shop/v1/guide/conversations")));
    var answer =
        result(
            s(post("/api/shop/v1/guide/conversations/" + c.get("id").asText() + "/messages"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + key() + "\",\"content\":\"我要固定时段测试预约\"}"));
    assertThat(answer.toString()).contains("appointmentOffers", "JAVA_APPOINTMENT");
    assertThat(
            result(s(get("/api/shop/v1/guide/conversations/" + c.get("id").asText()))).toString())
        .contains("appointmentOffers");
    assertThat(
            json.readValue(
                    "{\"answer\":\"旧历史\",\"products\":[],\"demoProductIds\":[]}",
                    com.megagraph.shop.guide.GuideChatResult.class)
                .appointmentOffers())
        .isEmpty();
    mvc.perform(get("/api/shop/v1/capabilities"))
        .andExpect(jsonPath("$.appointmentDeliveryAvailable").value(true))
        .andExpect(jsonPath("$.guideAvailable").value(false));
  }

  @Test
  void createHoldPaymentReleaseRefundAndCompletionOutboxRollback() throws Exception {
    long supply = db.queryForObject("select count(*) from appointment_offer", Long.class);
    fault.set("APPOINTMENT_OFFER_CREATED");
    try {
      assertThatThrownBy(() -> service.create(merchant(), key(), details(null)))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(db.queryForObject("select count(*) from appointment_offer", Long.class))
        .isEqualTo(supply);
    var offer = offer(2);
    String purchaseKey = key();
    fault.set("APPOINTMENT_HELD");
    try {
      assertThatThrownBy(() -> service.purchase(user(), purchaseKey, request(offer)))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(orderRepo.findByUserIdAndIdempotencyKey(user(), purchaseKey)).isEmpty();
    assertThat(store.publicOffer(offer.id()).slots().getFirst().remaining()).isEqualTo(2);
    var order = buy(offer);
    fault.set("APPOINTMENT_CONFIRMED");
    try {
      assertThatThrownBy(() -> pay(order)).isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(orders.getForUser(user(), order.id()).status()).isEqualTo("PENDING_PAYMENT");
    assertThat(store.reservation(order.id(), false).status()).isEqualTo("HELD");
    fault.set("APPOINTMENT_RELEASED");
    try {
      assertThatThrownBy(() -> orders.cancel(user(), order.id()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(orders.getForUser(user(), order.id()).status()).isEqualTo("PENDING_PAYMENT");
    assertThat(store.reservation(order.id(), false).status()).isEqualTo("HELD");
    var paid = pay(order);
    String completionKey = key();
    fault.set("APPOINTMENT_TEST_FULFILLED");
    try {
      assertThatThrownBy(
              () ->
                  service.complete(
                      merchant(),
                      paid.id(),
                      completionKey,
                      paid.appointment().reservation().version()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(store.reservation(paid.id(), false).status()).isEqualTo("CONFIRMED");
    assertThat(
            db.queryForObject(
                "select count(*) from appointment_complete_key where merchant_id=? and"
                    + " idempotency_key=?",
                Long.class,
                merchant(),
                completionKey))
        .isZero();
    afterSales.request(user(), paid.id(), key(), new CreateAfterSaleRequest("测试退款回滚"));
    afterSales.approve(merchant(), paid.id(), key(), new ResolveAfterSaleRequest("测试批准"));
    String refund = "appt-refund-" + paid.id();
    db.update("update refund_order set provider_refund_id=? where order_id=?", refund, paid.id());
    fault.set("APPOINTMENT_RELEASED");
    try {
      assertThatThrownBy(
              () -> event(key(), refund, "REFUND_SUCCEEDED", "REFUND", paid.totalAmount()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(orders.getForUser(user(), paid.id()).status()).isEqualTo("PAID");
    assertThat(store.reservation(paid.id(), false).status()).isEqualTo("CONFIRMED");
    event(key(), refund, "REFUND_SUCCEEDED", "REFUND", paid.totalAmount());
    assertThat(store.reservation(paid.id(), false).status()).isEqualTo("RELEASED");
  }

  @Test
  void staleSnapshotCapacityAndCompletionRefundGuardsUseCurrentFacts() throws Exception {
    var offer = offer(1);
    Long other = anotherUser();
    CountDownLatch ready = new CountDownLatch(1), go = new CountDownLatch(1);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var loser =
          pool.submit(
              () ->
                  new TransactionTemplate(tm)
                      .execute(
                          tx -> {
                            db.queryForObject(
                                "select count(*) from appointment_reservation", Long.class);
                            ready.countDown();
                            await(go);
                            return service.purchase(other, key(), request(offer));
                          }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      buy(offer);
      go.countDown();
      assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    }
    for (boolean completeWins : List.of(true, false)) {
      var paid = paid(1);
      CountDownLatch r = new CountDownLatch(1), g = new CountDownLatch(1);
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
                                  "select count(*) from appointment_reservation", Long.class);
                              r.countDown();
                              await(g);
                              return completeWins
                                  ? afterSales.request(
                                      user(), paid.id(), key(), new CreateAfterSaleRequest("测试旧快照"))
                                  : service.complete(
                                      merchant(),
                                      paid.id(),
                                      key(),
                                      paid.appointment().reservation().version());
                            }));
        assertThat(r.await(5, TimeUnit.SECONDS)).isTrue();
        if (completeWins)
          service.complete(
              merchant(), paid.id(), key(), paid.appointment().reservation().version());
        else afterSales.request(user(), paid.id(), key(), new CreateAfterSaleRequest("测试先申请"));
        g.countDown();
        assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(com.megagraph.shop.shared.ConflictException.class);
      }
    }
  }

  static void await(CountDownLatch l) {
    try {
      if (!l.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void merchantSharedParentLockDoesNotDeadlockRefundForeignKey() throws Exception {
    var offer = offer(2);
    var paid = pay(buy(offer));
    Long other = anotherUser();
    CountDownLatch purchaseAtSlot = new CountDownLatch(1);
    var delegate =
        org.mockito.Mockito.mockingDetails(store).getMockCreationSettings().getDefaultAnswer();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (Thread.currentThread().getName().equals("appointment-purchase-fk"))
                purchaseAtSlot.countDown();
              return delegate.answer(invocation);
            })
        .when(store)
        .currentSlot(offer.slots().getFirst().id(), offer.id());
    try (var pool =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "appointment-purchase-fk"))) {
      AtomicReference<Future<OrderResponse>> buyer = new AtomicReference<>();
      new TransactionTemplate(tm)
          .executeWithoutResult(
              tx -> {
                var order = orderRepo.findByIdForUpdate(paid.id()).orElseThrow();
                store.lockReservation(order);
                buyer.set(pool.submit(() -> service.purchase(other, key(), request(offer))));
                await(purchaseAtSlot);
                afterSales.request(user(), paid.id(), key(), new CreateAfterSaleRequest("测试共享父锁"));
              });
      assertThat(buyer.get().get(10, TimeUnit.SECONDS).appointment().reservation().status())
          .isEqualTo("HELD");
      assertThat(orders.getForUser(user(), paid.id()).afterSale().status()).isEqualTo("REQUESTED");
    }
  }

  @Test
  void expiryEachOrderTransactionCannotHoldSlotWhileWaitingNextOrder() throws Exception {
    var offer = offer(2);
    var a = buy(offer);
    var b = buy(offer);
    expired(a.id());
    expired(b.id());
    CountDownLatch paymentHasOrder = new CountDownLatch(1), firstRelease = new CountDownLatch(1);
    var delegate =
        org.mockito.Mockito.mockingDetails(store).getMockCreationSettings().getDefaultAnswer();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              SalesOrder o = invocation.getArgument(0);
              if (o.getId().equals(b.id())) paymentHasOrder.countDown();
              return delegate.answer(invocation);
            })
        .when(store)
        .canConfirm(org.mockito.ArgumentMatchers.any(SalesOrder.class));
    releaseHook.set(
        () -> {
          firstRelease.countDown();
          await(paymentHasOrder);
        });
    try (var pool = Executors.newFixedThreadPool(2)) {
      var scan = pool.submit(() -> orders.expireOverdueOrders());
      assertThat(firstRelease.await(5, TimeUnit.SECONDS)).isTrue();
      var callback =
          pool.submit(
              () ->
                  event(
                      key(),
                      b.payment().providerPaymentId(),
                      "PAYMENT_SUCCEEDED",
                      "PAYMENT",
                      b.totalAmount()));
      scan.get(10, TimeUnit.SECONDS);
      assertThat(callback.get(10, TimeUnit.SECONDS).result().status())
          .isEqualTo(ChannelEventProcessingStatus.REVIEW_REQUIRED);
    } finally {
      releaseHook.set(null);
    }
    assertThat(orders.getForUser(user(), a.id()).status()).isEqualTo("EXPIRED");
    assertThat(orders.getForUser(user(), b.id()).status()).isEqualTo("EXPIRED");
    assertThat(store.publicOffer(offer.id()).slots().getFirst().remaining()).isEqualTo(2);
  }

  @Test
  void staleExpiryCandidateDoesNotExpireAlreadyPaidOrder() throws Exception {
    var p = paid(1);
    org.mockito.Mockito.doReturn(List.of(p.id()))
        .when(orderRepo)
        .findOverdueIds(
            org.mockito.ArgumentMatchers.eq(OrderStatus.PENDING_PAYMENT),
            org.mockito.ArgumentMatchers.any(Instant.class));
    orders.expireOverdueOrders();
    assertThat(orders.getForUser(user(), p.id()).status()).isEqualTo("PAID");
    assertThat(store.reservation(p.id(), false).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void strictVersionRolesTenantAndCrossTypeKeys() throws Exception {
    var o = offer(1);
    for (String version : List.of("1.9", "18446744073709551616"))
      mvc.perform(
              m(post("/api/merchant/v1/appointment-offers/" + o.id() + "/off-shelf"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":" + version + "}"))
          .andExpect(status().isBadRequest());
    var p = pay(buy(o));
    String name = "appt-editor-" + key() + "@test.local";
    var editor =
        users.saveAndFlush(
            new com.megagraph.shop.identity.UserAccount(
                name, encoder.encode("appt-test-only"), "测试编辑"));
    memberships.saveAndFlush(
        new com.megagraph.shop.identity.MerchantUser(
            merchant(), editor.getId(), com.megagraph.shop.identity.MerchantRole.EDITOR));
    mvc.perform(
            post("/api/merchant/v1/orders/" + p.id() + "/appointment-complete")
                .with(httpBasic(name, "appt-test-only"))
                .header("X-Merchant-Id", merchant())
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isForbidden());
    Long other =
        merchants.saveAndFlush(new com.megagraph.shop.identity.Merchant("另一测试预约商家")).getId();
    memberships.saveAndFlush(
        new com.megagraph.shop.identity.MerchantUser(
            other, editor.getId(), com.megagraph.shop.identity.MerchantRole.OWNER));
    mvc.perform(
            post("/api/merchant/v1/orders/" + p.id() + "/appointment-complete")
                .with(httpBasic(name, "appt-test-only"))
                .header("X-Merchant-Id", other)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isNotFound());
    mvc.perform(m(get("/api/shop/v1/orders/" + p.id()))).andExpect(status().isNotFound());
    String k = key();
    var open = offer(2);
    service.purchase(user(), k, request(open));
    assertThatThrownBy(
            () -> orders.create(user(), k, new CreateOrderRequest("测试", "00000000000", "测试")))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    String physicalKey = key();
    orderRepo.saveAndFlush(
        new SalesOrder(
            "APPT-PHY-" + key().substring(0, 20),
            user(),
            merchant(),
            physicalKey,
            "CNY",
            BigDecimal.ONE,
            "测试",
            "00000000000",
            "测试",
            future()));
    assertThatThrownBy(() -> service.purchase(user(), physicalKey, request(open)))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
  }

  @Autowired DigitalDeliveryService digital;

  @Test
  void existingAppointmentMutexSameUserDigitalPurchaseRespectsSharedParentOrdering()
      throws Exception {
    var appointment = offer(2);
    var first = buy(appointment);
    orders.cancel(user(), first.id());
    assertThat(
            db.queryForObject(
                "select count(*) from appointment_account_lock where user_id=?",
                Long.class,
                user()))
        .isEqualTo(1);
    var d =
        digital.create(
            merchant(),
            key(),
            new DeliveryOfferRequest(
                null, "DIGITAL_FILE", "自建测试文件", "仅测试资料", new BigDecimal("9.90"), "CNY"));
    d = digital.transition(merchant(), d.id(), d.version(), "confirm");
    d = digital.transition(merchant(), d.id(), d.version(), "publish");
    var digitalOffer = d;
    try (var pool = Executors.newSingleThreadExecutor()) {
      AtomicReference<Future<OrderResponse>> appt = new AtomicReference<>();
      CountDownLatch started = new CountDownLatch(1);
      var result =
          new TransactionTemplate(tm)
              .execute(
                  tx -> {
                    db.queryForObject(
                        "select id from app_user where id=? for update", Long.class, user());
                    appt.set(
                        pool.submit(
                            () -> {
                              started.countDown();
                              return service.purchase(user(), key(), request(appointment));
                            }));
                    await(started);
                    assertThatThrownBy(() -> appt.get().get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                    return digital.purchase(
                        user(),
                        key(),
                        new DeliveryOrderRequest(
                            List.of(
                                Map.of(
                                    "offerId",
                                    digitalOffer.id(),
                                    "quantity",
                                    1,
                                    "expectedOfferVersion",
                                    digitalOffer.version()))));
                  });
      assertThat(result.deliveryType()).isEqualTo("DIGITAL_FILE");
      assertThat(appt.get().get(10, TimeUnit.SECONDS).deliveryType()).isEqualTo("APPOINTMENT");
    }
  }

  @Test
  void pendingAndFailedRefundBlockCompletionAndDeliveryTypesCannotShareKey() throws Exception {
    var p = paid(1);
    afterSales.request(user(), p.id(), key(), new CreateAfterSaleRequest("测试失败退款"));
    afterSales.approve(merchant(), p.id(), key(), new ResolveAfterSaleRequest("测试批准"));
    assertThatThrownBy(
            () ->
                service.complete(
                    merchant(), p.id(), key(), p.appointment().reservation().version()))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    String refund = "appt-failed-" + p.id();
    db.update("update refund_order set provider_refund_id=? where order_id=?", refund, p.id());
    event(key(), refund, "REFUND_FAILED", "REFUND", p.totalAmount());
    assertThat(orders.getForUser(user(), p.id()).appointment().completeAvailable()).isFalse();
    assertThatThrownBy(
            () ->
                service.complete(
                    merchant(), p.id(), key(), p.appointment().reservation().version()))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    var appointment = offer(2);
    var d =
        digital.create(
            merchant(),
            key(),
            new DeliveryOfferRequest(
                null, "DIGITAL_FILE", "跨类型测试文件", "自建测试资料", new BigDecimal("9.90"), "CNY"));
    d = digital.transition(merchant(), d.id(), d.version(), "confirm");
    d = digital.transition(merchant(), d.id(), d.version(), "publish");
    var req =
        new DeliveryOrderRequest(
            List.of(Map.of("offerId", d.id(), "quantity", 1, "expectedOfferVersion", d.version())));
    String dk = key();
    digital.purchase(user(), dk, req);
    assertThatThrownBy(() -> service.purchase(user(), dk, request(appointment)))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
    String ak = key();
    service.purchase(user(), ak, request(appointment));
    assertThatThrownBy(() -> digital.purchase(user(), ak, req))
        .isInstanceOf(com.megagraph.shop.shared.ConflictException.class);
  }

  @Test
  void startDeadlineAndDurationBoundaryAndLateReleaseFaultRollback() throws Exception {
    var o = service.create(merchant(), key(), details(null));
    Instant start = future();
    var slots =
        service.addSlot(
            merchant(),
            o.id(),
            key(),
            new AppointmentSlotRequest(0L, start, start.plus(Duration.ofHours(4)), 1));
    assertThat(slots.slots().getFirst().endAt()).isEqualTo(start.plus(Duration.ofHours(4)));
    assertThatThrownBy(
            () ->
                service.addSlot(
                    merchant(),
                    o.id(),
                    key(),
                    new AppointmentSlotRequest(
                        slots.version(), start, start.plus(Duration.ofHours(4)).plusMillis(1), 1)))
        .isInstanceOf(com.megagraph.shop.shared.DomainException.class);
    var near = service.create(merchant(), key(), details(null));
    Instant soon = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
    near =
        service.addSlot(
            merchant(),
            near.id(),
            key(),
            new AppointmentSlotRequest(near.version(), soon, soon.plusSeconds(3600), 1));
    near = service.transition(merchant(), near.id(), near.version(), "confirm");
    near = service.transition(merchant(), near.id(), near.version(), "publish");
    var order = buy(near);
    assertThat(order.expiresAt()).isEqualTo(soon);
    assertThat(order.appointment().reservation().expiresAt()).isEqualTo(soon);
    expired(order.id());
    fault.set("APPOINTMENT_RELEASED");
    try {
      assertThatThrownBy(
              () ->
                  event(
                      key(),
                      order.payment().providerPaymentId(),
                      "PAYMENT_SUCCEEDED",
                      "PAYMENT",
                      order.totalAmount()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      fault.set(null);
    }
    assertThat(orders.getForUser(user(), order.id()).status()).isEqualTo("PENDING_PAYMENT");
    assertThat(store.reservation(order.id(), false).status()).isEqualTo("HELD");
    assertThat(
            event(
                    key(),
                    order.payment().providerPaymentId(),
                    "PAYMENT_SUCCEEDED",
                    "PAYMENT",
                    order.totalAmount())
                .result()
                .status())
        .isEqualTo(ChannelEventProcessingStatus.REVIEW_REQUIRED);
    assertThat(store.reservation(order.id(), false).status()).isEqualTo("RELEASED");
  }
}
