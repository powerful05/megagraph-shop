package com.megagraph.shop.commerce;

import com.megagraph.shop.api.OrderItemResponse;
import com.megagraph.shop.api.OrderResponse;
import com.megagraph.shop.api.PaymentResponse;
import com.megagraph.shop.api.ReservationResponse;
import com.megagraph.shop.api.ShipmentResponse;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    private final SalesOrderItemRepository items;
    private final PaymentOrderRepository payments;
    private final StockReservationRepository reservations;
    private final ShipmentRepository shipments;

    public OrderMapper(
            SalesOrderItemRepository items,
            PaymentOrderRepository payments,
            StockReservationRepository reservations,
            ShipmentRepository shipments
    ) {
        this.items = items;
        this.payments = payments;
        this.reservations = reservations;
        this.shipments = shipments;
    }

    public OrderResponse toResponse(SalesOrder order) {
        PaymentResponse payment = payments.findByOrderId(order.getId())
                .map(value -> new PaymentResponse(
                        value.getProvider(),
                        value.getProviderPaymentId(),
                        value.getStatus().name()
                ))
                .orElse(null);
        ShipmentResponse shipment = shipments.findByOrderId(order.getId())
                .map(value -> new ShipmentResponse(
                        value.getId(),
                        value.getCarrierName(),
                        value.getTrackingNo(),
                        value.getStatus().name(),
                        value.getShippedAt(),
                        value.getDeliveredAt()
                ))
                .orElse(null);
        return new OrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getUserId(),
                order.getMerchantId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getRecipientName(),
                order.getRecipientPhone(),
                order.getShippingAddress(),
                order.getExpiresAt(),
                order.getCreatedAt(),
                payment,
                shipment,
                items.findByOrderIdOrderByIdAsc(order.getId()).stream()
                        .map(value -> new OrderItemResponse(
                                value.getProductId(),
                                value.getSkuId(),
                                value.getProductTitle(),
                                value.getSkuCode(),
                                value.getSkuName(),
                                value.getUnitPrice(),
                                value.getCurrency(),
                                value.getQuantity(),
                                value.getLineAmount()
                        ))
                        .toList(),
                reservations.findByOrderIdOrderByIdAsc(order.getId()).stream()
                        .map(value -> new ReservationResponse(
                                value.getSkuId(),
                                value.getQuantity(),
                                value.getStatus().name()
                        ))
                        .toList()
        );
    }
}
