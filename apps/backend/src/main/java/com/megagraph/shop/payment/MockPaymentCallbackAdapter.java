package com.megagraph.shop.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public class MockPaymentCallbackAdapter implements PaymentCallbackSignatureVerifier, PaymentCallbackParser {

    public static final String SIGNATURE_HEADER = "X-Mock-Signature";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public MockPaymentCallbackAdapter(
            ObjectMapper objectMapper,
            @Value("${app.payments.mock.callback-secret}") String secret
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String provider() {
        return "MOCK";
    }

    @Override
    public void verify(HttpHeaders headers, byte[] rawBody) {
        String supplied = headers.getFirst(SIGNATURE_HEADER);
        if (supplied == null || supplied.isBlank()) {
            throw new CallbackException(
                    HttpStatus.UNAUTHORIZED,
                    "CALLBACK_SIGNATURE_MISSING",
                    "缺少 Mock 回调签名"
            );
        }
        String normalized = supplied.trim();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new CallbackException(
                    HttpStatus.UNAUTHORIZED,
                    "CALLBACK_SIGNATURE_INVALID",
                    "Mock 回调签名无效"
            );
        }
        byte[] expected = sign(rawBody).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = normalized.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new CallbackException(
                    HttpStatus.UNAUTHORIZED,
                    "CALLBACK_SIGNATURE_INVALID",
                    "Mock 回调签名无效"
            );
        }
    }

    @Override
    public ChannelEvent parse(byte[] rawBody, String payloadDigest) {
        try {
            MockEventPayload payload = objectMapper.readValue(rawBody, MockEventPayload.class);
            if (payload.providerEventId() == null || payload.providerEventId().isBlank()
                    || payload.providerEventId().length() > 120
                    || payload.eventType() == null
                    || payload.resourceType() == null
                    || payload.providerResourceId() == null || payload.providerResourceId().isBlank()
                    || payload.providerResourceId().length() > 120
                    || payload.occurredAt() == null
                    || payload.amount() == null || payload.amount().signum() < 0
                    || payload.currency() == null || payload.currency().length() != 3) {
                throw new IllegalArgumentException("Missing required callback field");
            }
            return new ChannelEvent(
                    provider(),
                    payload.providerEventId(),
                    payload.eventType(),
                    payload.resourceType(),
                    payload.providerResourceId(),
                    payload.occurredAt(),
                    payload.amount(),
                    payload.currency().toUpperCase(),
                    payloadDigest
            );
        } catch (Exception exception) {
            throw new CallbackException(
                    HttpStatus.BAD_REQUEST,
                    "CALLBACK_PAYLOAD_INVALID",
                    "支付回调报文格式无效"
            );
        }
    }

    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign Mock callback", exception);
        }
    }

    public record MockEventPayload(
            String providerEventId,
            ChannelEventType eventType,
            ChannelResourceType resourceType,
            String providerResourceId,
            Instant occurredAt,
            BigDecimal amount,
            String currency
    ) {
    }
}
