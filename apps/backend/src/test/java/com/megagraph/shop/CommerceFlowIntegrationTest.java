package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.megagraph.shop.payment.MockChannelCommandJob;
import com.megagraph.shop.payment.CallbackIngressResult;
import com.megagraph.shop.payment.ChannelEventType;
import com.megagraph.shop.payment.ChannelResourceType;
import com.megagraph.shop.payment.MockPaymentCallbackAdapter;
import com.megagraph.shop.payment.PaymentCallbackIngress;
import com.megagraph.shop.payment.OutboxFaultInjector;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CommerceFlowIntegrationTest.FaultInjectionConfig.class)
class CommerceFlowIntegrationTest {
    @Autowired private com.megagraph.shop.ingestion.IngestionWorker ingestionWorker;

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

    @Autowired
    private MockChannelCommandJob mockCommands;

    @Autowired
    private MockPaymentCallbackAdapter mockCallbackAdapter;

    @Autowired
    private PaymentCallbackIngress callbackIngress;

    @Autowired
    private TestOutboxFaultInjector outboxFaults;

    @Test
    void mockPaymentIsAcceptedAsProcessingBeforeDurableCommandAppliesTerminalState() throws Exception {
        PublishedSku published = publishProduct("异步支付处理中", "ASYNC-" + UUID.randomUUID(), 109.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("async-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();

        mvc.perform(post("/api/shop/v1/orders/{orderId}/mock-payment", orderId)
                        .with(shopper())
                        .header("Idempotency-Key", "async-payment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.status").value("PROCESSING"))
                .andExpect(jsonPath("$.reservations[0].status").value("RESERVED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_channel_command WHERE status = 'PENDING' AND payment_order_id = (SELECT id FROM payment_order WHERE order_id = ?)",
                Integer.class,
                orderId
        )).isEqualTo(1);

        jdbcTemplate.update("""
                UPDATE payment_channel_command
                SET status = 'PROCESSING', attempt_count = 1,
                    claimed_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP)
                WHERE payment_order_id = (SELECT id FROM payment_order WHERE order_id = ?)
                """, orderId);

        assertThat(mockCommands.processAvailable(10)).isEqualTo(1);
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reservations[0].status").value("CONFIRMED"));
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT CONCAT(status, ':', attempt_count)
                FROM payment_channel_command
                WHERE payment_order_id = (SELECT id FROM payment_order WHERE order_id = ?)
                """,
                String.class,
                orderId
        )).isEqualTo("COMPLETED:2");
    }

    @Test
    void signedCallbackIsIdempotentAndPayloadMismatchIsRecordedOnce() throws Exception {
        PublishedSku published = publishProduct("回调去重", "CALLBACK-" + UUID.randomUUID(), 119.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("callback-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        String resourceId = created.at("/payment/providerPaymentId").asText();
        String eventId = "evt_" + UUID.randomUUID();
        byte[] original = mockCallbackBytes(
                eventId,
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                resourceId,
                created.required("totalAmount").decimalValue(),
                "CNY"
        );

        postSignedCallback(original)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.processingStatus").value("APPLIED"))
                .andExpect(jsonPath("$.duplicate").value(false));
        postSignedCallback(original)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        byte[] conflicting = mockCallbackBytes(
                eventId,
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                resourceId,
                created.required("totalAmount").decimalValue().add(BigDecimal.ONE),
                "CNY"
        );
        postSignedCallback(conflicting)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ID_PAYLOAD_MISMATCH"));
        postSignedCallback(conflicting)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ID_PAYLOAD_MISMATCH"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT conflict_count FROM payment_channel_event WHERE provider = 'MOCK' AND provider_event_id = ?",
                Integer.class,
                eventId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_type = 'CHANNEL_EVENT' AND event_type = 'PAYMENT_RECONCILIATION_REQUIRED' AND aggregate_id = (SELECT id FROM payment_channel_event WHERE provider = 'MOCK' AND provider_event_id = ?)",
                Integer.class,
                eventId
        )).isEqualTo(1);

        String contradictoryEventId = "evt_" + UUID.randomUUID();
        postSignedCallback(mockCallbackBytes(
                contradictoryEventId,
                ChannelEventType.PAYMENT_FAILED,
                ChannelResourceType.PAYMENT,
                resourceId,
                created.required("totalAmount").decimalValue(),
                "CNY"
        )).andExpect(status().isOk()).andExpect(jsonPath("$.processingStatus").value("REVIEW_REQUIRED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_code FROM payment_channel_event WHERE provider = 'MOCK' AND provider_event_id = ?",
                String.class,
                contradictoryEventId
        )).isEqualTo("EVENT_STATE_CONFLICT");
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
    }

    @Test
    void callbackBoundaryRejectsUnknownUnsignedInvalidAndOversizedPayloads() throws Exception {
        byte[] validShape = mockCallbackBytes(
                "evt_" + UUID.randomUUID(),
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                "missing_resource",
                BigDecimal.ONE,
                "CNY"
        );
        mvc.perform(post("/api/payment-callbacks/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validShape))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CALLBACK_PROVIDER_UNKNOWN"));
        mvc.perform(post("/api/payment-callbacks/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validShape))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CALLBACK_SIGNATURE_MISSING"));
        mvc.perform(post("/api/payment-callbacks/mock")
                        .header(MockPaymentCallbackAdapter.SIGNATURE_HEADER, "00")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validShape))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CALLBACK_SIGNATURE_INVALID"));
        byte[] malformed = "not-json".getBytes();
        mvc.perform(post("/api/payment-callbacks/mock")
                        .header(MockPaymentCallbackAdapter.SIGNATURE_HEADER, mockCallbackAdapter.sign(malformed))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CALLBACK_PAYLOAD_INVALID"));
        byte[] oversized = new byte[17000];
        mvc.perform(post("/api/payment-callbacks/mock")
                        .header(MockPaymentCallbackAdapter.SIGNATURE_HEADER, mockCallbackAdapter.sign(oversized))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("CALLBACK_PAYLOAD_TOO_LARGE"));
    }

    @Test
    void callbackFactMismatchesRequireReviewWithoutChangingPayment() throws Exception {
        PublishedSku published = publishProduct("回调事实校验", "FACTS-" + UUID.randomUUID(), 139.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("facts-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        String resourceId = created.at("/payment/providerPaymentId").asText();
        BigDecimal amount = created.required("totalAmount").decimalValue();

        String amountEvent = "evt_" + UUID.randomUUID();
        postSignedCallback(mockCallbackBytes(
                amountEvent,
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                resourceId,
                amount.add(BigDecimal.ONE),
                "CNY"
        )).andExpect(status().isOk()).andExpect(jsonPath("$.processingStatus").value("REVIEW_REQUIRED"));
        String currencyEvent = "evt_" + UUID.randomUUID();
        postSignedCallback(mockCallbackBytes(
                currencyEvent,
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                resourceId,
                amount,
                "USD"
        )).andExpect(status().isOk()).andExpect(jsonPath("$.processingStatus").value("REVIEW_REQUIRED"));
        String resourceEvent = "evt_" + UUID.randomUUID();
        postSignedCallback(mockCallbackBytes(
                resourceEvent,
                ChannelEventType.REFUND_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                resourceId,
                amount,
                "CNY"
        )).andExpect(status().isOk()).andExpect(jsonPath("$.processingStatus").value("REVIEW_REQUIRED"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT failure_code FROM payment_channel_event WHERE provider_event_id IN (?, ?, ?) ORDER BY failure_code",
                String.class,
                amountEvent,
                currencyEvent,
                resourceEvent
        )).containsExactlyInAnyOrder(
                "EVENT_AMOUNT_MISMATCH",
                "EVENT_CURRENCY_MISMATCH",
                "EVENT_RESOURCE_MISMATCH"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_type = 'CHANNEL_EVENT' AND event_type = 'PAYMENT_RECONCILIATION_REQUIRED' AND aggregate_id IN (SELECT id FROM payment_channel_event WHERE provider_event_id IN (?, ?, ?))",
                Integer.class,
                amountEvent,
                currencyEvent,
                resourceEvent
        )).isEqualTo(3);
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.reservations[0].status").value("RESERVED"));
    }

    @Test
    void latePaymentSuccessRequiresReviewWithoutRestoringReleasedInventory() throws Exception {
        PublishedSku published = publishProduct("晚到支付成功", "LATE-" + UUID.randomUUID(), 79.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("late-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        String resourceId = created.at("/payment/providerPaymentId").asText();
        jdbcTemplate.update("UPDATE sales_order SET expires_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE id = ?", orderId);
        orderService.expireOverdueOrders();

        byte[] callback = mockCallbackBytes(
                "evt_" + UUID.randomUUID(),
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                resourceId,
                created.required("totalAmount").decimalValue(),
                "CNY"
        );
        postSignedCallback(callback)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("REVIEW_REQUIRED"));
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.payment.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reservations[0].status").value("RELEASED"));
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(2));
    }

    @Test
    void concurrentDuplicateCallbackCreatesOneInboxAndOneBusinessTransition() throws Exception {
        PublishedSku published = publishProduct("并发回调", "CONCURRENT-" + UUID.randomUUID(), 69.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("concurrent-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        String eventId = "evt_" + UUID.randomUUID();
        byte[] callback = mockCallbackBytes(
                eventId,
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                created.at("/payment/providerPaymentId").asText(),
                created.required("totalAmount").decimalValue(),
                "CNY"
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set(MockPaymentCallbackAdapter.SIGNATURE_HEADER, mockCallbackAdapter.sign(callback));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CallbackIngressResult>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index += 1) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return callbackIngress.receive("mock", headers, callback);
                }));
            }
            start.countDown();
            assertThat(futures.get(0).get().result().status().name()).isEqualTo("APPLIED");
            assertThat(futures.get(1).get().result().status().name()).isEqualTo("APPLIED");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_channel_event WHERE provider = 'MOCK' AND provider_event_id = ?",
                Integer.class,
                eventId
        )).isEqualTo(1);
        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER", orderId))
                .extracting("eventType")
                .containsExactly("ORDER_CREATED", "ORDER_PAID");
    }

    @Test
    void refundFailureDoesNotRestockAndOwnerCanRetryIdempotently() throws Exception {
        PublishedSku published = publishProduct("退款失败重试", "REFUND-RETRY-" + UUID.randomUUID(), 149.00, 3);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("refund-retry-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        applyMockPayment(orderId, "refund-retry-payment-" + UUID.randomUUID(), "SUCCESS");
        requestRefund(orderId, "refund-retry-request-" + UUID.randomUUID(), "测试退款失败");

        JsonNode processing = responseJson(mvc.perform(post(
                                "/api/merchant/v1/orders/{orderId}/after-sales/approve",
                                orderId
                        )
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", "refund-first-attempt-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"同意退款但渠道失败\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.afterSale.status").value("REFUND_PROCESSING"))
                .andExpect(jsonPath("$.afterSale.refund.status").value("PENDING"))
                .andReturn());
        long refundId = processing.at("/afterSale/refund/id").asLong();
        String providerRefundId = "mock_refund_manual_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("UPDATE refund_order SET provider_refund_id = ? WHERE id = ?", providerRefundId, refundId);
        jdbcTemplate.update("UPDATE payment_channel_command SET status = 'FAILED' WHERE refund_order_id = ?", refundId);

        byte[] failed = mockCallbackBytes(
                "evt_" + UUID.randomUUID(),
                ChannelEventType.REFUND_FAILED,
                ChannelResourceType.REFUND,
                providerRefundId,
                processing.required("totalAmount").decimalValue(),
                "CNY"
        );
        postSignedCallback(failed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("APPLIED"));
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.reservations[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.afterSale.status").value("REFUND_FAILED"))
                .andExpect(jsonPath("$.afterSale.refund.status").value("FAILED"))
                .andExpect(jsonPath("$.afterSale.refund.failureCode").value("CHANNEL_REFUND_FAILED"));
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(1));
        mvc.perform(post("/api/merchant/v1/orders/{orderId}/ship", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", "ship-refund-failed-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"BLOCKED-REFUND-FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_HAS_PENDING_AFTER_SALE"));

        String retryKey = "refund-retry-" + UUID.randomUUID();
        mvc.perform(post("/api/merchant/v1/orders/{orderId}/after-sales/refund-retry", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", retryKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"重新提交退款\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afterSale.status").value("REFUND_PROCESSING"))
                .andExpect(jsonPath("$.afterSale.refund.status").value("PENDING"));
        mockCommands.processAvailable(10);
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"))
                .andExpect(jsonPath("$.reservations[0].status").value("RESTOCKED"))
                .andExpect(jsonPath("$.afterSale.status").value("REFUNDED"))
                .andExpect(jsonPath("$.afterSale.refund.status").value("SUCCEEDED"));
        mvc.perform(post("/api/merchant/v1/orders/{orderId}/after-sales/refund-retry", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", retryKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"重复重试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afterSale.refund.status").value("SUCCEEDED"));
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(3));
    }

    @Test
    void outboxFailureRollsBackInboxAndBusinessTransitionTogether() throws Exception {
        PublishedSku published = publishProduct("Outbox 原子性", "OUTBOX-" + UUID.randomUUID(), 99.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("outbox-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        String eventId = "evt_" + UUID.randomUUID();
        byte[] callback = mockCallbackBytes(
                eventId,
                ChannelEventType.PAYMENT_SUCCEEDED,
                ChannelResourceType.PAYMENT,
                created.at("/payment/providerPaymentId").asText(),
                created.required("totalAmount").decimalValue(),
                "CNY"
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set(MockPaymentCallbackAdapter.SIGNATURE_HEADER, mockCallbackAdapter.sign(callback));
        outboxFaults.failNext("PAYMENT_SUCCEEDED");
        assertThatThrownBy(() -> callbackIngress.receive("mock", headers, callback))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Injected Outbox failure");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM payment_channel_event WHERE provider = 'MOCK' AND provider_event_id = ?",
                String.class,
                eventId
        )).isEqualTo("RECEIVED");
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.reservations[0].status").value("RESERVED"));

        CallbackIngressResult retried = callbackIngress.receive("mock", headers, callback);
        assertThat(retried.duplicate()).isTrue();
        assertThat(retried.result().status().name()).isEqualTo("APPLIED");
        mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId).with(shopper()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
    }

    @TestConfiguration
    static class FaultInjectionConfig {
        @Bean
        TestOutboxFaultInjector testOutboxFaultInjector() {
            return new TestOutboxFaultInjector();
        }
    }

    static class TestOutboxFaultInjector implements OutboxFaultInjector {
        private final AtomicReference<String> nextFailure = new AtomicReference<>();

        void failNext(String eventType) {
            nextFailure.set(eventType);
        }

        @Override
        public void beforeSave(String eventType) {
            if (eventType.equals(nextFailure.get()) && nextFailure.compareAndSet(eventType, null)) {
                throw new IllegalStateException("Injected Outbox failure for " + eventType);
            }
        }
    }

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
    void approvedRefundRestocksInventoryExactlyOnce() throws Exception {
        PublishedSku published = publishProduct("退款回库测试", "REFUND-" + UUID.randomUUID(), 159.00, 3);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("refund-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        applyMockPayment(orderId, "refund-payment-" + UUID.randomUUID(), "SUCCESS");
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(1));

        String requestKey = "refund-request-" + UUID.randomUUID();
        JsonNode requested = requestRefund(orderId, requestKey, "尺码不合适，尚未发货");
        assertThat(requested.at("/afterSale/status").asText()).isEqualTo("REQUESTED");
        JsonNode repeatedRequest = requestRefund(orderId, requestKey, "不应覆盖原因");
        assertThat(repeatedRequest.at("/afterSale/id").asLong())
                .isEqualTo(requested.at("/afterSale/id").asLong());
        assertThat(repeatedRequest.at("/afterSale/reason").asText()).isEqualTo("尺码不合适，尚未发货");

        mvc.perform(post("/api/merchant/v1/orders/{orderId}/ship", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", "blocked-shipment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF-REFUND-BLOCKED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_HAS_PENDING_AFTER_SALE"));

        String approvalKey = "refund-approval-" + UUID.randomUUID();
        JsonNode refunded = resolveRefund(orderId, "approve", approvalKey, "同意未发货退款");
        assertThat(refunded.required("status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.at("/payment/status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.at("/reservations/0/status").asText()).isEqualTo("RESTOCKED");
        assertThat(refunded.at("/afterSale/status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.at("/afterSale/refund/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(refunded.at("/afterSale/refund/amount").decimalValue()).isEqualByComparingTo("318.00");

        JsonNode repeatedApproval = resolveRefund(orderId, "approve", approvalKey, "重复调用");
        assertThat(repeatedApproval.at("/afterSale/refund/id").asLong())
                .isEqualTo(refunded.at("/afterSale/refund/id").asLong());
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(3));

        long afterSaleId = refunded.at("/afterSale/id").asLong();
        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("ORDER", orderId))
                .extracting("eventType")
                .containsExactly("ORDER_CREATED", "ORDER_PAID", "ORDER_REFUNDED");
        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("AFTER_SALE", afterSaleId))
                .extracting("eventType")
                .containsExactly("AFTER_SALE_REQUESTED", "AFTER_SALE_REFUNDED");
    }

    @Test
    void rejectedRefundLeavesPaidOrderShippable() throws Exception {
        PublishedSku published = publishProduct("退款驳回测试", "REJECT-" + UUID.randomUUID(), 129.00, 2);
        mvc.perform(put("/api/shop/v1/cart/items/{skuId}", published.skuId())
                        .with(shopper())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        JsonNode created = createOrder("reject-order-" + UUID.randomUUID());
        long orderId = created.required("id").asLong();
        applyMockPayment(orderId, "reject-payment-" + UUID.randomUUID(), "SUCCESS");
        JsonNode requested = requestRefund(
                orderId,
                "reject-request-" + UUID.randomUUID(),
                "不再需要"
        );
        long afterSaleId = requested.at("/afterSale/id").asLong();

        String rejectionKey = "refund-rejection-" + UUID.randomUUID();
        JsonNode rejected = resolveRefund(orderId, "reject", rejectionKey, "商品已进入出库处理");
        assertThat(rejected.required("status").asText()).isEqualTo("PAID");
        assertThat(rejected.at("/payment/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(rejected.at("/reservations/0/status").asText()).isEqualTo("CONFIRMED");
        assertThat(rejected.at("/afterSale/status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.at("/afterSale/refund").isNull()).isTrue();

        mvc.perform(post("/api/merchant/v1/orders/{orderId}/ship", orderId)
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", "shipment-after-rejection-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF-AFTER-REJECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(get("/api/shop/v1/products/{productId}", published.productId()))
                .andExpect(jsonPath("$.availableStock").value(1));
        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("AFTER_SALE", afterSaleId))
                .extracting("eventType")
                .containsExactly("AFTER_SALE_REQUESTED", "AFTER_SALE_REJECTED");
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

    private byte[] mockCallbackBytes(
            String eventId,
            ChannelEventType eventType,
            ChannelResourceType resourceType,
            String providerResourceId,
            BigDecimal amount,
            String currency
    ) throws Exception {
        return objectMapper.writeValueAsBytes(new MockPaymentCallbackAdapter.MockEventPayload(
                eventId,
                eventType,
                resourceType,
                providerResourceId,
                Instant.now(),
                amount,
                currency
        ));
    }

    private ResultActions postSignedCallback(byte[] rawBody) throws Exception {
        return mvc.perform(post("/api/payment-callbacks/mock")
                .header(MockPaymentCallbackAdapter.SIGNATURE_HEADER, mockCallbackAdapter.sign(rawBody))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody));
    }

    private JsonNode applyMockPayment(long orderId, String idempotencyKey, String result) throws Exception {
        responseJson(mvc.perform(post("/api/shop/v1/orders/{orderId}/mock-payment", orderId)
                        .with(shopper())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"" + result + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
        mockCommands.processAvailable(10);
        return responseJson(mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId)
                        .with(shopper()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode requestRefund(long orderId, String idempotencyKey, String reason) throws Exception {
        return responseJson(mvc.perform(post("/api/shop/v1/orders/{orderId}/after-sales", orderId)
                        .with(shopper())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode resolveRefund(
            long orderId,
            String action,
            String idempotencyKey,
            String note
    ) throws Exception {
        JsonNode response = responseJson(mvc.perform(post(
                                "/api/merchant/v1/orders/{orderId}/after-sales/{action}",
                                orderId,
                                action
                        )
                        .with(httpBasic(MERCHANT_USERNAME, MERCHANT_PASSWORD))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + note + "\"}"))
                .andExpect(status().isOk())
                .andReturn());
        if ("approve".equals(action)) {
            mockCommands.processAvailable(10);
            return responseJson(mvc.perform(get("/api/shop/v1/orders/{orderId}", orderId)
                            .with(shopper()))
                    .andExpect(status().isOk())
                    .andReturn());
        }
        return response;
    }

    private PublishedSku publishProduct(String title, String skuCode, double price, int stock) throws Exception {
        byte[] png = TestImages.png();
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
                .andExpect(status().isAccepted())
                .andReturn());
        assertThat(ingestion.path("status").asText()).isEqualTo("QUEUED");
        assertThat(ingestionWorker.runOnce()).isTrue();
        ingestion=merchantGet("/api/merchant/v1/ingestions/"+ingestion.path("id").asLong());
        assertThat(ingestion.path("status").asText()).isEqualTo("SUCCEEDED");
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
