package com.megagraph.shop.ingestion;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ingestion_step")
public class IngestionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;
    private Long merchantId;
    private String stepType;

    @Enumerated(EnumType.STRING)
    private StepStatus status;

    private Integer attemptCount;
    private String resultJson;
    private String errorCode;
    private String errorSummary;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;

    protected IngestionStep() {
    }

    public IngestionStep(Long jobId, Long merchantId, String stepType) {
        this.jobId = jobId;
        this.merchantId = merchantId;
        this.stepType = stepType;
        this.status = StepStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = Instant.now();
    }

    public void start() {
        this.status = StepStatus.RUNNING;
        this.attemptCount += 1;
        this.startedAt = Instant.now();
    }

    public void succeed(String resultJson) {
        this.status = StepStatus.SUCCEEDED;
        this.resultJson = resultJson;
        this.finishedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getStepType() {
        return stepType;
    }

    public StepStatus getStatus() {
        return status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public String getResultJson() {
        return resultJson;
    }
}

