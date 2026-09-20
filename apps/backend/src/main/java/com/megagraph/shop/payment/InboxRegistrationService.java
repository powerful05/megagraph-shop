package com.megagraph.shop.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxRegistrationService {

    private final PaymentChannelEventRepository events;

    public InboxRegistrationService(PaymentChannelEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(ChannelEvent event, String payloadText) {
        events.saveAndFlush(new PaymentChannelEvent(event, payloadText));
    }
}
