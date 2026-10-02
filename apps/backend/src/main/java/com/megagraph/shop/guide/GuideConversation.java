package com.megagraph.shop.guide;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guide_conversation")
public class GuideConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String publicId;
    private Long ownerUserId;
    private String title;
    private Long nextSequence;
    private Long eventVersion;
    private Instant createdAt;
    private Instant updatedAt;

    protected GuideConversation() {}

    GuideConversation(Long ownerUserId) {
        this.publicId = UUID.randomUUID().toString();
        this.ownerUserId = ownerUserId;
        this.title = "新对话";
        this.nextSequence = 1L;
        this.eventVersion = 0L;
        this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        this.updatedAt = createdAt;
    }

    long append(String content, Instant now) {
        if (nextSequence == 1) title = content.substring(0, Math.min(content.length(), 60));
        updatedAt = now;
        return nextSequence++;
    }

    void touch(Instant now) { updatedAt = now; }
    Long id() { return id; }
    String publicId() { return publicId; }
    String title() { return title; }
    Long sequence() { return nextSequence - 1; }
    Long nextEventVersion() { return ++eventVersion; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
