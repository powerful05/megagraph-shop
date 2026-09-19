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
    public DraftSuggestion generate(String originalFilename) {
        String normalized = originalFilename == null ? "待确认商品" : originalFilename
                .replaceFirst("\\.[^.]+$", "")
                .replaceAll("[_-]+", " ")
                .trim();
        if (normalized.isBlank()) {
            normalized = "待确认商品";
        }
        String title = "AI 草稿 · " + normalized;
        String subtitle = "图片已接收；请确认类目、标题、价格和库存后发布";
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

