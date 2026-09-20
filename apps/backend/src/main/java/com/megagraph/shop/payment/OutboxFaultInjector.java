package com.megagraph.shop.payment;

@FunctionalInterface
public interface OutboxFaultInjector {
    void beforeSave(String eventType);
}
