package com.megagraph.shop.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.payment.OutboxFaultInjector;
import com.megagraph.shop.shared.DomainException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "guide"})
@TestPropertySource(properties = {"GUIDE_MODEL_API_KEY=test-only-not-a-real-key", "GUIDE_MODEL_NAME=test-only-model",
        "app.security.csrf-enabled=true"})
class GuideConversationIntegrationTest {
    private static final String ROOT = "/api/shop/v1/guide/conversations";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired GuideConversationStore store;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GuideModel model;
    @MockitoBean PublicCatalogService catalog;
    @MockitoBean OutboxFaultInjector faults;

    @BeforeEach
    void emptyCatalog() {
        when(catalog.list()).thenReturn(List.of());
        when(model.chat(anyList(), anyList(), any(GuideIntent.class)))
                .thenReturn(new GuideModel.Selection("", List.of()));
    }

    @Test
    void persistenceRequiresLoginAndWriteCsrfEvenWhenPublicChatIsAvailable() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT).with(httpBasic("shopper@megagraph.local", "shopper123"))).andExpect(status().isForbidden());
        var result = create();
        assertThat(result.path("requests")).isEmpty();
        mvc.perform(get(ROOT + "/" + result.path("id").asText()).with(httpBasic("shopper@megagraph.local", "shopper123")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"));
    }

    @Test
    void jdbcLoginSessionRestoresConversationAndLogoutRevokesAccess() throws Exception {
        var csrfResponse = mvc.perform(get("/api/auth/v1/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String token = json.readTree(csrfResponse.getContentAsString()).path("token").asText();
        var loginResponse = mvc.perform(post("/api/auth/v1/login").cookie(csrfResponse.getCookie("SESSION"))
                .header("X-CSRF-TOKEN", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"shopper@megagraph.local\",\"password\":\"shopper123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var cookie = loginResponse.getCookie("SESSION");
        assertThat(cookie).isNotNull();
        var authenticatedCsrf = mvc.perform(get("/api/auth/v1/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();
        String authenticatedToken = json.readTree(authenticatedCsrf.getContentAsString()).path("token").asText();
        JsonNode conversation = json.readTree(mvc.perform(post(ROOT).cookie(cookie).header("X-CSRF-TOKEN", authenticatedToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String id = conversation.path("id").asText();
        mvc.perform(post(ROOT + "/" + id + "/messages").cookie(cookie).header("X-CSRF-TOKEN", authenticatedToken)
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "账号会话测试")))
                .andExpect(status().isOk());
        mvc.perform(get(ROOT + "/" + id).cookie(cookie)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.requests.length()").value(1));
        assertThat(jdbc.queryForObject("select count(*) from SPRING_SESSION where PRINCIPAL_NAME=?", Long.class, "shopper@megagraph.local"))
                .isGreaterThan(0);
        mvc.perform(post("/api/auth/v1/logout").cookie(cookie).header("X-CSRF-TOKEN", authenticatedToken)).andExpect(status().isNoContent());
        mvc.perform(get(ROOT + "/" + id).cookie(cookie)).andExpect(status().isUnauthorized());
        assertThat(store.get(owner(), id).requests()).hasSize(1);
    }

    @Test
    void anotherAccountCannotReadSendRetryOrEnumerateOwnedConversations() throws Exception {
        String id = create().path("id").asText();
        var other = httpBasic("merchant@megagraph.local", "merchant123");
        mvc.perform(get(ROOT + "/" + id).with(other)).andExpect(status().isNotFound());
        mvc.perform(post(ROOT + "/" + id + "/messages").with(other).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID().toString(), "找纸本"))).andExpect(status().isNotFound());
        mvc.perform(post(ROOT + "/" + id + "/requests/" + UUID.randomUUID() + "/retry").with(other).with(csrf()))
                .andExpect(status().isNotFound());
        JsonNode list = json.readTree(mvc.perform(get(ROOT).with(other)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.findValuesAsText("id")).doesNotContain(id);
        verify(model, times(0)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void repeatedRequestReplaysSavedResultAndRejectsDifferentContent() throws Exception {
        String id = create().path("id").asText();
        String requestId = UUID.randomUUID().toString();
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new GuideModel.Selection("", List.of());
        });
        JsonNode first = send(id, requestId, "找纸本");
        JsonNode again = send(id, requestId, "找纸本");
        assertThat(first.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(again).isEqualTo(first);
        mvc.perform(post(ROOT + "/" + id + "/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(requestId, "找袋子")))
                .andExpect(status().isConflict());
        assertThat(store.get(owner(), id).requests()).hasSize(1);
        verify(model, times(1)).chat(anyList(), anyList(), any(GuideIntent.class));
        assertThat(jdbc.queryForObject("select model_version from guide_turn_request where request_id=?", String.class, requestId))
                .isEqualTo("test-only-model");
    }

    @Test
    void pendingReplayCannotStartAnotherTurnAndDoesNotExecuteTwice() {
        String id = store.create(owner()).id();
        String key = UUID.randomUUID().toString();
        assertThat(store.begin(owner(), id, key, "找纸本").execute()).isTrue();
        assertThat(store.begin(owner(), id, key, "找纸本").execute()).isFalse();
        assertThat(store.retry(owner(), id, key).execute()).isFalse();
        assertThatThrownBy(() -> store.begin(owner(), id, UUID.randomUUID().toString(), "新需求"))
                .isInstanceOf(DomainException.class).hasMessageContaining("仍在处理");
        assertThat(store.get(owner(), id).requests()).hasSize(1);
    }

    @Test
    void simultaneousDifferentRequestsSerializeToOnePendingTurn() throws Exception {
        String id = store.create(owner()).id();
        Long ownerId = owner();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> task = () -> {
                ready.countDown();
                start.await();
                try {
                    store.begin(ownerId, id, UUID.randomUUID().toString(), "并发测试");
                    return "STARTED";
                } catch (DomainException error) {
                    return error.getCode();
                }
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("STARTED", "GUIDE_CONVERSATION_BUSY");
        }
        assertThat(store.get(owner(), id).requests()).hasSize(1);
    }

    @Test
    void failureIsSafeAndExplicitRetryReusesTheUserTurn() throws Exception {
        String id = create().path("id").asText();
        String key = UUID.randomUUID().toString();
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenThrow(new IllegalStateException("private provider detail"));
        var failed = send(id, key, "找纸本");
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("error").path("code").asText()).isEqualTo("GUIDE_MODEL_FAILED");
        assertThat(failed.toString()).doesNotContain("private provider detail");
        when(model.chat(anyList(), anyList(), any(GuideIntent.class))).thenReturn(new GuideModel.Selection("", List.of()));
        JsonNode retried = json.readTree(mvc.perform(post(ROOT + "/" + id + "/requests/" + key + "/retry")
                .with(httpBasic("shopper@megagraph.local", "shopper123")).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(retried.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(retried.path("attempt").asInt()).isEqualTo(2);
        assertThat(store.get(owner(), id).requests()).hasSize(1);
        verify(model, times(2)).chat(anyList(), anyList(), any(GuideIntent.class));
    }

    @Test
    void expiredAttemptIsRecoverableAndLateResultCannotOverwriteRetry() {
        String id = store.create(owner()).id();
        String key = UUID.randomUUID().toString();
        store.begin(owner(), id, key, "找纸本");
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("update guide_turn_request set lease_expires_at=? where request_id=?");
            statement.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
            statement.setString(2, key);
            return statement;
        });
        assertThat(store.get(owner(), id).requests().getFirst().error().code()).isEqualTo("GUIDE_WAIT_EXPIRED");
        var retry = store.retry(owner(), id, key);
        assertThat(retry.execute()).isTrue();
        assertThat(retry.request().attempt()).isEqualTo(2);
        var stale = store.finish(owner(), id, key, 1, result("旧回复"), null);
        assertThat(stale.status()).isEqualTo("PENDING");
        assertThat(stale.result()).isNull();
        var current = store.finish(owner(), id, key, 2, result("新回复"), null);
        assertThat(current.result().answer()).isEqualTo("新回复");
        assertThat(store.get(owner(), id).requests()).hasSize(1);
    }

    @Test
    void olderFailedTurnCannotBeRetriedAfterNewMessages() {
        String id = store.create(owner()).id();
        String key = UUID.randomUUID().toString();
        store.begin(owner(), id, key, "旧需求");
        store.finish(owner(), id, key, 1, null, new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_MODEL_FAILED", "失败"));
        var next = store.begin(owner(), id, UUID.randomUUID().toString(), "新需求");
        store.finish(owner(), id, next.request().requestId(), 1, result("新答复"), null);
        assertThatThrownBy(() -> store.retry(owner(), id, key)).isInstanceOf(DomainException.class).hasMessageContaining("后续已有");
    }

    @Test
    void historyIsServerOwnedAndBoundedWithoutDuplicatingCurrentUserTurn() {
        String id = store.create(owner()).id();
        for (int index = 0; index < 11; index++) {
            var work = store.begin(owner(), id, UUID.randomUUID().toString(), "用户".repeat(900));
            store.finish(owner(), id, work.request().requestId(), 1, result("答复".repeat(900)), null);
        }
        var next = store.begin(owner(), id, UUID.randomUUID().toString(), "最后需求");
        assertThat(next.turns().size()).isLessThanOrEqualTo(19);
        assertThat(next.turns().stream().mapToInt(t -> t.content().length()).sum()).isLessThanOrEqualTo(12000);
        assertThat(next.turns().getLast().role()).isEqualTo("user");
        assertThat(next.turns().getLast().content()).isEqualTo("最后需求");
        assertThat(next.turns().stream().filter(t -> t.content().equals("最后需求"))).hasSize(1);
    }

    @Test
    void startFailureRollsBackRequestSequenceAndOutboxWithoutLeakingChatInEvents() {
        String id = store.create(owner()).id();
        doThrow(new IllegalStateException("injected outbox fault")).when(faults).beforeSave("GUIDE_REQUEST_STARTED");
        assertThatThrownBy(() -> store.begin(owner(), id, UUID.randomUUID().toString(), "私密测试文本"))
                .isInstanceOf(IllegalStateException.class);
        reset(faults);
        assertThat(store.get(owner(), id).requests()).isEmpty();
        assertThat(jdbc.queryForObject("select next_sequence from guide_conversation where public_id=?", Long.class, id)).isEqualTo(1L);
        var work = store.begin(owner(), id, UUID.randomUUID().toString(), "私密测试文本");
        assertThat(work.request().sequence()).isEqualTo(1);
        List<String> events = jdbc.queryForList("select payload_json from outbox_event where aggregate_type='GUIDE_CONVERSATION'", String.class);
        assertThat(events).allSatisfy(payload -> assertThat(payload).doesNotContain("私密测试文本", "userContent", "ownerUserId", "result"));
    }

    @Test
    void completionFailureRollsBackResultAndLeavesPendingForRecovery() {
        String id = store.create(owner()).id();
        String key = UUID.randomUUID().toString();
        store.begin(owner(), id, key, "找纸本");
        doThrow(new IllegalStateException("injected outbox fault")).when(faults).beforeSave("GUIDE_REQUEST_COMPLETED");
        assertThatThrownBy(() -> store.finish(owner(), id, key, 1, result("已核验结果"), null)).isInstanceOf(IllegalStateException.class);
        reset(faults);
        var saved = store.get(owner(), id).requests().getFirst();
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(saved.result()).isNull();
    }

    private Long owner() { return users.findByUsernameIgnoreCase("shopper@megagraph.local").orElseThrow().getId(); }
    private GuideChatResult result(String answer) { return new GuideChatResult(answer, List.of(), List.of(), "通用建议", List.of()); }
    private String body(String id, String content) throws Exception { return json.writeValueAsString(Map.of("requestId", id, "content", content)); }
    private JsonNode create() throws Exception {
        return json.readTree(mvc.perform(post(ROOT).with(httpBasic("shopper@megagraph.local", "shopper123")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode send(String id, String key, String content) throws Exception {
        return json.readTree(mvc.perform(post(ROOT + "/" + id + "/messages").with(httpBasic("shopper@megagraph.local", "shopper123"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(key, content)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
