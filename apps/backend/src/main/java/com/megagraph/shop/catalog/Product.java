package com.megagraph.shop.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long merchantId;
    private Long categoryId;
    private String title;
    private String subtitle;

    @Enumerated(EnumType.STRING)
    private DraftStatus draftStatus;

    @Enumerated(EnumType.STRING)
    private PublishStatus publishStatus;

    private Long confirmedBy;
    private Instant confirmedAt;
    private Instant publishedAt;
    private Instant offShelfAt;
    private Instant deletedAt;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected Product() {
    }

    public Product(Long merchantId, String draftTitle) {
        this.merchantId = merchantId;
        this.title = draftTitle;
        this.draftStatus = DraftStatus.READY_FOR_CONFIRMATION;
        this.publishStatus = PublishStatus.UNPUBLISHED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void revise(String title, String subtitle, Long categoryId) {
        this.title = title;
        this.subtitle = subtitle;
        this.categoryId = categoryId;
        this.draftStatus = DraftStatus.READY_FOR_CONFIRMATION;
        this.confirmedBy = null;
        this.confirmedAt = null;
        if (this.publishStatus == PublishStatus.PUBLISHED) {
            this.publishStatus = PublishStatus.UNPUBLISHED;
            this.publishedAt = null;
        }
    }

    public void confirm(Long userId) {
        this.draftStatus = DraftStatus.CONFIRMED;
        this.confirmedBy = userId;
        this.confirmedAt = Instant.now();
    }

    public void publish() {
        this.publishStatus = PublishStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.offShelfAt = null;
    }

    public void offShelf() {
        this.publishStatus = PublishStatus.OFF_SHELF;
        this.offShelfAt = Instant.now();
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public DraftStatus getDraftStatus() {
        return draftStatus;
    }

    public PublishStatus getPublishStatus() {
        return publishStatus;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

