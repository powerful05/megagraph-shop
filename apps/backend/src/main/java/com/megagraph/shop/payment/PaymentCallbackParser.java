package com.megagraph.shop.payment;

public interface PaymentCallbackParser {
    String provider();
    ChannelEvent parse(byte[] rawBody, String payloadDigest);
}
