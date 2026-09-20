package com.megagraph.shop.payment;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PaymentCallbackAdapterRegistry {

    private final List<PaymentCallbackSignatureVerifier> verifiers;
    private final List<PaymentCallbackParser> parsers;

    public PaymentCallbackAdapterRegistry(
            List<PaymentCallbackSignatureVerifier> verifiers,
            List<PaymentCallbackParser> parsers
    ) {
        this.verifiers = verifiers;
        this.parsers = parsers;
    }

    public PaymentCallbackSignatureVerifier verifier(String provider) {
        return verifiers.stream()
                .filter(value -> value.provider().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new CallbackException(
                        HttpStatus.NOT_FOUND,
                        "CALLBACK_PROVIDER_UNKNOWN",
                        "未知支付回调渠道"
                ));
    }

    public PaymentCallbackParser parser(String provider) {
        return parsers.stream()
                .filter(value -> value.provider().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new CallbackException(
                        HttpStatus.NOT_FOUND,
                        "CALLBACK_PROVIDER_UNKNOWN",
                        "未知支付回调渠道"
                ));
    }
}
