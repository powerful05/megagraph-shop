package com.megagraph.shop.payment;

public record CallbackIngressResult(
        ChannelEventResult result,
        boolean duplicate
) {
}
