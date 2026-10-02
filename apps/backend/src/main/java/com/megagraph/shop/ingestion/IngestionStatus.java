package com.megagraph.shop.ingestion;

public enum IngestionStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    DEAD_LETTER,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
}

