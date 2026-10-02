package com.megagraph.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.catalog.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductSupplyIntegrationTest {
    @Autowired private com.megagraph.shop.ingestion.IngestionWorker ingestionWorker;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProductRepository products;
    private static final String USER = "merchant@megagraph.local";
    private final String skuCode = "SUPPLY-" + UUID.randomUUID();

    @Test
    void confirmedSupplyIsVersionedAndRevisionInvalidatesConfirmation() throws Exception {
        JsonNode draft = createDraft();
        long id = draft.path("id").asLong();
        JsonNode revised = revise(draft, details(skuCode, "100% 棉"));
        assertThat(revised.path("evidence").path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        JsonNode published = publish(revised);
        JsonNode visible = json.readTree(mvc.perform(get("/api/shop/v1/products/{id}", id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(visible.path("evidence").path("productVersion").asLong()).isEqualTo(published.path("version").asLong());
        assertThat(visible.path("evidence").path("confirmationStatus").asText()).isEqualTo("MERCHANT_CONFIRMED");
        assertThat(visible.path("evidence").path("merchantName").asText()).isNotBlank();
        assertThat(visible.path("evidence").path("details").path("sizeChart").get(0).path("chestCm").asInt()).isEqualTo(104);
        assertThat(visible.path("evidence").path("details").path("profile").asText()).isEqualTo("APPAREL");
        assertThat(visible.path("evidence").path("details").path("specifications")).isEmpty();
        JsonNode offShelf = transition(published, "off-shelf");
        JsonNode edited = revise(offShelf, details(skuCode, "50% 棉 / 50% 粘纤"));
        assertThat(edited.path("evidence").path("confirmedAt").isNull()).isTrue();
        assertThat(edited.path("evidence").path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        mvc.perform(get("/api/shop/v1/products/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(post("/api/merchant/v1/products/{id}/publish", id).with(httpBasic(USER, "merchant123"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", edited.path("version").asLong()))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_CONFIRMED"));
        JsonNode republished = publish(edited);
        assertThat(republished.path("evidence").path("details").path("fabric").asText()).contains("50% 棉");
    }

    @Test
    void rejectsUnknownDuplicateAndInvalidSizeRowsWithoutUpdatingTheDraft() throws Exception {
        JsonNode draft = createDraft();
        var unknown = details("OTHER-M", "棉");
        mvc.perform(patch("/api/merchant/v1/products/{id}/draft", draft.path("id").asLong())
                .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(draft, unknown))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SIZE_CHART_SKU_INVALID"));
        var duplicate = new java.util.HashMap<>(details(skuCode, "棉"));
        var row = ((List<?>) duplicate.get("sizeChart")).getFirst();
        duplicate.put("sizeChart", List.of(row, row));
        mvc.perform(patch("/api/merchant/v1/products/{id}/draft", draft.path("id").asLong())
                .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(draft, duplicate))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SIZE_CHART_SKU_INVALID"));
        var invalid = new java.util.HashMap<>(details(skuCode, "棉"));
        invalid.put("sizeChart", List.of(Map.of("skuCode", skuCode, "sizeLabel", "M", "chestCm", -1, "lengthCm", 66)));
        mvc.perform(patch("/api/merchant/v1/products/{id}/draft", draft.path("id").asLong())
                .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(draft, invalid))))
                .andExpect(status().isBadRequest());
        assertThat(products.findById(draft.path("id").asLong()).orElseThrow().getVersion()).isEqualTo(draft.path("version").asLong());
    }

    @Test
    void generalPhysicalSpecificationsCanBeConfirmedPublishedAndRevisedWithoutApparelMeasurements() throws Exception {
        JsonNode published = publish(revise(createDraft(), generalDetails(skuCode)));
        mvc.perform(get("/api/shop/v1/products/{id}", published.path("id").asLong()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidence.details.profile").value("GENERAL_PHYSICAL"))
                .andExpect(jsonPath("$.evidence.details.sizeChart").isEmpty())
                .andExpect(jsonPath("$.evidence.details.specifications[0].value").value("A5"));
        var editedDetails = new java.util.HashMap<>(generalDetails(skuCode));
        editedDetails.put("specifications", List.of(Map.of("skuCode", skuCode, "name", "规格", "value", "A4")));
        JsonNode edited = revise(transition(published, "off-shelf"), editedDetails);
        assertThat(edited.path("evidence").path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(publish(edited).path("evidence").path("details").path("specifications").get(0).path("value").asText()).isEqualTo("A4");
    }

    @Test
    void generalSpecificationsRejectUnknownOrDuplicateSkuAndCannotKeepOrphanedRowsAfterCodeChange() throws Exception {
        JsonNode draft = createDraft();
        for (var invalid : List.of(generalDetails("OTHER-SKU"), duplicateSpecifications())) {
            mvc.perform(patch("/api/merchant/v1/products/{id}/draft", draft.path("id").asLong())
                    .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body(draft, invalid))))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SPECIFICATION_SKU_INVALID"));
        }
        JsonNode revised = revise(draft, generalDetails(skuCode));
        var changed = new java.util.HashMap<>(body(revised, null));
        changed.put("sku", Map.of("code", skuCode + "-NEW", "name", "A5", "price", 29, "stock", 10));
        mvc.perform(patch("/api/merchant/v1/products/{id}/draft", draft.path("id").asLong())
                .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SPECIFICATION_SKU_INVALID"));
        assertThat(products.findById(draft.path("id").asLong()).orElseThrow().getVersion()).isEqualTo(revised.path("version").asLong());
    }

    @Test
    void generalProfileCannotUseApparelSizeChartOrOmitSpecifications() throws Exception {
        JsonNode draft = createDraft();
        var mixed = new java.util.HashMap<>(generalDetails(skuCode));
        mixed.put("sizeChart", details(skuCode, "棉").get("sizeChart"));
        var missing = new java.util.HashMap<>(generalDetails(skuCode));
        missing.put("specifications", List.of());
        for (var invalid : List.of(mixed, missing)) {
            mvc.perform(patch("/api/merchant/v1/products/{id}/draft", draft.path("id").asLong())
                    .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body(draft, invalid)))).andExpect(status().isBadRequest());
        }
        assertThat(products.findById(draft.path("id").asLong()).orElseThrow().getVersion()).isEqualTo(draft.path("version").asLong());
    }

    private Map<String, Object> generalDetails(String code) {
        var result = new java.util.HashMap<String, Object>();
        result.put("profile", "GENERAL_PHYSICAL"); result.put("color", "红"); result.put("fabric", "纸质");
        result.put("fit", ""); result.put("seasons", List.of()); result.put("scenarios", List.of("会议记录"));
        result.put("measurementNote", "测试规格"); result.put("imageSourceNote", "测试样图"); result.put("sizeChart", List.of());
        result.put("specifications", List.of(Map.of("skuCode", code, "name", "规格", "value", "A5")));
        return result;
    }

    private Map<String, Object> duplicateSpecifications() {
        var result = new java.util.HashMap<>(generalDetails(skuCode));
        var row = ((List<?>) result.get("specifications")).getFirst();
        result.put("specifications", List.of(row, row));
        return result;
    }

    @Test
    void legacyProductsRemainReadableWithExplicitMissingEvidence() throws Exception {
        JsonNode revised = revise(createDraft(), null);
        JsonNode published = publish(revised);
        mvc.perform(get("/api/shop/v1/products/{id}", published.path("id").asLong()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidence.confirmationStatus").value("MISSING"))
                .andExpect(jsonPath("$.evidence.details").isEmpty());
    }

    @Test
    void testSupplyCannotLeakIntoDefaultCatalogOrCartEvenWithKnownIds() throws Exception {
        JsonNode draft = createDraft();
        var product = products.findById(draft.path("id").asLong()).orElseThrow();
        product.markTestSupply();
        products.saveAndFlush(product);
        draft = merchantDraft(product.getId());
        JsonNode published = publish(revise(draft, details(skuCode, "棉")));
        long skuId = published.path("skus").get(0).path("id").asLong();
        assertThat(published.path("evidence").path("dataOrigin").asText()).isEqualTo("LOCAL_TEST");
        mvc.perform(get("/api/shop/v1/products/{id}", product.getId())).andExpect(status().isNotFound());
        mvc.perform(put("/api/shop/v1/cart/items/{id}", skuId).with(httpBasic("shopper@megagraph.local", "shopper123"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    private JsonNode createDraft() throws Exception {
        byte[] png = TestImages.png();
        var image = new MockMultipartFile("image", "supply.png", "image/png", png);
        JsonNode job = json.readTree(mvc.perform(multipart("/api/merchant/v1/ingestions").file(image)
                .with(httpBasic(USER, "merchant123")).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray());
        assertThat(job.path("status").asText()).isEqualTo("QUEUED");
        assertThat(ingestionWorker.runOnce()).isTrue();
        job=json.readTree(mvc.perform(get("/api/merchant/v1/ingestions/{id}",job.path("id").asLong()).with(httpBasic(USER,"merchant123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        return merchantDraft(job.path("productId").asLong());
    }

    private JsonNode merchantDraft(long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/merchant/v1/products/{id}/draft", id).with(httpBasic(USER, "merchant123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private Map<String, Object> details(String code, String fabric) {
        return Map.of("color", "浅蓝", "fabric", fabric, "fit", "直筒", "seasons", List.of("春", "秋"),
                "scenarios", List.of("通勤"), "measurementNote", "平铺成衣测量，误差 2 cm", "imageSourceNote", "商家上传样图",
                "sizeChart", List.of(Map.of("skuCode", code, "sizeLabel", "M", "chestCm", 104, "lengthCm", 66)));
    }

    private Map<String, Object> body(JsonNode draft, Map<String, Object> details) throws Exception {
        JsonNode categories = json.readTree(mvc.perform(get("/api/merchant/v1/categories").with(httpBasic(USER, "merchant123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        long category = java.util.stream.StreamSupport.stream(categories.spliterator(), false)
                .filter(value -> value.path("level").asInt() > 1).findFirst().orElseThrow().path("id").asLong();
        var body = new java.util.HashMap<String, Object>();
        body.put("version", draft.path("version").asLong()); body.put("title", "测试资料商品"); body.put("categoryId", category);
        body.put("sku", Map.of("code", skuCode, "name", "浅蓝 / M", "price", 199, "stock", 10));
        body.put("supplyDetails", details);
        return body;
    }

    private JsonNode revise(JsonNode draft, Map<String, Object> details) throws Exception {
        return json.readTree(mvc.perform(patch("/api/merchant/v1/products/{id}/draft", draft.path("id").asLong())
                .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(draft, details))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode publish(JsonNode draft) throws Exception { return transition(transition(draft, "confirm"), "publish"); }
    private JsonNode transition(JsonNode draft, String action) throws Exception {
        return json.readTree(mvc.perform(post("/api/merchant/v1/products/{id}/{action}", draft.path("id").asLong(), action)
                .with(httpBasic(USER, "merchant123")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", draft.path("version").asLong()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
}
