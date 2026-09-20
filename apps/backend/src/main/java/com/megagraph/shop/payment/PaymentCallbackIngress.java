package com.megagraph.shop.payment;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentCallbackIngress {

    private final PaymentCallbackAdapterRegistry adapters;
    private final PaymentChannelEventRepository events;
    private final InboxRegistrationService registration;
    private final ChannelEventService channelEvents;
    private final int maxPayloadBytes;

    public PaymentCallbackIngress(
            PaymentCallbackAdapterRegistry adapters,
            PaymentChannelEventRepository events,
            InboxRegistrationService registration,
            ChannelEventService channelEvents,
            @Value("${app.payments.callback-max-bytes:16384}") int maxPayloadBytes
    ) {
        this.adapters = adapters;
        this.events = events;
        this.registration = registration;
        this.channelEvents = channelEvents;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public CallbackIngressResult receive(String provider, HttpHeaders headers, byte[] rawBody) {
        if (rawBody.length > maxPayloadBytes) {
            throw new CallbackException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "CALLBACK_PAYLOAD_TOO_LARGE",
                    "支付回调报文超过允许大小"
            );
        }
        PaymentCallbackSignatureVerifier verifier = adapters.verifier(provider);
        PaymentCallbackParser parser = adapters.parser(provider);
        verifier.verify(headers, rawBody);
        String digest = sha256(rawBody);
        ChannelEvent event = parser.parse(rawBody, digest);
        boolean inserted = register(event, safePayload(event));
        PaymentChannelEvent existing = events
                .findByProviderAndProviderEventId(event.provider(), event.providerEventId())
                .orElseThrow(() -> new IllegalStateException("Registered callback event is missing"));
        if (!existing.hasDigest(digest)) {
            channelEvents.recordPayloadMismatch(event);
            throw new CallbackException(
                    HttpStatus.CONFLICT,
                    "EVENT_ID_PAYLOAD_MISMATCH",
                    "相同渠道事件 ID 对应了不同报文"
            );
        }
        return new CallbackIngressResult(channelEvents.process(event), !inserted);
    }

    private boolean register(ChannelEvent event, String payload) {
        try {
            registration.insert(event, payload);
            return true;
        } catch (DataIntegrityViolationException exception) {
            if (events.findByProviderAndProviderEventId(event.provider(), event.providerEventId()).isEmpty()) {
                throw exception;
            }
            return false;
        }
    }

    private String sha256(byte[] rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safePayload(ChannelEvent event) {
        return "eventType=" + event.eventType()
                + ",resourceType=" + event.resourceType()
                + ",providerResourceId=" + event.providerResourceId()
                + ",occurredAt=" + event.occurredAt()
                + ",amount=" + event.amount()
                + ",currency=" + event.currency();
    }
}
