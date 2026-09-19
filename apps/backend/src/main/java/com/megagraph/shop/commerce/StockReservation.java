package com.megagraph.shop.commerce;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "stock_reservation")
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;
    private Long skuId;
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected StockReservation() {
    }

    public StockReservation(Long orderId, Long skuId, int quantity, Instant expiresAt) {
        this.orderId = orderId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.status = ReservationStatus.RESERVED;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public boolean confirm() {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        this.status = ReservationStatus.CONFIRMED;
        return true;
    }

    public boolean release() {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        this.status = ReservationStatus.RELEASED;
        return true;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }
}
