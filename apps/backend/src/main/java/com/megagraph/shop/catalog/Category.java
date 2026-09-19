package com.megagraph.shop.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long parentId;
    private String name;
    private Integer levelNo;
    private boolean visible;
    private Instant createdAt;

    protected Category() {
    }

    public Category(Long parentId, String name, int levelNo) {
        this.parentId = parentId;
        this.name = name;
        this.levelNo = levelNo;
        this.visible = true;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public Integer getLevelNo() {
        return levelNo;
    }

    public boolean isVisible() {
        return visible;
    }
}

