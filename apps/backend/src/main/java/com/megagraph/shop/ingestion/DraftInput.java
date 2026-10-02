package com.megagraph.shop.ingestion;
public record DraftInput(String originalFilename, String inputHash, byte[] image, String contentType, String scenario, int attempt) {}
