package com.megagraph.shop.ingestion;

public interface DraftGenerator {
    DraftSuggestion generate(String originalFilename);
}

