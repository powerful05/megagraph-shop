package com.megagraph.shop.ingestion;

public enum IngestionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
}

