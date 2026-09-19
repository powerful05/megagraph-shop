package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.identity.Merchant;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.identity.MerchantUser;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.projection.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductFlowIntegrationTest {

    private static final String USERNAME = "merchant@megagraph.local";
    private static final String PASSWORD = "merchant123";

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

    @Test
    void merchantCanCompleteThePhaseOneProductLifecycle() throws Exception {
        long categoryId = firstCategoryId();
        String idempotencyKey = "test-" + UUID.randomUUID();

        JsonNode ingestion = upload(idempotencyKey);
        long jobId = ingestion.required("id").asLong();
        long productId = ingestion.required("productId").asLong();

        assertThat(ingestion.required("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(ingestion.required("steps").get(0).required("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode duplicate = upload(idempotencyKey);
        assertThat(duplicate.required("id").asLong()).isEqualTo(jobId);
        assertThat(duplicate.required("productId").asLong()).isEqualTo(productId);

        JsonNode draft = getJson("/api/merchant/v1/products/" + productId + "/draft");
        long initialVersion = draft.required("version").asLong();
        long imageId = draft.required("images").get(0).required("id").asLong();

        mvc.perform(get("/api/shop/v1/media/{imageId}", imageId))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/merchant/v1/products/{productId}/publish", productId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + initialVersion + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_CONFIRMED"));

        JsonNode revised = responseJson(mvc.perform(patch("/api/merchant/v1/products/{productId}/draft", productId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": %d,
                                  "title": "暮色机能外套",
                                  "subtitle": "轻量防风，城市通勤",
                                  "categoryId": %d,
                                  "sku": {
                                    "code": "JACKET-BLACK-M",
                                    "name": "黑色 / M",
                                    "price": 699.00,
                                    "stock": 12
                                  }
                                }
                                """.formatted(initialVersion, categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftStatus").value("READY_FOR_CONFIRMATION"))
                .andReturn());

        long revisedVersion = revised.required("version").asLong();

        mvc.perform(patch("/api/merchant/v1/products/{productId}/draft", productId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": %d,
                                  "title": "过期修改",
                                  "categoryId": %d,
                                  "sku": {
                                    "code": "JACKET-BLACK-M",
                                    "name": "黑色 / M",
                                    "price": 699.00,
                                    "stock": 12
                                  }
                                }
                                """.formatted(initialVersion, categoryId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        JsonNode confirmed = responseJson(mvc.perform(post("/api/merchant/v1/products/{productId}/confirm", productId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + revisedVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftStatus").value("CONFIRMED"))
                .andReturn());

        JsonNode published = responseJson(mvc.perform(post("/api/merchant/v1/products/{productId}/publish", productId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + confirmed.required("version").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishStatus").value("PUBLISHED"))
                .andReturn());

        mvc.perform(get("/api/shop/v1/products/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("暮色机能外套"))
                .andExpect(jsonPath("$.priceFrom").value(699.00))
                .andExpect(jsonPath("$.availableStock").value(12));
        mvc.perform(get("/api/shop/v1/media/{imageId}", imageId))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE));

        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("PRODUCT", productId))
                .extracting("eventType")
                .containsExactly("PRODUCT_PUBLISHED");

        JsonNode offShelf = responseJson(mvc.perform(post("/api/merchant/v1/products/{productId}/off-shelf", productId)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + published.required("version").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishStatus").value("OFF_SHELF"))
                .andReturn());

        assertThat(offShelf.required("version").asLong()).isGreaterThan(published.required("version").asLong());
        mvc.perform(get("/api/shop/v1/products/{productId}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/shop/v1/media/{imageId}", imageId))
                .andExpect(status().isNotFound());

        assertThat(outboxEvents.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("PRODUCT", productId))
                .extracting("eventType")
                .containsExactly("PRODUCT_PUBLISHED", "PRODUCT_OFF_SHELF");
    }

    @Test
    void protectedEndpointsRejectAnonymousRequestsAndInvalidImages() throws Exception {
        mvc.perform(get("/api/merchant/v1/products"))
                .andExpect(status().isUnauthorized());

        MockMultipartFile invalidImage = new MockMultipartFile(
                "image",
                "not-an-image.png",
                MediaType.IMAGE_PNG_VALUE,
                "plain text".getBytes(StandardCharsets.UTF_8)
        );

        mvc.perform(multipart("/api/merchant/v1/ingestions")
                        .file(invalidImage)
                        .header("Idempotency-Key", "bad-image-" + UUID.randomUUID())
                        .with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMAGE_SIGNATURE_INVALID"));
    }

    @Test
    void merchantsCannotReadEachOthersProductsOrIngestionJobs() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String otherUsername = "other-" + suffix + "@megagraph.local";
        String otherPassword = "merchant456";
        UserAccount otherUser = users.save(new UserAccount(
                otherUsername,
                passwordEncoder.encode(otherPassword),
                "其他商家"
        ));
        Merchant otherMerchant = merchants.save(new Merchant("其他店铺"));
        memberships.save(new MerchantUser(otherMerchant.getId(), otherUser.getId(), MerchantRole.OWNER));

        JsonNode ingestion = upload("isolated-" + suffix, otherUsername, otherPassword);
        long jobId = ingestion.required("id").asLong();
        long productId = ingestion.required("productId").asLong();

        mvc.perform(get("/api/merchant/v1/ingestions/{jobId}", jobId)
                        .with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INGESTION_NOT_FOUND"));
        mvc.perform(get("/api/merchant/v1/products/{productId}/draft", productId)
                        .with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private long firstCategoryId() throws Exception {
        JsonNode categories = responseJson(
                mvc.perform(get("/api/merchant/v1/categories")
                                .with(httpBasic(USERNAME, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn()
        );
        for (JsonNode category : categories) {
            if (!category.required("parentId").isNull()) {
                return category.required("id").asLong();
            }
        }
        throw new IllegalStateException("Test seed must include a leaf category");
    }

    private JsonNode upload(String idempotencyKey) throws Exception {
        return upload(idempotencyKey, USERNAME, PASSWORD);
    }

    private JsonNode upload(String idempotencyKey, String username, String password) throws Exception {
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x00
        };
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "urban-jacket.png",
                MediaType.IMAGE_PNG_VALUE,
                png
        );
        return responseJson(
                mvc.perform(multipart("/api/merchant/v1/ingestions")
                                .file(image)
                                .header("Idempotency-Key", idempotencyKey)
                                .with(httpBasic(username, password)))
                        .andExpect(status().isOk())
                        .andReturn()
        );
    }

    private JsonNode getJson(String path) throws Exception {
        return responseJson(
                mvc.perform(get(path).with(httpBasic(USERNAME, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn()
        );
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
