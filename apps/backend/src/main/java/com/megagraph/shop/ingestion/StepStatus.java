package com.megagraph.shop.ingestion;

public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    RETRY_WAIT,
    FAILED,
    SKIPPED
}

