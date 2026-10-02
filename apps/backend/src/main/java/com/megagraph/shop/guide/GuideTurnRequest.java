package com.megagraph.shop.guide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "guide_turn_request")
public class GuideTurnRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String requestId;
    private Long conversationId;
    private Long turnSequence;
    @Column(length = 2000) private String userContent;
    private String status;
    private Integer attempt;
    private Instant leaseExpiresAt;
    @Column(columnDefinition = "LONGTEXT") private String resultJson;
    private String errorCode;
    private String errorMessage;
    private String modelVersion;
    private String protocolVersion;
    private Instant createdAt;
    private Instant updatedAt;

    protected GuideTurnRequest() {}

    GuideTurnRequest(String requestId, GuideConversation conversation, String content, String model, Instant now) {
        this.requestId = requestId;
        this.conversationId = conversation.id();
        this.turnSequence = conversation.append(content, now);
        this.userContent = content;
        this.attempt = 0;
        this.modelVersion = model;
        this.protocolVersion = GuideGuidance.VERSION;
        this.createdAt = now;
        start(now);
    }

    void start(Instant now) {
        status = "PENDING";
        attempt++;
        leaseExpiresAt = now.plusSeconds(105);
        errorCode = null;
        errorMessage = null;
        resultJson = null;
        updatedAt = now;
    }

    void complete(String json, Instant now) {
        resultJson = json;
        status = "COMPLETED";
        updatedAt = now;
    }

    void fail(String code, String message, Instant now) {
        status = "FAILED";
        errorCode = code;
        errorMessage = message;
        updatedAt = now;
    }

    boolean expired(Instant now) { return status.equals("PENDING") && !leaseExpiresAt.isAfter(now); }
    String requestId() { return requestId; }
    Long sequence() { return turnSequence; }
    String content() { return userContent; }
    String status() { return status; }
    int attempt() { return attempt; }
    String resultJson() { return resultJson; }
    String errorCode() { return errorCode; }
    String errorMessage() { return errorMessage; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
