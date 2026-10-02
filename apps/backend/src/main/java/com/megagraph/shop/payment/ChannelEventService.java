package com.megagraph.shop.payment;

import com.megagraph.shop.commerce.AfterSaleRequest;
import com.megagraph.shop.commerce.AfterSaleRequestRepository;
import com.megagraph.shop.commerce.AfterSaleStatus;
import com.megagraph.shop.commerce.OrderStatus;
import com.megagraph.shop.commerce.PaymentOrder;
import com.megagraph.shop.commerce.PaymentOrderRepository;
import com.megagraph.shop.commerce.PaymentStatus;
import com.megagraph.shop.commerce.RefundOrder;
import com.megagraph.shop.commerce.RefundOrderRepository;
import com.megagraph.shop.commerce.RefundStatus;
import com.megagraph.shop.commerce.SalesOrder;
import com.megagraph.shop.commerce.SalesOrderRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelEventService {

    private final PaymentChannelEventRepository inbox;
    private final PaymentOrderRepository payments;
    private final RefundOrderRepository refunds;
    private final SalesOrderRepository orders;
    private final AfterSaleRequestRepository afterSales;
    private final RefundAttemptRepository refundAttempts;
    private final InventoryReservationService inventory;
    private final CommerceOutboxWriter outbox;
    private final com.megagraph.shop.delivery.DigitalDeliveryStore digital;
    private final com.megagraph.shop.delivery.AppointmentStore appointments;

    public ChannelEventService(
            PaymentChannelEventRepository inbox,
            PaymentOrderRepository payments,
            RefundOrderRepository refunds,
            SalesOrderRepository orders,
            AfterSaleRequestRepository afterSales,
            RefundAttemptRepository refundAttempts,
            InventoryReservationService inventory,
            CommerceOutboxWriter outbox,
            com.megagraph.shop.delivery.DigitalDeliveryStore digital,
            com.megagraph.shop.delivery.AppointmentStore appointments
    ) {
        this.inbox = inbox;
        this.payments = payments;
        this.refunds = refunds;
        this.orders = orders;
        this.afterSales = afterSales;
        this.refundAttempts = refundAttempts;
        this.inventory = inventory;
        this.outbox = outbox;
        this.digital = digital;
        this.appointments = appointments;
    }

    @Transactional
    public ChannelEventResult process(ChannelEvent incoming) {
        PaymentChannelEvent event = requireInbox(incoming);
        if (!event.hasDigest(incoming.payloadDigest())) {
            throw new IllegalStateException("Digest mismatch must be handled before processing");
        }
        if (event.getProcessingStatus() != ChannelEventProcessingStatus.RECEIVED) {
            return result(event);
        }
        return switch (incoming.resourceType()) {
            case PAYMENT -> processPayment(event, incoming);
            case REFUND -> processRefund(event, incoming);
        };
    }

    @Transactional
    public ChannelEventResult recordPayloadMismatch(ChannelEvent incoming) {
        PaymentChannelEvent event = requireInbox(incoming);
        boolean firstConflict = event.recordPayloadConflict(incoming.payloadDigest());
        inbox.flush();
        if (firstConflict) {
            Map<String, Object> payload = eventDetails(event, null, null);
            payload.put("failureCode", "EVENT_ID_PAYLOAD_MISMATCH");
            outbox.save(
                    "CHANNEL_EVENT",
                    event.getId(),
                    event.getVersion(),
                    event.getResourceType() == ChannelResourceType.PAYMENT
                            ? "PAYMENT_RECONCILIATION_REQUIRED"
                            : "REFUND_RECONCILIATION_REQUIRED",
                    payload
            );
        }
        return result(event);
    }

    private ChannelEventResult processPayment(PaymentChannelEvent inboxEvent, ChannelEvent event) {
        if (event.eventType().name().startsWith("REFUND_")) {
            return review(inboxEvent, "EVENT_RESOURCE_MISMATCH", null, null);
        }
        Long snapshotOrderId = payments.resourceOrderId(event.provider(), event.providerResourceId()).orElse(null);
        if (snapshotOrderId == null) return review(inboxEvent, "EVENT_RESOURCE_NOT_FOUND", null, null);
        SalesOrder order = orders.findByIdForUpdate(snapshotOrderId)
                .orElseThrow(() -> new IllegalStateException("Payment order has no sales order"));
        PaymentOrder payment = payments.findByProviderResourceForUpdate(event.provider(), event.providerResourceId()).orElse(null);
        if (payment == null) {
            return review(inboxEvent, "EVENT_RESOURCE_NOT_FOUND", null, null);
        }
        if (payment.getAmount().compareTo(event.amount()) != 0) {
            return review(inboxEvent, "EVENT_AMOUNT_MISMATCH", payment.getId(), null);
        }
        if (!payment.getCurrency().equalsIgnoreCase(event.currency())) {
            return review(inboxEvent, "EVENT_CURRENCY_MISMATCH", payment.getId(), null);
        }
        PaymentStatus oldPaymentStatus = payment.getStatus();
        OrderStatus oldOrderStatus = order.getStatus();

        switch (event.eventType()) {
            case PAYMENT_PROCESSING -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.markProcessing(inboxEvent.getId());
                    payments.flush();
                    outbox.save("PAYMENT", payment.getId(), payment.getVersion(), "PAYMENT_PROCESSING",
                            eventDetails(inboxEvent, oldPaymentStatus.name(), payment.getStatus().name()));
                } else if (payment.getStatus() != PaymentStatus.PROCESSING) {
                    return review(inboxEvent, "EVENT_STATE_CONFLICT", payment.getId(), null);
                }
            }
            case PAYMENT_SUCCEEDED -> {
                if (payment.getStatus() == PaymentStatus.SUCCEEDED && order.getStatus() == OrderStatus.PAID) {
                    inboxEvent.markApplied(payment.getId(), null);
                    return result(inboxEvent);
                }
                if (order.getStatus() == OrderStatus.EXPIRED || order.getStatus() == OrderStatus.CANCELLED
                        || order.getStatus() == OrderStatus.PAYMENT_FAILED) {
                    return review(inboxEvent, "LATE_PAYMENT_SUCCESS", payment.getId(), null);
                }
                if (!isPaymentOpen(payment) || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                    return review(inboxEvent, "EVENT_STATE_CONFLICT", payment.getId(), null);
                }
                if (!appointments.canConfirm(order)) {
                    payment.cancel(inboxEvent.getId());
                    order.expire();
                    appointments.release(order, "EXPIRED");
                    payments.flush(); orders.flush();
                    outbox.save("PAYMENT",payment.getId(),payment.getVersion(),"PAYMENT_CANCELLED",eventDetails(inboxEvent,oldPaymentStatus.name(),payment.getStatus().name()));
                    outbox.save("ORDER",order.getId(),order.getVersion(),"ORDER_EXPIRED",orderEventDetails(inboxEvent,order,oldOrderStatus));
                    return review(inboxEvent,"LATE_PAYMENT_SUCCESS",payment.getId(),null);
                }
                payment.succeed(inboxEvent.getId());
                order.markPaid();
                inventory.confirm(order.getId());
                digital.paid(order);
                appointments.paid(order);
                payments.flush();
                orders.flush();
                outbox.save("PAYMENT", payment.getId(), payment.getVersion(), "PAYMENT_SUCCEEDED",
                        eventDetails(inboxEvent, oldPaymentStatus.name(), payment.getStatus().name()));
                outbox.save("ORDER", order.getId(), order.getVersion(), "ORDER_PAID",
                        orderEventDetails(inboxEvent, order, oldOrderStatus));
            }
            case PAYMENT_FAILED -> {
                if (payment.getStatus() == PaymentStatus.FAILED && order.getStatus() == OrderStatus.PAYMENT_FAILED) {
                    inboxEvent.markApplied(payment.getId(), null);
                    return result(inboxEvent);
                }
                if (!isPaymentOpen(payment) || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                    return review(inboxEvent, "EVENT_STATE_CONFLICT", payment.getId(), null);
                }
                payment.fail(inboxEvent.getId());
                order.markPaymentFailed("CHANNEL_PAYMENT_FAILED");
                inventory.release(order.getId());
                appointments.release(order, order.getStatus() == OrderStatus.PAYMENT_FAILED ? "PAYMENT_FAILED" : "CANCELLED");
                payments.flush();
                orders.flush();
                outbox.save("PAYMENT", payment.getId(), payment.getVersion(), "PAYMENT_FAILED",
                        eventDetails(inboxEvent, oldPaymentStatus.name(), payment.getStatus().name()));
                outbox.save("ORDER", order.getId(), order.getVersion(), "ORDER_PAYMENT_FAILED",
                        orderEventDetails(inboxEvent, order, oldOrderStatus));
            }
            case PAYMENT_CANCELLED -> {
                if (payment.getStatus() == PaymentStatus.CANCELLED && order.getStatus() == OrderStatus.CANCELLED) {
                    inboxEvent.markApplied(payment.getId(), null);
                    return result(inboxEvent);
                }
                if (!isPaymentOpen(payment) || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                    return review(inboxEvent, "EVENT_STATE_CONFLICT", payment.getId(), null);
                }
                payment.cancel(inboxEvent.getId());
                order.cancel("CHANNEL_PAYMENT_CANCELLED");
                inventory.release(order.getId());
                appointments.release(order, order.getStatus() == OrderStatus.PAYMENT_FAILED ? "PAYMENT_FAILED" : "CANCELLED");
                payments.flush();
                orders.flush();
                outbox.save("PAYMENT", payment.getId(), payment.getVersion(), "PAYMENT_CANCELLED",
                        eventDetails(inboxEvent, oldPaymentStatus.name(), payment.getStatus().name()));
                outbox.save("ORDER", order.getId(), order.getVersion(), "ORDER_CANCELLED",
                        orderEventDetails(inboxEvent, order, oldOrderStatus));
            }
            default -> {
                return review(inboxEvent, "EVENT_RESOURCE_MISMATCH", payment.getId(), null);
            }
        }
        inboxEvent.markApplied(payment.getId(), null);
        return result(inboxEvent);
    }

    private ChannelEventResult processRefund(PaymentChannelEvent inboxEvent, ChannelEvent event) {
        if (event.eventType().name().startsWith("PAYMENT_")) {
            return review(inboxEvent, "EVENT_RESOURCE_MISMATCH", null, null);
        }
        Long snapshotOrderId = refunds.resourceOrderId(event.provider(), event.providerResourceId()).orElse(null);
        if (snapshotOrderId == null) return review(inboxEvent, "EVENT_RESOURCE_NOT_FOUND", null, null);
        SalesOrder order = orders.findByIdForUpdate(snapshotOrderId)
                .orElseThrow(() -> new IllegalStateException("Refund has no sales order"));
        Long snapshotRefundId = refunds.resourceRefundId(event.provider(), event.providerResourceId()).orElseThrow();
        Long snapshotPaymentId = payments.findByOrderIdForUpdate(order.getId()).orElseThrow().getId();
        PaymentOrder payment = payments.findByIdForUpdate(snapshotPaymentId).orElseThrow();
        RefundOrder refund = refunds.findByIdForUpdate(snapshotRefundId)
                .orElseThrow(() -> new IllegalStateException("Refund disappeared"));
        if (refund.getAmount().compareTo(event.amount()) != 0) {
            return review(inboxEvent, "EVENT_AMOUNT_MISMATCH", null, refund.getId());
        }
        if (!refund.getCurrency().equalsIgnoreCase(event.currency())) {
            return review(inboxEvent, "EVENT_CURRENCY_MISMATCH", null, refund.getId());
        }
        AfterSaleRequest afterSale = afterSales.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new IllegalStateException("Refund has no after-sale request"));
        RefundAttempt attempt = refundAttempts.findFirstByRefundOrderIdOrderByAttemptNoDesc(refund.getId())
                .orElseThrow(() -> new IllegalStateException("Refund has no attempt"));
        RefundStatus oldRefundStatus = refund.getStatus();
        OrderStatus oldOrderStatus = order.getStatus();

        switch (event.eventType()) {
            case REFUND_PROCESSING -> {
                if (refund.getStatus() == RefundStatus.PENDING) {
                    refund.markProcessing(inboxEvent.getId());
                    attempt.markProcessing();
                    refunds.flush();
                    outbox.save("REFUND", refund.getId(), refund.getVersion(), "REFUND_PROCESSING",
                            eventDetails(inboxEvent, oldRefundStatus.name(), refund.getStatus().name()));
                } else if (refund.getStatus() != RefundStatus.PROCESSING) {
                    return review(inboxEvent, "EVENT_STATE_CONFLICT", null, refund.getId());
                }
            }
            case REFUND_SUCCEEDED -> {
                if (refund.getStatus() == RefundStatus.SUCCEEDED
                        && afterSale.getStatus() == AfterSaleStatus.REFUNDED) {
                    inboxEvent.markApplied(null, refund.getId());
                    return result(inboxEvent);
                }
                if (!isRefundOpen(refund)
                        || afterSale.getStatus() != AfterSaleStatus.REFUND_PROCESSING
                        || payment.getStatus() != PaymentStatus.SUCCEEDED
                        || order.getStatus() != OrderStatus.PAID) {
                    return review(inboxEvent, "EVENT_STATE_CONFLICT", null, refund.getId());
                }
                refund.succeed(inboxEvent.getId());
                attempt.markSucceeded();
                afterSale.markRefunded(null, null);
                payment.refund();
                order.markRefunded();
                inventory.restock(order.getId());
                digital.refunded(order);
                appointments.release(order,"REFUNDED");
                refunds.flush();
                payments.flush();
                afterSales.flush();
                orders.flush();
                outbox.save("REFUND", refund.getId(), refund.getVersion(), "REFUND_SUCCEEDED",
                        eventDetails(inboxEvent, oldRefundStatus.name(), refund.getStatus().name()));
                outbox.save("AFTER_SALE", afterSale.getId(), afterSale.getVersion(), "AFTER_SALE_REFUNDED",
                        afterSaleEventDetails(inboxEvent, afterSale, AfterSaleStatus.REFUND_PROCESSING));
                outbox.save("ORDER", order.getId(), order.getVersion(), "ORDER_REFUNDED",
                        orderEventDetails(inboxEvent, order, oldOrderStatus));
            }
            case REFUND_FAILED -> {
                if (refund.getStatus() == RefundStatus.FAILED
                        && afterSale.getStatus() == AfterSaleStatus.REFUND_FAILED) {
                    inboxEvent.markApplied(null, refund.getId());
                    return result(inboxEvent);
                }
                if (!isRefundOpen(refund) || afterSale.getStatus() != AfterSaleStatus.REFUND_PROCESSING) {
                    return review(inboxEvent, "EVENT_STATE_CONFLICT", null, refund.getId());
                }
                refund.fail(inboxEvent.getId(), "CHANNEL_REFUND_FAILED", "Mock channel reported refund failure");
                attempt.markFailed();
                afterSale.markRefundFailed();
                refunds.flush();
                afterSales.flush();
                outbox.save("REFUND", refund.getId(), refund.getVersion(), "REFUND_FAILED",
                        eventDetails(inboxEvent, oldRefundStatus.name(), refund.getStatus().name()));
            }
            default -> {
                return review(inboxEvent, "EVENT_RESOURCE_MISMATCH", null, refund.getId());
            }
        }
        inboxEvent.markApplied(null, refund.getId());
        return result(inboxEvent);
    }

    private ChannelEventResult review(
            PaymentChannelEvent event,
            String code,
            Long paymentId,
            Long refundId
    ) {
        event.markReviewRequired(code, paymentId, refundId);
        inbox.flush();
        Map<String, Object> payload = eventDetails(event, null, null);
        payload.put("failureCode", code);
        outbox.save(
                "CHANNEL_EVENT",
                event.getId(),
                event.getVersion(),
                event.getResourceType() == ChannelResourceType.PAYMENT
                        ? "PAYMENT_RECONCILIATION_REQUIRED"
                        : "REFUND_RECONCILIATION_REQUIRED",
                payload
        );
        return result(event);
    }

    private PaymentChannelEvent requireInbox(ChannelEvent incoming) {
        return inbox.findForUpdate(incoming.provider(), incoming.providerEventId())
                .orElseThrow(() -> new IllegalStateException("Inbox event was not registered"));
    }

    private boolean isPaymentOpen(PaymentOrder payment) {
        return payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING;
    }

    private boolean isRefundOpen(RefundOrder refund) {
        return refund.getStatus() == RefundStatus.PENDING || refund.getStatus() == RefundStatus.PROCESSING;
    }

    private Map<String, Object> eventDetails(
            PaymentChannelEvent event,
            String oldStatus,
            String newStatus
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", event.getProvider());
        details.put("providerEventId", event.getProviderEventId());
        details.put("providerResourceId", event.getProviderResourceId());
        details.put("oldStatus", oldStatus);
        details.put("newStatus", newStatus);
        return details;
    }

    private Map<String, Object> orderEventDetails(
            PaymentChannelEvent event,
            SalesOrder order,
            OrderStatus oldStatus
    ) {
        Map<String, Object> details = eventDetails(event, oldStatus.name(), order.getStatus().name());
        details.put("orderId", order.getId());
        details.put("orderNo", order.getOrderNo());
        return details;
    }

    private Map<String, Object> afterSaleEventDetails(
            PaymentChannelEvent event,
            AfterSaleRequest afterSale,
            AfterSaleStatus oldStatus
    ) {
        Map<String, Object> details = eventDetails(event, oldStatus.name(), afterSale.getStatus().name());
        details.put("afterSaleId", afterSale.getId());
        details.put("orderId", afterSale.getOrderId());
        return details;
    }

    private ChannelEventResult result(PaymentChannelEvent event) {
        return new ChannelEventResult(
                event.getProvider(),
                event.getProviderEventId(),
                event.getProcessingStatus(),
                event.getFailureCode()
        );
    }
}
