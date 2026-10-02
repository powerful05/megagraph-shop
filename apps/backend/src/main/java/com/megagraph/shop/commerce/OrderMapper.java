package com.megagraph.shop.commerce;

import com.megagraph.shop.api.AfterSaleResponse;
import com.megagraph.shop.api.OrderItemResponse;
import com.megagraph.shop.api.OrderResponse;
import com.megagraph.shop.api.PaymentResponse;
import com.megagraph.shop.api.ReservationResponse;
import com.megagraph.shop.api.RefundResponse;
import com.megagraph.shop.api.ShipmentResponse;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.megagraph.shop.api.SupplyDetails;

@Component
public class OrderMapper {

    private final SalesOrderItemRepository items;
    private final PaymentOrderRepository payments;
    private final StockReservationRepository reservations;
    private final ShipmentRepository shipments;
    private final AfterSaleRequestRepository afterSales;
    private final RefundOrderRepository refunds;
    private final ObjectMapper json;

    public OrderMapper(
            SalesOrderItemRepository items,
            PaymentOrderRepository payments,
            StockReservationRepository reservations,
            ShipmentRepository shipments,
            AfterSaleRequestRepository afterSales,
            RefundOrderRepository refunds,
            ObjectMapper json
    ) {
        this.items = items;
        this.payments = payments;
        this.reservations = reservations;
        this.shipments = shipments;
        this.afterSales = afterSales;
        this.refunds = refunds;
        this.json = json;
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
        AfterSaleResponse afterSale = afterSales.findByOrderId(order.getId())
                .map(value -> new AfterSaleResponse(
                        value.getId(),
                        value.getStatus().name(),
                        value.getReason(),
                        value.getMerchantNote(),
                        value.getRequestedAt(),
                        value.getResolvedAt(),
                        refunds.findByAfterSaleId(value.getId())
                                .map(refund -> new RefundResponse(
                                        refund.getId(),
                                        refund.getProvider(),
                                        refund.getProviderRefundId(),
                                        refund.getAmount(),
                                        refund.getCurrency(),
                                        refund.getStatus().name(),
                                        refund.getRefundedAt(),
                                        refund.getFailureCode(),
                                        refund.getErrorSummary()
                                ))
                                .orElse(null)
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
                afterSale,
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
                                value.getLineAmount(),
                                value.getProductVersion(),
                                readSupply(value.getSupplyDetails())
                        ))
                        .toList(),
                reservations.findByOrderIdOrderByIdAsc(order.getId()).stream()
                        .map(value -> new ReservationResponse(
                                value.getSkuId(),
                                value.getQuantity(),
                                value.getStatus().name()
                        ))
                        .toList(),
                order.getCheckoutGroupId(),
                order.isTestOrder()
        );
    }

    private SupplyDetails readSupply(String value) {
        if (value == null) return null;
        try { return json.readValue(value, SupplyDetails.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid order supply snapshot", exception); }
    }
}
