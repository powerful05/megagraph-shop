package com.megagraph.shop.commerce;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiryJob {

    private final OrderService orders;

    public OrderExpiryJob(OrderService orders) {
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "${app.orders.expiry-scan-ms:60000}")
    public void releaseExpiredReservations() {
        orders.expireOverdueOrders();
    }
}
