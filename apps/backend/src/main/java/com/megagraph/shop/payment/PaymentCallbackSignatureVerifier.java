package com.megagraph.shop.payment;

import org.springframework.http.HttpHeaders;

public interface PaymentCallbackSignatureVerifier {
    String provider();
    void verify(HttpHeaders headers, byte[] rawBody);
}
