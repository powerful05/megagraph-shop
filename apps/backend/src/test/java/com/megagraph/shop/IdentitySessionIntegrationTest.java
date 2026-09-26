package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.megagraph.shop.identity.TrialInviteService;
import com.megagraph.shop.identity.Merchant;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.identity.MerchantUser;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductRepository;
import com.megagraph.shop.catalog.ProductImage;
import com.megagraph.shop.catalog.ProductImageRepository;
import com.megagraph.shop.catalog.ImageRole;
import com.megagraph.shop.ingestion.IngestionJob;
import com.megagraph.shop.ingestion.IngestionJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.legacy-basic-enabled=false",
        "app.security.legacy-merchant-context-enabled=false",
        "app.security.csrf-enabled=true"
})
class IdentitySessionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TrialInviteService invites;
    @Autowired ObjectMapper mapper;
    @Autowired UserAccountRepository users;
    @Autowired MerchantRepository merchants;
    @Autowired MerchantUserRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired IngestionJobRepository jobs;

    @Test
    void browserIssuedCsrfTokenCanBeUsedForSessionLogin() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/v1/csrf"))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(csrf.getResponse().getContentAsString()).required("token").asText();
        var cookie = csrf.getResponse().getCookie("SESSION");
        assertThat(cookie).isNotNull();
        MvcResult login = mvc.perform(post("/api/auth/v1/login")
                        .cookie(cookie)
                        .header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"merchant@megagraph.local\",\"password\":\"merchant123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andReturn();
        assertThat(login.getResponse().getCookie("SESSION")).isNotNull();
        assertThat(login.getResponse().getCookie("SESSION").getValue()).isNotEqualTo(cookie.getValue());
    }

    @Test
    void invitationRegistrationSessionAndCsrfProtectPrivateApis() throws Exception {
        String invite = invites.issue(Duration.ofDays(1));
        mvc.perform(get("/api/auth/v1/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"));
        mvc.perform(post("/api/auth/v1/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(invite, "alice@example.test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mvc.perform(post("/api/auth/v1/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(invite, "Alice@Example.Test")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/v1/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(invite, "second@example.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITE_USED_OR_EXPIRED"));
        mvc.perform(get("/api/shop/v1/cart").with(httpBasic("alice@example.test", "password-123456")))
                .andExpect(status().isUnauthorized());

        MvcResult login = mvc.perform(post("/api/auth/v1/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ALICE@EXAMPLE.TEST\",\"password\":\"password-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(0))
                .andReturn();
        assertThat(login.getResponse().getCookie("SESSION")).isNotNull();
        mvc.perform(get("/api/auth/v1/me").cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice"));
        mvc.perform(get("/api/shop/v1/cart").cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isOk());
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE username_normalized = ?",
                "alice@example.test");
        mvc.perform(get("/api/auth/v1/me").cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shop/v1/cart").cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shop/v1/orders").cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/v1/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice@example.test\",\"password\":\"password-123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/v1/logout").cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/v1/logout").with(csrf())
                        .cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/v1/me").cookie(login.getResponse().getCookie("SESSION")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failedRegistrationDoesNotConsumeInvitation() throws Exception {
        String invite = invites.issue(Duration.ofHours(1));
        mvc.perform(post("/api/auth/v1/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(invite, "merchant@megagraph.local")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        mvc.perform(post("/api/auth/v1/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(invite, "recovered@example.test")))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentRedemptionCreatesOnlyOneAccount() throws Exception {
        String invite = invites.issue(Duration.ofHours(1));
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> registerWhenReleased(start, invite, "race-first@example.test"));
            var second = workers.submit(() -> registerWhenReleased(start, invite, "race-second@example.test"));
            start.countDown();
            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstResult, secondResult).stream()
                    .filter(result -> result instanceof com.megagraph.shop.identity.UserAccount)).hasSize(1);
            assertThat(java.util.List.of(firstResult, secondResult).stream()
                    .filter(result -> result instanceof com.megagraph.shop.shared.ConflictException)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username_normalized IN (?, ?)",
                    Integer.class, "race-first@example.test", "race-second@example.test")).isEqualTo(1);
        }
    }

    @Test
    void merchantSelectorIsRequiredAndCheckedAgainstMembership() throws Exception {
        var account = users.findByUsernameIgnoreCase("merchant@megagraph.local").orElseThrow();
        Merchant secondMerchant = merchants.save(new Merchant("第二商家"));
        memberships.save(new MerchantUser(secondMerchant.getId(), account.getId(), MerchantRole.VIEWER));
        MvcResult login = mvc.perform(post("/api/auth/v1/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"merchant@megagraph.local\",\"password\":\"merchant123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var cookie = login.getResponse().getCookie("SESSION");
        Long firstMerchantId = memberships.findByUserId(account.getId()).stream()
                .filter(membership -> !membership.getMerchantId().equals(secondMerchant.getId()))
                .findFirst().orElseThrow().getMerchantId();
        Product otherDraft = products.save(new Product(firstMerchantId, "另一商家私有草稿"));
        ProductImage otherImage = images.save(new ProductImage(firstMerchantId, otherDraft.getId(),
                "test-only-missing.png", "test.png", "image/png", 12, ImageRole.MAIN, 0));
        IngestionJob otherJob = jobs.save(new IngestionJob(firstMerchantId,
                "private-job-" + secondMerchant.getId(), "test.png", account.getId()));
        for (String path : java.util.List.of("/api/merchant/v1/products/" + otherDraft.getId() + "/draft",
                "/api/merchant/v1/media/" + otherImage.getId(), "/api/merchant/v1/ingestions/" + otherJob.getId())) {
            mvc.perform(get(path).cookie(cookie).header("X-Merchant-Id", secondMerchant.getId()))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/merchant/v1/me").cookie(cookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MERCHANT_CONTEXT_REQUIRED"));
        mvc.perform(get("/api/merchant/v1/me").cookie(cookie).header("X-Merchant-Id", "999999999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_ACCESS_DENIED"));
        mvc.perform(get("/api/merchant/v1/me").cookie(cookie)
                        .header("X-Merchant-Id", secondMerchant.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.merchantId").value(secondMerchant.getId()));
        mvc.perform(post("/api/merchant/v1/products/123/publish").with(csrf()).cookie(cookie)
                        .header("X-Merchant-Id", secondMerchant.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_ROLE_FORBIDDEN"));
        jdbc.update("UPDATE merchant_user SET role = 'OWNER' WHERE merchant_id = ? AND user_id = ?",
                secondMerchant.getId(), account.getId());
        mvc.perform(get("/api/merchant/v1/me").cookie(cookie)
                        .header("X-Merchant-Id", secondMerchant.getId()))
                .andExpect(jsonPath("$.role").value("OWNER"));
        jdbc.update("UPDATE merchant_user SET role = 'VIEWER' WHERE merchant_id = ? AND user_id = ?",
                secondMerchant.getId(), account.getId());
        mvc.perform(post("/api/merchant/v1/products/123/publish").with(csrf()).cookie(cookie)
                        .header("X-Merchant-Id", secondMerchant.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE merchant SET status = 'SUSPENDED' WHERE id = ?", secondMerchant.getId());
        mvc.perform(get("/api/merchant/v1/me").cookie(cookie)
                        .header("X-Merchant-Id", secondMerchant.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_ACCESS_DENIED"));
        jdbc.update("UPDATE merchant SET status = 'ACTIVE' WHERE id = ?", secondMerchant.getId());
        jdbc.update("DELETE FROM merchant_user WHERE merchant_id = ? AND user_id = ?",
                secondMerchant.getId(), account.getId());
        mvc.perform(get("/api/merchant/v1/me").cookie(cookie)
                        .header("X-Merchant-Id", secondMerchant.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_ACCESS_DENIED"));
    }

    private static String registerBody(String invite, String username) {
        return "{\"inviteCode\":\"" + invite + "\",\"username\":\"" + username
                + "\",\"displayName\":\"Alice\",\"password\":\"password-123456\"}";
    }

    private Object registerWhenReleased(CountDownLatch start, String invite, String username) throws Exception {
        start.await();
        try {
            return invites.register(invite, username, "Race", "password-123456");
        } catch (RuntimeException exception) {
            return exception;
        }
    }
}
