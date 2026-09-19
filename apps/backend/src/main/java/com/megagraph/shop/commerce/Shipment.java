package com.megagraph.shop.commerce;

import com.megagraph.shop.shared.ConflictException;
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
@Table(name = "shipment")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;
    private Long merchantId;
    private String idempotencyKey;
    private String carrierName;
    private String trackingNo;

    @Enumerated(EnumType.STRING)
    private ShipmentStatus status;

    private Instant shippedAt;
    private Instant deliveredAt;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected Shipment() {
    }

    public Shipment(
            Long orderId,
            Long merchantId,
            String idempotencyKey,
            String carrierName,
            String trackingNo
    ) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.carrierName = carrierName;
        this.trackingNo = trackingNo;
        this.status = ShipmentStatus.SHIPPED;
        this.shippedAt = Instant.now();
        this.createdAt = this.shippedAt;
        this.updatedAt = this.shippedAt;
    }

    public void markDelivered() {
        if (status == ShipmentStatus.DELIVERED) {
            return;
        }
        if (status != ShipmentStatus.SHIPPED) {
            throw new ConflictException("SHIPMENT_NOT_IN_TRANSIT", "物流单当前不能确认收货");
        }
        this.status = ShipmentStatus.DELIVERED;
        this.deliveredAt = Instant.now();
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

    public Long getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCarrierName() {
        return carrierName;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
