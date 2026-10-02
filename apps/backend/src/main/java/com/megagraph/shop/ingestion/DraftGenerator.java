package com.megagraph.shop.ingestion;
public interface DraftGenerator {
 DraftSuggestion generate(String originalFilename);
 default DraftSuggestion generate(DraftInput input) { return generate(input.originalFilename()); }
}
