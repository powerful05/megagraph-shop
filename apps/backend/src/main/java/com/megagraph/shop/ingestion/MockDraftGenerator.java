package com.megagraph.shop.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MockDraftGenerator implements DraftGenerator {

    private final ObjectMapper objectMapper;

    public MockDraftGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DraftSuggestion generate(DraftInput input) {
        switch (input.scenario()) {
            case "slow", "timeout" -> {
                try { Thread.sleep(input.scenario().equals("slow") ? 1500 : 30000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IngestionProviderException("PROVIDER_TIMEOUT", true); }
            }
            case "malformed" -> { return new DraftSuggestion(null, null, "{invalid"); }
            case "retryable" -> { if (input.attempt() == 1) throw new IngestionProviderException("PROVIDER_RETRYABLE", true); }
            case "fail" -> throw new IngestionProviderException("PROVIDER_UNAVAILABLE", true);
            default -> { }
        }
        return generate(input.originalFilename());
    }
    @Override
    public DraftSuggestion generate(String originalFilename) {
        String normalized = originalFilename == null ? "待确认商品" : originalFilename
                .replaceFirst("\\.[^.]+$", "")
                .replaceAll("[_-]+", " ")
                .trim();
        if (normalized.isBlank()) {
            normalized = "待确认商品";
        }
        String title = "AI 草稿 · " + normalized;
        String subtitle = "图片已接收；材质、性能和规格待确认；请确认类目、标题、价格和库存后发布";
        try {
            return new DraftSuggestion(
                    title,
                    subtitle,
                    objectMapper.writeValueAsString(Map.of(
                            "provider", "mock",
                            "source", "filename",
                            "requiresMerchantConfirmation", true
                    ))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize draft evidence", exception);
        }
    }
}

